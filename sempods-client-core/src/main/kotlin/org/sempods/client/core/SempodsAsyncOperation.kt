package org.sempods.client.core

import okhttp3.Call
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** One piece of work a [SempodsAsync] started: its result, and the handle that cancels its calls. */
class SempodsAsyncOperation<T> internal constructor(calls: Call.Factory) {

  private val lock = Any()

  /** The calls the work made while it ran. Guarded by [lock]. */
  private val made = mutableListOf<Call>()

  @Volatile
  private var cancelled = false

  /** Set when the work has returned or will never run. Guarded by [lock]; [cancel] does nothing after it. */
  private var finished = false

  private val completion = CompletableFuture<T>()

  private val tracked = Call.Factory { request ->
    val call = calls.newCall(request)
    synchronized(lock) {
      if (cancelled) call.cancel() else if (!finished) made += call
    }
    call
  }

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
   * Once the work has returned this does nothing, so it cannot break a response the result still reads.
   */
  fun cancel() {
    val calls = synchronized(lock) {
      if (cancelled || finished) return
      cancelled = true
      made.toList()
    }
    calls.forEach(Call::cancel)
  }

  /**
   * How the work ended, once it has returned. The operation then holds no admission slot and no connection.
   *
   * | The work | The stage completes with |
   * |---|---|
   * | returned a value | the value |
   * | threw | that exception |
   * | was cancelled before it started | a `CancellationException`; the work never runs |
   * | was cancelled before it returned | a `CancellationException` whose cause is what the work threw, if anything; a value it still returned is closed when it is `AutoCloseable` |
   *
   * The stage cannot be completed or cancelled through this reference. A dependent stage added without an
   * `…Async` method runs on the operation's thread; anything slow belongs on an executor of its own.
   */
  fun result(): CompletionStage<T> = completion.minimalCompletionStage()

  internal fun run(work: SempodsAsyncWork<T>) {
    val start = synchronized(lock) {
      if (cancelled) finished = true
      !cancelled
    }
    if (!start) {
      completion.completeExceptionally(cancellation(cause = null))
      return
    }
    var value: T? = null
    var failure: Throwable? = null
    try {
      value = work.run(tracked)
    } catch (thrown: Throwable) {
      failure = thrown
    }
    val cancelledFirst = synchronized(lock) {
      finished = true
      made.clear()
      cancelled
    }
    when {
      cancelledFirst -> {
        if (failure == null) closeQuietly(value)
        completion.completeExceptionally(cancellation(failure))
      }
      failure != null -> completion.completeExceptionally(failure)
      else -> @Suppress("UNCHECKED_CAST") completion.complete(value as T)
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
}
