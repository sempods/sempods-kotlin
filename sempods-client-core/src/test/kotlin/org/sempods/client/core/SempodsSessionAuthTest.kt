package org.sempods.client.core

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
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
 * Who sends which credential where, and when a refused one is worth sending again.
 *
 * Every case here is a way a client can be wrong that a server cannot correct for it: a credential
 * reaching the wrong pod, a retry that re-sends an empty body, a retry after the caller has already
 * been handed the answer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsSessionAuthTest {

  private lateinit var server: ClientAndServer
  private lateinit var transport: SempodsTransport
  private lateinit var origin: String

  @BeforeAll
  fun start() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    origin = "http://localhost:${server.port}"
    transport = SempodsTransport.builder().build()
  }

  @AfterAll
  fun stop() {
    transport.close()
    server.stop()
  }

  @BeforeEach
  fun reset() {
    server.reset()
  }

  /**
   * `refreshable` takes its supplier first, so a trailing lambda would bind to the header name.
   * A Java caller writes the interface out; here one helper keeps the cases below readable.
   */
  private fun refreshable(supplier: (Boolean) -> String) =
    SempodsRequestAuth.refreshable(SempodsCredentialSupplier { supplier(it) })

  private fun session(pod: String, auth: SempodsRequestAuth) =
    SempodsSession.builder(SempodsPodBase.of("$origin/$pod")).transport(transport).auth(auth).build()

  private fun SempodsSession.text(path: String, method: String = "GET"): Pair<Int, String> =
    execute(newRequest(method, path).build()).use { it.code to it.body.string() }

  /** A body that may be written once, for the case where a retry must not happen. */
  private fun oneShotBody(content: String): RequestBody = object : RequestBody() {
    private val stream = ByteArrayInputStream(content.toByteArray())
    override fun contentType() = null
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) { stream.source().use { sink.writeAll(it) } }
  }

  @Test
  fun `two sessions share a transport and never each other's credential`() {
    // The acceptance case from the issue: one pod wants an API key, the other a bearer plus a
    // header of its own. What is being checked is not that each works — it is that neither header
    // appears on the other pod's request, although both ran through one transport.
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
  fun `a request built for one pod cannot be executed by another pod's session`() {
    // A request is a plain object holding an absolute URL, so nothing about it remembers which
    // session built it. Without the check at execution time this sends bob's bearer to alice.
    val a = session("alice", SempodsRequestAuth.apiKeyHeader("X-Api-Key", "key-a"))
    val b = session("bob", SempodsRequestAuth.bearer("token-b"))

    val forAlice = a.newRequest("GET", "_system/contexts").build()
    val refused = assertThrows<SempodsClientException> { b.execute(forAlice) }

    assertTrue(refused.message!!.contains("not under this session's pod"))
    assertEquals(0, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `authentication may set headers and nothing else`() {
    // A mechanism that rewrote the URL would carry this session's credential to another authority.
    // The builder is OkHttp's, so the restriction is enforced rather than typed away.
    val moves = SempodsRequestAuth { request, _ -> request.url("$origin/bob/stolen") }
    val a = session("alice", moves)

    val refused = assertThrows<SempodsClientException> { a.text("x") }

    assertTrue(refused.message!!.contains("Authentication moved the request"), refused.message)
    assertEquals(0, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a sibling path sharing the prefix is not under the pod`() {
    val a = session("alice", SempodsRequestAuth.bearer("token-a"))
    assertThrows<IllegalArgumentException> { a.newRequest("GET", "../alice-archive/secret") }
  }

  @Test
  fun `an auth header replaces a same-named header the caller set`() {
    val a = session("alice", SempodsRequestAuth.bearer("session-token"))
    server.`when`(request()).respond(response().withStatusCode(200))

    a.execute(a.newRequest("GET", "x").header("Authorization", "Bearer caller-token").build()).close()

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
    replayable.execute(
      replayable.newRequest("PUT", "x").method("PUT", "body".toRequestBody()).build(),
    ).close()
    assertEquals(2, server.retrieveRecordedRequests(request()).size)

    server.reset()
    server.`when`(request()).respond(response().withStatusCode(401))
    val oneShot = session("alice", refreshable { _ -> "t" })
    oneShot.execute(oneShot.newRequest("PUT", "x").method("PUT", oneShotBody("body")).build()).close()
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a request-bound header is regenerated for every attempt`() {
    // The seam a later proof-of-possession mechanism needs: the header is computed from the request
    // and the attempt, so replaying the first attempt's value is structurally impossible.
    val challenges = mutableListOf<String>()
    val perAttempt = object : SempodsRequestAuth {
      override fun apply(request: Request.Builder, attempt: Int) {
        request.header("X-Proof", "$attempt")
      }

      override fun recover(response: Response, attempt: Int): Boolean {
        challenges += response.headers("WWW-Authenticate").joinToString()
        return attempt == 1
      }
    }
    val a = session("alice", perAttempt)
    server.`when`(request().withHeader("X-Proof", "1"))
      .respond(response().withStatusCode(401).withHeader("WWW-Authenticate", "DPoP-ish nonce=\"n1\""))
    server.`when`(request().withHeader("X-Proof", "2"))
      .respond(response().withStatusCode(200).withBody("accepted"))

    assertEquals("accepted", a.text("x").second)
    assertEquals(listOf("DPoP-ish nonce=\"n1\""), challenges)
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
  fun `a failure status is an answer, not an exception`() {
    // 304, 404 and 412 are answers on the routes above this. `Response.isSuccessful` is OkHttp's,
    // and a core that threw would force every caller to read them out of a `catch`.
    val a = session("alice", SempodsRequestAuth.anonymous())
    listOf(304, 404, 412).forEach { status ->
      server.reset()
      server.`when`(request()).respond(response().withStatusCode(status).withHeader("ETag", "\"v1\""))

      a.execute(a.newRequest("GET", "x").build()).use { response ->
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

    a.execute(a.newRequest("GET", "x").build()).use { response ->
      assertEquals(listOf("<a>; rel=next", "<b>; rel=prev"), response.headers("link"))
      assertNull(response.header("X-Absent"))
    }
  }

  @Test
  fun `HEAD and OPTIONS are ordinary methods an extension can use`() {
    val a = session("alice", SempodsRequestAuth.anonymous())
    server.`when`(request()).respond(response().withStatusCode(204).withHeader("Allow", "GET, HEAD, OPTIONS"))

    listOf("HEAD", "OPTIONS", "PROPFIND").forEach { verb ->
      a.execute(a.newRequest(verb, "x").build()).use { response ->
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

private fun String.toRequestBody() = okhttp3.RequestBody.create(null, this.toByteArray())
