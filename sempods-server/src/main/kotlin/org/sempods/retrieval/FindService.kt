package org.sempods.retrieval

import com.google.inject.Inject
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.sempods.commons.net.SempodsVocabulary
import java.math.BigInteger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Central `find` orchestration. Fans out to every registered [FindAdapter] in parallel, merges
 * the returned models, caps the hits to the limit, and enriches the kept hits with the fixed
 * expansion set (`rdf:type` / `rdfs:label` / `schema:name`).
 *
 * - **No post-filter:** each adapter self-scopes to the sandbox (Eigen-Scoping); the service
 *   trusts adapter output and does not re-filter by context.
 * - **Hits = IRI subjects** of the merged model. A blank node is never a hit: it is whatever an
 *   adapter hung off a hit, such as a node behind `sps:findResult`. The expansion runs only over
 *   the hits, via the [ResourceExpander] (itself sandboxed), so this orchestrator stays free of
 *   any store/query specifics.
 * - **The cap keeps the best-ranked hits.** A hit's rank is the lowest integer `schema:position`
 *   on any node its `sps:findResult` links to. Ranked hits come before unranked ones, and the IRI
 *   breaks ties, so a hit no adapter ranked is kept in IRI order.
 * - **A kept hit keeps what hangs off it:** the result holds the kept hits' statements and every
 *   blank node reachable from them. A dropped hit's statements and its blank nodes go.
 *
 * Merge + cap + expansion are central logic, never the adapters'.
 */
class FindService @Inject constructor(
  private val adapters: Set<@JvmSuppressWildcards FindAdapter>,
  private val expander: ResourceExpander,
) {

  // Shared across requests: a virtual-thread-per-task executor spawns a fresh virtual thread per
  // submitted task and holds no idle threads, so one instance for this singleton's lifetime is
  // enough — no per-request executor churn, and nothing to shut down.
  private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

  /**
   * Run a `find` against one pod and return the result graph.
   *
   * @param request the parsed query: tokens, an optional `rdf:type` constraint (OR-combined),
   *   and the (already clamped) limit.
   * @param sandbox the pod-scoped authorization context. The pod is identified by
   *   [FindSandbox.podUri] (its global URI); [FindSandbox.effectiveScope] (the optional caller
   *   context downscope, else the readable ceiling) is the sandbox both the adapters and the
   *   expansion stay within.
   * @return a context-sandboxed RDF [Model]: at most [FindRequest.limit] hits with the blank nodes
   *   reachable from them, plus the fixed `rdf:type` / `rdfs:label` / `schema:name` expansion
   *   over those hits. Empty when nothing matched.
   */
  fun find(request: FindRequest, sandbox: FindSandbox): Model {
    val merged = LinkedHashModel()

    // Fan out across adapters on virtual threads (Java 25); join and merge their models.
    // TODO: the submitted threads inherit no trace, so every adapter logs without a traceId.
    //  Capture the TraceContextHolder trace here and re-bind it inside the task, the way
    //  DirectTaskExecutor does.
    val futures = adapters.map { adapter ->
      executor.submit<Model> { adapter.find(request, sandbox) }
    }
    futures.forEach { future -> merged.addAll(future.get()) }

    val hits: List<IRI> = merged.subjects().filterIsInstance<IRI>()
    if (hits.isEmpty()) return LinkedHashModel()

    val keep: Set<IRI> = hits
      .sortedWith(byRank(merged).thenBy { it.stringValue() })
      .take(request.limit)
      .toSet()
    val result = reachableFrom(merged, keep)
    result.addAll(expander.expand(keep, sandbox))
    return result
  }

  private companion object {

    private val FIND_RESULT: IRI = Values.iri(SempodsVocabulary.FIND_RESULT)
    private val POSITION: IRI = Values.iri("https://schema.org/position")

    /** Ranked hits first, lower position first; unranked hits compare equal to each other. */
    private fun byRank(model: Model): Comparator<IRI> {
      val ranks = HashMap<IRI, BigInteger?>()
      return Comparator { a, b ->
        val rankA = ranks.getOrPut(a) { rankOf(model, a) }
        val rankB = ranks.getOrPut(b) { rankOf(model, b) }
        when {
          rankA != null && rankB != null -> rankA.compareTo(rankB)
          rankA != null -> -1
          rankB != null -> 1
          else -> 0
        }
      }
    }

    /** The lowest integer `schema:position` behind [hit]'s `sps:findResult` links, or `null`. */
    private fun rankOf(model: Model, hit: IRI): BigInteger? =
      model.filter(hit, FIND_RESULT, null).objects().asSequence()
        .filterIsInstance<Resource>()
        .flatMap { node -> model.filter(node, POSITION, null).objects() }
        .filterIsInstance<Literal>()
        .mapNotNull { runCatching { it.integerValue() }.getOrNull() }
        .minOrNull()

    /** The statements of [roots] and of every blank node reachable from them through objects. */
    private fun reachableFrom(model: Model, roots: Set<IRI>): Model {
      val subjects: MutableSet<Resource> = LinkedHashSet(roots)
      val frontier = ArrayDeque<Resource>(roots)
      while (frontier.isNotEmpty()) {
        model.filter(frontier.removeFirst(), null, null).objects()
          .filterIsInstance<Resource>()
          .filter { it.isBNode && subjects.add(it) }
          .forEach(frontier::addLast)
      }
      val result = LinkedHashModel()
      subjects.forEach { subject -> result.addAll(model.filter(subject, null, null)) }
      return result
    }
  }
}
