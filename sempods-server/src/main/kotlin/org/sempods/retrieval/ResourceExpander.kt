package org.sempods.retrieval

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model

/**
 * Enriches found resources with the configured fixed expansion set (`rdf:type` / `rdfs:label` /
 * `schema:name`) read from the pod's own store.
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
}
