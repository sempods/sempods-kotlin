package org.sempods.retrieval

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource

/**
 * Enriches found resources with the configured fixed expansion set (`rdf:type` / `rdfs:label` /
 * `schema:name`) read from the pod's own store, and says which contexts hold a hit.
 *
 * This is a central, post-merge step in [FindService] — consumer-agnostic and independent of which
 * adapter found the hits — but it is inherently a pod-store read, so the concrete query engine
 * (SPARQL today) lives behind this SPI in an `impls` package. Keeping it behind the interface lets
 * [FindService] stay free of any store/query specifics.
 */
interface ResourceExpander {

  /**
   * @param hits the found resource IRIs to expand (may be empty → empty result).
   * @param sandbox the pod-scoped context; the expansion stays within [FindSandbox.effectiveScope].
   * @return a model carrying the expansion triples for [hits]; never exceeds the sandbox.
   */
  fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model

  /**
   * The contexts within [FindSandbox.effectiveScope] that hold each of [hits]: those with a stored
   * statement whose subject is the hit (`SPS-FIND-027`). A hit no such context holds is absent from
   * the map.
   *
   * [FindService] calls it at most twice per `find`:
   *
   * - **Before the limit**, for the candidates the merged answer shows only through observations, with
   *   no stored statement. A candidate absent from the result is no hit and is dropped
   *   (`SPS-FIND-029`), so it takes no place under the limit.
   * - **During placement**, for a kept hit whose observation an adapter put in a context the answer
   *   does not show holding the hit. A context returned here keeps the observation where the adapter
   *   put it; with none, the observation moves to a holding context the answer already showed.
   *
   * An empty map means the store shows no holding context. The default knows none, so with it a
   * candidate seen only through observations is dropped, and placement uses only what the answer
   * shows.
   */
  fun holdingContexts(hits: Collection<IRI>, sandbox: FindSandbox): Map<IRI, Set<Resource>> = emptyMap()
}
