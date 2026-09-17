package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.query.BindingSet
import org.eclipse.rdf4j.query.impl.ListBindingSet
import org.sempods.client.core.SempodsSparqlResults
import org.sempods.client.core.SempodsSparqlTerm
import org.sempods.client.core.SempodsSparqlTermKind
import java.util.Collections

/**
 * A SELECT query's result in RDF4J values: the declared variables, and one [BindingSet] per solution, in
 * the order the pod sent them.
 *
 * Every binding set names every declared variable; one a solution leaves unbound has no binding there.
 * The result is held in memory, and can be read as often as needed.
 */
class SempodsRdf4jSelectResults internal constructor(
  variables: List<String>,
  bindingSets: List<BindingSet>,
) {

  /** The variables the result declares, each once. */
  val variables: List<String> = Collections.unmodifiableList(ArrayList(variables))

  val bindingSets: List<BindingSet> = Collections.unmodifiableList(ArrayList(bindingSets))

  override fun equals(other: Any?): Boolean =
    other is SempodsRdf4jSelectResults && other.variables == variables && other.bindingSets == bindingSets

  override fun hashCode(): Int = 31 * variables.hashCode() + bindingSets.hashCode()

  /** The variables and the number of solutions: the values are the pod's data and stay out of logs. */
  override fun toString(): String = "SempodsRdf4jSelectResults(variables=$variables, bindingSets=${bindingSets.size})"
}

/** [results] term by term; a term RDF4J refuses is an [IllegalArgumentException]. */
internal fun selectResultsOf(results: SempodsSparqlResults): SempodsRdf4jSelectResults {
  val variables = results.variables
  val bindingSets = results.solutions.map { solution ->
    ListBindingSet(variables, variables.map { variable -> solution.bindings[variable]?.let(::valueOf) })
  }
  return SempodsRdf4jSelectResults(variables, bindingSets)
}

private val values = SimpleValueFactory.getInstance()

private fun valueOf(term: SempodsSparqlTerm): Value = when (term.kind) {
  SempodsSparqlTermKind.IRI -> values.createIRI(term.value)
  SempodsSparqlTermKind.BLANK_NODE -> values.createBNode(term.value)
  SempodsSparqlTermKind.LITERAL -> when {
    term.language != null -> values.createLiteral(term.value, term.language)
    term.datatype != null -> values.createLiteral(term.value, values.createIRI(term.datatype))
    else -> values.createLiteral(term.value)
  }
}
