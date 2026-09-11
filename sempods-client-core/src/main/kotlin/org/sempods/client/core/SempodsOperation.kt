package org.sempods.client.core

import java.util.concurrent.Callable

/**
 * One execution a caller can abort.
 *
 * **Why this is not `Thread.interrupt()`.** The obvious way to abort a blocking call from another
 * thread does not work here: the engine blocks on plain socket reads, which interruption does not
 * unblock, and Okio *clears* the interrupt flag on the way — so an interrupt neither stops the call
 * nor leaves a trace that it was tried. What does work is cancelling the call object, which closes
 * the socket, and that is what an operation holds.
 *
 * **Cancelling is sticky, and that is what makes it safe between attempts.** Once cancelled, work
 * that has not started yet is refused as soon as it tries to — so an abort arriving in the gap
 * before the request is bound to a connection, or between an authentication failure and its retry,
 * is not silently lost and cannot start uncancelled follow-up work.
 *
 * **What it reaches, and what it does not.** Admission, every attempt, the connection and the body
 * handler are all checked. A consumer's own [SempodsRequestAuth] or [SempodsBodyHandler] is not
 * forcibly stopped — nothing here terminates arbitrary code on another thread. A hook that may run
 * long should poll [isCancelled]; one that does not will finish, and the operation ends after it.
 *
 * Pass one to [SempodsSession.execute] explicitly. [using] binds one to the current thread instead,
 * which is what a bridge from an older cancellation handle needs.
 */
class SempodsOperation {

  @Volatile private var cancelInFlight: Runnable? = null
  @Volatile private var cancelled: Boolean = false

  /** Aborts the current attempt, and every later one. Safe from any thread. */
  fun cancel() {
    cancelled = true
    cancelInFlight?.run()
  }

  val isCancelled: Boolean get() = cancelled

  internal fun bind(cancel: Runnable) {
    cancelInFlight = cancel
    // The cancel may have arrived before the call existed; do not let it fall between the two.
    if (cancelled) cancel.run()
  }

  internal fun unbind() {
    cancelInFlight = null
  }

  internal fun failIfCancelled() {
    if (cancelled) throw SempodsTransportException("The operation was cancelled.")
  }

  companion object {

    private val CURRENT = ThreadLocal<SempodsOperation?>()

    /**
     * Runs [block] with [operation] bound to this thread, so a call that is not given one
     * explicitly still finds it.
     *
     * **An already-bound operation is kept.** A nested call must not install a second operation:
     * the outer caller holds the handle to the first, and replacing it would leave that handle
     * pointing at work nobody can reach. The inner block runs under the operation that already
     * owns the thread, which is what makes the outer cancel keep working.
     */
    @JvmStatic
    fun <T> using(operation: SempodsOperation, block: Callable<T>): T {
      val previous = CURRENT.get()
      if (previous != null) return block.call()
      CURRENT.set(operation)
      return try {
        block.call()
      } finally {
        CURRENT.set(null)
      }
    }

    /** The operation bound to this thread, or `null`. */
    @JvmStatic
    fun current(): SempodsOperation? = CURRENT.get()
  }
}
