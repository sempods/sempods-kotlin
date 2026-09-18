package org.sempods.client.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a protocol module gets when it runs a route of its own through the seam. */
class SempodsExchangeContractTest : MockPodTest() {

  private val client = sempodsClient()

  private val exchange get() = SempodsExchange(client)

  private val session get() = SempodsSession(SempodsPodBase.of("$origin/alice"), SempodsRequestAuth.bearer("t"))

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun own(method: String = "GET") = session.newRequest(method, "_system/own/route").build()

  @Test
  fun `a listed answer carries the status, the headers and the body a group's answer would`() {
    server.`when`(request()).respond(response().withStatusCode(201).withHeader("Location", "/alice/thing").withBody("made"))

    val answer = exchange.text(own("POST"), 200, 201)

    assertEquals(201, answer.status)
    assertEquals("/alice/thing", answer.headers["Location"])
    assertEquals("made", answer.body)
    assertTrue(answer.url.endsWith("/alice/_system/own/route"))
  }

  @Test
  fun `a listed answer outside 2xx is an answer without a body`() {
    server.`when`(request()).respond(response().withStatusCode(404).withBody("gone"))

    assertNull(exchange.text(own(), 200, 404).body, "a listed non-2xx closes its body unread")
  }

  @Test
  fun `a status the module did not list is a failure carrying what the pod wrote`() {
    server.`when`(request()).respond(response().withStatusCode(409).withBody("conflict"))

    val refused = assertThrows<SempodsStatusException> { exchange.text(own(), 200) }

    assertEquals(409, refused.status)
    assertEquals("conflict", refused.bodyExcerpt)
    assertTrue(refused.message!!.contains("_system/own/route"), refused.message!!)
  }

  @Test
  fun `the session authenticates a route the core does not own`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))

    exchange.text(own(), 200)

    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("Bearer t", sent.getFirstHeader("Authorization"))
  }

  @Test
  fun `a body the module cannot read is a decoding failure with the answer's status`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("not a number"))

    val refused = assertThrows<SempodsDecodingException> { exchange.text(own(), 200).map { it.toInt() } }

    assertEquals(200, refused.status)
    assertTrue(refused.message!!.contains("NumberFormatException"), refused.message!!)
  }

  @Test
  fun `a status check reads no body`() {
    server.`when`(request()).respond(response().withStatusCode(204))

    assertEquals(204, exchange.status(own("HEAD"), 204, 404))
  }
}
