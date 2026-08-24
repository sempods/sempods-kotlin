package org.sempods.mcp.pods

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.SempodsHttpTransport
import org.sempods.commons.ktor.trace.TraceContextElement
import org.sempods.commons.trace.TraceContext
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The bridge between this service's coroutines and the blocking client: that a trace survives the
 * thread hop, that cancellation actually reaches the socket, and that a fan-out is concurrent.
 */
class PodIoTest {

  private lateinit var server: ClientAndServer
  private val transport = SempodsHttpTransport()

  @BeforeEach fun setup() {
    server = ClientAndServer.startClientAndServer(0)
  }

  @AfterEach fun teardown() = server.stop()

  private fun url(path: String) = URI("http://localhost:${server.port}$path")

  private fun get(path: String) =
    transport.send(transport.newRequest(url(path)).GET().build())

  @Test
  fun `the caller's trace reaches the pod across the dispatcher hop`() = runBlocking {
    // The regression this guards: the trace binding is a ThreadLocal, and podIo runs the request on
    // a different thread than the one handling the MCP call. It survives only because
    // TraceContextElement re-binds on whichever thread the coroutine resumes on — assert it, because
    // losing it would be invisible until someone tried to follow a trace across the two processes.
    server.`when`(request().withMethod("GET").withPath("/traced"))
      .respond(response().withStatusCode(200).withBody("ok"))
    val trace = TraceContext.random()

    withContext(TraceContextElement(trace)) {
      podIo { get("/traced") }
    }

    val recorded = server.retrieveRecordedRequests(request().withPath("/traced"))
    assertEquals(1, recorded.size)
    val traceparent = recorded[0].getFirstHeader("traceparent")
    assertTrue(traceparent.isNotBlank(), "no traceparent on the outgoing request")
    assertTrue(traceparent.contains(trace.traceId), "trace id lost across the hop: $traceparent")
  }

  @Test
  fun `cancelling the caller aborts the request instead of waiting it out`() = runBlocking {
    // Thread.interrupt() does not unblock an OkHttp read — this is the check that the call handle
    // is what gets cancelled. Without it the coroutine would sit here for the full delay.
    val started = CompletableDeferred<Unit>()
    server.`when`(request().withMethod("GET").withPath("/slow"))
      .respond(response().withStatusCode(200).withBody("late").withDelay(java.util.concurrent.TimeUnit.SECONDS, 30))

    val elapsed = kotlin.system.measureTimeMillis {
      assertFailsWith<Exception> {
        withTimeout(2_000) {
          coroutineScope {
            val call = async { podIo { started.complete(Unit); get("/slow") } }
            started.await()
            call.await()
          }
        }
      }
    }
    assertTrue(elapsed < 10_000, "cancellation did not reach the socket; waited ${elapsed}ms")
  }

  @Test
  fun `a fan-out runs concurrently rather than one pod at a time`() = runBlocking {
    // Virtual threads are the reason a blocking client is affordable here: ten concurrent calls
    // must not serialise behind a bounded dispatcher.
    server.`when`(request().withMethod("GET").withPath("/wait"))
      .respond(response().withStatusCode(200).withBody("ok").withDelay(java.util.concurrent.TimeUnit.MILLISECONDS, 300))

    val elapsed = kotlin.system.measureTimeMillis {
      coroutineScope {
        (1..10).map { async { podIo { get("/wait") } } }.awaitAll()
      }
    }
    assertTrue(elapsed < 2_000, "ten 300ms calls took ${elapsed}ms — they serialised")
  }
}
