package org.sempods.retrieval

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.sempods.commons.net.SempodsVocabulary
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

/**
 * Central `find` orchestration. Fans out to every registered [FindAdapter] in parallel on the
 * bound [FindExecutor], merges the returned models, drops the hits it cannot verify, caps the rest
 * to the limit, and enriches the kept hits with the fixed expansion set (`rdf:type` /
 * `rdfs:label` / `schema:name`).
 *
 * - **An empty effective scope yields nothing** ([FindSandbox.matchesNothing]): no adapter runs.
 * - **Hits = IRI subjects** of the merged model. A blank node is never a hit: it is whatever an
 *   adapter hung off a hit, such as a node behind `sps:findResult`.
 * - **One expansion verifies and enriches.** The [ResourceExpander] (itself sandboxed) runs once,
 *   over every merged hit, so this orchestrator stays free of any store/query specifics.
 * - **A hit is present** when an adapter that is [selfScoped][FindAdapter.selfScoped] returned it,
 *   or when the expansion carries an `rdf:type` for it — one of [FindRequest.types] when those
 *   are given, any type otherwise. Every other hit is dropped, so an adapter that ignores the
 *   context scope or the `type` constraint cannot widen what the caller sees. With only
 *   self-scoped adapters every hit is present.
 * - **The cap keeps the best-ranked present hits.** A hit's rank is the lowest integer
 *   `schema:position` on any node its `sps:findResult` links to. Ranked hits come before unranked
 *   ones, and the IRI breaks ties, so a hit no adapter ranked is kept in IRI order. A dropped hit
 *   does not count against the limit.
 * - **A kept hit keeps what hangs off it:** the result holds the kept hits' statements, every
 *   blank node reachable from them, and their expansion. A dropped hit's statements, its blank
 *   nodes and its expansion go.
 * - **An adapter's failure fails the request.** It surfaces as the `ExecutionException`
 *   `Future.get()` throws, with the adapter's exception as its cause.
 * - **Each request logs at DEBUG** one line per adapter — its class name, how many hits it
 *   returned and how long it took — and one for the merge: hits, adapters, kept, and dropped as
 *   unverified or over the limit. Counts only, never a hit or a token. DEBUG, because a line every
 *   request writes is traffic (`docs/logging.md`).
 *
 * Merge + verification + cap + expansion are central logic, never the adapters'. The accepted cost
 * is an expansion over every merged hit, up to the number of adapters times the limit.
 */
class FindService @Inject constructor(
  private val adapters: Set<@JvmSuppressWildcards FindAdapter>,
  private val expander: ResourceExpander,
  private val executor: FindExecutor,
) {

  /**
   * Run a `find` against one pod and return the result graph.
   *
   * @param request the parsed query: tokens, an optional `rdf:type` constraint (OR-combined),
   *   and the (already clamped) limit.
   * @param sandbox the pod-scoped authorization context. The pod is identified by
   *   [FindSandbox.podUri] (its global URI); [FindSandbox.effectiveScope] (the optional caller
   *   context downscope, else the readable ceiling) is the sandbox every returned hit lies in.
   * @return a context-sandboxed RDF [Model]: at most [FindRequest.limit] present hits with the
   *   blank nodes reachable from them, plus the fixed `rdf:type` / `rdfs:label` / `schema:name`
   *   expansion over those hits. Empty when no hit is present.
   */
  fun find(request: FindRequest, sandbox: FindSandbox): Model {
    if (sandbox.matchesNothing()) return LinkedHashModel()
    val merged = LinkedHashModel()
    val vouched = HashSet<IRI>()

    // Fan out, then join in adapter order with `get()`: ApiExceptionMapper unwraps its
    // ExecutionException, so a failing adapter's own status survives. Each model is read on its
    // own first, because only a self-scoped adapter's hits are vouched for.
    val runs = adapters
      .map { adapter -> CompletableFuture.supplyAsync({ runAdapter(adapter, request, sandbox) }, executor) }
      .map { it.get() }
    runs.forEach { run ->
      logger.debug { "find: adapter '${run.name}' returned ${run.hitCount} hit(s) in ${run.millis}ms" }
      if (run.selfScoped) vouched.addAll(run.model.subjects().filterIsInstance<IRI>())
      merged.addAll(run.model)
    }

    val hits: List<IRI> = merged.subjects().filterIsInstance<IRI>()
    if (hits.isEmpty()) {
      logger.debug { summary(adapters = runs.size, hits = 0, present = 0, kept = 0) }
      return LinkedHashModel()
    }

    val expansion = expander.expand(hits, sandbox)
    val present = hits.filter { it in vouched || hasRequestedType(expansion, it, request.types) }

    val keep: Set<IRI> = present
      .sortedWith(byRank(merged).thenBy { it.stringValue() })
      .take(request.limit)
      .toSet()
    logger.debug { summary(adapters = runs.size, hits = hits.size, present = present.size, kept = keep.size) }
    val result = reachableFrom(merged, keep)
    keep.forEach { hit -> result.addAll(expansion.filter(hit, null, null)) }
    return result
  }

  /** One adapter's answer, with what the log needs to account for it. */
  private class AdapterRun(
    val name: String,
    val selfScoped: Boolean,
    val model: Model,
    val hitCount: Int,
    val millis: Long,
  )

  private companion object {

    private val logger = KotlinLogging.logger {}

    private val FIND_RESULT: IRI = Values.iri(SempodsVocabulary.FIND_RESULT)
    private val POSITION: IRI = Values.iri("https://schema.org/position")

    private fun runAdapter(adapter: FindAdapter, request: FindRequest, sandbox: FindSandbox): AdapterRun {
      val start = System.nanoTime()
      val model = adapter.find(request, sandbox)
      return AdapterRun(
        name = adapter.javaClass.simpleName.ifEmpty { adapter.javaClass.name },
        selfScoped = adapter.selfScoped,
        model = model,
        hitCount = model.subjects().count { it is IRI },
        millis = (System.nanoTime() - start) / 1_000_000,
      )
    }

    private fun summary(adapters: Int, hits: Int, present: Int, kept: Int): String =
      "find: merged $hits hit(s) from $adapters adapter(s); kept $kept, dropped ${hits - kept} " +
        "(${hits - present} unverified, ${present - kept} over limit)"

    /** Whether [expansion] types [hit]: with any `rdf:type` when [types] is empty, else with one of them. */
    private fun hasRequestedType(expansion: Model, hit: IRI, types: Set<IRI>): Boolean {
      val asserted = expansion.filter(hit, RDF.TYPE, null).objects()
      return if (types.isEmpty()) asserted.isNotEmpty() else asserted.any { it in types }
    }

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
