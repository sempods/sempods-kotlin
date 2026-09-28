package org.sempods.commons.ktor.trace

import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.request.header
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Ktor half of the trace wiring, held to the same contract as `TraceContextFilterTest` in
 * `commons-jaxrs`: adopt or start, bind for the whole call, echo, and release.
 */
class KtorTraceContextTest {

  private val incoming = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  private val incomingState = "congo=t61rcWkgMzE"

  @AfterTest
  fun clearBinding() = TraceContextHolder.clear()

  @Test
  fun `adopts an incoming traceparent and echoes it`() = testApplication {
    application {
      installTraceContext()
      routing { get("/t") { call.respondText(TraceContextHolder.getTraceId() ?: "none") } }
    }
    val response = client.get("/t") { header(TraceContext.TRACEPARENT, incoming) }
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", response.bodyAsText())
    assertEquals(incoming, response.headers[TraceContext.TRACEPARENT])
  }

  @Test
  fun `starts a fresh trace when the header is absent or malformed`() = testApplication {
    application {
      installTraceContext()
      routing { get("/t") { call.respondText(TraceContextHolder.getTraceId() ?: "none") } }
    }
    for (header in listOf(null, "nonsense", "00-4bf92f3577b34da6a3ce929d0e0e4736")) {
      val response = client.get("/t") { header?.let { header(TraceContext.TRACEPARENT, it) } }
      val traceId = response.bodyAsText()
      assertEquals(32, traceId.length, "expected a fresh trace id, got '$traceId'")
      assertTrue(traceId != "4bf92f3577b34da6a3ce929d0e0e4736")
      assertNotNull(response.headers[TraceContext.TRACEPARENT])
    }
  }

  @Test
  fun `adopts the tracestate that arrives with the traceparent`() = testApplication {
    application {
      installTraceContext()
      routing { get("/t") { call.respondText(TraceContextHolder.get()?.traceState ?: "none") } }
    }
    val response = client.get("/t") {
      header(TraceContext.TRACEPARENT, incoming)
      header(TraceContext.TRACESTATE, incomingState)
      header(TraceContext.TRACESTATE, "rojo=00f067aa0ba902b7")
    }
    assertEquals("$incomingState,rojo=00f067aa0ba902b7", response.bodyAsText())
  }

  @Test
  fun `a fresh trace carries no tracestate`() = testApplication {
    application {
      installTraceContext()
      routing { get("/t") { call.respondText(TraceContextHolder.get()?.traceState ?: "none") } }
    }
    for (header in listOf(null, "nonsense")) {
      val response = client.get("/t") {
        header?.let { header(TraceContext.TRACEPARENT, it) }
        header(TraceContext.TRACESTATE, incomingState)
      }
      assertEquals("none", response.bodyAsText())
    }
  }

  @Test
  fun `the binding survives a dispatch to another thread`() = testApplication {
    application {
      installTraceContext()
      routing {
        get("/t") {
          // The whole reason `TraceContextElement` exists: a plain ThreadLocal would be gone here.
          val afterHop = withContext(Dispatchers.IO) {
            delay(1)
            TraceContextHolder.get()
          }
          call.respondText(afterHop?.let { "${it.traceId} ${it.traceState}" } ?: "lost")
        }
      }
    }
    val response = client.get("/t") {
      header(TraceContext.TRACEPARENT, incoming)
      header(TraceContext.TRACESTATE, incomingState)
    }
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736 $incomingState", response.bodyAsText())
  }

  @Test
  fun `the outbound plugin carries the trace id and mints a new span`() = testApplication {
    application {
      routing {
        get("/downstream") {
          call.respondText(call.request.headers[TraceContext.TRACEPARENT] ?: "none")
        }
      }
    }
    val outbound = createClient { install(TraceparentClientPlugin) }
    val bound = TraceContext.parse(incoming)!!

    val sent = withContext(TraceContextElement(bound)) {
      outbound.get("/downstream").bodyAsText()
    }

    val child = assertNotNull(TraceContext.parse(sent), "expected a traceparent on the wire")
    assertEquals(bound.traceId, child.traceId, "the journey must carry")
    assertTrue(child.spanId != bound.spanId, "the hop must not")
  }

  @Test
  fun `the outbound plugin carries the tracestate as it is`() = testApplication {
    application {
      routing {
        get("/downstream") {
          call.respondText(call.request.headers[TraceContext.TRACESTATE] ?: "none")
        }
      }
    }
    val outbound = createClient { install(TraceparentClientPlugin) }

    val sent = withContext(TraceContextElement(TraceContext.parse(incoming, incomingState)!!)) {
      outbound.get("/downstream").bodyAsText()
    }

    assertEquals(incomingState, sent)
  }

  @Test
  fun `the outbound plugin sends nothing outside a bound trace`() = testApplication {
    application {
      routing {
        get("/downstream") {
          call.respondText(call.request.headers[TraceContext.TRACEPARENT] ?: "none")
        }
      }
    }
    val outbound = createClient { install(TraceparentClientPlugin) }
    assertEquals("none", outbound.get("/downstream").bodyAsText())
  }

  @Test
  fun `an explicit traceparent beats the ambient one`() = testApplication {
    application {
      routing {
        get("/downstream") {
          val headers = call.request.headers
          call.respondText("${headers[TraceContext.TRACEPARENT]} ${headers[TraceContext.TRACESTATE]}")
        }
      }
    }
    val outbound = createClient { install(TraceparentClientPlugin) }
    val explicit = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

    val sent = withContext(TraceContextElement(TraceContext.parse(incoming, incomingState)!!)) {
      outbound.get("/downstream") { header(TraceContext.TRACEPARENT, explicit) }.bodyAsText()
    }

    // The ambient tracestate belongs to the ambient trace, so it stays off as well.
    assertEquals("$explicit null", sent)
  }
}
