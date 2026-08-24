package org.sempods.client

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.slf4j.event.Level
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * `call` against `read`, because the difference is not obvious and getting it wrong is silent
 * either way: too tight and a legitimate stream is cut off mid-body, absent and a server that
 * answers slowly enough keeps a request open with nothing to stop it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsHttpTimeoutsTest {

  private lateinit var mockServer: ClientAndServer
  private lateinit var slow: URI

  @BeforeAll
  fun startServer() {
    mockServer = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    slow = URI("http://localhost:${mockServer.port}/slow")
  }

  @AfterAll
  fun stopServer() = mockServer.stop()

  @BeforeEach
  fun resetServer() {
    mockServer.reset()
    mockServer.`when`(request().withPath("/slow"))
      .respond(response().withStatusCode(200).withBody("late").withDelay(TimeUnit.SECONDS, 3))
  }

  private fun get(transport: SempodsHttpTransport) =
    transport.send(transport.newRequest(slow).GET().build())

  @Test
  fun `the whole-call deadline bounds an answer the read timeout would tolerate`() {
    // The distinction that matters: `read` only ever measures the gap between two bytes, so a
    // server answering slowly — or dripping just inside that gap — never trips it. Only `call`
    // bounds the total, which is why a transport a person is waiting on must set one.
    val transport = SempodsHttpTransport(
      timeouts = SempodsHttpTimeouts(read = Duration.ofSeconds(10), call = Duration.ofSeconds(1)),
    )
    val elapsed = measureTimeMillis {
      assertThrows<IOException> { get(transport) }
    }
    assertTrue(elapsed < 2_500, "the call deadline did not fire; waited ${elapsed}ms")
  }

  @Test
  fun `ZERO means no whole-call deadline, which is what a streaming read needs`() {
    // `dumpContext` streams a whole context and must not be cut off by elapsed time; the default is
    // ZERO for that reason, and this is the assertion that keeps someone from "tightening" it.
    val transport = SempodsHttpTransport(
      timeouts = SempodsHttpTimeouts(read = Duration.ofSeconds(10), call = Duration.ZERO),
    )
    assertEquals(200, get(transport).statusCode)
  }
}
