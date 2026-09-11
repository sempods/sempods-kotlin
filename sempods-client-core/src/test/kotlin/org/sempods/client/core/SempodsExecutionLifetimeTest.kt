package org.sempods.client.core

import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
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

/**
 * What holds a resource, and what releases it.
 *
 * A body that is still arriving, an operation waiting for a slot, an abort that lands between two
 * attempts: each of these is a place where the honest answer and the convenient one differ, and
 * where being wrong shows up as a leak rather than as a failure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsExecutionLifetimeTest {

  private lateinit var server: ClientAndServer
  private lateinit var origin: String

  @BeforeAll
  fun start() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    origin = "http://localhost:${server.port}"
  }

  @AfterAll
  fun stop() {
    server.stop()
  }

  @BeforeEach
  fun reset() {
    server.reset()
  }

  private fun session(
    transport: SempodsTransport,
    auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
  ) = SempodsSession.builder(SempodsPodBase.of("$origin/alice")).transport(transport).auth(auth).build()

  @Test
  fun `a handler reads the body before the server has finished sending it`() {
    // Buffering the whole body first would make this test pass and the streaming claim false. What
    // proves incremental delivery is reading fewer bytes than were sent and arriving before EOF.
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("x".repeat(512 * 1024)).withDelay(TimeUnit.MILLISECONDS, 400),
    )
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      val firstBytes = a.execute(a.newRequest("GET", "big").build(), { streamed ->
        val head = ByteArray(16)
        streamed.bodyStream().readNBytes(head, 0, head.size)
        String(head)
      })
      assertEquals("x".repeat(16), firstBytes.body)
    }
  }

  @Test
  fun `the stream is closed however the handler leaves it`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("payload"))
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)

      // Returned early, having read nothing.
      val untouched = a.execute(a.newRequest("GET", "x").build(), { "ignored" })
      assertEquals("ignored", untouched.body)

      // Thrown out of.
      assertThrows<IOException> {
        a.execute(a.newRequest("GET", "x").build(), { throw IOException("handler said no") })
      }

      // And the transport still works afterwards, which is what a stranded connection would stop.
      assertEquals("payload", a.executeText(a.newRequest("GET", "x").build()).body)
    }
  }

  @Test
  fun `a handler failure is not reported as a broken connection`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("payload"))
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      val thrown = assertThrows<IllegalStateException> {
        a.execute(a.newRequest("GET", "x").build(), { error("the decoder gave up") })
      }
      assertEquals("the decoder gave up", thrown.message)
    }
  }

  @Test
  fun `cancelling before the request is bound stops it starting at all`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      val operation = SempodsOperation()
      operation.cancel()

      assertThrows<SempodsTransportException> {
        a.executeText(a.newRequest("GET", "x").build(), operation)
      }
      assertEquals(0, server.retrieveRecordedRequests(request()).size)
    }
  }

  @Test
  fun `cancelling between attempts stops the retry`() {
    // The gap the issue names: an abort that lands while a credential is being refreshed must not
    // start the request the refresh was for.
    val operation = SempodsOperation()
    val auth = object : SempodsRequestAuth {
      override fun apply(request: SempodsAuthRequest) {
        request.setHeader("Authorization", "Bearer attempt-${request.attempt()}")
      }

      override fun recover(challenge: SempodsAuthChallenge): SempodsAuthRecovery {
        operation.cancel()
        return SempodsAuthRecovery.retry()
      }
    }
    server.`when`(request()).respond(response().withStatusCode(401))

    SempodsTransport.builder().build().use { transport ->
      val a = session(transport, auth)
      assertThrows<SempodsTransportException> {
        a.executeText(a.newRequest("GET", "x").build(), operation)
      }
    }
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `cancelling during the read reaches the connection`() {
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("slow").withDelay(TimeUnit.SECONDS, 10),
    )
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      val operation = SempodsOperation()
      val pool = Executors.newSingleThreadExecutor()
      try {
        val call = pool.submit<Int> { a.executeText(a.newRequest("GET", "x").build(), operation).statusCode }
        Thread.sleep(300)
        operation.cancel()
        // Not "awaiting returned early": the call must fail, which is what closing the socket does.
        val thrown = assertThrows<java.util.concurrent.ExecutionException> { call.get(10, TimeUnit.SECONDS) }
        assertTrue(thrown.cause is SempodsTransportException, "was ${thrown.cause}")
        assertTrue(operation.isCancelled)
      } finally {
        pool.shutdownNow()
      }
    }
  }

  @Test
  fun `a nested use keeps the operation that already owns the thread`() {
    // What the legacy bridge needs: installing a second operation would leave the outer handle
    // pointing at work nobody can reach.
    val outer = SempodsOperation()
    val inner = SempodsOperation()
    val seen = SempodsOperation.using(outer) {
      SempodsOperation.using(inner) { SempodsOperation.current() }
    }
    assertTrue(seen === outer)

    outer.cancel()
    assertTrue(outer.isCancelled)
    assertFalse(inner.isCancelled)
  }

  @Test
  fun `an operation bound to the thread is picked up by a call that was given none`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      val operation = SempodsOperation()
      operation.cancel()

      assertThrows<SempodsTransportException> {
        SempodsOperation.using(operation) { a.executeText(a.newRequest("GET", "x").build()) }
      }
    }
  }

  @Test
  fun `an active stream holds its admission slot until the body is closed`() {
    val inHandler = CountDownLatch(1)
    val releaseHandler = CountDownLatch(1)
    server.`when`(request()).respond(response().withStatusCode(200).withBody("body"))

    SempodsTransport.builder()
      .admission(SempodsAdmission(maxActive = 1, maxWaiting = 4))
      .build()
      .use { transport ->
        val a = session(transport)
        val pool = Executors.newFixedThreadPool(2)
        try {
          val holding = pool.submit {
            a.execute(a.newRequest("GET", "x").build(), {
              inHandler.countDown()
              releaseHandler.await(10, TimeUnit.SECONDS)
              it.bodyText()
            })
          }
          assertTrue(inHandler.await(10, TimeUnit.SECONDS))

          // The single slot is taken by a body that is still being read, so a second operation
          // waits. Releasing on the status line instead would let this one straight through.
          val second = pool.submit<Int> { a.executeText(a.newRequest("GET", "x").build()).statusCode }
          Thread.sleep(300)
          assertFalse(second.isDone, "the slot was released before the body was closed")

          releaseHandler.countDown()
          holding.get(10, TimeUnit.SECONDS)
          assertEquals(200, second.get(10, TimeUnit.SECONDS))
        } finally {
          releaseHandler.countDown()
          pool.shutdownNow()
        }
      }
  }

  @Test
  fun `a slot is released on a failing path too`() {
    server.`when`(request()).respond(response().withStatusCode(500).withBody("no"))
    SempodsTransport.builder()
      .admission(SempodsAdmission(maxActive = 1, maxWaiting = 0))
      .build()
      .use { transport ->
        val a = session(transport)
        repeat(3) {
          assertThrows<IOException> {
            a.execute(a.newRequest("GET", "x").build(), { throw IOException("nope") })
          }
        }
        // A slot leaked on the failure path would make the fourth call block rather than fail here.
        assertEquals(500, a.executeText(a.newRequest("GET", "x").build()).statusCode)
      }
  }

  @Test
  fun `waiting work is bounded rather than queued without limit`() {
    val held = CountDownLatch(1)
    val started = CountDownLatch(1)
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))

    SempodsTransport.builder()
      .admission(SempodsAdmission(maxActive = 1, maxWaiting = 1))
      .build()
      .use { transport ->
        val a = session(transport)
        val pool = Executors.newFixedThreadPool(4)
        val refused = AtomicInteger()
        try {
          pool.submit {
            a.execute(a.newRequest("GET", "x").build(), {
              started.countDown()
              held.await(10, TimeUnit.SECONDS)
              it.bodyText()
            })
          }
          assertTrue(started.await(10, TimeUnit.SECONDS))

          val queued = (1..3).map {
            pool.submit {
              try {
                a.executeText(a.newRequest("GET", "x").build())
              } catch (e: SempodsTransportException) {
                refused.incrementAndGet()
              }
            }
          }
          Thread.sleep(500)
          // One active, one allowed to wait, and the other two told so rather than piling up.
          assertEquals(2, refused.get())

          held.countDown()
          queued.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
          held.countDown()
          pool.shutdownNow()
        }
      }
  }

  @Test
  fun `the operation deadline covers waiting for a slot`() {
    // Wider than the engine's own call timeout on purpose: that one starts when a request is sent,
    // and a caller stuck in admission has not sent one.
    val held = CountDownLatch(1)
    val started = CountDownLatch(1)
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))

    SempodsTransport.builder()
      .admission(SempodsAdmission(maxActive = 1, maxWaiting = 4))
      .timeouts(SempodsHttpTimeouts(operation = Duration.ofMillis(500)))
      .build()
      .use { transport ->
        val a = session(transport)
        val pool = Executors.newFixedThreadPool(2)
        try {
          pool.submit {
            a.execute(a.newRequest("GET", "x").build(), {
              started.countDown()
              held.await(10, TimeUnit.SECONDS)
              it.bodyText()
            })
          }
          assertTrue(started.await(10, TimeUnit.SECONDS))

          val thrown = assertThrows<SempodsTransportException> {
            a.executeText(a.newRequest("GET", "x").build())
          }
          assertTrue(thrown.message!!.contains("slot"), thrown.message)
        } finally {
          held.countDown()
          pool.shutdownNow()
        }
      }
  }

  @Test
  fun `a zero deadline lets a long-lived stream run past what would otherwise bound it`() {
    val consumed = AtomicBoolean(false)
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("late").withDelay(TimeUnit.MILLISECONDS, 900),
    )
    SempodsTransport.builder()
      .timeouts(SempodsHttpTimeouts(operation = Duration.ofMillis(300)))
      .build()
      .use { transport ->
        val a = session(transport)
        assertThrows<SempodsTransportException> { a.executeText(a.newRequest("GET", "x").build()) }

        val opted = a.newRequest("GET", "x").operationTimeout(Duration.ZERO).build()
        assertEquals("late", a.execute(opted, { consumed.set(true); it.bodyText() }).body)
        assertTrue(consumed.get())
      }
  }
}
