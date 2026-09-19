package org.sempods.mcp.pods

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import org.sempods.client.core.SempodsAsync
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The bridge between this service, which is `suspend` throughout, and the client core, which
 * blocks.
 *
 * **Why a blocking client is not a cost here.** On Java 25 a blocking call on a virtual thread
 * parks and unmounts: no carrier thread is held while the socket waits, so a fan-out over every
 * connected pod costs one virtual thread each and nothing a platform-thread pool would have to be
 * sized for. The pinning that used to make this false was removed in JDK 24 (JEP 491). A bounded
 * dispatcher such as `Dispatchers.IO` would be the wrong home for exactly that reason — 64 threads
 * is a ceiling a fan-out can reach. [SempodsAsync] starts that virtual thread itself.
 *
 * **Cancellation goes through the call, not the thread.** `Thread.interrupt()` does not unblock an
 * OkHttp socket read, and Okio clears the interrupt flag on the way, so the obvious
 * `runInterruptible` would leave a cancelled coroutine waiting out the full request timeout.
 * [block] therefore receives the operation's own `Call.Factory` and every call it makes has to go
 * through it — that is what `SempodsAsyncOperation.cancel` reaches, a call nested inside another
 * call's body or read included. A call made through any other factory ends on its own terms.
 *
 * **And it has to be [suspendCancellableCoroutine].** `invokeOnCompletion` on the job fires when the
 * job *finishes* — which, for a job whose body is this blocking call, is after the very wait it was
 * supposed to cut short. `invokeOnCancellation` fires the moment cancellation is requested, and the
 * operation is submitted inside the block so that a coroutine cancelled before this ran cancels an
 * operation that has not started: it then completes at once and the work never runs.
 *
 * The trace is read on the caller's thread, where the coroutine's `TraceContextElement` has it
 * bound, and re-bound around the block — otherwise the request would leave without a `traceparent`
 * and one tool call would stop being one trace across the two processes.
 */
suspend fun <T> podIo(calls: Call.Factory, block: (Call.Factory) -> T): T {
  val trace = TraceContextHolder.get()
  return suspendCancellableCoroutine { continuation ->
    val operation = SempodsAsync(calls).submit { tracked -> bound(trace) { block(tracked) } }
    continuation.invokeOnCancellation { operation.cancel() }
    operation.result().whenComplete { value, failure ->
      if (failure == null) continuation.resume(value) else continuation.resumeWithException(unwrapped(failure))
    }
  }
}

private fun <T> bound(trace: TraceContext?, block: () -> T): T =
  if (trace == null) block() else TraceContextHolder.with(trace) { block() }

/**
 * What the work threw, out of the wrapper a dependent stage adds.
 *
 * The stage the operation completes hands the failure over as it was set; one derived from it would
 * wrap, and the surfaces catch the pod's own failure by type.
 */
private fun unwrapped(failure: Throwable): Throwable =
  if (failure is CompletionException) failure.cause ?: failure else failure
