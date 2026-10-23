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
 * The adapter SHOULD scope to [FindSandbox.effectiveScope] — SPARQL adapters build a `Dataset`
 * from it, external engines filter their own index by it. That set is the caller's optional context
 * downscope when present, else the readable ceiling; it is always pre-intersected with the ceiling,
 * so scoping to it can never leak. Whether [FindService] relies on that is what [selfScoped] says.
 *
 * Verification is per hit: [FindService] keeps or drops a hit, together with the statements and
 * blank nodes the adapter attached to it, and never inspects what is attached. So even an adapter
 * that is not [selfScoped] MUST NOT attach to a hit facts the caller may not read — a snippet drawn
 * from an unreadable context on a hit that is readable would reach the response.
 */
interface FindAdapter {

  /**
   * `true` when this adapter promises that every hit it returns exists in
   * [FindSandbox.effectiveScope] and, when [FindRequest.types] is non-empty, carries one of those
   * types. [FindService] then keeps its hits unchecked.
   *
   * `false`, the default, fails closed: [FindService] keeps a hit only if the [ResourceExpander]
   * returns an asserted `rdf:type` for it within the effective scope, one of [FindRequest.types]
   * when those are given.
   */
  val selfScoped: Boolean
    get() = false

  /**
   * @param request the parsed query: tokens, an optional `rdf:type` constraint, and the limit.
   * @param sandbox the pod-scoped authorization context. The pod is identified by
   *   [FindSandbox.podUri] (its global URI); the effective context set is
   *   [FindSandbox.effectiveScope].
   * @return a model whose **IRI subjects are the found resources** (matched edges optional). A
   *   [selfScoped] adapter's model never exceeds the sandbox.
   */
  fun find(request: FindRequest, sandbox: FindSandbox): Model
}
