package org.sempods.commons.trace

import org.sempods.commons.logging.LoggingCtx

/**
 * The per-thread binding of the current [TraceContext].
 *
 * Storage and log label are set together on purpose: every path that binds a trace also gets it
 * into the MDC, so `[%X]` in the log pattern cannot silently disagree with what the outgoing
 * headers say.
 *
 * A `ThreadLocal` rather than a `ScopedValue`, deliberately. `ScopedValue` needs the whole
 * request inside one structured block, which the imperative [set] / [clear] pair below — driven
 * by a JAX-RS request filter and its matching response filter — cannot provide. It would also
 * buy nothing at the two thread hops in this codebase: neither inherits automatically, so both
 * capture and re-bind explicitly either way.
 */
object TraceContextHolder {

  /** MDC key; rendered by the `[%X]` conversion word in `logback.xml`. */
  const val MDC_TRACE_ID = "traceId"

  private val current = ThreadLocal<TraceContext?>()

  fun get(): TraceContext? = current.get()

  fun getTraceId(): String? = current.get()?.traceId

  /** Binds [context] until [clear]. For filters that cannot wrap the call — prefer [with]. */
  fun set(context: TraceContext) {
    current.set(context)
    LoggingCtx.putLabels(MDC_TRACE_ID to context.traceId)
  }

  fun clear() {
    current.remove()
    LoggingCtx.removeLabels(MDC_TRACE_ID)
  }

  /**
   * Runs [block] with [context] bound, restoring whatever was bound before — including nothing.
   *
   * Nesting matters here: test helpers rely on an inner call joining the trace its caller
   * already established rather than starting a second one.
   */
  fun <T> with(context: TraceContext, block: (TraceContext) -> T): T {
    val outer = current.get()
    current.set(context)
    val replacedLabels = LoggingCtx.putLabels(MDC_TRACE_ID to context.traceId)
    try {
      return block(context)
    } finally {
      if (outer == null) current.remove() else current.set(outer)
      LoggingCtx.removeLabels(MDC_TRACE_ID, replacements = replacedLabels)
    }
  }

  /** Joins the bound trace if there is one, otherwise starts a fresh one. */
  fun <T> withInherited(context: TraceContext? = null, block: (TraceContext) -> T): T =
    with(context ?: current.get() ?: TraceContext.random(), block)
}
