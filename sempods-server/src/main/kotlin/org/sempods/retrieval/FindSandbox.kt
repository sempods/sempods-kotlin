package org.sempods.retrieval

import org.sempods.pods.grants.SempodsCredentials
import org.sempods.query.SparqlSandbox
import java.net.URI

/**
 * The pod-scoped authorization context handed to every [FindAdapter] — engine-agnostic.
 *
 * @property podUri the pod's **URI** — its global identifier (e.g. `https://sempods.org/alice`).
 *   sempods are decentralized Linked Open Data: a pod is itself a resource, identified by (and
 *   dereferenceable at) this URI, not by a server-local name. Adapters (including future
 *   standalone-pod libraries and external index engines) key on this. In this still-multi-pod
 *   server the local RDF4J store is resolved from [credentials] (`pod.name`); a standalone
 *   deployment would resolve its single store straight from this URI.
 * @property credentials the authorized caller for this request — carries the pod
 *   ([SempodsCredentials.pod]) and the bearer's effective scopes, and is the source of
 *   [visibleContexts].
 * @property visibleContexts the **authorization ceiling**: the caller's readable named graphs
 *   (`null` = "all readable"). This is the security invariant — a hit may never come from outside
 *   it. Nothing SPARQL-specific lives here, so a vector / OpenSearch adapter can project the context
 *   set onto its own index.
 * @property contextFilter the optional caller-requested **downscope** within [visibleContexts]
 *   (`null` = no downscope → search pod-wide inside the ceiling). It is always already intersected
 *   with the readable set by the endpoint (`requested ∩ readable`, unknown/unreadable contexts
 *   silently dropped — see `lod-crud/lod-layer.md` §"Reads"), so it can be empty ("everything the
 *   caller asked for is unreadable/unknown" → empty result) and can never exceed the ceiling.
 *   Kept separate from [visibleContexts] because a search/vector adapter needs the explicit filter
 *   to set as a concrete `context IN (…)` index clause, and conflating the two would erase the
 *   distinction between the security ceiling and a functional narrowing.
 *
 * Each adapter (and the expander) is solely responsible for self-scoping to [effectiveScope] — the
 * endpoint does not post-filter adapter output. Because [contextFilter] is pre-intersected with the
 * ceiling, an adapter that scopes to it alone can never leak.
 */
class FindSandbox(
  val podUri: URI,
  val credentials: SempodsCredentials,
  val visibleContexts: Set<URI>?,
  val contextFilter: Set<URI>? = null,
) {

  /**
   * The single context set adapters and the [ResourceExpander] must scope to: the requested
   * [contextFilter] when present, otherwise the full [visibleContexts] ceiling. The *one* place
   * that encodes the "filter else ceiling" fallback, so no engine implementation forgets it.
   */
  fun effectiveScope(): Set<URI>? = contextFilter ?: visibleContexts

  /**
   * `true` when [effectiveScope] is a present-but-empty set — the caller requested a downscope (or
   * carries a ceiling) that resolved to no readable context, so the find MUST yield nothing. Engines
   * MUST honor this with a fail-closed short-circuit **before** building a query: an RDF4J `Dataset`
   * with no graphs is treated as "no restriction" (the whole store), not "match nothing", so handing
   * an empty scope to the store would leak every context. `null` (genuinely unrestricted) is
   * distinct and is *not* match-nothing.
   */
  fun matchesNothing(): Boolean = SparqlSandbox.matchesNothing(effectiveScope())
}
