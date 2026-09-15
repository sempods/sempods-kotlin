package org.sempods.client.core

import java.util.Collections

/** One row of a SELECT result: the terms its variables are bound to. */
class SempodsSparqlSolution internal constructor(
  private val variables: List<String>,
  bindings: Map<String, SempodsSparqlTerm>,
) {

  /** The bound variables and their terms, in the order the pod sent them. An unbound variable is absent. */
  val bindings: Map<String, SempodsSparqlTerm> = Collections.unmodifiableMap(LinkedHashMap(bindings))

  /**
   * The term [variable] is bound to, or null where this row leaves it unbound.
   *
   * @throws IllegalArgumentException for a name that is not a variable of the result, so a typo does not
   *   read as an unbound cell.
   */
  operator fun get(variable: String): SempodsSparqlTerm? {
    require(variable in variables) { "'$variable' is not a variable of this result: ${variables.size} were declared." }
    return bindings[variable]
  }

  override fun equals(other: Any?): Boolean =
    other is SempodsSparqlSolution && other.variables == variables && other.bindings == bindings

  override fun hashCode(): Int = 31 * variables.hashCode() + bindings.hashCode()

  override fun toString(): String = "SempodsSparqlSolution(bindings=${bindings.size})"
}
