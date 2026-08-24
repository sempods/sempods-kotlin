package org.sempods.mcp.core

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * The MCP method types both surfaces answer with.
 *
 * See https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle. What is *not* here
 * is what differs per surface by design: the `serverInfo` identity, the instructions text, and the
 * transport's status codes.
 */

/** The protocol versions both surfaces support, newest first. */
object McpProtocol {

  val SUPPORTED = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

  val LATEST = SUPPORTED.first()

  /**
   * The version to answer `initialize` with: the client's, when we speak it, and [LATEST]
   * otherwise.
   *
   * Answering an unknown version with [LATEST] rather than an error is deliberate — MCP has the
   * client decide whether it can live with the server's answer. Whether a *missing* version is an
   * error is policy and stays with each surface: the pod rejects a blank one, the hosted service
   * treats it as absent.
   */
  fun negotiate(requested: String?): String = if (requested in SUPPORTED) requested!! else LATEST
}

data class InitializeParams(
  val protocolVersion: String,
  val capabilities: Map<String, Any> = emptyMap(),
  val clientInfo: Implementation? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Implementation(
  val name: String,
  val version: String? = null,
  val title: String? = null,
  val description: String? = null,
  val websiteUrl: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InitializeResult(
  val protocolVersion: String,
  val capabilities: Capabilities,
  val serverInfo: Implementation,
  val instructions: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Capabilities(
  val tools: ToolsCapability? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolsCapability(
  val listChanged: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolsListResult(
  val tools: List<Tool>,
)

/**
 * Empty stub for the standard MCP `resources/list` discovery method.
 *
 * sempods exposes everything through the `tools/…` family, but ChatGPT and Open-Code probe this
 * method on connect; answering with an empty list avoids `method not found` noise in their UI and
 * in our audit logs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResourcesListResult(
  val resources: List<Any> = emptyList(),
)

/** Empty stub for `prompts/list`. Same rationale as [ResourcesListResult]. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PromptsListResult(
  val prompts: List<Any> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Tool(
  val name: String,
  val description: String,
  val inputSchema: ToolInputSchema,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolInputSchema(
  val type: String,
  val properties: Map<String, PropertySchema>,
  val required: List<String>,
  // `additionalProperties: false` makes conforming MCP clients reject calls with unknown top-level
  // arguments before they are sent — the defence against models that invent extra fields
  // (observed: ChatGPT adding a `statements` array alongside `jsonld`).
  val additionalProperties: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PropertySchema(
  val type: String,
  val description: String,
  /**
   * Element type of an `array` property, and null for every other type.
   *
   * Last so that a scalar property still serializes as `{type, description}` — the shape the
   * pod-immanent MCP has always sent.
   */
  val items: ItemsSchema? = null,
)

/** The element schema of an array property. Carries a type and nothing else: no nesting is used. */
data class ItemsSchema(val type: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolCallResult(
  val content: List<ContentItem>,
  val isError: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ContentItem(
  val type: String,
  val text: String? = null,
  val data: String? = null,
  val mimeType: String? = null,
)

/**
 * A successful `tools/call` result: one text block carrying [body] as JSON.
 *
 * Every tool on both surfaces answers in this shape — there is no `structuredContent` and no
 * `outputSchema` anywhere, because the clients that matter read the text block. What differs is
 * only what [body] is: the pod's own result on one side, the per-pod envelope around it on the
 * other.
 */
fun toolText(objectMapper: ObjectMapper, body: Any?): ToolCallResult =
  ToolCallResult(content = listOf(ContentItem(type = "text", text = objectMapper.writeValueAsString(body))))

/**
 * A tool-level error: `isError: true` with a plain-text message.
 *
 * This is the "the call never reached a pod" answer — a mistyped argument, an unknown tool. A pod
 * that *was* reached and refused is not this: it is a result the caller can branch on, carrying the
 * pod's own status.
 */
fun toolError(message: String): ToolCallResult =
  ToolCallResult(content = listOf(ContentItem(type = "text", text = message)), isError = true)
