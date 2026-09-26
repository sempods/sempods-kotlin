package org.sempods.client

import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okhttp3.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * Who sends which credential where, and when a refused one is worth sending again.
 *
 * Every case here is a way a client can be wrong that a server cannot correct for it: a credential
 * reaching the wrong pod, a retry that re-sends an empty body, a retry after the caller has already
 * been handed the answer.
 */
class SempodsSessionAuthTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  /**
   * `refreshable` takes its supplier first, so a trailing lambda would bind to the header name.
   * A Java caller writes the interface out; here one helper keeps the cases below readable.
   */
  private fun refreshable(supplier: (Boolean) -> String) =
    SempodsRequestAuth.refreshable(SempodsCredentialSupplier { force, _ -> supplier(force) })

  private fun session(pod: String, auth: SempodsRequestAuth) = SempodsSession(SempodsPodBase.of("$origin/$pod"), auth)

  private fun send(request: Request): Response = client.newCall(request).execute()

  private fun SempodsSession.text(path: String, method: String = "GET"): Pair<Int, String> =
    send(newRequest(method, path).build()).use { it.code to it.body.string() }

  @Test
  fun `two sessions share a client and never each other's credential`() {
    // The acceptance case from the issue: one pod wants an API key, the other a bearer plus a
    // header of its own. What is being checked is not that each works — it is that neither header
    // appears on the other pod's request, although both ran through one client.
    val a = session("alice", SempodsRequestAuth.apiKeyHeader("X-Api-Key", "key-a"))
    val b = session(
      "bob",
      SempodsRequestAuth.bearer("token-b").andThen(SempodsRequestAuth.apiKeyHeader("X-Tenant", "b")),
    )
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))

    a.text("_system/contexts")
    b.text("_system/contexts")

    val toAlice = server.retrieveRecordedRequests(request().withPath("/alice/_system/contexts")).single()
    assertEquals("key-a", toAlice.getFirstHeader("X-Api-Key"))
    assertEquals("", toAlice.getFirstHeader("Authorization"))
    assertEquals("", toAlice.getFirstHeader("X-Tenant"))

    val toBob = server.retrieveRecordedRequests(request().withPath("/bob/_system/contexts")).single()
    assertEquals("Bearer token-b", toBob.getFirstHeader("Authorization"))
    assertEquals("b", toBob.getFirstHeader("X-Tenant"))
    assertEquals("", toBob.getFirstHeader("X-Api-Key"))
  }

  @Test
  fun `a request built for one pod cannot be sent to another pod`() {
    // A request is a plain object whose URL can be replaced after the session built it, and the
    // session travels with it. Without the check at execution time this sends bob's bearer to alice.
    val b = session("bob", SempodsRequestAuth.bearer("token-b"))

    val movedToAlice = b.newRequest("GET", "_system/contexts").url("$origin/alice/_system/contexts").build()
    val refused = assertThrows<SempodsClientException> { send(movedToAlice) }

    assertTrue(refused.message!!.contains("not under this session's pod"))
    assertEquals(0, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `authentication may set headers and nothing else`() {
    // A mechanism that rewrote the URL would carry this session's credential to another authority,
    // and one that changed the method or the body would send a request the caller never built. The
    // builder is OkHttp's, so the restriction is enforced rather than typed away.
    val mechanisms = mapOf(
      "target" to SempodsRequestAuth { request, _ -> request.url("$origin/bob/stolen") },
      "method" to SempodsRequestAuth { request, _ -> request.method("POST", request.build().body) },
      "body" to SempodsRequestAuth { request, _ -> request.put("replaced".toRequestBody()) },
    )

    mechanisms.forEach { (changed, mechanism) ->
      val a = session("alice", mechanism)
      val refused = assertThrows<SempodsClientException> {
        send(a.newRequest("PUT", "x").put("original".toRequestBody()).build()).close()
      }
      assertTrue(refused.message!!.contains("Authentication changed the $changed of"), refused.message)
    }
    assertEquals(0, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `an auth header replaces a same-named header the caller set`() {
    val a = session("alice", SempodsRequestAuth.bearer("session-token"))
    server.`when`(request()).respond(response().withStatusCode(200))

    send(a.newRequest("GET", "x").header("Authorization", "Bearer caller-token").build()).close()

    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals(listOf("Bearer session-token"), sent.getHeader("Authorization"))
  }

  @Test
  fun `a decorator can remove what the mechanism before it set`() {
    // Declaration order, stated so a conflict is decided rather than discovered.
    val strip = SempodsRequestAuth { request, _ -> request.removeHeader("Authorization") }
    val a = session("alice", SempodsRequestAuth.bearer("t").andThen(strip))
    server.`when`(request()).respond(response().withStatusCode(200))

    a.text("x")

    assertEquals("", server.retrieveRecordedRequests(request()).single().getFirstHeader("Authorization"))
  }

  @Test
  fun `a fixed bearer is not retried after a 401`() {
    // Nothing to re-mint, so a second attempt only pays for the failure twice.
    val a = session("alice", SempodsRequestAuth.bearer("dead"))
    server.`when`(request()).respond(response().withStatusCode(401).withBody("expired"))

    val (code, body) = a.text("x")

    assertEquals(401, code)
    assertEquals("expired", body)
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `an anonymous session is not retried after a 401`() {
    val a = session("alice", SempodsRequestAuth.anonymous())
    server.`when`(request()).respond(response().withStatusCode(401))

    assertEquals(401, a.text("x").first)
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a refreshable credential gets exactly one further attempt, with a new value`() {
    val minted = AtomicInteger()
    val a = session("alice", refreshable { _ -> "token-${minted.incrementAndGet()}" })
    server.`when`(request().withHeader("Authorization", "Bearer token-1"))
      .respond(response().withStatusCode(401).withBody("expired"))
    server.`when`(request().withHeader("Authorization", "Bearer token-2"))
      .respond(response().withStatusCode(200).withBody("fresh"))

    val (code, body) = a.text("x")

    assertEquals(200, code)
    assertEquals("fresh", body)
    assertEquals(2, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `recovery is exhausted after one retry rather than looping`() {
    val minted = AtomicInteger()
    val a = session("alice", refreshable { _ -> "token-${minted.incrementAndGet()}" })
    server.`when`(request()).respond(response().withStatusCode(401).withBody("no"))

    assertEquals(401, a.text("x").first)
    assertEquals(2, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a chain of mechanisms still gets at most one extra attempt`() {
    val a = session(
      "alice",
      refreshable { _ -> "a" }.andThen(refreshable { _ -> "b" }).andThen(refreshable { _ -> "c" }),
    )
    server.`when`(request()).respond(response().withStatusCode(401))

    a.text("x")

    assertEquals(2, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a one-shot body rules out the retry that a replayable one allows`() {
    // The honest trade: a second attempt over a drained stream would upload nothing and be
    // answered 200, which is worse than the 401 the caller gets here.
    server.`when`(request()).respond(response().withStatusCode(401))

    val replayable = session("alice", refreshable { _ -> "t" })
    send(replayable.newRequest("PUT", "x").put("body".toRequestBody()).build()).close()
    assertEquals(2, server.retrieveRecordedRequests(request()).size)

    server.reset()
    server.`when`(request()).respond(response().withStatusCode(401))
    val oneShot = session("alice", refreshable { _ -> "t" })
    send(oneShot.newRequest("PUT", "x").put(oneShotBody("body")).build()).close()
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a body a later interceptor made one-shot rules out the retry too`() {
    // What counts is the body the attempt sent, and an interceptor after the session's may replace it.
    server.`when`(request()).respond(response().withStatusCode(401))
    val draining = Interceptor { chain -> chain.proceed(chain.request().newBuilder().put(oneShotBody("body")).build()) }

    sempodsClient { addInterceptor(draining) }.closing { swapping ->
      val put = session("alice", refreshable { _ -> "t" }).newRequest("PUT", "x").put("body".toRequestBody()).build()
      swapping.newCall(put).execute().use { assertEquals(401, it.code) }
    }
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a request-bound header is regenerated for every attempt`() {
    // The seam a later proof-of-possession mechanism needs: the header is computed from the request
    // and the attempt, so replaying the first attempt's value is structurally impossible.
    val challenges = mutableListOf<String>()
    val schemes = mutableListOf<String>()
    val perAttempt = object : SempodsRequestAuth {
      override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) {
        request.header("X-Proof", "${attempt.number}")
      }

      override fun recover(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt): Boolean {
        challenges += facts.headers.values("WWW-Authenticate").joinToString()
        schemes += facts.challenges.map { it.scheme }
        return attempt.number == 1
      }
    }
    val a = session("alice", perAttempt)
    server.`when`(request().withHeader("X-Proof", "1"))
      .respond(response().withStatusCode(401).withHeader("WWW-Authenticate", "DPoP-ish nonce=\"n1\""))
    server.`when`(request().withHeader("X-Proof", "2"))
      .respond(response().withStatusCode(200).withBody("accepted"))

    assertEquals("accepted", a.text("x").second)
    assertEquals(listOf("DPoP-ish nonce=\"n1\""), challenges)
    // The challenges are OkHttp's own parse of that header, so a mechanism needs no parser of its own.
    assertEquals(listOf("DPoP-ish"), schemes)
  }

  @Test
  fun `a request-bound header is computed from the request as it is written`() {
    // A proof covers the method and the URL that go out, and an interceptor on the builder may still
    // change them after the session built the request.
    val applied = CopyOnWriteArrayList<String>()
    val bound = SempodsRequestAuth { request, _ ->
      val sent = request.build()
      applied += "${sent.method} ${sent.url.encodedPath}"
      request.header("X-Proof", "${sent.method} ${sent.url.encodedPath}")
    }
    val versioned = Interceptor { chain ->
      val request = chain.request()
      chain.proceed(request.newBuilder().url(request.url.newBuilder().addPathSegment("v2").build()).build())
    }
    server.`when`(request()).respond(response().withStatusCode(200))

    sempodsClient { addNetworkInterceptor(versioned) }.closing { rewriting ->
      rewriting.newCall(session("alice", bound).newRequest("GET", "x").build()).execute().use { assertEquals(200, it.code) }
    }

    assertEquals(listOf("GET /alice/x/v2"), applied)
    assertEquals("GET /alice/x/v2", server.retrieveRecordedRequests(request()).single().getFirstHeader("X-Proof"))
  }

  @Test
  fun `a redirect OkHttp follows within the pod is authenticated and observed for itself`() {
    // A consumer may turn redirects back on after `install`. OkHttp then writes the redirect's request
    // below the session's interceptor, and it passes the last network interceptor like any other.
    val numbered = Numbered("X-Proof")
    server.`when`(request().withPath("/alice/x")).respond(response().withStatusCode(307).withHeader("Location", "$origin/alice/y"))
    server.`when`(request().withPath("/alice/y")).respond(response().withStatusCode(200))

    SempodsOkHttp.install(OkHttpClient.Builder()).followRedirects(true).build().closing { following ->
      following.newCall(session("alice", numbered).newRequest("GET", "x").build()).execute().use { assertEquals(200, it.code) }
    }

    assertEquals(listOf(307, 200), numbered.told)
    assertEquals("2", server.retrieveRecordedRequests(request().withPath("/alice/y")).single().getFirstHeader("X-Proof"))
  }

  @Test
  fun `a concurrent refusal mints one credential between the callers`() {
    // Coalescing: the lock is the credential's, so concurrent callers that were all refused make
    // one acquisition. Without it each of them mints, and a rotating issuer invalidates the others.
    val minted = AtomicInteger()
    val ready = CountDownLatch(1)
    val auth = refreshable { force: Boolean ->
      if (force) {
        ready.await(5, TimeUnit.SECONDS)
        Thread.sleep(50)
      }
      "token-${minted.incrementAndGet()}"
    }
    val a = session("alice", auth)
    server.`when`(request().withHeader("Authorization", "Bearer token-1"))
      .respond(response().withStatusCode(401))
    server.`when`(request().withHeader("Authorization", "Bearer token-2"))
      .respond(response().withStatusCode(200).withBody("ok"))

    val pool = Executors.newFixedThreadPool(4)
    try {
      val calls = (1..4).map { pool.submit<Int> { a.text("x").first } }
      ready.countDown()
      calls.forEach { assertEquals(200, it.get(15, TimeUnit.SECONDS)) }
    } finally {
      pool.shutdownNow()
    }

    // One initial acquisition plus one refresh. More than two means the coalescing did not hold.
    assertEquals(2, minted.get())
  }

  @Test
  fun `a concurrent refusal mints once although the value does not change`() {
    // Coalescing counts acquisitions, whatever value they yield: a supplier that answers the same
    // token after a forced refresh is asked once for the whole wave.
    val minted = AtomicInteger()
    val inRecovery = CountDownLatch(4)
    val go = CountDownLatch(1)
    val auth = refreshable { force: Boolean ->
      minted.incrementAndGet()
      if (force) go.await(5, TimeUnit.SECONDS)
      "same-token"
    }
    val counted = object : SempodsRequestAuth {
      override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) = auth.apply(request, attempt)

      override fun recover(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt): Boolean {
        inRecovery.countDown()
        return auth.recover(facts, attempt)
      }
    }
    val a = session("alice", counted)
    // Every attempt is refused, so the count below is four callers with two attempts each.
    server.`when`(request()).respond(response().withStatusCode(401))

    val pool = Executors.newFixedThreadPool(4)
    try {
      val calls = (1..4).map { pool.submit<Int> { a.text("x").first } }
      assertTrue(inRecovery.await(5, TimeUnit.SECONDS), "not every caller reached recovery")
      // The winner holds the lock until `go`; this is the losers' room to read the generation.
      Thread.sleep(100)
      go.countDown()
      calls.forEach { assertEquals(401, it.get(15, TimeUnit.SECONDS)) }
    } finally {
      pool.shutdownNow()
    }

    assertEquals(2, minted.get(), "one initial acquisition and one refresh for the whole wave")
    assertEquals(8, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a slow refresh on one pod does not hold up another`() {
    val blocked = CountDownLatch(1)
    val slow = session("alice", refreshable { _ -> blocked.await(5, TimeUnit.SECONDS); "a" })
    val quick = session("bob", SempodsRequestAuth.bearer("b"))
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))

    val pool = Executors.newFixedThreadPool(2)
    try {
      val held = pool.submit { slow.text("x") }
      assertEquals(200, quick.text("x").first)
      assertFalse(held.isDone, "the unrelated session should not have waited on the other's credential")
      blocked.countDown()
      held.get(15, TimeUnit.SECONDS)
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `a failing refresh fails the call with its own exception`() {
    server.`when`(request()).respond(response().withStatusCode(401))
    val a = session("alice", refreshable { force -> if (force) throw IOException("issuer unavailable") else "stale" })

    val failed = assertThrows<IOException> { send(a.newRequest("GET", "x").build()).close() }

    assertEquals("issuer unavailable", failed.message)
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `an interrupted wait for a credential is an InterruptedIOException`() {
    val acquiring = CountDownLatch(1)
    val release = CountDownLatch(1)
    val a = session("alice", refreshable { _ -> acquiring.countDown(); release.await(5, TimeUnit.SECONDS); "t" })
    server.`when`(request()).respond(response().withStatusCode(200))
    // The waiter is interrupted once it has connected, so the next thing it waits for is the
    // credential. An interrupt while OkHttp connects is OkHttp's to answer.
    val connected = CountDownLatch(2)
    val connecting = sempodsClient { addNetworkInterceptor { chain -> connected.countDown(); chain.proceed(chain.request()) } }

    val pool = Executors.newSingleThreadExecutor()
    connecting.closing { client ->
      try {
        pool.submit { client.newCall(a.newRequest("GET", "x").build()).execute().close() }
        assertTrue(acquiring.await(5, TimeUnit.SECONDS))
        val outcome = CompletableFuture<Throwable?>()
        val waiter = Thread { outcome.complete(runCatching { client.newCall(a.newRequest("GET", "x").build()).execute().close() }.exceptionOrNull()) }
        waiter.start()
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        waiter.interrupt()
        val failure = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(failure is InterruptedIOException, "was $failure")
      } finally {
        release.countDown()
        pool.shutdownNow()
      }
    }
  }

  @Test
  fun `a wait for a credential ends with the call's deadline`() {
    val acquiring = CountDownLatch(1)
    val release = CountDownLatch(1)
    val a = session("alice", refreshable { _ -> acquiring.countDown(); release.await(10, TimeUnit.SECONDS); "t" })
    server.`when`(request()).respond(response().withStatusCode(200))

    val pool = Executors.newSingleThreadExecutor()
    try {
      pool.submit { a.text("x") }
      assertTrue(acquiring.await(5, TimeUnit.SECONDS))
      sempodsClient { callTimeout(Duration.ofMillis(300)) }.closing { client ->
        val started = System.nanoTime()
        assertThrows<IOException> { client.newCall(a.newRequest("GET", "x").build()).execute().close() }
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(waited < 5_000, "waited ${waited}ms for a credential past a 300ms deadline")
      }
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `a call cancelled while it waits for a credential starts no acquisition when the lock comes free`() {
    // The first acquisition fails and leaves no credential behind, so a waiter that took the lock
    // after its cancel would ask the supplier again.
    val acquiring = CountDownLatch(1)
    val fail = CountDownLatch(1)
    val asked = AtomicInteger()
    val a = session("alice", refreshable { _ ->
      if (asked.incrementAndGet() == 1) {
        acquiring.countDown()
        fail.await(5, TimeUnit.SECONDS)
        throw IOException("issuer unavailable")
      }
      "t"
    })
    server.`when`(request()).respond(response().withStatusCode(200))

    val pool = Executors.newFixedThreadPool(2)
    try {
      val first = pool.submit { a.text("x") }
      assertTrue(acquiring.await(5, TimeUnit.SECONDS))
      val waiting = client.newCall(a.newRequest("GET", "x").build())
      val second = pool.submit { waiting.execute().close() }
      Thread.sleep(200)

      waiting.cancel()
      fail.countDown()

      assertThrows<ExecutionException> { first.get(5, TimeUnit.SECONDS) }
      assertTrue(assertThrows<ExecutionException> { second.get(5, TimeUnit.SECONDS) }.cause is IOException)
      assertEquals(1, asked.get(), "the cancelled call asked the supplier for a credential")
      assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
    } finally {
      fail.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `an OkHttp authenticator on the builder does not answer a session's 401`() {
    server.`when`(request()).respond(response().withStatusCode(401))
    val asked = AtomicInteger()
    val engine = Authenticator { _, refused ->
      asked.incrementAndGet()
      refused.request.newBuilder().header("Authorization", "Bearer from-the-engine").build()
    }

    sempodsClient { authenticator(engine) }.closing { client ->
      val a = session("alice", SempodsRequestAuth.bearer("fixed"))
      client.newCall(a.newRequest("GET", "x").build()).execute().use { assertEquals(401, it.code) }
    }
    assertEquals(0, asked.get())
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a failure status is an answer, not an exception`() {
    // 304, 404 and 412 are answers on the routes above this. `Response.isSuccessful` is OkHttp's,
    // and a core that threw would force every caller to read them out of a `catch`.
    val a = session("alice", SempodsRequestAuth.anonymous())
    listOf(304, 404, 412).forEach { status ->
      server.reset()
      server.`when`(request()).respond(response().withStatusCode(status).withHeader("ETag", "\"v1\""))

      send(a.newRequest("GET", "x").build()).use { response ->
        assertEquals(status, response.code)
        assertEquals("\"v1\"", response.header("etag"))
        assertFalse(response.isSuccessful)
      }
    }
  }

  @Test
  fun `a raw body is returned unchanged, malformed or not`() {
    // The core neither parses nor repairs JSON; only a decoder a caller selected interprets it.
    val malformed = """{"contexts": [ "urn:x", ] // trailing"""
    val a = session("alice", SempodsRequestAuth.anonymous())
    server.`when`(request()).respond(response().withStatusCode(200).withBody(malformed))

    assertEquals(malformed, a.text("x").second)
  }

  @Test
  fun `an absent header is null and a repeated one keeps every value`() {
    val a = session("alice", SempodsRequestAuth.anonymous())
    server.`when`(request()).respond(
      response().withStatusCode(200)
        .withHeader("Link", "<a>; rel=next")
        .withHeader("Link", "<b>; rel=prev"),
    )

    send(a.newRequest("GET", "x").build()).use { response ->
      assertEquals(listOf("<a>; rel=next", "<b>; rel=prev"), response.headers("link"))
      assertNull(response.header("X-Absent"))
    }
  }

  @Test
  fun `HEAD and OPTIONS are ordinary methods an extension can use`() {
    val a = session("alice", SempodsRequestAuth.anonymous())
    server.`when`(request()).respond(response().withStatusCode(204).withHeader("Allow", "GET, HEAD, OPTIONS"))

    listOf("HEAD", "OPTIONS", "PROPFIND").forEach { verb ->
      send(a.newRequest(verb, "x").build()).use { response ->
        assertEquals(204, response.code, verb)
        assertEquals("GET, HEAD, OPTIONS", response.header("Allow"), verb)
      }
    }
    assertEquals(
      listOf("HEAD", "OPTIONS", "PROPFIND"),
      server.retrieveRecordedRequests(request()).map { it.method.value },
    )
  }
}
