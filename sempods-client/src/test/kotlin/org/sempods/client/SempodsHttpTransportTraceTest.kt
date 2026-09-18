package org.sempods.client

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import org.slf4j.event.Level
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The caller's trace on the way out, which this surface puts on a request as it builds it.
 *
 * `newRequest` is the only place it happens, so a request built any other way carries none — which
 * is why every call this transport makes is one it built ([`docs/request-tracing.md`]).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsHttpTransportTraceTest {

  private lateinit var server: ClientAndServer
  private lateinit var target: URI

  private val transport = SempodsHttpTransport()

  @BeforeAll
  fun startServer() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    target = URI("http://localhost:${server.port}/alice/thing")
  }

  @AfterAll
  fun stopServer() = server.stop()

  @BeforeEach
  fun answerAnything() {
    server.reset()
    server.`when`(request()).respond(response().withStatusCode(204))
  }

  private fun sentHeader(): String? =
    server.retrieveRecordedRequests(request()).single().getFirstHeader(TraceContext.TRACEPARENT)
      .takeIf { it.isNotEmpty() }

  @Test
  fun `a request carries the trace bound on the calling thread`() {
    val bound = TraceContext.random()

    TraceContextHolder.with(bound) { transport.send(transport.newRequest(target).GET().build()) }

    val header = sentHeader()
    assertTrue(header!!.contains(bound.traceId), "the trace id carries: $header")
    assertTrue(!header.contains(bound.spanId), "each request is its own span")
  }

  @Test
  fun `a request made outside a trace carries none`() {
    TraceContextHolder.clear()

    transport.send(transport.newRequest(target).GET().build())

    assertNull(sentHeader())
  }

  @Test
  fun `two requests in one trace share its id and not its span`() {
    TraceContextHolder.with(TraceContext.random()) {
      transport.send(transport.newRequest(target).GET().build())
      transport.send(transport.newRequest(target).GET().build())
    }

    val sent = server.retrieveRecordedRequests(request())
      .map { it.getFirstHeader(TraceContext.TRACEPARENT) }
    assertEquals(2, sent.size)
    assertEquals(sent[0].substringBefore('-').let { sent[0].split('-')[1] }, sent[1].split('-')[1], "one trace id")
    assertTrue(sent[0] != sent[1], "two spans")
  }
}
