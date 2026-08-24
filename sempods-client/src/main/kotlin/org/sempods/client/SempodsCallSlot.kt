package org.sempods.client

/**
 * A cancel handle for the request a thread is currently blocked on.
 *
 * **Why this is not `Thread.interrupt()`.** The obvious way to abort a blocking call from another
 * thread does not work here: OkHttp blocks on plain socket reads, which interruption does not
 * unblock, and Okio *clears* the interrupt flag on the way — so an interrupt neither stops the call
 * nor leaves a trace that it was tried. The engine's own answer is to cancel the call object, which
 * closes the socket, and that is what this hands out.
 *
 * **Opt-in, and per thread.** A transport with no slot installed does nothing extra; a caller that
 * wants to abort — a coroutine whose job was cancelled, a supervisor enforcing its own deadline —
 * installs one around the blocking work with [using] and calls [cancel] from wherever it needs to.
 * Per thread because that is exactly the granularity a blocking call has: one thread is inside one
 * request. A caller that runs several requests concurrently gives each its own thread and its own
 * slot, which is what a bridge over a virtual-thread dispatcher does anyway.
 *
 * Cancelling is sticky: once cancelled, a call that has not started yet is refused as soon as it
 * tries to, so an abort that arrives in the gap between two requests is not silently lost.
 */
class SempodsCallSlot {

  @Volatile private var cancelInFlight: (() -> Unit)? = null
  @Volatile private var cancelled: Boolean = false

  /** Aborts the current call, and every later one made through this slot. Safe from any thread. */
  fun cancel() {
    cancelled = true
    cancelInFlight?.invoke()
  }

  val isCancelled: Boolean get() = cancelled

  internal fun bind(cancel: () -> Unit) {
    cancelInFlight = cancel
    // The cancel may have arrived before the call existed; do not let it fall between the two.
    if (cancelled) cancel()
  }

  internal fun unbind() {
    cancelInFlight = null
  }

  companion object {

    private val CURRENT = ThreadLocal<SempodsCallSlot?>()

    /** Runs [block] with [slot] bound to this thread, restoring whatever was bound before. */
    fun <T> using(slot: SempodsCallSlot, block: () -> T): T {
      val previous = CURRENT.get()
      CURRENT.set(slot)
      return try {
        block()
      } finally {
        CURRENT.set(previous)
      }
    }

    internal fun current(): SempodsCallSlot? = CURRENT.get()
  }
}
