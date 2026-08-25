package org.sempods.commons.jaxrs.filters

import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.container.PreMatching

/**
 * Binds the W3C trace of the incoming request, and echoes it back on the response.
 *
 * A caller that sends a valid `traceparent` keeps its journey: the same trace id appears in this
 * process's logs and on every call it makes onward. A caller that sends nothing — or something
 * malformed, which the spec says to treat the same way — starts a fresh one here.
 *
 * The received context is adopted as-is rather than opening a child span for the server. Nothing
 * in this deployment records spans, so a parent chain would be written and never read; the trace
 * id is the part that has to survive, and it does.
 *
 * **`@PreMatching` is load-bearing.** A 404, 405 or 406 is decided *during* matching, so a
 * post-matching filter never runs for one and the failure reaches `ApiExceptionMapper` with no
 * trace at all — which is why 29 `406`s in 25 hours of production could not be attributed to a
 * caller. Nothing here reads what matching produces, so running earlier costs nothing and covers
 * the requests that never reach a resource method. Do not move it back.
 */
@PreMatching
class TraceContextFilter : ContainerRequestFilter, ContainerResponseFilter {

  override fun filter(requestContext: ContainerRequestContext) {
    val traceContext = TraceContext.parse(requestContext.getHeaderString(TraceContext.TRACEPARENT))
      ?: TraceContext.random()
    TraceContextHolder.set(traceContext)
    requestContext.setProperty(TRACE_CONTEXT_PROPERTY, traceContext)
  }

  override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
    // Read back from the request rather than the thread: an asynchronous response may well be
    // written on a different thread than the one that bound the trace.
    val traceContext = requestContext.getProperty(TRACE_CONTEXT_PROPERTY) as? TraceContext
      ?: TraceContextHolder.get()
    if (traceContext != null) {
      responseContext.headers.putSingle(TraceContext.TRACEPARENT, traceContext.toHeader())
    }
    TraceContextHolder.clear()
  }

  companion object {
    private const val TRACE_CONTEXT_PROPERTY = "commons.jaxrs.traceContext"
  }
}
