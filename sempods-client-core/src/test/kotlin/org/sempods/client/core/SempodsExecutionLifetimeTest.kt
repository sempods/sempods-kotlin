package org.sempods.client.core

import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What holds a resource, and what releases it.
 *
 * A body that is still arriving, an operation waiting for a slot, a call cancelled from another
 * thread, a deadline across several attempts: each is a place where the honest answer and the
 * convenient one differ, and where being wrong shows up as a leak rather than as a failure.
 */
class SempodsExecutionLifetimeTest : MockPodTest() {

  private fun session(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsSession(SempodsPodBase.of("$origin/alice"), auth)

  private fun OkHttpClient.get(session: SempodsSession, path: String = "x"): Response =
    newCall(session.newRequest("GET", path).build()).execute()

  private fun counting(minted: AtomicInteger) =
    SempodsRequestAuth.refreshable(SempodsCredentialSupplier { "token-${minted.incrementAndGet()}" })

  @Test
  fun `a caller reads the body before the server has finished sending it`() {
    // Buffering the whole body first would make this test pass and the streaming claim false. What
    // proves incremental delivery is reading fewer bytes than were sent and arriving before EOF.
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("x".repeat(512 * 1024)),
    )
    sempodsClient().closing { client ->
      client.get(session(), "big").use { response ->
        val head = ByteArray(16)
        response.body.source().readFully(head)
        assertEquals("x".repeat(16), String(head))
      }
    }
  }

