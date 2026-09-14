package org.sempods.client.core

import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** One JSON object of the protocol, read. What a member may hold, the route's operation decides. */
internal interface ProtocolObject {

  /** [name]'s string, and null when the member is `null` or missing. Any other value is a [ProtocolViolation]. */
  fun stringOrNull(name: String): String?
}

/** [bytes] as one JSON object, or a [ProtocolViolation]. */
internal fun decodeObject(bytes: ByteArray): ProtocolObject = ProtocolJson.decodeObject(bytes)

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
    if (root == null || !root.isObject) {
      throw ProtocolViolation("the document: expected an object, found ${kind(root)}")
    }
    return TreeObject(root)
  }

  fun unreadable(failure: JacksonException): String {
    val what = if (failure is StreamConstraintsException) "JSON beyond this client's read limits" else "malformed JSON"
    val where = failure.location?.takeIf { it.lineNr > 0 }?.let { " at line ${it.lineNr}, column ${it.columnNr}" }
    return what + where.orEmpty()
  }

  fun kind(node: JsonNode?): String =
    if (node == null || node.isMissingNode) "no value" else node.nodeType.name.lowercase()

  private class TreeObject(private val node: JsonNode) : ProtocolObject {

    override fun stringOrNull(name: String): String? {
      val member = node.get(name) ?: return null
      return when {
        member.isNull -> null
        member.isString -> member.stringValue()
        else -> throw ProtocolViolation("/$name: expected a string or null, found ${ProtocolJson.kind(member)}")
      }
    }
  }
}
