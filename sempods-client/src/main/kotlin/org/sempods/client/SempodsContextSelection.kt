package org.sempods.client

import java.util.Collections

/**
 * Which contexts a read may see: everything the session may read, a set of contexts, or none.
 *
 * ```java
 * sparql.select(query);                                          // what the session may read
 * sparql.select(query, SempodsContextSelection.of(tasks, notes)); // only these, as far as readable
 * sparql.select(query, SempodsContextSelection.none());           // nothing
 * ```
 *
 * **A selection only narrows.** A context the session may not read is dropped by the pod, and a
 * selection whose contexts are all dropped matches nothing (SPS-SPARQL-012, SPS-SPARQL-013, SPS-CRUD-015).
 *
 * **Empty is not omitted.** `of()` with nothing in it is [none], so a computed list that turned out
 * empty never reads more than it asked for. An operation takes the selection as an optional
 * argument: leaving it out reads what the session may read, and `null` is refused.
 *
 * [isRestricted] and [contextUris] let an extension put a selection on its own route the same way.
 */
class SempodsContextSelection private constructor(
  /** `false` for [readable], `true` for [none] and for [of]. */
  val isRestricted: Boolean,
  contextUris: List<String>,
) {

  /** The selected context IRIs, each once, in the order first given; empty for [readable] and [none]. */
  val contextUris: List<String> = Collections.unmodifiableList(ArrayList(contextUris))

  override fun equals(other: Any?): Boolean =
    other is SempodsContextSelection && other.isRestricted == isRestricted && other.contextUris == contextUris

  override fun hashCode(): Int = 31 * isRestricted.hashCode() + contextUris.hashCode()

  override fun toString(): String =
    when {
      !isRestricted -> "SempodsContextSelection(readable)"
      contextUris.isEmpty() -> "SempodsContextSelection(none)"
      else -> "SempodsContextSelection($contextUris)"
    }

  companion object {

    private val READABLE = SempodsContextSelection(isRestricted = false, emptyList())

    private val NONE = SempodsContextSelection(isRestricted = true, emptyList())

    /** Every context the session may read: no selection is sent. */
    @JvmStatic
    fun readable(): SempodsContextSelection = READABLE

    /** No context: the read matches nothing. */
    @JvmStatic
    fun none(): SempodsContextSelection = NONE

    /** These contexts, as far as the session may read them. Nothing given is [none]. */
    @JvmStatic
    fun of(vararg contextUris: String): SempodsContextSelection = of(contextUris.asList())

    /**
     * These contexts, as far as the session may read them. Nothing given is [none]; a repeated IRI
     * counts once.
     *
     * @throws IllegalArgumentException for a blank IRI, which some routes read as nothing and others as
     *   no selection at all.
     */
    @JvmStatic
    fun of(contextUris: Iterable<String>): SempodsContextSelection {
      val distinct = LinkedHashSet<String>()
      for ((index, uri) in contextUris.withIndex()) {
        val given: String? = uri
        require(!given.isNullOrBlank()) {
          "Context IRI $index of the selection is blank. Leave it out, or select none() to match nothing."
        }
        distinct += given
      }
      return if (distinct.isEmpty()) NONE else SempodsContextSelection(isRestricted = true, ArrayList(distinct))
    }
  }
}
