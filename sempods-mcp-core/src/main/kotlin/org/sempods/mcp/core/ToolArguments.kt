package org.sempods.mcp.core

import com.fasterxml.jackson.databind.JsonNode

/**
 * Reading one argument out of a `tools/call` argument object.
 *
 * Lenient on purpose: every extractor answers null (or an empty list) rather than throwing, because
 * by the time these run [ToolCatalog.validate] has already refused anything mistyped, missing or
 * unknown. What is left for them is the narrowing the schema cannot express — a `string` that is
 * present but blank is not a value, and a filter built from blank entries is not a filter.
 *
 * They live here rather than in a surface because [PodToolExecutor] and the fan-out in front of it
 * read the same arguments: the executor reads `context_iri`, the hosted service reads `targets`
 * out of the very same object.
 */
object ToolArguments {

  /** A non-blank string argument, or null if absent / not a string / blank. */
  fun string(node: JsonNode?, field: String): String? =
    node?.get(field)?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

  /** The non-blank string elements of an array argument (non-arrays / non-strings drop out). */
  fun stringList(node: JsonNode?, field: String): List<String> {
    val arr = node?.get(field) ?: return emptyList()
    if (!arr.isArray) return emptyList()
    return arr.mapNotNull { it.takeIf { v -> v.isTextual }?.asText()?.trim()?.takeIf { s -> s.isNotEmpty() } }
  }

  /** A JSON object argument (e.g. a JSON-LD body / value object), or null if absent / not an object. */
  fun obj(node: JsonNode?, field: String): JsonNode? = node?.get(field)?.takeIf { it.isObject }

  /** A JSON array argument (e.g. a value-object array), or null if absent / not an array. */
  fun arr(node: JsonNode?, field: String): JsonNode? = node?.get(field)?.takeIf { it.isArray }

  /** A boolean argument, defaulting to false — the shape every optional flag in the catalog has. */
  fun flag(node: JsonNode?, field: String): Boolean = node?.get(field)?.asBoolean(false) ?: false
}
