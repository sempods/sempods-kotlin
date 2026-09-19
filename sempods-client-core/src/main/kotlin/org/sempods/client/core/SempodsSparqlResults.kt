package org.sempods.client.core

import java.util.Collections

/**
 * A SELECT query's result, as the W3C SPARQL 1.1 Query Results JSON Format describes it: the declared
 * variables and the solutions, in the order the pod sent them.
 */
class SempodsSparqlResults private constructor(
  variables: List<String>,
  private val declared: Set<String>,
  solutions: List<SempodsSparqlSolution>,
) {

  /** The variables the result declares (`head.vars`), each once. */
  val variables: List<String> = Collections.unmodifiableList(ArrayList(variables))

  val solutions: List<SempodsSparqlSolution> = Collections.unmodifiableList(ArrayList(solutions))

  /**
   * The terms [variable] is bound to, one per solution that binds it, in solution order.
   *
   * @throws IllegalArgumentException for a name that is not a declared variable.
   */
  fun column(variable: String): List<SempodsSparqlTerm> {
    require(variable in declared) { "'$variable' is not a variable of this result: ${variables.size} were declared." }
    return solutions.mapNotNull { it[variable] }
  }

  override fun equals(other: Any?): Boolean =
    other is SempodsSparqlResults && other.variables == variables && other.solutions == solutions

  override fun hashCode(): Int = 31 * variables.hashCode() + solutions.hashCode()

  /** The variables and the number of solutions: the terms are the pod's data and stay out of logs. */
  override fun toString(): String = "SempodsSparqlResults(variables=$variables, solutions=${solutions.size})"

  internal companion object {

    @JvmSynthetic
    internal fun of(
      variables: List<String>,
      declared: Set<String>,
      solutions: List<SempodsSparqlSolution>,
    ): SempodsSparqlResults = SempodsSparqlResults(variables, declared, solutions)
  }
}
