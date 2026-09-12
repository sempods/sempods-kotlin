package org.sempods.client.core

import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * A body that is still arriving, an operation waiting for a slot, a call cancelled from another
 * thread: each is a place where the honest answer and the convenient one differ, and where being
 * wrong shows up as a leak rather than as a failure.
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
  fun stop() = server.stop()

  @BeforeEach
  fun reset() {
    server.reset()
  }

  private fun session(transport: SempodsTransport) =
    SempodsSession.builder(SempodsPodBase.of("$origin/alice")).transport(transport).build()

  @Test
  fun `a caller reads the body before the server has finished sending it`() {
    // Buffering the whole body first would make this test pass and the streaming claim false. What
    // proves incremental delivery is reading fewer bytes than were sent and arriving before EOF.
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("x".repeat(512 * 1024)).withDelay(TimeUnit.MILLISECONDS, 400),
    )
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      a.execute(a.newRequest("GET", "big").build()).use { response ->
        val head = ByteArray(16)
        response.body.source().readFully(head)
        assertEquals("x".repeat(16), String(head))
      }
    }
  }

  @Test
  fun `an unread response still releases its slot when it is closed`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("payload"))
    SempodsTransport.builder()
      .admission(SempodsAdmission(maxActive = 1, maxWaiting = 0))
      .build()
      .use { transport ->
        val a = session(transport)
        // Closed without reading anything, three times over. A slot leaked on that path would make
        // the fourth call fail rather than answer.
        repeat(3) { a.execute(a.newRequest("GET", "x").build()).close() }
        a.execute(a.newRequest("GET", "x").build()).use { assertEquals(200, it.code) }
      }
  }

  @Test
  fun `cancelling a call reaches the connection`() {
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("slow").withDelay(TimeUnit.SECONDS, 10),
    )
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      // `newCall` hands out the engine's own handle, which is what cancellation is: no second
      // vocabulary, and it reaches the socket rather than merely letting an await return early.
      val call = a.newCall(a.newRequest("GET", "x").build())
      val pool = Executors.newSingleThreadExecutor()
      try {
        val running = pool.submit<Int> { call.execute().use { it.code } }
        Thread.sleep(300)
        call.cancel()
        val thrown = assertThrows<java.util.concurrent.ExecutionException> { running.get(10, TimeUnit.SECONDS) }
        assertTrue(thrown.cause is IOException, "was ${thrown.cause}")
        assertTrue(call.isCanceled())
      } finally {
        pool.shutdownNow()
      }
    }
  }

  @Test
  fun `an active body holds its admission slot until it is closed`() {
    val reading = CountDownLatch(1)
    val release = CountDownLatch(1)
    server.`when`(request()).respond(response().withStatusCode(200).withBody("body"))

    SempodsTransport.builder()
      .admission(SempodsAdmission(maxActive = 1, maxWaiting = 4))
      .build()
      .use { transport ->
        val a = session(transport)
        val pool = Executors.newFixedThreadPool(2)
        try {
          val holding = pool.submit {
            a.execute(a.newRequest("GET", "x").build()).use {
              reading.countDown()
              release.await(10, TimeUnit.SECONDS)
              it.body.string()
            }
          }
          assertTrue(reading.await(10, TimeUnit.SECONDS))

          // The single slot is held by a response nobody has closed, so a second operation waits.
          // Releasing when the status line arrived would let this one straight through.
          val second = pool.submit<Int> { a.execute(a.newRequest("GET", "x").build()).use { it.code } }
          Thread.sleep(300)
          assertFalse(second.isDone, "the slot was released before the body was closed")

          release.countDown()
          holding.get(10, TimeUnit.SECONDS)
          assertEquals(200, second.get(10, TimeUnit.SECONDS))
        } finally {
          release.countDown()
          pool.shutdownNow()
        }
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
            a.execute(a.newRequest("GET", "x").build()).use {
              started.countDown()
              held.await(10, TimeUnit.SECONDS)
              it.body.string()
            }
          }
          assertTrue(started.await(10, TimeUnit.SECONDS))

          val queued = (1..3).map {
            pool.submit {
              try {
                a.execute(a.newRequest("GET", "x").build()).close()
              } catch (e: SempodsClientException) {
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
  fun `the call deadline bounds an answer the read timeout would tolerate`() {
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("late").withDelay(TimeUnit.MILLISECONDS, 900),
    )
    SempodsTransport.builder()
      .timeouts(read = Duration.ofSeconds(10), call = Duration.ofMillis(300))
      .build()
      .use { transport ->
        val a = session(transport)
        assertThrows<IOException> { a.execute(a.newRequest("GET", "x").build()).close() }
      }
  }

  @Test
  fun `a zero deadline lets a long-lived read run past what would otherwise bound it`() {
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("late").withDelay(TimeUnit.MILLISECONDS, 700),
    )
    SempodsTransport.builder()
      .timeouts(read = Duration.ofSeconds(10), call = Duration.ZERO)
      .build()
      .use { transport ->
        val a = session(transport)
        a.execute(a.newRequest("GET", "x").build()).use { assertEquals("late", it.body.string()) }
      }
  }

  @Test
  fun `a consumer's own client is derived from rather than adopted`() {
    // The guard, the redirect policy and the deadlines are applied on top of whatever is handed in,
    // so a consumer cannot lose the SSRF defence by supplying a client that has none.
    val theirs = okhttp3.OkHttpClient.Builder().followRedirects(true).build()
    SempodsTransport.builder().httpClient(theirs).build().use { transport ->
      assertFalse(transport.httpClient.followRedirects)
      // Same pool, so sharing one is what it buys them.
      assertTrue(transport.httpClient.connectionPool === theirs.connectionPool)
    }
  }
}
