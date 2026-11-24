package org.sempods.api.pod.system.find

import org.sempods.commons.json.JsonMappers
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * JSON body of `POST /{pod}/_system/find` — the body-based twin of the GET query parameters, for
 * requests whose `contexts` list no longer fits a URL (`SPS-FIND-004`). The same
 * [org.sempods.retrieval.FindRequestParser] validation applies afterwards (absent/blank `text` → 400).
 *
 * [parse] reads it strictly (`SPS-FIND-013`): a member this body does not declare, a member named
 * twice, a value of another JSON type (`"limit":"10"`, `"text":123`) and a `null` inside `type` or
 * `contexts` are each an [IllegalArgumentException], which the endpoint answers with `400`. A
 * client that sends a typo'd field, or the deferred general `filter`, learns that its request was
 * not understood; a result silently broadened by the ignored member would look like an answer. A
 * member whose value is `null` counts as absent.
 */
data class FindPostRequest(
  val text: String? = null,
  val type: List<String>? = null,
  /** `null` searches every readable context; an empty list, nothing (`SPS-FIND-015`). */
  val contexts: List<String>? = null,
  val includeContexts: Boolean? = null,
  val limit: Int? = null,
) {

  companion object {

    private val MEMBERS = setOf("text", "type", "contexts", "include_contexts", "limit")

    private val READER: JsonMapper = JsonMappers.strict().rebuild()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build()

    /** [body] as a find request, or an [IllegalArgumentException] naming the member that broke it. */
    fun parse(body: String): FindPostRequest {
      val root = try {
        READER.readTree(body)
      } catch (e: JacksonException) {
        throw IllegalArgumentException("invalid request body: ${(e.originalMessage ?: "malformed JSON").substringBefore('\n')}")
      }
      require(root != null && root.isObject) { "invalid request body: expected a JSON object" }
      root.propertyNames().forEach { name -> require(name in MEMBERS) { "invalid request body: unknown member '$name'" } }
      return FindPostRequest(
        text = root.member("text")?.let { value -> value.takeIf { it.isString }?.stringValue() ?: refuse("text", "a string") },
        type = root.member("type")?.let { strings(it, "type") },
        contexts = root.member("contexts")?.let { strings(it, "contexts") },
        includeContexts = root.member("include_contexts")?.let { value ->
          value.takeIf { it.isBoolean }?.booleanValue() ?: refuse("include_contexts", "a boolean")
        },
        limit = root.member("limit")?.let { value ->
          value.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: refuse("limit", "an integer")
        },
      )
    }

    private fun JsonNode.member(name: String): JsonNode? = get(name)?.takeUnless { it.isNull }

    private fun strings(value: JsonNode, name: String): List<String> {
      if (!value.isArray) refuse(name, "an array of strings")
      return (0 until value.size()).map { index ->
        value.get(index).takeIf { it.isString }?.stringValue() ?: refuse("$name[$index]", "a string")
      }
    }

    private fun refuse(member: String, expected: String): Nothing =
      throw IllegalArgumentException("invalid request body: '$member' must be $expected")
  }
}
