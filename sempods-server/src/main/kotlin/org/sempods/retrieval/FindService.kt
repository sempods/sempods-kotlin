package org.sempods.retrieval

import com.google.inject.Inject
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.sempods.commons.net.SempodsVocabulary
import org.sempods.commons.trace.TraceContextHolder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Central `find` orchestration. Fans out to every registered [FindAdapter] in parallel, merges
 * the returned models, and enriches the hits with the fixed expansion set
 * (`rdf:type` / `rdfs:label` / `schema:name`).
 *
 * - **A self-scoped adapter is trusted on what it matched**, and on the contexts its stored
 *   statements name ([FindAdapter.selfScoped]).
 * - **Any other adapter's answer is checked against the store first**, with one expansion over the
 *   resources it names, reused for those that are kept. A candidate no self-scoped adapter returned
 *   stays only when the store holds it in [FindSandbox.effectiveScope] — the expansion shows that,
 *   or [ResourceExpander.holdingContexts] before the limit — and, when [FindRequest.types] are
 *   given, only when the expansion reads one of them as its `rdf:type` there. It goes with its
 *   observations otherwise. Its stored statements only name candidates and do not reach the
 *   answer: what is stored about a kept hit comes from the expansion, and the contexts the
 *   expansion shows are where the hit is held.
 * - **Hits = IRI subjects** of the merged model that a context in [FindSandbox.effectiveScope]
 *   holds (`SPS-FIND-029`), at most [FindRequest.limit] of them (`SPS-FIND-025`). Under the limit a
 *   hit ranks by the lowest `schema:position` among its observations, then by IRI; a hit with no
 *   position ranks after every hit with one. A candidate the answer shows only through an
 *   observation is checked with [ResourceExpander.holdingContexts] before the limit applies, so a
 *   stale candidate takes no place from a held one. The expansion runs only over the kept hits, via
 *   the [ResourceExpander] (itself sandboxed), so this orchestrator stays free of any store/query
 *   specifics.
 * - **A kept hit keeps the blank nodes it reaches.** An observation (`<hit> sps:findResult _:b`,
 *   `SPS-FIND-028`) survives the cap with its details; a blank node no kept hit reaches is dropped.
 *   Each adapter's blank nodes are its own: two adapters' `_:m` stay two nodes.
 * - **Every statement leaves in a context that holds its hit** (`SPS-FIND-027`). A stored
 *   statement keeps its own; a stored statement with no context, or with one outside
 *   [FindSandbox.effectiveScope], is dropped (`SPS-FIND-030`), from an adapter and from the
 *   expansion alike. A generated statement its adapter attributes to a context outside the
 *   selection is dropped too (`SPS-FIND-010`): the caller narrowed the search away from it. A
 *   generated statement keeps a context in the selection when that context holds the hit; one with
 *   no context, or with one the hit is not held in, moves to the first, by IRI, of the contexts the
 *   service knows to hold the hit — those the answer shows and those
 *   [ResourceExpander.holdingContexts] returned. That is a holding context, not necessarily the
 *   first of every context that holds the hit. Where the answer shows the hit held elsewhere but not
 *   in the observation's own context, [ResourceExpander.holdingContexts] is asked first.
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

    // Fan out across adapters on virtual threads (Java 25); join and merge their models. A virtual
    // thread inherits no trace, so each task re-binds the caller's: an adapter logs under it, and
    // reaches what a test observes for it.
    val trace = TraceContextHolder.get()
    val futures = adapters.map { adapter ->
      executor.submit<Pair<Model, Boolean>> {
        val answer = { adapter.find(request, sandbox) to adapter.selfScoped }
        if (trace == null) answer() else TraceContextHolder.with(trace) { answer() }
      }
    }
    val scope = sandbox.effectiveScope()?.mapTo(HashSet()) { it.toString() }
    val trusted = LinkedHashModel()
    val unchecked = LinkedHashModel()
    futures.forEach { future ->
      val (model, selfScoped) = future.get()
      (if (selfScoped) trusted else unchecked).addAll(admitted(withOwnBlankNodes(model), scope))
    }

    // What an adapter that does not self-scope said is checked against the store: one expansion
    // over the resources it names, reused for those that are kept. Its candidate is a hit only where
    // the store holds it in the selection, typed as requested when a type is. Its stored statements
    // name the candidates and stay out of the answer: a context it gives a statement cannot be
    // checked, so the store's own expansion speaks for the resource.
    val vouched = trusted.subjects().filterIsInstance<IRI>().toSet()
    val checked = unchecked.subjects().filterIsInstance<IRI>().toSet()
    val verification = if (checked.isEmpty()) emptyList() else stored(expander.expand(checked, sandbox), scope)
    val storeHolding = HashMap<IRI, MutableSet<Resource>>()
    verification.forEach { st -> (st.subject as? IRI)?.let { storeHolding.getOrPut(it) { HashSet() } += checkNotNull(st.context) } }
    val typed = verification
      .filter { it.predicate == RDF.TYPE && it.`object` in request.types }
      .mapNotNullTo(HashSet()) { it.subject as? IRI }
    val merged = LinkedHashModel(trusted)
    unchecked.filterTo(merged, ::isGenerated)

    // Only a candidate a context in the selection holds is a hit (SPS-FIND-029). A stored
    // statement shows that; a candidate the answer names only through observations, and one the
    // expansion did not show, is looked up before the cap, so it cannot take a place a held
    // candidate would have had.
    val candidates = vouched + if (request.types.isEmpty()) checked else typed intersect checked
    val holding = storedHolding(trusted)
    storeHolding.forEach { (hit, contexts) -> if (hit in candidates) holding.getOrPut(hit) { HashSet() } += contexts }
    val unseen = candidates.filterTo(HashSet()) { it !in holding }
    if (unseen.isNotEmpty()) holding.addHeld(expander.holdingContexts(unseen, sandbox), unseen)

    // No rank fusion (#136): each hit ranks by its own lowest position, whichever engine gave it.
    val keep = candidates
      .filter { it in holding }
      .sortedWith(compareBy<IRI> { lowestPosition(it, merged) ?: Long.MAX_VALUE }.thenBy { it.stringValue() })
      .take(request.limit)
      .toSet()
    if (keep.isEmpty()) return LinkedHashModel()

    val result = reachableFrom(keep, merged)
    verification.filterTo(result) { it.subject in keep }
    val toExpand = keep - checked
    if (toExpand.isNotEmpty()) result.addAll(stored(expander.expand(toExpand, sandbox), scope))
    return placed(result, keep, holding, asked = unseen + checked, sandbox)
  }

  /** The lowest `schema:position` among [hit]'s observations in [model], or null when none has one. */
  private fun lowestPosition(hit: IRI, model: Model): Long? =
    model.filter(hit, FIND_RESULT, null).objects()
      .filterIsInstance<BNode>()
      .flatMap { observation -> model.filter(observation, POSITION, null).objects() }
      .mapNotNull { (it as? Literal)?.label?.trim()?.toLongOrNull() }
      .minOrNull()

  /** For each IRI subject of a stored statement in [model], the contexts those statements are in. */
  private fun storedHolding(model: Model): HashMap<IRI, MutableSet<Resource>> {
    val holding = HashMap<IRI, MutableSet<Resource>>()
    model.filterNot(::isGenerated).forEach { st ->
      (st.subject as? IRI)?.let { hit -> holding.getOrPut(hit) { HashSet() } += checkNotNull(st.context) }
    }
    return holding
  }

  /** Adds what the store said about [only], skipping a hit no context holds. */
  private fun HashMap<IRI, MutableSet<Resource>>.addHeld(answer: Map<IRI, Set<Resource>>, only: Set<IRI>) {
    answer.forEach { (hit, contexts) -> if (hit in only && contexts.isNotEmpty()) getOrPut(hit) { HashSet() } += contexts }
  }

  /** [model] with every blank node renamed to a fresh one, so no two adapters share one by label. */
  private fun withOwnBlankNodes(model: Model): Model {
    val renamed = HashMap<BNode, BNode>()
    fun own(value: Value): Value = if (value is BNode) renamed.getOrPut(value) { VF.createBNode() } else value
    return model.mapTo(LinkedHashModel()) { st ->
      VF.createStatement(own(st.subject) as Resource, st.predicate, own(st.`object`), st.context)
    }
  }

  /**
   * What an adapter may contribute: stored statements in [scope], and generated ones in [scope] or
   * with no context, which placement then gives one.
   */
  private fun admitted(model: Model, scope: Set<String>?): List<Statement> =
    model.filter { st ->
      val inScope = st.context != null && (scope == null || st.context.stringValue() in scope)
      inScope || (isGenerated(st) && st.context == null)
    }

  /** What the expansion may contribute: stored statements in [scope]; one without a context has no source. */
  private fun stored(model: Model, scope: Set<String>?): List<Statement> =
    model.filter { st -> !isGenerated(st) && st.context != null && (scope == null || st.context.stringValue() in scope) }

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

  /**
   * [result] with every generated statement in a context that holds its hit. [known] is what is
   * known of where the hits are held so far; [asked] are the hits the store was already asked about.
   */
  private fun placed(
    result: Model,
    hits: Set<IRI>,
    known: Map<IRI, Set<Resource>>,
    asked: Set<IRI>,
    sandbox: FindSandbox,
  ): Model {
    val generated = result.filter(::isGenerated)
    if (generated.isEmpty()) return result

    // The expansion's stored statements may show further holding contexts.
    val holding = storedHolding(result)
    known.forEach { (hit, contexts) -> if (hit in hits) holding.getOrPut(hit) { HashSet() } += contexts }
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

    // Ask the store about a hit whose observation sits in a context the answer does not show
    // holding it: a sparse adapter's answer is no evidence that the context does not. A hit the
    // store was asked about before the cap already has its full answer.
    val unconfirmed = HashSet<IRI>()
    generated.forEach { st ->
      val context = st.context ?: return@forEach
      owning(st).filterTo(unconfirmed) { it !in asked && context !in holding[it].orEmpty() }
    }
    if (unconfirmed.isNotEmpty()) holding.addHeld(expander.holdingContexts(unconfirmed, sandbox), unconfirmed)

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
    val POSITION: IRI = VF.createIRI("https://schema.org/position")
  }
}
