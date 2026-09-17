package org.sempods.client.core

import okhttp3.Call
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Runs blocking work on the client core away from the caller's thread, with a handle that cancels its
 * calls.
 *
 * ```java
 * SempodsAsync async = new SempodsAsync(client);
 * SempodsAsyncOperation<SempodsResponse<Boolean>> ask =
 *     async.submit(calls -> new SempodsPod(session, calls).sparql().ask("ASK { ?s ?p ?o }"));
 * ask.result().thenAccept(answer -> ...);
 * ask.cancel();
 * ```
 *
 * **One virtual thread per operation**, unless [executor] is given. A blocking call on a virtual thread
 * holds no platform thread while it waits, so there is nothing to size or to close. An executor passed in
 * stays the caller's: this never shuts it down, and one that refuses the work makes [submit] throw.
 *
 * **Each operation blocks the thread it runs on.** A fork-join pool or an event loop is the wrong executor,
 * and so is one that runs the task on the calling thread: with `Runnable::run`, [submit] returns only once
 * the work has ended, too late for its handle to cancel anything.
 *
 * **Nothing travels to the operation's thread on its own**, a trace context included. A caller that needs
 * one passes an executor that carries it, such as OpenTelemetry's `Context.taskWrapping`.
 *
 * Admission, deadlines, authentication and resends belong to the calls, as they do on the caller's thread:
 * the client [SempodsOkHttp.install] configured applies them.
 */
class SempodsAsync @JvmOverloads constructor(
  /** What the work's calls run on: a client [SempodsOkHttp.install] configured, or a factory over one. */
  val calls: Call.Factory,
  private val executor: Executor? = null,
) {

  /** Hands [work] to a new virtual thread, or to [executor], and returns its handle. */
  fun <T> submit(work: SempodsAsyncWork<T>): SempodsAsyncOperation<T> {
    val operation = SempodsAsyncOperation<T>(calls)
    val task = Runnable { operation.run(work) }
    if (executor == null) Thread.ofVirtual().name("sempods-async").start(task) else executor.execute(task)
    return operation
  }
}

/**
 * The work a [SempodsAsync] runs. Every call it makes goes through `calls`, which is how
 * [SempodsAsyncOperation.cancel] reaches it.
 *
 * Cancelling cancels those calls and nothing else, and interrupts no thread. A wait on anything else, such
 * as a latch or a call through another factory, ends on its own terms.
 */
fun interface SempodsAsyncWork<T> {

  @Throws(IOException::class)
  fun run(calls: Call.Factory): T
}
