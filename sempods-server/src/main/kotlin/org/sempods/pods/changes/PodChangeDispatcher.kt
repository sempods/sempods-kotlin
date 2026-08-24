package org.sempods.pods.changes

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Fans a committed [PodChangeSet] out to every registered [PodChangeListener].
 *
 * Listeners are contributed via a Guice `Multibinder` (see `SempodsModule`), so new sinks plug in
 * without changing the write path. Dispatch is **synchronous** and runs post-commit inside the
 * write transaction's compensating-rollback guard.
 *
 * Two-phase by durability: the single [Durability.CRITICAL] listener runs first and its failure
 * propagates (the write path then compensates the store and fails the request);
 * [Durability.BEST_EFFORT] listeners run afterwards and their failures are logged and swallowed,
 * so a backup/index hiccup never rolls a user's write back. At most one critical listener is
 * allowed — the store compensation cannot undo a second critical sink's side effects (see the
 * `init` guard).
 *
 * Scoped as a singleton by an explicit binding in `SempodsModule` (the project binds in the module,
 * not via class-level annotations).
 */
class PodChangeDispatcher @Inject constructor(
  // @JvmSuppressWildcards so Guice injects Set<PodChangeListener>, not Set<? extends ...>.
  private val listeners: Set<@JvmSuppressWildcards PodChangeListener>,
) {

  init {
    val critical = listeners.count { it.durability == Durability.CRITICAL }
    require(critical <= 1) {
      "At most one CRITICAL PodChangeListener is allowed (store compensation cannot undo a second " +
        "critical sink's side effects); found $critical. Make additional sinks BEST_EFFORT."
    }
  }

  fun dispatch(changeSet: PodChangeSet) {
    if (changeSet.resources.isEmpty()) return

    // Critical first: a failure propagates so doWork compensates the store and fails the request.
    listeners.asSequence()
      .filter { it.durability == Durability.CRITICAL }
      .forEach { it.onChange(changeSet) }

    // Best-effort: failures are logged and swallowed — they never roll the store back.
    listeners.asSequence()
      .filter { it.durability == Durability.BEST_EFFORT }
      .forEach { listener ->
        try {
          listener.onChange(changeSet)
        } catch (e: Exception) {
          logger.warn(e) {
            "Best-effort pod change listener ${listener::class.java.simpleName} failed for pod ${changeSet.podName}; that sink is now behind and " +
              "will be reconciled later"
          }
        }
      }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
