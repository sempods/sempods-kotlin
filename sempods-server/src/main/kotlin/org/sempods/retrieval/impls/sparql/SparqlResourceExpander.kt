package org.sempods.retrieval.impls.sparql

import com.google.inject.Inject
import org.sempods.query.SparqlQueryService
import org.sempods.query.SparqlSandbox
import org.sempods.retrieval.FindSandbox
import org.sempods.retrieval.ResourceExpander
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel

/**
 * SPARQL implementation of [ResourceExpander]: one sandboxed `SELECT ?s ?p ?o ?g` over the hit IRIs
 * that returns `rdf:type` / `rdfs:label` / `schema:name` where present, each tagged with its source
 * context `?g` (so the expansion carries the same provenance as the matched edges). The expansion is
 * itself context-sandboxed (so it can't read properties from contexts the caller can't see) even
 * though the adapters already self-scoped their finds.
 */
class SparqlResourceExpander @Inject constructor(
  private val sparqlQueryService: SparqlQueryService,
) : ResourceExpander {

  override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model {
    if (hits.isEmpty()) return LinkedHashModel()
    // Same effective scope as the find: the optional context downscope applies to the whole find
    // pattern, expansion included (so an expansion can't pull a label from a context the caller
    // filtered out), and falls back to the readable ceiling when no downscope was requested.
    // Fail-closed on an empty scope, like the find adapter (an empty Dataset means "no restriction").
    if (sandbox.matchesNothing()) return LinkedHashModel()
    val dataset = SparqlSandbox.buildDataset(sandbox.effectiveScope())
    val query = FindQueryBuilder.expansion(hits)
    return sparqlQueryService.executeSelectToQuadModel(
      // Mono-repo bridge: SparqlQueryService keys RDF4J stores by local pod name. A standalone
      // pod deployment would resolve its single store directly from sandbox.podUri.
      pod = sandbox.credentials.pod.name,
      query = query.sparql,
      dataset = dataset,
      bindings = query.bindings,
    )
  }
}
