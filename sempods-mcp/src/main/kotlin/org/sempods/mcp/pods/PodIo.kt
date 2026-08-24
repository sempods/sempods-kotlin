package org.sempods.mcp.pods

import kotlinx.coroutines.suspendCancellableCoroutine
import org.sempods.client.SempodsCallSlot
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import java.util.concurrent.Executors

/**
 * The bridge between this service, which is `suspend` throughout, and `:sempods-client`, which
 * blocks.
 *
 * **Why a blocking client is not a cost here.** On Java 25 a blocking call on a virtual thread
 * parks and unmounts: no carrier thread is held while the socket waits, so a fan-out over every
 * connected pod costs one virtual thread each and nothing a platform-thread pool would have to be
 * sized for. The pinning that used to make this false was removed in JDK 24 (JEP 491). A bounded
 * dispatcher such as `Dispatchers.IO` would be the wrong home for exactly that reason — 64 threads
 * is a ceiling a fan-out can reach.
 */
private val podIoExecutor = Executors.newVirtualThreadPerTaskExecutor()

/**
 * Runs a blocking pod call on a virtual thread, with coroutine cancellation wired to the HTTP call
 * and the caller's trace carried across the thread hop.
 *
 * **Cancellation goes through the call handle, not the thread.** `Thread.interrupt()` does not
 * unblock an OkHttp socket read, and Okio clears the interrupt flag on the way, so the obvious
 * `runInterruptible` would leave a cancelled coroutine waiting out the full request timeout.
 * [SempodsCallSlot] closes the socket instead.
 *
 * **And it has to be [suspendCancellableCoroutine].** `invokeOnCompletion` on the job fires when the
 * job *finishes* — which, for a job whose body is this blocking call, is after the very wait it was
 * supposed to cut short. `invokeOnCancellation` fires the moment cancellation is requested.
 *
 * The trace is read on the caller's thread, where the coroutine's `TraceContextElement` has it
 * bound, and re-bound around the block — otherwise the request would leave without a `traceparent`
 * and one tool call would stop being one trace across the two processes.
 */
suspend fun <T> podIo(block: () -> T): T {
  val slot = SempodsCallSlot()
  val trace = TraceContextHolder.get()
  return suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { slot.cancel() }
    podIoExecutor.execute {
      continuation.resumeWith(runCatching { bound(trace, slot, block) })
    }
  }
}

private fun <T> bound(trace: TraceContext?, slot: SempodsCallSlot, block: () -> T): T =
  SempodsCallSlot.using(slot) {
    if (trace == null) block() else TraceContextHolder.with(trace) { block() }
  }
