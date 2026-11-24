package org.sempods.client

import java.util.Collections

/**
 * What a `find` asks for: the text, the types a hit must have, which contexts may answer, whether the
 * answer is grouped by context, and how many distinct resources it may match.
 *
 * ```java
 * SempodsFindQuery query = SempodsFindQuery.text("harbour")
 *     .withTypes("https://schema.org/Article", "https://schema.org/Book")
 *     .withSelection(SempodsContextSelection.of(articles))
 *     .withLimit(20);
 * ```
 *
 * Each member mirrors one of the route's (SPS-FIND-004). The pod decides how to search the text
 * (SPS-FIND-002), so a query carries no mode, engine or ranking.
 */
class SempodsFindQuery private constructor(
  /** The words or question to search for (SPS-FIND-005). */
  val text: String,
  types: List<String>,
  /** Which contexts may answer; `readable()` sends no `contexts` (SPS-FIND-009). */
  val selection: SempodsContextSelection,
  /** `true` asks for every statement grouped by its context (SPS-FIND-027); `false`, the default, for one graph. */
  val includeContexts: Boolean,
  /**
   * The most distinct matched resource IRIs the answer may hold, or null for the pod's default
   * (SPS-FIND-025). It bounds hits only: the answer may still name other IRIs, such as a linked
   * resource or a type, and carry several observations per hit.
   */
  val limit: Int?,
) {

  /** The `rdf:type` IRIs a hit must have one of, each once, in the order first given (SPS-FIND-006); empty for any type. */
  val types: List<String> = Collections.unmodifiableList(ArrayList(types))

  /**
   * Hits of any of these types; nothing given accepts any type. A repeated IRI counts once.
   *
   * @throws IllegalArgumentException for a blank IRI.
   */
  fun withTypes(vararg types: String): SempodsFindQuery = withTypes(types.asList())

  /** Hits of any of these types; nothing given accepts any type. A repeated IRI counts once. */
  fun withTypes(types: Iterable<String>): SempodsFindQuery {
    val distinct = LinkedHashSet<String>()
    for ((index, type) in types.withIndex()) {
      val given: String? = type
      require(!given.isNullOrBlank()) { "Type IRI $index is blank. Leave it out to accept any type." }
      distinct += given
    }
    return SempodsFindQuery(text, ArrayList(distinct), selection, includeContexts, limit)
  }

  fun withSelection(selection: SempodsContextSelection): SempodsFindQuery =
    SempodsFindQuery(text, types, selection, includeContexts, limit)

  fun withIncludeContexts(includeContexts: Boolean): SempodsFindQuery =
    SempodsFindQuery(text, types, selection, includeContexts, limit)

  /**
   * At most [limit] distinct matched resource IRIs (SPS-FIND-025); null leaves the cap to the pod. A
   * pod caps a larger limit at its maximum. Linked IRIs and observations do not count towards it.
   *
   * @throws IllegalArgumentException for a limit below 1.
   */
  fun withLimit(limit: Int?): SempodsFindQuery {
    require(limit == null || limit >= 1) { "A find limit must be at least 1; pass null for the pod's default." }
    return SempodsFindQuery(text, types, selection, includeContexts, limit)
  }

  override fun equals(other: Any?): Boolean =
    other is SempodsFindQuery && other.text == text && other.types == types && other.selection == selection &&
      other.includeContexts == includeContexts && other.limit == limit

  override fun hashCode(): Int = listOf(text, types, selection, includeContexts, limit).hashCode()

  /** Leaves the text out: it is what someone searched for. */
  override fun toString(): String =
    "SempodsFindQuery(types=$types, selection=$selection, includeContexts=$includeContexts, limit=$limit)"

  companion object {

    /**
     * A search for [text] in everything the session may read, of any type, as one graph, capped at the
     * pod's default.
     *
     * @throws IllegalArgumentException for a blank text, which the pod refuses.
     */
    @JvmStatic
    fun text(text: String): SempodsFindQuery {
      val given: String? = text
      require(!given.isNullOrBlank()) { "A find needs a text to search for." }
      return SempodsFindQuery(given, emptyList(), SempodsContextSelection.readable(), includeContexts = false, limit = null)
    }
  }
}
