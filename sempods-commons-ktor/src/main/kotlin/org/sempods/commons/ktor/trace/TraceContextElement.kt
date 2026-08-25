package org.sempods.commons.ktor.trace

import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carries a [TraceContext] across coroutine dispatches by re-binding [TraceContextHolder] on
 * whichever thread the coroutine resumes on.
 *
 * This is what makes one trace mechanism serve both halves of the tree. [TraceContextHolder] is a
 * `ThreadLocal` — the right choice for the Jersey services, where a request occupies one thread
 * from filter to filter, and useless on its own in a Ktor service, where a single call hops
 * threads at every suspension point. Rather than give the Ktor services a second, coroutine-shaped
 * trace binding to keep in step with the first, this element makes the existing one true inside a
 * coroutine: `TraceContextHolder.get()` answers correctly after a dispatch, and so does the MDC —
 * the holder writes the `traceId` label in the same call that binds the context, so `[%X]` in
 * `logback-base.xml` renders it in a Ktor log line exactly as it does in a Jersey one.
 *
 * The previous binding is saved and restored rather than cleared, so a nested `withContext` leaves
 * its caller's trace intact — the same contract [TraceContextHolder.with] has.
 */
class TraceContextElement(private val traceContext: TraceContext) : ThreadContextElement<TraceContext?> {

  companion object Key : CoroutineContext.Key<TraceContextElement>

  override val key: CoroutineContext.Key<TraceContextElement> get() = Key

  override fun updateThreadContext(context: CoroutineContext): TraceContext? {
    val previous = TraceContextHolder.get()
    TraceContextHolder.set(traceContext)
    return previous
  }

  override fun restoreThreadContext(context: CoroutineContext, oldState: TraceContext?) {
    if (oldState == null) TraceContextHolder.clear() else TraceContextHolder.set(oldState)
  }
}
