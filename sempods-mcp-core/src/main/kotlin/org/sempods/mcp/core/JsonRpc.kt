package org.sempods.mcp.core

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * JSON-RPC 2.0, as both MCP surfaces speak it.
 *
 * This file knows nothing about MCP — no tools, no methods, no protocol versions. That separation
 * is the point: [Mcp] may depend on JSON-RPC, never the other way round.
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcRequest(
  val jsonrpc: String,
  val id: Any?,
  val method: String,
  val params: Map<String, Any>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcResponse(
  val jsonrpc: String,
  val id: Any?,
  val result: Any,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcErrorResponse(
  val jsonrpc: String,
  /**
   * Two ways to say "no id", and both surfaces need one of them.
   *
   * A Kotlin `null` **omits the field**: that is the shape the MCP transport asks for when a
   * notification POST is rejected outright, and the pod relies on it (`McpEndpointHttpTest`,
   * `a notification on an unknown pod should 404 rather than claim acceptance`).
   *
   * A Jackson `NullNode` emits `"id": null`: that is what JSON-RPC 2.0 §5 asks for when the id
   * could not be read off an otherwise-answerable request, and the hosted service relies on it for
   * its invalid-request branch.
   *
   * So the distinction is carried by the value, not by an inclusion annotation — an `ALWAYS` here
   * would flatten the first case into the second.
   */
  val id: Any?,
  val error: JsonRpcError,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcError(
  val code: Int,
  val message: String,
  val data: Any? = null,
)

class JsonRpcException(
  val code: Int,
  override val message: String,
  val data: Any? = null,
) : Exception(message)

/**
 * The error codes both surfaces answer with.
 *
 * The first four are the specification's own (JSON-RPC 2.0 §5.1). The last three are not: `-32000`
 * to `-32099` is the implementation-defined server-error range. [RESOURCE_NOT_FOUND] is MCP's use
 * of it; [UNAUTHORIZED] and [SERVER_ERROR] are this project's, which makes them an agreement
 * between the two surfaces rather than a protocol rule — and an agreement kept in two places is
 * not one.
 * They are declared together even where only one surface uses one today, so that neither surface
 * can later give a number a second meaning.
 *
 * The HTTP status each code rides on is deliberately *not* here. That is transport, the two
 * surfaces map it differently (the pod answers [INTERNAL_ERROR] with 500 and [INVALID_PARAMS] with
 * 200; the hosted service answers [INVALID_REQUEST] with 400), and a shared table would have to
 * invent an answer neither of them gives.
 */
object JsonRpcErrorCodes {
  const val INVALID_REQUEST = -32600
  const val METHOD_NOT_FOUND = -32601
  const val INVALID_PARAMS = -32602
  const val INTERNAL_ERROR = -32603

  /** Authentication required — carried alongside a `WWW-Authenticate` challenge. */
  const val UNAUTHORIZED = -32001

  /** MCP's own use of the range: the addressed resource does not exist. */
  const val RESOURCE_NOT_FOUND = -32002

  /** A server condition the caller can retry, such as an exhausted quota. */
  const val SERVER_ERROR = -32000
}

/**
 * A JSON-RPC notification carries no `id` and must not be answered.
 *
 * Written against `Any?` rather than a Jackson type because the pod deserializes the envelope into
 * [JsonRpcRequest] (where a JSON `null` arrives as a Kotlin `null`) while the hosted service reads
 * the raw tree (where it arrives as a `NullNode`). Both are "no id".
 */
fun isNotification(id: Any?): Boolean = id == null || (id as? com.fasterxml.jackson.databind.JsonNode)?.isNull == true
