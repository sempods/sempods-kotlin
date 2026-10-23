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
 *
 * [FindService] also verifies with it the hits of adapters that are not
 * [selfScoped][FindAdapter.selfScoped]: such a hit is kept only if the expansion carries an
 * `rdf:type` for it. An implementation therefore:
 *
 * - MUST return every asserted `rdf:type` a hit carries in [FindSandbox.effectiveScope] — a type it
 *   leaves out can drop a hit the caller may read;
 * - MUST NOT return anything from outside that scope, inferred statements included — a type from
 *   there would admit a hit the caller may not read;
 * - returns nothing when [FindSandbox.matchesNothing].
 *
 * An expander that returns nothing is valid, and drops every hit no self-scoped adapter vouches for.
 */
interface ResourceExpander {

  /**
   * @param hits the found resource IRIs to expand (may be empty → empty result).
   * @param sandbox the pod-scoped context; the expansion stays within [FindSandbox.effectiveScope].
   * @return a model carrying the expansion triples for [hits]; never exceeds the sandbox.
   */
  fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model
}
