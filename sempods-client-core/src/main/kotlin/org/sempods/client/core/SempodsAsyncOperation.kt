package org.sempods.client.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** One piece of work a [SempodsAsync] started: its result, and the handle that cancels its calls. */
class SempodsAsyncOperation<T> private constructor(calls: Call.Factory) {

  private val lock = Any()

  /**
   * The work's calls that can still be running, made and neither failed nor closed, each with its response
   * once one arrived. A long run of calls holds only the ones still open. Guarded by [lock].
   */
  private val open: MutableMap<Call, Response?> = IdentityHashMap()

  @Volatile
  private var cancelled = false

  /** Set when the work begins. Guarded by [lock]. */
  private var started = false

  /** Set when the work has returned or will never run. Guarded by [lock]; [cancel] does nothing after it. */
  private var finished = false

  private val completion = CompletableFuture<T>()

  private val tracked = Call.Factory { request -> track(calls.newCall(request)) }

  /** Whether [cancel] took effect: it was called before the work returned. */
  val isCancelled: Boolean
    get() = cancelled

  /**
   * Cancels every call the work made and every call it makes from now on, from any thread and as often as
   * needed.
   *
   * It reaches a call waiting for an admission slot or for a credential another call is fetching, a call
   * sending or receiving, a body being read, and the gap between two calls. A call that is fetching its own
   * credential ends when that fetch returns ([SempodsAuthAttempt.calls]).
   *
   * Before the work has started, the operation completes at once and the work never runs, even when the
   * executor holding it never gets to it. Once the work has returned this does nothing, so it cannot break a
   * response the result still reads.
   */
  fun cancel() {
    var beforeStart = false
    val calls = synchronized(lock) {
      if (cancelled || finished) return
      cancelled = true
      beforeStart = !started
      if (beforeStart) finished = true
      open.keys.toList()
    }
    if (beforeStart) completion.completeExceptionally(cancellation(cause = null))
    calls.forEach(Call::cancel)
  }

  /**
   * How the work ended: once it has returned, or at once when it was cancelled before it started.
   *
   * When the work threw or was cancelled, every response of its calls still open is closed first, so the
   * operation holds no admission slot and no connection. When it returned a value, what the value holds is
   * the caller's: a response the work returned unread stays open.
   *
   * | The work | The stage completes with |
   * |---|---|
   * | returned a value | the value |
   * | threw | that exception |
   * | was cancelled before it started | a `CancellationException`, at once; the work never runs |
   * | was cancelled before it returned | a `CancellationException` whose cause is what the work threw, if anything; a value it still returned is closed when it is `AutoCloseable` |
   *
   * The stage cannot be completed or cancelled through this reference. A dependent stage added without an
   * `…Async` method runs on the thread that completes the stage, usually the operation's, or on the thread
   * that adds it once the stage is complete. Anything slow belongs on an executor of its own.
   */
  fun result(): CompletionStage<T> = completion.minimalCompletionStage()

  @JvmSynthetic
  internal fun run(work: SempodsAsyncWork<T>) {
    synchronized(lock) {
      // Cancelled before it started: [cancel] completed the operation already.
      if (cancelled) return
      started = true
    }
    var value: T? = null
    var failure: Throwable? = null
    try {
      value = work.run(tracked)
    } catch (thrown: Throwable) {
      failure = thrown
    }
    var outstanding: List<Response> = emptyList()
    val cancelledFirst = synchronized(lock) {
      finished = true
      if (cancelled || failure != null) outstanding = open.values.filterNotNull()
      open.clear()
      cancelled
    }
    outstanding.forEach(::closeQuietly)
    when {
      cancelledFirst -> {
        if (failure == null) closeQuietly(value)
        completion.completeExceptionally(cancellation(failure))
      }
      failure != null -> completion.completeExceptionally(failure)
      else -> @Suppress("UNCHECKED_CAST") completion.complete(value as T)
    }
  }

  /** How many of the work's calls are still open. */
  @JvmSynthetic
  internal fun openCalls(): Int = synchronized(lock) { open.size }

  private fun track(call: Call): Call {
    synchronized(lock) {
      if (cancelled) call.cancel() else if (!finished) open[call] = null
    }
    return TrackedCall(call)
  }

  private fun closed(call: Call) {
    synchronized(lock) { open.remove(call) }
  }

  /** [call], recorded in [open] with its response, and dropped from it when it fails or that body is closed. */
  private inner class TrackedCall(private val call: Call) : Call by call {

    override fun execute(): Response {
      val response = try {
        call.execute()
      } catch (failure: Throwable) {
        closed(call)
        throw failure
      }
      return observed(response)
    }

    override fun enqueue(responseCallback: Callback) {
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          closed(this@TrackedCall.call)
          responseCallback.onFailure(this@TrackedCall, e)
        }

        override fun onResponse(call: Call, response: Response) {
          responseCallback.onResponse(this@TrackedCall, observed(response))
        }
      })
    }

    override fun clone(): Call = track(call.clone())

    private fun observed(response: Response): Response {
      val observed = response.newBuilder().body(ClosingBody(response.body) { closed(call) }).build()
      synchronized(lock) { if (open.containsKey(call)) open[call] = observed }
      return observed
    }
  }

  private fun cancellation(cause: Throwable?): CancellationException =
    CancellationException("The operation was cancelled.").apply { if (cause != null) initCause(cause) }

  private fun closeQuietly(value: Any?) {
    try {
      (value as? AutoCloseable)?.close()
    } catch (_: Exception) {
      // The caller asked for nothing from this value; a failure to close it has no one to go to.
    }
  }

  internal companion object {

    @JvmSynthetic
    internal fun <T> of(calls: Call.Factory): SempodsAsyncOperation<T> = SempodsAsyncOperation(calls)
  }
}
