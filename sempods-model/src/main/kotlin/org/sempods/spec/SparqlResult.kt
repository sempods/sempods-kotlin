package org.sempods.spec

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model

/**
 * A page of a semantic query: the matched resource IRIs in order ([result]), the RDF [model] that
 * backs them (the resources' statements, view-projected and context-preserving), and an opaque
 * keyset [cursor] for the next page (null = last page).
 *
 * Deliberately generic — it carries no domain shape, so any paginated resource query reuses it. A
 * SPARQL-native listing in an application builds on it; the domain-specific query *inputs* (filters,
 * sort keys) live with that app, not here.
 */
data class SparqlResult(

  val result: List<IRI> = emptyList(),

  val model: Model,

  val cursor: String? = null,
)
