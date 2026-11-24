package org.sempods.retrieval

import com.google.inject.Inject
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.sempods.commons.net.SempodsVocabulary
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Central `find` orchestration. Fans out to every registered [FindAdapter] in parallel, merges
 * the returned models, and enriches the hits with the fixed expansion set
 * (`rdf:type` / `rdfs:label` / `schema:name`).
 *
 * - **No post-filter on matching:** each adapter self-scopes to the sandbox (Eigen-Scoping); the
 *   service trusts what an adapter matched on.
 * - **Hits = IRI subjects** of the merged model, at most [FindRequest.limit] of them in IRI order
 *   (`SPS-FIND-025`). The expansion runs only over those, via the [ResourceExpander] (itself
 *   sandboxed), so this orchestrator stays free of any store/query specifics.
 * - **A kept hit keeps the blank nodes it reaches.** An observation (`<hit> sps:findResult _:b`,
 *   `SPS-FIND-028`) survives the cap with its details; a blank node no kept hit reaches is dropped.
 *   Each adapter's blank nodes are its own: two adapters' `_:m` stay two nodes.
 * - **Every statement leaves in a context that holds its hit** (`SPS-FIND-027`). A stored
 *   statement keeps its own; a stored statement with no context, or with one outside
 *   [FindSandbox.effectiveScope], is dropped (`SPS-FIND-030`). A generated statement keeps the
 *   context its adapter gave it when that context holds the hit; otherwise it moves to the first such
 *   context in IRI order. Where the response names no holding context, or none that holds an
 *   observation's own context, [ResourceExpander.holdingContexts] is asked, and a hit no context
 *   holds is dropped (`SPS-FIND-029`), after it counted towards the limit.
 *
 * The flat representation drops the contexts afterwards, so both representations come from the
 * same placed model and grouping changes only the representation.
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
   * @return a context-sandboxed quad [Model]: the kept hits with what the adapters said about them,
   *   plus the fixed `rdf:type` / `rdfs:label` / `schema:name` expansion, every statement in a
   *   context of the effective scope. Empty when nothing matched.
   */
  fun find(request: FindRequest, sandbox: FindSandbox): Model {
    if (sandbox.matchesNothing()) return LinkedHashModel()

    // Fan out across adapters on virtual threads (Java 25); join and merge their models.
    // TODO: the submitted threads inherit no trace, so every adapter logs without a traceId.
    //  Capture the TraceContextHolder trace here and re-bind it inside the task, the way
    //  DirectTaskExecutor does.
    val futures = adapters.map { adapter ->
      executor.submit<Model> { adapter.find(request, sandbox) }
    }
    val scope = sandbox.effectiveScope()?.mapTo(HashSet()) { it.toString() }
    val merged = LinkedHashModel()
    futures.forEach { future -> merged.addAll(admitted(withOwnBlankNodes(future.get()), scope)) }

    // Cap deterministically by IRI, consistent with each adapter's `ORDER BY STR(?s)`.
    // TODO: replace the IRI-order truncation with cross-adapter rank fusion once engines
    //  return relevance scores — a flat union has no ranking to merge on yet.
    val keep = merged.subjects().filterIsInstance<IRI>().sortedBy { it.stringValue() }.take(request.limit).toSet()
    if (keep.isEmpty()) return LinkedHashModel()

    val result = reachableFrom(keep, merged)
    result.addAll(expander.expand(keep, sandbox))
    return placed(result, keep, sandbox)
  }

  /** [model] with every blank node renamed to a fresh one, so no two adapters share one by label. */
  private fun withOwnBlankNodes(model: Model): Model {
    val renamed = HashMap<BNode, BNode>()
    fun own(value: Value): Value = if (value is BNode) renamed.getOrPut(value) { VF.createBNode() } else value
    return model.mapTo(LinkedHashModel()) { st ->
      VF.createStatement(own(st.subject) as Resource, st.predicate, own(st.`object`), st.context)
    }
  }

  /** What an adapter may contribute: stored statements in [scope], and generated ones wherever it put them. */
  private fun admitted(model: Model, scope: Set<String>?): List<Statement> =
    model.filter { st -> isGenerated(st) || (st.context != null && (scope == null || st.context.stringValue() in scope)) }

  /** The statements of [hits] and of every blank node they reach, in [model]'s order. */
  private fun reachableFrom(hits: Set<IRI>, model: Model): Model {
    val reached = HashSet<Resource>(hits)
    var frontier: Collection<Resource> = hits
    while (frontier.isNotEmpty()) {
      frontier = frontier
        .flatMap { node -> model.filter(node, null, null).objects() }
        .filterIsInstance<BNode>()
        .filter { reached.add(it) }
    }
    return model.filterTo(LinkedHashModel()) { it.subject in reached }
  }

  /** [result] with every generated statement in a context that holds its hit, and unheld hits dropped. */
  private fun placed(result: Model, hits: Set<IRI>, sandbox: FindSandbox): Model {
    val generated = result.filter(::isGenerated)
    if (generated.isEmpty()) return result

    val holding = HashMap<IRI, MutableSet<Resource>>()
    result.filterNot(::isGenerated).forEach { st ->
      (st.subject as? IRI)?.let { hit -> holding.getOrPut(hit) { HashSet() } += checkNotNull(st.context) }
    }
    val annotated = generated.mapNotNullTo(HashSet()) { it.subject as? IRI }

    // Which hits own a blank node: those that reach it through generated statements.
    val owners = HashMap<BNode, MutableSet<IRI>>()
    annotated.forEach { hit ->
      var frontier: List<Resource> = listOf(hit)
      while (frontier.isNotEmpty()) {
        frontier = frontier
          .flatMap { node -> generated.filter { it.subject == node }.map { it.`object` } }
          .filterIsInstance<BNode>()
          .filter { owners.getOrPut(it) { HashSet() }.add(hit) }
      }
    }
    fun owning(st: Statement): Set<IRI> = when (val subject = st.subject) {
      is IRI -> setOf(subject)
      is BNode -> owners[subject].orEmpty()
      else -> emptySet()
    }

    // Ask the store about a hit the response names no holding context for, and about one whose
    // observation sits in a context the response does not show holding it: a sparse adapter's
    // answer is no evidence that the context does not.
    val unconfirmed = annotated.filterTo(HashSet()) { it !in holding }
    generated.forEach { st ->
      val context = st.context ?: return@forEach
      owning(st).filterTo(unconfirmed) { context !in holding[it].orEmpty() }
    }
    if (unconfirmed.isNotEmpty()) {
      expander.holdingContexts(unconfirmed, sandbox).forEach { (hit, contexts) ->
        if (hit in hits && contexts.isNotEmpty()) holding.getOrPut(hit) { HashSet() } += contexts
      }
    }

    val out = LinkedHashModel()
    result.forEach { st ->
      if (!isGenerated(st)) {
        out.add(st)
        return@forEach
      }
      val eligible = owning(st).flatMap { holding[it].orEmpty() }.toSet()
      if (eligible.isEmpty()) return@forEach
      val context = st.context.takeIf { it in eligible } ?: eligible.minBy { it.stringValue() }
      out.add(st.subject, st.predicate, st.`object`, context)
    }
    return out
  }

  /** An observation link to a blank node, or a statement about such a node (`SPS-FIND-028`). */
  private fun isGenerated(st: Statement): Boolean =
    st.subject is BNode || (st.predicate == FIND_RESULT && st.`object` is BNode)

  private companion object {
    val VF: SimpleValueFactory = SimpleValueFactory.getInstance()
    val FIND_RESULT: IRI = VF.createIRI(SempodsVocabulary.FIND_RESULT)
  }
}
