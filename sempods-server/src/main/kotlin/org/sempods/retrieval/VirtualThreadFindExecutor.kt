package org.sempods.retrieval

import org.sempods.commons.trace.TraceContextHolder

/**
 * The default [FindExecutor]: a fresh virtual thread per task. It holds no idle threads, so one
 * instance serves every request and there is nothing to shut down.
 *
 * The trace bound on the submitting thread is re-bound inside the task, so an adapter's log lines
 * carry the request's `traceId` and its outgoing calls a `traceparent`. A submitting thread with
 * no trace hands none on.
 */
class VirtualThreadFindExecutor : FindExecutor {

  override fun execute(command: Runnable) {
    val trace = TraceContextHolder.get()
    Thread.ofVirtual().start {
      if (trace == null) command.run() else TraceContextHolder.with(trace) { command.run() }
    }
  }
}
