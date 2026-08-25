package org.sempods.commons.ktor.trace

import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header

/**
 * Carries the current trace onto every outgoing Ktor-client request — the counterpart of
 * `TraceparentInterceptor` in `commons-okhttp` and of `SempodsHttpTransport.newRequest` in
 * `sempods-client`, one of the four outbound paths in this tree (`docs/request-tracing.md`).
 *
 * Each request gets its own span id via [TraceContext.newChild]: the trace id stays, the hop does
 * not. A request that already carries a `traceparent` is left alone — an explicit header beats an
 * ambient one.
 *
 * Reads the ambient trace through [TraceContextHolder], which is correct inside a Ktor call
 * because [installTraceContext] binds a [TraceContextElement] for the call's coroutine. Outside a
 * call — a background sweep, say — there is no ambient trace and the request goes out without the
 * header, which is the same thing the other two paths do.
 */
val TraceparentClientPlugin = createClientPlugin("Traceparent") {
  onRequest { request, _ ->
    val traceContext = TraceContextHolder.get() ?: return@onRequest
    if (request.headers.contains(TraceContext.TRACEPARENT)) return@onRequest
    request.header(TraceContext.TRACEPARENT, traceContext.newChild().toHeader())
  }
}
