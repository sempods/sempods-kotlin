package org.sempods.client

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
 * A [ProtocolViolation] names where it happened: a JSON Pointer from the document's root, or, at and
 * below a member whose name came from the document, the location the reading gave that member.
 */
internal interface ProtocolObject {

  /** [name]'s string, and null when the member is `null` or missing. Any other value is a [ProtocolViolation]. */
  fun stringOrNull(name: String): String?

  /** [name]'s string. A missing member, a `null` and any other value are a [ProtocolViolation]. */
  fun string(name: String): String

  /**
   * [name]'s integer, and null when the member is `null` or missing. A fraction, a number beyond a `Long`
   * and any other value are a [ProtocolViolation].
   */
  fun longOrNull(name: String): Long?

  /** [name]'s boolean, under the same rule as [string]. */
  fun boolean(name: String): Boolean

  /** [name]'s object, under the same rule as [string]. */
  fun nested(name: String): ProtocolObject

  /** [name]'s array of objects, under the same rule as [string], and an element that is not an object. */
  fun objects(name: String): List<ProtocolObject>

  /** [name]'s array of strings, under the same rule as [objects]. */
  fun strings(name: String): List<String>

  /** The member names in document order. They come from the document, so no message may quote one. */
  fun names(): List<String>

  /**
   * The member [name], a name taken from [names], as an object. [locatedAs] names it in every violation
   * at or below it, in place of the name itself.
   */
  fun nestedNamed(name: String, locatedAs: String): ProtocolObject

  /** A violation located at this object, for a rule the reading checks itself. [detail] quotes nothing from the document. */
  fun violation(detail: String): ProtocolViolation
}

/** [bytes] as one JSON object, or a [ProtocolViolation]. */
@JvmSynthetic
internal fun decodeObject(bytes: ByteArray): ProtocolObject = ProtocolJson.decodeObject(bytes)

/** [members] as one JSON object, in their order. A value is a `String` or a `Boolean`. */
@JvmSynthetic
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
    val document = Location(JsonPointer.empty(), label = null)
    if (root == null || !root.isObject) throw document.expected("an object", root)
    return TreeObject(root, document)
  }

  fun encodeObject(members: Map<String, Any>): ByteArray = mapper.writeValueAsBytes(members)

  fun unreadable(failure: JacksonException): String {
    val what = if (failure is StreamConstraintsException) "JSON beyond this client's read limits" else "malformed JSON"
    val where = failure.location?.takeIf { it.lineNr > 0 }?.let { " at line ${it.lineNr}, column ${it.columnNr}" }
    return what + where.orEmpty()
  }

  fun kind(node: JsonNode?): String =
    if (node == null || node.isMissingNode) "no value" else node.nodeType.name.lowercase()

  /** Where a value is: a JSON Pointer from the root, or the label a reading gave a member the document named. */
  private class Location(private val pointer: JsonPointer?, private val label: String?) {

    fun member(name: String): Location =
      if (pointer != null) Location(pointer.appendProperty(name), label = null) else Location(pointer = null, "$label, member $name")

    fun element(index: Int): Location =
      if (pointer != null) Location(pointer.appendIndex(index), label = null) else Location(pointer = null, "$label, element $index")

    fun expected(expected: String, found: JsonNode?) = ProtocolViolation("$this: expected $expected, found ${kind(found)}")

    override fun toString(): String =
      when {
        pointer == null -> label.orEmpty()
        pointer.matches() -> "the document"
        else -> pointer.toString()
      }
  }

  private class TreeObject(private val node: JsonNode, private val at: Location) : ProtocolObject {

    override fun stringOrNull(name: String): String? {
      val member = node.get(name)?.takeUnless { it.isNull } ?: return null
      if (!member.isString) throw at.member(name).expected("a string or null", member)
      return member.stringValue()
    }

    override fun string(name: String): String = text(node.get(name), at.member(name), "a string")

    override fun longOrNull(name: String): Long? {
      val member = node.get(name)?.takeUnless { it.isNull } ?: return null
      if (!member.isIntegralNumber || !member.canConvertToLong()) throw at.member(name).expected("an integer or null", member)
      return member.longValue()
    }

    override fun boolean(name: String): Boolean {
      val member = node.get(name)
      if (member == null || !member.isBoolean) throw at.member(name).expected("a boolean", member)
      return member.booleanValue()
    }

    override fun nested(name: String): ProtocolObject = objectAt(node.get(name), at.member(name))

    override fun objects(name: String): List<ProtocolObject> {
      val where = at.member(name)
      return elements(node.get(name), where, "an array of objects").mapIndexed { index, element ->
        objectAt(element, where.element(index))
      }
    }

    override fun strings(name: String): List<String> {
      val where = at.member(name)
      return elements(node.get(name), where, "an array of strings").mapIndexed { index, element ->
        text(element, where.element(index), "a string")
      }
    }

    override fun names(): List<String> = node.propertyNames().toList()

    override fun nestedNamed(name: String, locatedAs: String): ProtocolObject =
      objectAt(node.get(name), Location(pointer = null, locatedAs))

    override fun violation(detail: String) = ProtocolViolation("$at: $detail")

    private fun objectAt(value: JsonNode?, where: Location): ProtocolObject {
      if (value == null || !value.isObject) throw where.expected("an object", value)
      return TreeObject(value, where)
    }

    private fun elements(value: JsonNode?, where: Location, expected: String): List<JsonNode> {
      if (value == null || !value.isArray) throw where.expected(expected, value)
      return (0 until value.size()).map { value.get(it) }
    }

    private fun text(value: JsonNode?, where: Location, expected: String): String {
      if (value == null || !value.isString) throw where.expected(expected, value)
      return value.stringValue()
    }
  }
}
