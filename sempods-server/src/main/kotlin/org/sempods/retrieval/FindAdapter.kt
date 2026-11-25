package org.sempods.retrieval

import org.eclipse.rdf4j.model.Model

/**
 * A `find` search engine. `find` is a specification, not an algorithm — the engine behind it
 * (SPARQL substring, vector, OpenSearch, …) is swappable, and several can run in parallel,
 * their results merged by [FindService].
 *
 * Returns a [Model] of what it found: by convention the **IRI subjects** of that model are the
 * found resources (the central logic expands those). An adapter MAY include the edges/values
 * that matched (the SPARQL adapter does, for free) but a sparser graph is allowed.
 *
 * **Two kinds of statement.** A stored statement is pod data and carries the context it is stored
 * in. A generated statement describes a match (`SPS-FIND-028`): a hit's `sps:findResult` link to a
 * blank node, and the statements whose subject is such a node, `schema:text`, `schema:position` or
 * `sps:engine` for example. A generated statement carries the context the match occurred in, or no
 * context when the adapter does not know it; [FindService] then places it in a context that holds
 * the hit (`SPS-FIND-027`). Stored data holds no blank nodes, so the two kinds cannot be confused.
 * A statement with no context that is neither kind claims a fact the pod does not hold, and
 * [FindService] drops it (`SPS-FIND-030`).
 *
 * The adapter scopes to [FindSandbox.effectiveScope] as far as it can — SPARQL adapters build a
 * `Dataset` from it, external engines filter their own index by it. That set is the caller's
 * optional context downscope when present, else the readable ceiling; it is always pre-intersected
 * with the ceiling. What [FindService] does with the answer depends on [selfScoped].
 */
interface FindAdapter {

  /**
   * Whether this adapter matches only on data in [FindSandbox.effectiveScope], honours
   * [FindRequest.types], and names the context each stored statement is in (`SPS-FIND-010`).
   *
   * | Value | What [FindService] does with the answer |
   * |---|---|
   * | `true` | takes its candidates and the contexts of its stored statements as they are |
   * | `false` (default) | keeps a candidate only where the [ResourceExpander] reads an `rdf:type` for it in the effective scope, one of [FindRequest.types] when any are given, and a stored statement only in a context the expansion shows holding its subject |
   *
   * An index that may lag behind the store, or that cannot filter by context, leaves this `false`.
   * A candidate without an `rdf:type` in the store is then never a hit.
   */
  val selfScoped: Boolean get() = false

  /**
   * @param request the parsed query: tokens, an optional `rdf:type` constraint, and the limit.
   * @param sandbox the pod-scoped authorization context. The pod is identified by
   *   [FindSandbox.podUri] (its global URI); the adapter must self-scope to
   *   [FindSandbox.effectiveScope].
   * @return a model whose **IRI subjects are the found resources** (matched edges optional);
   *   never exceeds the sandbox.
   */
  fun find(request: FindRequest, sandbox: FindSandbox): Model
}
