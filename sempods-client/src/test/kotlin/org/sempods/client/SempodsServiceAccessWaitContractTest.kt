package org.sempods.client

import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** What the wait for a service's access asks the pod, and when it stops. */
class SempodsServiceAccessWaitContractTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val alice get() = SempodsPodBase.of("$origin/alice")

  private val c get() = "$origin/alice/_system/contexts/c"
  private val d get() = "$origin/alice/_system/contexts/d"

  private fun waiting(initialDelay: Duration = Duration.ofMillis(10)) = SempodsServiceAccessWait(
    SempodsSession(alice, SempodsRequestAuth.clientSecretBasic("svc:1", "sc_1")),
    client,
    initialDelay,
    maxOf(Duration.ofMillis(40), initialDelay),
  )

  private fun token(status: Int, body: String, times: Times = Times.unlimited()) {
    server.`when`(request().withPath("/alice/_system/auth/token"), times)
      .respond(response().withStatusCode(status).withHeader("Content-Type", "application/json").withBody(body))
  }

  /** The catalogue in canonical JSON-LD; with no context the pod writes no `sd:namedGraph` member. */
  private fun contexts(vararg iris: String, times: Times = Times.unlimited()) {
    val named = if (iris.isEmpty()) "" else
      ""","$NAMED_GRAPH":[${iris.joinToString(",") { """{"@id":"$it"}""" }}],"$READABLE":[${iris.joinToString(",") { """{"@id":"$it"}""" }}]"""
    val body = """{"@id":"$origin/alice/_system/contexts","@type":["http://www.w3.org/ns/sparql-service-description#GraphCollection"]$named}"""
    server.`when`(request().withPath("/alice/_system/contexts"), times)
      .respond(response().withStatusCode(200).withHeader("Content-Type", "application/ld+json").withBody(body))
  }

  private val granted = """{"access_token":"at-1","token_type":"Bearer","expires_in":3600}"""
  private val noGrant = """{"error":"invalid_scope","error_description":"no scopes registered for this client"}"""

  private fun sent(path: String) = server.retrieveRecordedRequests(request().withPath(path))

  @Test
  fun `a pending service waits through invalid_scope until its context is listed`() {
    token(400, noGrant, Times.exactly(2))
    token(200, granted)
    contexts(c)

    assertEquals(SempodsServiceAccessWait.Outcome.REACHABLE, waiting().await(listOf(c), Duration.ofSeconds(5)))

    assertEquals(3, sent("/alice/_system/auth/token").size)
    val listing = sent("/alice/_system/contexts").single()
    assertEquals("Bearer at-1", listing.getFirstHeader("Authorization"))
    assertEquals("application/ld+json", listing.getFirstHeader("Accept"))
  }

  @Test
  fun `a working token is not enough, and the wait lasts until the named context appears`() {
    token(200, granted)
    contexts(c, times = Times.exactly(2))
    contexts(c, d)

    assertEquals(SempodsServiceAccessWait.Outcome.REACHABLE, waiting().await(listOf(d), Duration.ofSeconds(5)))

    assertEquals(1, sent("/alice/_system/auth/token").size, "the token is kept while the pod accepts it")
    assertEquals(3, sent("/alice/_system/contexts").size)
  }

  @Test
  fun `a catalogue without a named graph reaches nothing yet`() {
    token(200, granted)
    contexts(times = Times.exactly(1))
    contexts(c)

    assertEquals(SempodsServiceAccessWait.Outcome.REACHABLE, waiting().await(listOf(c), Duration.ofSeconds(5)))
  }

  @Test
  fun `a token the pod no longer accepts is minted again`() {
    token(200, granted)
    server.`when`(request().withPath("/alice/_system/contexts"), Times.exactly(1))
      .respond(response().withStatusCode(401).withHeader("WWW-Authenticate", "Bearer error=\"invalid_token\""))
    contexts(c)

    assertEquals(SempodsServiceAccessWait.Outcome.REACHABLE, waiting().await(listOf(c), Duration.ofSeconds(5)))

    assertEquals(2, sent("/alice/_system/auth/token").size)
  }

  @Test
  fun `invalid_client ends the wait as the pod's refusal`() {
    token(401, """{"error":"invalid_client"}""")

    val failure = assertThrows<SempodsStatusException> { waiting().await(listOf(c), Duration.ofSeconds(5)) }

    assertEquals(401, failure.status)
    assertEquals(1, sent("/alice/_system/auth/token").size, "nothing is asked again")
  }

  @Test
  fun `the time limit ends a wait for a decision that never comes`() {
    token(400, noGrant)

    val started = System.nanoTime()
    assertEquals(SempodsServiceAccessWait.Outcome.TIME_LIMIT, waiting().await(listOf(c), Duration.ofMillis(300)))

    assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
    assertTrue(sent("/alice/_system/auth/token").size > 1, "it asked more than once")
  }

  @Test
  fun `cancel ends a pause at once`() {
    token(400, noGrant)
    val wait = waiting(initialDelay = Duration.ofSeconds(20))
    val outcome = CompletableFuture.supplyAsync { wait.await(listOf(c), Duration.ofMinutes(5)) }

    while (sent("/alice/_system/auth/token").isEmpty()) Thread.onSpinWait()
    wait.cancel()

    assertEquals(SempodsServiceAccessWait.Outcome.CANCELLED, outcome.get(5, TimeUnit.SECONDS))
  }

  @Test
  fun `an interrupt ends the wait as an InterruptedIOException`() {
    token(400, noGrant)
    Thread.currentThread().interrupt()

    try {
      assertThrows<InterruptedIOException> { waiting(initialDelay = Duration.ofSeconds(20)).await(listOf(c), Duration.ofMinutes(5)) }
    } finally {
      Thread.interrupted()
    }
  }

  private companion object {
    const val NAMED_GRAPH = "http://www.w3.org/ns/sparql-service-description#namedGraph"
    const val READABLE = "https://sempods.org/ns#readableContext"
  }
}
