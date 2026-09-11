package org.sempods.client

import org.sempods.client.core.SempodsOperation
import java.util.concurrent.Callable

/**
 * A cancel handle for the request a thread is currently blocked on.
 *
 * **A bridge to [SempodsOperation], and only that.** Cancellation is now explicit: an operation is
 * passed to `SempodsSession.execute`, which is what a caller can hold, hand to another thread and
 * reason about. This class remains because `PodIo` in `:sempods-mcp` binds one per coroutine and
 * migrating that is [#152](https://github.com/sempods/sempods-kotlin/issues/152). It does nothing
 * of its own: [using] installs the operation on the thread, and the core picks it up when a call is
 * made without one.
 *
 * **A nested slot no longer hides the outer one.** Installing a second operation on a thread that
 * already has one would leave the outer caller holding a handle to work nobody can reach —
 * `PodOAuthClient` carried a comment warning about exactly that. [SempodsOperation.using] keeps the
 * operation that already owns the thread, so a nested call is cancelled by the outer handle.
 *
 * Cancelling is sticky: once cancelled, a call that has not started yet is refused as soon as it
 * tries to, so an abort that arrives in the gap between two requests is not silently lost.
 */
class SempodsCallSlot {

  private val operation = SempodsOperation()

  /** Aborts the current call, and every later one made through this slot. Safe from any thread. */
  fun cancel() = operation.cancel()

  val isCancelled: Boolean get() = operation.isCancelled

  companion object {

    /** Runs [block] with this slot's operation bound to the thread. */
    fun <T> using(slot: SempodsCallSlot, block: () -> T): T =
      SempodsOperation.using(slot.operation, Callable { block() })
  }
}
