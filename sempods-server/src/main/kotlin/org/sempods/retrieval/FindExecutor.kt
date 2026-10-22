package org.sempods.retrieval

import java.util.concurrent.Executor

/**
 * Runs the work [FindService] fans out: one task per [FindAdapter] and request, all joined before
 * the request returns. Its own type, so a composition binds it without claiming the injector's
 * `Executor` key.
 *
 * The default is [VirtualThreadFindExecutor]. A deployment with a pool of its own — one that
 * propagates its own context, or is drained on shutdown — binds that instead, for example
 * `FindExecutor(pool::execute)`.
 *
 * An implementation runs every task it accepts, or throws from [execute] when it cannot accept
 * one; the exception then fails the request. Carrying the submitting thread's trace
 * ([org.sempods.commons.trace.TraceContextHolder]) into the task is the implementation's job:
 * a thread the task runs on does not inherit it.
 */
fun interface FindExecutor : Executor
