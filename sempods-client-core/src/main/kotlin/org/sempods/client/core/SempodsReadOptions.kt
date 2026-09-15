package org.sempods.client.core

/**
 * How a resource is read: which contexts may answer, whether the answer is grouped by context, and a
 * validator it may be unchanged from.
 *
 * ```java
 * resources.getText(iri);                                                    // defaults()
 * resources.getText(iri, SempodsGraphFormat.JSON_LD,
 *     SempodsReadOptions.of(SempodsContextSelection.of(tasks)).withIfNoneMatch(etag));
 * ```
 */
class SempodsReadOptions private constructor(
  /** Which contexts may answer; `readable()` sends no `context` parameter. */
  val selection: SempodsContextSelection,
  /** `true` asks for JSON-LD grouped by context (SPS-CRUD-022). N-Quads carries the context in every statement. */
  val includeContexts: Boolean,
  /** An entity tag sent as `If-None-Match`, exactly as given; null sends none. */
  val ifNoneMatch: String?,
) {

  fun withSelection(selection: SempodsContextSelection): SempodsReadOptions =
    SempodsReadOptions(selection, includeContexts, ifNoneMatch)

  fun withIncludeContexts(includeContexts: Boolean): SempodsReadOptions =
    SempodsReadOptions(selection, includeContexts, ifNoneMatch)

  /** @throws IllegalArgumentException for a blank tag; null removes the condition. */
  fun withIfNoneMatch(entityTag: String?): SempodsReadOptions {
    require(entityTag == null || entityTag.isNotBlank()) { "An entity tag must not be blank; pass null to send none." }
    return SempodsReadOptions(selection, includeContexts, entityTag)
  }

  override fun equals(other: Any?): Boolean =
    other is SempodsReadOptions && other.selection == selection && other.includeContexts == includeContexts &&
      other.ifNoneMatch == ifNoneMatch

  override fun hashCode(): Int = listOf(selection, includeContexts, ifNoneMatch).hashCode()

  override fun toString(): String =
    "SempodsReadOptions(selection=$selection, includeContexts=$includeContexts, ifNoneMatch=$ifNoneMatch)"

  companion object {

    private val DEFAULTS = SempodsReadOptions(SempodsContextSelection.readable(), includeContexts = false, ifNoneMatch = null)

    /** What the session may read, merged, unconditional. */
    @JvmStatic
    fun defaults(): SempodsReadOptions = DEFAULTS

    /** [selection], merged, unconditional. */
    @JvmStatic
    fun of(selection: SempodsContextSelection): SempodsReadOptions = DEFAULTS.withSelection(selection)
  }
}