  @Test
  fun `an unread response still releases its slot when it is closed`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("payload"))
    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { client ->
      val a = session()
      // Closed without reading anything, three times over. A slot leaked on that path would make
      // the fourth call fail rather than answer.
      repeat(3) { client.get(a).close() }
      client.get(a).use { assertEquals(200, it.code) }
    }
  }

  @Test
  fun `a body read with string() releases its slot without closing the response`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("payload"))
    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { client ->
      val a = session()
      // `string()` closes the body's source, which OkHttp counts as closing the body.
      repeat(3) { assertEquals("payload", client.get(a).body.string()) }
      client.get(a).use { assertEquals(200, it.code) }
    }
  }

  @Test
  fun `a refusal handed back holds its slot until it is closed`() {
    server.`when`(request()).respond(response().withStatusCode(404).withBody("not here"))
    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { client ->
      val a = session()
      val refusal = client.get(a)
      try {
        assertEquals(404, refusal.code)
        assertThrows<SempodsClientException> { client.get(a).close() }
      } finally {
        refusal.close()
      }
      client.get(a).use { assertEquals(404, it.code) }
    }
  }

  @Test
  fun `a slow credential occupies its call's slot, so waiting stays bounded`() {
    val acquiring = CountDownLatch(1)
    val release = CountDownLatch(1)
    val slow = SempodsRequestAuth.refreshable(
      SempodsCredentialSupplier { _ -> acquiring.countDown(); release.await(10, TimeUnit.SECONDS); "t" },
    )
    server.`when`(request()).respond(response().withStatusCode(200))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { client ->
      val pool = Executors.newSingleThreadExecutor()
      try {
        val first = pool.submit<Int> { client.get(session(slow)).use { it.code } }
        assertTrue(acquiring.await(5, TimeUnit.SECONDS))
        assertThrows<SempodsClientException> { client.get(session()).close() }
        release.countDown()
        assertEquals(200, first.get(10, TimeUnit.SECONDS))
      } finally {
        release.countDown()
        pool.shutdownNow()
      }
    }
  }

  @Test
  fun `a credential fetched through the same client does not wait for its caller's slot`() {
    server.`when`(request().withPath("/alice/x").withHeader("Authorization", "Bearer token-1"))
      .respond(response().withStatusCode(401))
    server.`when`(request().withPath("/token")).respond(response().withStatusCode(200).withBody("token-2"))
    server.`when`(request().withPath("/alice/x").withHeader("Authorization", "Bearer token-2"))
      .respond(response().withStatusCode(200))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 4)) { callTimeout(Duration.ofSeconds(5)) }.closing { client ->
      val fetching = SempodsCredentialSupplier { force ->
        if (!force) "token-1"
        else client.newCall(Request.Builder().url("$origin/token").build()).execute().use { it.body.string() }
      }
      client.get(session(SempodsRequestAuth.refreshable(fetching))).use { assertEquals(200, it.code) }
    }
  }

  @Test
  fun `a supplier's call through another client needs a slot of that client`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("t"))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { other ->
      val holding = other.get(session())
      try {
        sempodsClient().closing { client ->
          val fetching = SempodsCredentialSupplier { _ ->
            other.newCall(Request.Builder().url("$origin/token").build()).execute().use { it.body.string() }
          }
          assertThrows<SempodsClientException> { client.get(session(SempodsRequestAuth.refreshable(fetching))).close() }
        }
      } finally {
        holding.close()
      }
    }
  }

  @Test
  fun `cancelling a call reaches the connection`() {
    server.`when`(request()).respond(
      response().withStatusCode(200).withBody("slow").withDelay(TimeUnit.SECONDS, 10),
    )
    val sent = CountDownLatch(1)
    val onTheWire = object : EventListener() {
      override fun requestHeadersEnd(call: Call, request: Request) = sent.countDown()
    }
    sempodsClient { eventListener(onTheWire) }.closing { client ->
      val a = session()
      // The call is the engine's own handle, which is what cancellation is: no second vocabulary,
      // and it reaches the socket rather than merely letting an await return early.
      val call = client.newCall(a.newRequest("GET", "x").build())
      val pool = Executors.newSingleThreadExecutor()
      try {
        val running = pool.submit<Int> { call.execute().use { it.code } }
        assertTrue(sent.await(10, TimeUnit.SECONDS), "the request never went out")
        call.cancel()
        val thrown = assertThrows<ExecutionException> { running.get(10, TimeUnit.SECONDS) }
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

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 4)).closing { client ->
      val a = session()
      val pool = Executors.newFixedThreadPool(2)
      try {
        val holding = pool.submit {
          client.get(a).use {
            reading.countDown()
            release.await(10, TimeUnit.SECONDS)
            it.body.string()
          }
        }
        assertTrue(reading.await(10, TimeUnit.SECONDS))

        // The single slot is held by a response nobody has closed, so a second operation waits.
        // Releasing when the status line arrived would let this one straight through.
        val second = pool.submit<Int> { client.get(a).use { it.code } }
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
  fun `an enqueued call waits for the slot a synchronous one holds`() {
    // OkHttp's dispatcher bounds enqueued calls by its own numbers; this is the admission budget,
    // which the same interceptor applies to both.
    server.`when`(request()).respond(response().withStatusCode(200).withBody("body"))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 4)).closing { client ->
      val a = session(SempodsRequestAuth.bearer("t-1"))
      val answered = CompletableFuture<Int>()
      val holding = client.get(a)
      try {
        client.newCall(a.newRequest("GET", "x").build()).enqueue(object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            answered.completeExceptionally(e)
          }

          override fun onResponse(call: Call, response: Response) {
            response.use { answered.complete(it.code) }
          }
        })
        Thread.sleep(300)
        assertFalse(answered.isDone, "the enqueued call ran while the only slot was held")
        assertEquals(1, server.retrieveRecordedRequests(request()).size)
      } finally {
        holding.close()
      }
      assertEquals(200, answered.get(10, TimeUnit.SECONDS))
    }
    assertEquals(2, server.retrieveRecordedRequests(request().withHeader("Authorization", "Bearer t-1")).size)
  }

  @Test
  fun `waiting work is bounded rather than queued without limit`() {
    val held = CountDownLatch(1)
    val started = CountDownLatch(1)
    val refusals = CountDownLatch(2)
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 1)).closing { client ->
      val a = session()
      val pool = Executors.newFixedThreadPool(4)
      val refused = AtomicInteger()
      try {
        pool.submit {
          client.get(a).use {
            started.countDown()
            held.await(10, TimeUnit.SECONDS)
            it.body.string()
          }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        val queued = (1..3).map {
          pool.submit {
            try {
              client.get(a).close()
            } catch (e: SempodsClientException) {
              refused.incrementAndGet()
              refusals.countDown()
            }
          }
        }
        // One active, one allowed to wait, and the other two told so rather than piling up.
        assertTrue(refusals.await(10, TimeUnit.SECONDS))

        held.countDown()
        queued.forEach { it.get(10, TimeUnit.SECONDS) }
        assertEquals(2, refused.get())
      } finally {
        held.countDown()
        pool.shutdownNow()
      }
    }
  }

  @Test
  fun `a caller waits for a slot no longer than its call deadline`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("body"))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 4)) { callTimeout(Duration.ofMillis(400)) }.closing { client ->
      val a = session()
      val holding = client.get(a)
      try {
        val started = System.nanoTime()
        val ended = assertThrows<InterruptedIOException> { client.get(a).close() }
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals("timeout", ended.message)
        assertTrue(waited < 3_000, "waited ${waited}ms for a slot")
        assertEquals(1, server.retrieveRecordedRequests(request()).size, "the waiting call was sent")
      } finally {
        holding.close()
      }
    }
  }

  @Test
  fun `cancelling a call that waits for a slot ends the wait`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("body"))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 4)).closing { client ->
      val a = session()
      val holding = client.get(a)
      val pool = Executors.newSingleThreadExecutor()
      try {
        val waiting = client.newCall(a.newRequest("GET", "x").build())
        val running = pool.submit<Int> { waiting.execute().use { it.code } }
        Thread.sleep(300)
        assertFalse(running.isDone, "the second call did not wait for the slot")

        waiting.cancel()

        val thrown = assertThrows<ExecutionException> { running.get(5, TimeUnit.SECONDS) }
        assertTrue(thrown.cause is IOException, "was ${thrown.cause}")
        assertEquals(1, server.retrieveRecordedRequests(request()).size, "the cancelled call was sent")
      } finally {
        holding.close()
        pool.shutdownNow()
      }
    }
  }

  @Test
  fun `the call deadline spans the authentication retry`() {
    // Each attempt alone answers well inside the deadline; the two together do not. A deadline per
    // attempt would let the first run succeed.
    fun refusedThenAccepted(): SempodsSession {
      server.reset()
      server.`when`(request().withHeader("Authorization", "Bearer token-1"))
        .respond(response().withStatusCode(401).withDelay(TimeUnit.MILLISECONDS, 400))
      server.`when`(request().withHeader("Authorization", "Bearer token-2"))
        .respond(response().withStatusCode(200).withBody("fresh").withDelay(TimeUnit.MILLISECONDS, 400))
      return session(counting(AtomicInteger()))
    }

    val tight = refusedThenAccepted()
    sempodsClient { readTimeout(Duration.ofSeconds(10)).callTimeout(Duration.ofMillis(650)) }.closing { client ->
      val ended = assertThrows<InterruptedIOException> { client.get(tight).close() }
      assertEquals("timeout", ended.message)
    }
    assertEquals(2, server.retrieveRecordedRequests(request()).size, "the retry never started")

    // With room for both, the same exchange succeeds: what ended the call above was the deadline.
    val roomy = refusedThenAccepted()
    sempodsClient { readTimeout(Duration.ofSeconds(10)).callTimeout(Duration.ofSeconds(5)) }.closing { client ->
      client.get(roomy).use { assertEquals("fresh", it.body.string()) }
    }
  }

  @Test
  fun `cancelling between two attempts starts no further one`() {
    // The refusal has arrived and the call is cancelled before the retry: nothing more is sent, and
    // the credential is not re-acquired for an attempt that will not run.
    server.`when`(request()).respond(response().withStatusCode(401))
    val minted = AtomicInteger()
    val a = session(counting(minted))
    val cancelOnRefusal = Interceptor { chain ->
      chain.proceed(chain.request()).also { if (it.code == 401) chain.call().cancel() }
    }

    sempodsClient { addNetworkInterceptor(cancelOnRefusal) }.closing { client ->
      val call = client.newCall(a.newRequest("GET", "x").build())
      assertThrows<IOException> { call.execute().close() }
      assertTrue(call.isCanceled())
    }
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
    assertEquals(1, minted.get(), "a credential was acquired for an attempt that never ran")
  }

  @Test
  fun `a consumer's own client is derived from rather than adopted`() {
    // The redirect policy and the interceptors are applied on top of whatever is handed in, so a
    // consumer cannot lose them by supplying a client that has none.
    val theirs = OkHttpClient.Builder().followRedirects(true).build()
    SempodsOkHttp.install(theirs.newBuilder()).build().closing { ours ->
      assertFalse(ours.followRedirects)
      // Same pool, so sharing one is what it buys them.
      assertTrue(ours.connectionPool === theirs.connectionPool)
    }
  }
}
