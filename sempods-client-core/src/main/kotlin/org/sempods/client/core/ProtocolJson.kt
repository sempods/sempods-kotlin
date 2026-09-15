package org.sempods.client.core

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonPointer
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * One JSON object of the protocol, read. What a member may hold, the route's operation decides.
 *
 * A [ProtocolViolation] names the member by its JSON Pointer from the document's root.
 */
internal interface ProtocolObject {

  /** [name]'s string, and null when the member is `null` or missing. Any other value is a [ProtocolViolation]. */
  fun stringOrNull(name: String): String?

  /** [name]'s string. A missing member, a `null` and any other value are a [ProtocolViolation]. */
  fun string(name: String): String

  /** [name]'s boolean, and null when the member is `null` or missing. Any other value is a [ProtocolViolation]. */
  fun booleanOrNull(name: String): Boolean?

  /**
   * [name]'s array of objects, and null when the member is `null` or missing. Any other value, and an
   * element that is not an object, is a [ProtocolViolation].
   */
  fun objectsOrNull(name: String): List<ProtocolObject>?

  /**
   * [name]'s array of strings, and null when the member is `null` or missing. Any other value, and an
   * element that is not a string, is a [ProtocolViolation].
   */
  fun stringsOrNull(name: String): List<String>?
}

/** [bytes] as one JSON object, or a [ProtocolViolation]. */
internal fun decodeObject(bytes: ByteArray): ProtocolObject = ProtocolJson.decodeObject(bytes)

/** [members] as one JSON object, in their order. A value is a `String` or a `Boolean`. */
internal fun encodeObject(members: Map<String, Any>): ByteArray = ProtocolJson.encodeObject(members)

/**
 * The one place this module names Jackson, and in no declaration that is public in bytecode:
 * `checkPublishedSignatures` reads the class files, where `internal` is public.
 *
 * The tree, so a missing member, a `null` and a member of another type stay apart and nothing coerces
 * `42` into `"42"`. Jackson describes a failure with the text around it; a [ProtocolViolation] keeps
 * only where it happened.
 */
private object ProtocolJson {

  const val MAX_NESTING = 64

  val mapper: JsonMapper = JsonMapper.builder(
    JsonFactory.builder()
      .streamReadConstraints(
        StreamReadConstraints.builder()
          .maxDocumentLength(MAX_BODY_BYTES)
          .maxNestingDepth(MAX_NESTING)
          .build(),
      )
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build(),
  ).build()

  fun decodeObject(bytes: ByteArray): ProtocolObject {
    val root = try {
      mapper.readTree(bytes)
    } catch (failure: JacksonException) {
      throw ProtocolViolation(unreadable(failure))
    }
    if (root == null || !root.isObject) throw violation(JsonPointer.empty(), "an object", root)
    return TreeObject(root, JsonPointer.empty())
  }

  fun encodeObject(members: Map<String, Any>): ByteArray = mapper.writeValueAsBytes(members)

  fun unreadable(failure: JacksonException): String {
    val what = if (failure is StreamConstraintsException) "JSON beyond this client's read limits" else "malformed JSON"
    val where = failure.location?.takeIf { it.lineNr > 0 }?.let { " at line ${it.lineNr}, column ${it.columnNr}" }
    return what + where.orEmpty()
  }

  fun violation(at: JsonPointer, expected: String, found: JsonNode?) =
    ProtocolViolation("${if (at.matches()) "the document" else at}: expected $expected, found ${kind(found)}")

  fun kind(node: JsonNode?): String =
    if (node == null || node.isMissingNode) "no value" else node.nodeType.name.lowercase()

  private class TreeObject(private val node: JsonNode, private val pointer: JsonPointer) : ProtocolObject {

    override fun stringOrNull(name: String): String? = present(name)?.let { text(it, at(name), "a string or null") }

    override fun string(name: String): String = text(node.get(name), at(name), "a string")

    override fun booleanOrNull(name: String): Boolean? {
      val member = present(name) ?: return null
      if (!member.isBoolean) throw violation(at(name), "a boolean or null", member)
      return member.booleanValue()
    }

    override fun objectsOrNull(name: String): List<ProtocolObject>? =
      elements(name, "an array of objects or null")?.mapIndexed { index, element ->
        val at = at(name).appendIndex(index)
        if (!element.isObject) throw violation(at, "an object", element)
        TreeObject(element, at)
      }

    override fun stringsOrNull(name: String): List<String>? =
      elements(name, "an array of strings or null")?.mapIndexed { index, element ->
        text(element, at(name).appendIndex(index), "a string")
      }

    /** [name]'s value, and null when the member is `null` or missing. */
    private fun present(name: String): JsonNode? = node.get(name)?.takeUnless { it.isNull }

    private fun elements(name: String, expected: String): List<JsonNode>? {
      val member = present(name) ?: return null
      if (!member.isArray) throw violation(at(name), expected, member)
      return (0 until member.size()).map { member.get(it) }
    }

    private fun at(name: String): JsonPointer = pointer.appendProperty(name)

    private fun text(value: JsonNode?, at: JsonPointer, expected: String): String {
      if (value == null || !value.isString) throw violation(at, expected, value)
      return value.stringValue()
    }
  }
}
