package org.sempods.client.core

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What a mechanism is told about an answer, and which mechanism a challenge belongs to.
 *
 * The cases here are the ones recovery alone cannot reach: a header worth keeping that arrived on a
 * 200, an answer that earns no repeat because its body can be written once, and a nonce challenge
 * that must not cost a token which is still valid.
 */
class SempodsResponseObservationTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun session(pod: String, auth: SempodsRequestAuth) = SempodsSession(SempodsPodBase.of("$origin/$pod"), auth)

  private fun send(request: Request): Response = client.newCall(request).execute()

  private fun SempodsSession.status(path: String, method: String = "GET"): Int =
    send(newRequest(method, path).build()).use { it.code }

  private fun refreshable(supplier: (Boolean) -> String) =
    SempodsRequestAuth.refreshable(SempodsCredentialSupplier { force, _ -> supplier(force) })

  private fun recorded() = server.retrieveRecordedRequests(request()).toList()

  /**
   * The shape [#112](https://github.com/sempods/sempods-kotlin/issues/112) needs, without a key: it
   * keeps the nonce the server supplied and claims only a challenge that names it.
   */
  private class Nonce : SempodsRequestAuth {

    @Volatile var held: String? = null

    val seen = CopyOnWriteArrayList<Int>()

    val challenged = AtomicInteger()

    override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) {
      held?.let { request.header("DPoP-Nonce", it) }
    }

    override fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) {
      seen += facts.status
      facts.headers["DPoP-Nonce"]?.let { held = it }
    }

    override fun recover(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt): Boolean {
      val asked = facts.challenges.any {
        it.scheme.equals("DPoP", ignoreCase = true) && it.authParams["error"] == "use_dpop_nonce"
      } || facts.status == 400 && facts.headers["DPoP-Nonce"] != null
      if (!asked) return false
      challenged.incrementAndGet()
      return held != null
    }
  }

  /** A mechanism whose bookkeeping fails, to show what that costs and what it does not. */
  private class NoStore : SempodsRequestAuth {

    override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) = Unit

    override fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt): Unit =
      throw IOException("no nonce store")
  }

  @Test
  fun `a nonce kept from a successful answer rides on the next request`() {
    // The case recovery could never reach: nothing was refused, so nothing would have been asked.
    val nonce = Nonce()
    val a = session("alice", nonce)
    server.`when`(request()).respond(response().withStatusCode(200).withHeader("DPoP-Nonce", "n1").withBody("ok"))

    assertEquals(200, a.status("x"))
    assertEquals(200, a.status("y"))

    val sent = recorded()
    assertEquals(2, sent.size)
    assertEquals("", sent[0].getFirstHeader("DPoP-Nonce"), "the first request had no nonce to send")
    assertEquals("n1", sent[1].getFirstHeader("DPoP-Nonce"))
    assertEquals(listOf(200, 200), nonce.seen)
  }

  @Test
  fun `a token-style 400 names its nonce in a header rather than a challenge`() {
    // RFC 9449 section 8.2: the token endpoint answers 400, and only a 401 defines a challenge.
    val nonce = Nonce()
    val challenges = CopyOnWriteArrayList<Int>()
    val counting = object : SempodsRequestAuth {
      override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) = nonce.apply(request, attempt)

      override fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) {
        challenges += facts.challenges.size
        nonce.observe(facts, attempt)
      }

      override fun recover(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) = nonce.recover(facts, attempt)
    }
    val a = session("alice", counting)
    server.`when`(request().withHeader("DPoP-Nonce", "n2")).respond(response().withStatusCode(200).withBody("ok"))
    server.`when`(request())
      .respond(response().withStatusCode(400).withHeader("DPoP-Nonce", "n2").withBody("""{"error":"use_dpop_nonce"}"""))

    assertEquals(200, a.status("token", method = "POST"))

    assertEquals(2, recorded().size)
    assertEquals(listOf(400, 200), nonce.seen)
    assertEquals(listOf(0, 0), challenges, "a status other than 401 or 407 defines no challenge")
    assertEquals(1, nonce.challenged.get())
  }

  @Test
  fun `a resource-style 401 names its nonce in the challenge`() {
    val nonce = Nonce()
    val a = session("alice", nonce)
    server.`when`(request().withHeader("DPoP-Nonce", "n3")).respond(response().withStatusCode(200).withBody("ok"))
    server.`when`(request()).respond(
      response()
        .withStatusCode(401)
        .withHeader("WWW-Authenticate", """DPoP error="use_dpop_nonce", algs="ES256"""")
        .withHeader("DPoP-Nonce", "n3"),
    )

    assertEquals(200, a.status("x"))

    assertEquals(2, recorded().size)
    assertEquals(listOf(401, 200), nonce.seen)
    assertEquals(1, nonce.challenged.get())
  }

  @Test
  fun `a nonce challenge is answered without asking for a credential`() {
    // The point of claiming by challenge: a token that is still valid survives a nonce round trip.
    val minted = AtomicInteger()
    val nonce = Nonce()
    val a = session("alice", refreshable { _ -> "t-${minted.incrementAndGet()}" }.andThen(nonce))
    server.`when`(request().withHeader("DPoP-Nonce", "n4")).respond(response().withStatusCode(200).withBody("ok"))
    server.`when`(request()).respond(
      response()
        .withStatusCode(401)
        .withHeader("WWW-Authenticate", """DPoP error="use_dpop_nonce"""")
        .withHeader("DPoP-Nonce", "n4"),
    )

    assertEquals(200, a.status("x"))

    val sent = recorded()
    assertEquals(2, sent.size)
    assertEquals(1, minted.get(), "the bearer was renewed for a challenge that was not its own")
    assertEquals(1, nonce.challenged.get())
    assertEquals("Bearer t-1", sent[1].getFirstHeader("Authorization"))
    assertEquals("n4", sent[1].getFirstHeader("DPoP-Nonce"))
  }

  @Test
  fun `a bearer challenge is the bearer's, whatever comes after it`() {
    val minted = AtomicInteger()
    val nonce = Nonce()
    val a = session("alice", refreshable { _ -> "t-${minted.incrementAndGet()}" }.andThen(nonce))
    server.`when`(request().withHeader("Authorization", "Bearer t-2")).respond(response().withStatusCode(200))
    server.`when`(request())
      .respond(response().withStatusCode(401).withHeader("WWW-Authenticate", """Bearer realm="alice""""))

    assertEquals(200, a.status("x"))

    assertEquals(2, recorded().size)
    assertEquals(2, minted.get())
    assertEquals(0, nonce.challenged.get(), "the nonce adapter was asked about a bearer challenge")
  }

  @Test
  fun `two challenges in one answer are decided by declaration order`() {
    // Both mechanisms claim this refusal, so the rule that remains is the order they were composed in.
    val minted = AtomicInteger()
    val nonce = Nonce()
    val a = session("alice", refreshable { _ -> "t-${minted.incrementAndGet()}" }.andThen(nonce))
    server.`when`(request().withHeader("Authorization", "Bearer t-2")).respond(response().withStatusCode(200))
    server.`when`(request()).respond(
      response()
        .withStatusCode(401)
        .withHeader("WWW-Authenticate", """DPoP error="use_dpop_nonce", Bearer realm="alice"""")
        .withHeader("DPoP-Nonce", "n5"),
    )

    assertEquals(200, a.status("x"))

    assertEquals(2, minted.get(), "the mechanism declared first did not win")
    assertEquals(0, nonce.challenged.get())
  }

  @Test
  fun `a mechanism with no scheme claims only an unchallenged 401`() {
    val minted = AtomicInteger()
    val key = SempodsRequestAuth.refreshable(
      SempodsCredentialSupplier { _, _ -> "k-${minted.incrementAndGet()}" },
      "X-Api-Key",
      "",
    )
    server.`when`(request()).respond(response().withStatusCode(401))
    assertEquals(401, session("alice", key).status("x"))
    assertEquals(2, recorded().size)
    assertEquals(2, minted.get())

    server.reset()
    server.`when`(request())
      .respond(response().withStatusCode(401).withHeader("WWW-Authenticate", """Bearer realm="alice""""))
    val fresh = AtomicInteger()
    val challenged = SempodsRequestAuth.refreshable(
      SempodsCredentialSupplier { _, _ -> "k-${fresh.incrementAndGet()}" },
      "X-Api-Key",
      "",
    )

    assertEquals(401, session("alice", challenged).status("x"))

    assertEquals(1, recorded().size, "a challenge naming a scheme is not this mechanism's to answer")
    assertEquals(1, fresh.get())
  }

  @Test
  fun `an answer is observed although a one-shot body rules out the retry`() {
    val nonce = Nonce()
    val a = session("alice", nonce)
    server.`when`(request()).respond(response().withStatusCode(401).withHeader("DPoP-Nonce", "n6"))

    send(a.newRequest("PUT", "x").put(oneShotBody("body")).build()).close()

    assertEquals(1, recorded().size)
    assertEquals(listOf(401), nonce.seen)
    assertEquals(0, nonce.challenged.get(), "recovery was asked although no attempt was possible")
    assertEquals("n6", nonce.held, "the nonce a later call needs was hidden by the body")
  }

  @Test
  fun `the answer that ends the attempt budget is observed too`() {
    val nonce = Nonce()
    val a = session("alice", nonce)
    // The server keeps challenging, so the retry is refused too and the budget ends on that answer.
    server.`when`(request()).respond(
      response()
        .withStatusCode(401)
        .withHeader("WWW-Authenticate", """DPoP error="use_dpop_nonce"""")
        .withHeader("DPoP-Nonce", "n7"),
    )

    assertEquals(401, a.status("x"))

    assertEquals(2, recorded().size)
    assertEquals(listOf(401, 401), nonce.seen, "the answer to the last attempt was not shown")
    assertEquals(1, nonce.challenged.get(), "recovery was asked again although the budget was spent")
  }

  @Test
  fun `a failure while observing closes the answer and fails the call`() {
    val a = session("alice", NoStore())

    // A 200 as well, because that is the answer a mechanism would otherwise lose in silence.
    for (status in listOf(200, 401)) {
      server.reset()
      server.`when`(request()).respond(response().withStatusCode(status))
      val failed = assertThrows<IOException> { a.status("x") }
      assertEquals("no nonce store", failed.message, "the answer to $status")
    }
  }

  @Test
  fun `a failing observer does not keep the next one from being told`() {
    // The call fails either way. What the mechanism after it keeps is for the call after that one.
    val nonce = Nonce()
    val a = session("alice", NoStore().andThen(nonce))
    server.`when`(request()).respond(response().withStatusCode(200).withHeader("DPoP-Nonce", "n8"))

    val failed = assertThrows<IOException> { a.status("x") }

    assertEquals("no nonce store", failed.message)
    assertEquals(listOf(200), nonce.seen)
    assertEquals("n8", nonce.held)
  }

  @Test
  fun `two sessions keep their nonces and their credentials apart`() {
    val nonceA = Nonce()
    val nonceB = Nonce()
    val mintedA = AtomicInteger()
    val mintedB = AtomicInteger()
    val a = session("alice", refreshable { _ -> "a-${mintedA.incrementAndGet()}" }.andThen(nonceA))
    val b = session("bob", refreshable { _ -> "b-${mintedB.incrementAndGet()}" }.andThen(nonceB))
    server.`when`(request().withPath("/alice/x"))
      .respond(response().withStatusCode(200).withHeader("DPoP-Nonce", "na").withBody("ok"))
    server.`when`(request().withPath("/bob/x"))
      .respond(response().withStatusCode(200).withHeader("DPoP-Nonce", "nb").withBody("ok"))

    val pool = Executors.newFixedThreadPool(2)
    try {
      repeat(3) {
        val calls = listOf(pool.submit<Int> { a.status("x") }, pool.submit<Int> { b.status("x") })
        calls.forEach { assertEquals(200, it.get(15, TimeUnit.SECONDS)) }
      }
    } finally {
      pool.shutdownNow()
    }

    assertEquals("na", nonceA.held)
    assertEquals("nb", nonceB.held)
    assertEquals(1, mintedA.get())
    assertEquals(1, mintedB.get())
    server.retrieveRecordedRequests(request().withPath("/alice/x")).drop(1).forEach {
      assertEquals("na", it.getFirstHeader("DPoP-Nonce"))
      assertTrue(it.getFirstHeader("Authorization").startsWith("Bearer a-"), it.getFirstHeader("Authorization"))
    }
    server.retrieveRecordedRequests(request().withPath("/bob/x")).drop(1).forEach {
      assertEquals("nb", it.getFirstHeader("DPoP-Nonce"))
      assertTrue(it.getFirstHeader("Authorization").startsWith("Bearer b-"), it.getFirstHeader("Authorization"))
    }
  }
}
