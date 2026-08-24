package org.sempods.commons.ktor.trace

import org.sempods.commons.trace.TraceContext
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext

/** Where the call's trace is parked, for anything that needs it outside a coroutine. */
val TraceContextAttribute: AttributeKey<TraceContext> = AttributeKey("sempods.traceContext")

/** The trace bound to this call, or `null` if [installTraceContext] is not installed. */
val ApplicationCall.traceContext: TraceContext? get() = attributes.getOrNull(TraceContextAttribute)

/**
 * Binds the W3C trace of the incoming request for the whole call, and echoes it on the response.
 *
 * The Ktor counterpart of `TraceContextFilter` in `commons-jaxrs`, and deliberately the same
 * behaviour: a caller that sends a valid `traceparent` keeps its journey; one that sends nothing —
 * or something malformed, which the spec says to treat identically — starts a fresh trace here.
 * The received context is adopted as-is rather than opened as a child span, because nothing in
 * this deployment records spans. See `docs/request-tracing.md`.
 *
 * Implemented as a pipeline interception rather than a `createApplicationPlugin` hook because the
 * binding has to *wrap* the call: [TraceContextElement] is a coroutine-context element, and no
 * `onCall`-style hook can put one around the rest of the pipeline.
 */
fun Application.installTraceContext() {
  intercept(ApplicationCallPipeline.Setup) {
    val traceContext = TraceContext.parse(call.request.header(TraceContext.TRACEPARENT))
      ?: TraceContext.random()
    call.attributes.put(TraceContextAttribute, traceContext)
    // Set on the response now rather than in a response hook: by the time a handler responds the
    // headers are already on their way, and an echo written after that is silently dropped.
    call.response.header(TraceContext.TRACEPARENT, traceContext.toHeader())
    withContext(TraceContextElement(traceContext)) {
      proceed()
    }
  }
}
