package org.sempods.commons.okhttp

import okhttp3.Interceptor
import okhttp3.Response
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder

/**
 * Carries the current trace onto every outgoing OkHttp request.
 *
 * Each request gets its own span id via [TraceContext.newChild] — the trace id stays, the hop does
 * not — and the trace's [TraceContext.traceState] as it is, in place of any `tracestate` the request
 * carried without a `traceparent`. A request that already carries a `traceparent` is left alone,
 * `tracestate` included: an explicit header beats an ambient one.
 *
 * **An application interceptor, never a network one**, and that is load-bearing rather than a
 * preference. [TraceContextHolder] is a `ThreadLocal`; an application interceptor runs on the thread
 * that called `execute()`, where the trace is bound, while a network interceptor may run after a
 * hand-off and would read an empty holder. It is also the layer that survives a redirect or a
 * retry as one logical request, which is what a span should be.
 */
object TraceparentInterceptor : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val traceContext = TraceContextHolder.get()
    if (traceContext == null || request.header(TraceContext.TRACEPARENT) != null) {
      return chain.proceed(request)
    }
    val child = traceContext.newChild()
    val traced = request.newBuilder().header(TraceContext.TRACEPARENT, child.toHeader())
    child.traceState
      ?.let { traced.header(TraceContext.TRACESTATE, it) }
      ?: traced.removeHeader(TraceContext.TRACESTATE)
    return chain.proceed(traced.build())
  }
}
