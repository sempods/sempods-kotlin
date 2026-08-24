package org.sempods.retrieval

import com.google.inject.Inject
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Central `find` orchestration. Fans out to every registered [FindAdapter] in parallel, merges
 * the returned models, and enriches the hits with the fixed expansion set
 * (`rdf:type` / `rdfs:label` / `schema:name`).
 *
 * - **No post-filter:** each adapter self-scopes to the sandbox (Eigen-Scoping); the service
 *   trusts adapter output and does not re-filter by context.
 * - **Hits = IRI subjects** of the merged model. The expansion runs only over those, via the
 *   [ResourceExpander] (itself sandboxed), so this orchestrator stays free of any store/query
 *   specifics.
 *
 * Merge + expansion are central logic, never the adapters'.
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
   * @return a context-sandboxed RDF [Model]: the merged adapter hits plus the fixed
   *   `rdf:type` / `rdfs:label` / `schema:name` expansion over the hit resources. Empty when
   *   nothing matched.
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
    if (hits.isEmpty()) return merged

    // Common case (single adapter, whose SELECT already applied LIMIT): nothing to cap.
    if (hits.size <= request.limit) {
      merged.addAll(expander.expand(hits, sandbox))
      return merged
    }

    // Several adapters together overflowed the limit. Cap deterministically by IRI (consistent
    // with each adapter's `ORDER BY STR(?s)`) and prune the dropped hits' edges from the result.
    // TODO(M3): replace the IRI-order truncation with cross-adapter rank fusion once engines
    // return relevance scores — a flat union has no ranking to merge on yet.
    val keep = hits.sortedBy { it.stringValue() }.take(request.limit).toSet()
    val capped = LinkedHashModel()
    merged.forEach { statement -> if (statement.subject in keep) capped.add(statement) }
    capped.addAll(expander.expand(keep, sandbox))
    return capped
  }
}
