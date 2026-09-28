package org.sempods.commons.okhttp

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * What an outgoing request carries of the caller's trace.
 *
 * Over a real socket rather than by inspecting a `Request` object, because the question is what the
 * *server* receives — an interceptor that builds the right header and then proceeds with the
 * original request would pass every assertion made on the builder.
 */
class TraceparentInterceptorTest {

  private lateinit var server: HttpServer
  private var received: String? = null
  private var receivedState: String? = null

  private val client = OkHttpClient.Builder()
    .addInterceptor(TraceparentInterceptor)
    .build()

  @BeforeEach
  fun startServer() {
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/") { exchange ->
      received = exchange.requestHeaders.getFirst(TraceContext.TRACEPARENT)
      receivedState = exchange.requestHeaders.getFirst(TraceContext.TRACESTATE)
      exchange.sendResponseHeaders(204, -1)
      exchange.close()
    }
    server.start()
  }

  @AfterEach
  fun stopServer() {
    server.stop(0)
    TraceContextHolder.clear()
  }

  @Test
  fun `a bound trace travels as a fresh child span`() {
    val bound = TraceContext.random().copy(traceState = "congo=t61rcWkgMzE")

    TraceContextHolder.with(bound) { get() }

    val sent = checkNotNull(TraceContext.parse(received)) { "no traceparent reached the server" }
    assertEquals(bound.traceId, sent.traceId, "the journey is what carries")
    assertNotEquals(bound.spanId, sent.spanId, "every hop mints its own span")
    assertEquals(bound.traceState, receivedState)
  }

  @Test
  fun `a traceparent the caller set explicitly is left alone`() {
    val explicit = TraceContext.random()

    TraceContextHolder.with(TraceContext.random().copy(traceState = "congo=t61rcWkgMzE")) {
      get { it.header(TraceContext.TRACEPARENT, explicit.toHeader()) }
    }

    assertEquals(explicit.toHeader(), received)
    assertNull(receivedState, "the ambient tracestate belongs to the ambient trace")
  }

  @Test
  fun `a tracestate the caller set alone does not ride along with the ambient trace`() {
    TraceContextHolder.with(TraceContext.random()) {
      get { it.header(TraceContext.TRACESTATE, "stray=1") }
    }
    assertNull(receivedState)

    TraceContextHolder.with(TraceContext.random().copy(traceState = "congo=t61rcWkgMzE")) {
      get { it.header(TraceContext.TRACESTATE, "stray=1") }
    }
    assertEquals("congo=t61rcWkgMzE", receivedState)
  }

  @Test
  fun `outside a trace no header is sent`() {
    get()

    assertNull(received)
    assertNull(receivedState)
  }

  private fun get(customise: (Request.Builder) -> Unit = {}) {
    val request = Request.Builder().url("http://${server.address.hostString}:${server.address.port}/")
    customise(request)
    client.newCall(request.build()).execute().close()
  }
}
