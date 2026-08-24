package org.sempods.commons.jaxrs.filters

import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TraceContextFilterTest {

  private val filter = TraceContextFilter()

  private val incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val incomingHeader = "00-$incomingTraceId-00f067aa0ba902b7-01"

  @AfterEach
  fun clearBinding() {
    TraceContextHolder.clear()
  }

  private fun requestWith(traceparent: String?): ContainerRequestContext {
    val requestContext = mockk<ContainerRequestContext>(relaxed = true)
    every { requestContext.getHeaderString(TraceContext.TRACEPARENT) } returns traceparent
    return requestContext
  }

  @Test
  fun `a valid traceparent is adopted`() {
    filter.filter(requestWith(incomingHeader))

    assertEquals(incomingTraceId, TraceContextHolder.getTraceId())
    assertEquals("00f067aa0ba902b7", TraceContextHolder.get()?.spanId)
  }

  @Test
  fun `a missing traceparent starts a fresh trace`() {
    filter.filter(requestWith(null))

    val started = TraceContextHolder.get()
    assertNotNull(started)
    assertNotEquals(incomingTraceId, started.traceId)
  }

  @Test
  fun `a malformed traceparent is treated as missing, not repaired`() {
    // Adopting a broken id would corrupt the journey it claims to belong to.
    filter.filter(requestWith("00-not-a-trace-id-01"))

    val started = TraceContextHolder.get()
    assertNotNull(started)
    assertEquals(32, started.traceId.length)
  }

  @Test
  fun `the trace id reaches the log context`() {
    filter.filter(requestWith(incomingHeader))

    assertEquals(incomingTraceId, MDC.get(TraceContextHolder.MDC_TRACE_ID))
  }

  @Test
  fun `the response echoes the trace and the binding is released`() {
    val requestContext = requestWith(incomingHeader)
    val property = slot<Any>()
    every { requestContext.setProperty(any(), capture(property)) } returns Unit
    filter.filter(requestContext)
    every { requestContext.getProperty(any()) } answers { property.captured }

    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()
    every { responseContext.headers } returns headers

    filter.filter(requestContext, responseContext)

    assertEquals(incomingHeader, headers.getFirst(TraceContext.TRACEPARENT))
    // Request threads are pooled: a trace left bound would leak into the next request.
    assertNull(TraceContextHolder.get())
    assertNull(MDC.get(TraceContextHolder.MDC_TRACE_ID))
  }
}
