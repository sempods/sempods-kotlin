package org.sempods.retrieval.impls.sparql

import com.google.inject.Inject
import org.sempods.query.SparqlQueryService
import org.sempods.query.SparqlSandbox
import org.sempods.retrieval.FindAdapter
import org.sempods.retrieval.FindRequest
import org.sempods.retrieval.FindSandbox
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel

/**
 * The PoC `find` engine: pure SPARQL substring match over literals, no new infrastructure.
 * Self-scopes by building the sandbox `Dataset` from `sandbox.effectiveScope()` (the optional
 * caller context downscope, else the readable ceiling), so the RDF4J dataset enforces the visible
 * contexts for this adapter.
 *
 * Known limitation (accepted for the PoC): `CONTAINS` over all literals is a full scan — no
 * index, no ranking, no semantic recall. A vector / OpenSearch engine replaces it later behind
 * the same [FindAdapter] contract, in a sibling `impls` package.
 */
class SparqlTextFindAdapter @Inject constructor(
  private val sparqlQueryService: SparqlQueryService,
) : FindAdapter {

  override fun find(request: FindRequest, sandbox: FindSandbox): Model {
    if (request.tokens.isEmpty()) return LinkedHashModel()
    // Fail-closed: an empty effective scope (downscope onto only unreadable/unknown contexts) must
    // match nothing. An empty RDF4J Dataset would mean "no restriction" (the whole store), so we
    // never hand one to the query — see FindSandbox.matchesNothing.
    if (sandbox.matchesNothing()) return LinkedHashModel()
    val dataset = SparqlSandbox.buildDataset(sandbox.effectiveScope())
    val query = FindQueryBuilder.textMatch(request)
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
