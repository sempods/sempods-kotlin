package org.sempods.retrieval.spi

/**
 * Runs the `find` adapters, one task each, when [org.sempods.retrieval.FindService] fans out to
 * them. A deployment that bounds its concurrency through pools of its own binds one of them here,
 * for example `FindExecutor(pool::execute)`.
 *
 * The task binds the caller's trace itself, so an executor owes it nothing but a thread. The
 * service waits for every task of a `find` before it answers, so a pool that queues tasks delays
 * the answer and a pool that rejects one fails it.
 */
fun interface FindExecutor {

  /** Runs [task] on some thread, now or later. */
  fun execute(task: Runnable)
}
