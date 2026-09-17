package org.sempods.client.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What [SempodsResponse.map] keeps of an answer, and how it reports a body its decoder cannot read. */
class SempodsResponseMapTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val event = "https://pods.example/alice/events/1"

  private fun read(status: Int, body: String = ""): SempodsResponse<ByteArray> {
    server.`when`(request()).respond(
      response().withStatusCode(status).withHeader("ETag", "\"v1\"").withHeader("X-Trace", "t-1").withBody(body),
    )
    val options = SempodsReadOptions.of(SempodsContextSelection.of("https://pods.example/alice/_system/contexts/tasks"))
    return SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client)
      .resources().getBytes(event.replace("https://pods.example", origin), SempodsGraphFormat.N_QUADS, options)
  }

  @Test
  fun `a decoded answer keeps the URL, status and headers`() {
    val answer = read(200, "<urn:s> <urn:p> \"secret\" .\n")

    val decoded = answer.map { String(it).lines().filter(String::isNotBlank) }

    assertEquals(answer.url, decoded.url)
    assertEquals(200, decoded.status)
    assertEquals("\"v1\"", decoded.headers["ETag"])
    assertEquals(listOf("<urn:s> <urn:p> \"secret\" ."), decoded.body)
  }

  @Test
  fun `a listed answer without a body stays without one, and its decoder is not called`() {
    val decoded = read(404).map<String> { error("called for an answer without a body") }

    assertEquals(404, decoded.status)
    assertNull(decoded.body)
  }

  @Test
  fun `a decoder failure is a decoding failure of this answer, quoting neither the body nor the failure`() {
    val answer = read(200, "<urn:s> <urn:p> \"secret\" .\n")

    val refused = assertThrows<SempodsDecodingException> {
      answer.map<String> { throw IllegalArgumentException("expected a graph, got ${String(it)}") }
    }

    assertEquals(200, refused.status)
    assertEquals("t-1", refused.headers["X-Trace"])
    val message = refused.message.orEmpty()
    assertTrue(message.startsWith("GET $origin/alice/events/1 answered 200"), message)
    assertTrue(IllegalArgumentException::class.java.name in message, message)
    assertFalse("secret" in message || "expected a graph" in message, message)
    assertFalse("?" in message, "the URL is named without its query: $message")
  }

  @Test
  fun `an IOException passes through as it is, a decoding failure of a nested map included`() {
    val answer = read(200, "x")
    val own = IOException("the decoder's own")

    assertSame(own, assertThrows<IOException> { answer.map<String> { throw own } })

    val inner = assertThrows<SempodsDecodingException> { answer.map<String> { error("inner") } }
    assertSame(inner, assertThrows<SempodsDecodingException> { answer.map<String> { throw inner } })
  }

  @Test
  fun `an interrupted decoder ends as an interrupted read, with the thread still interrupted`() {
    val answer = read(200, "x")

    try {
      assertThrows<InterruptedIOException> { answer.map<String> { throw InterruptedException() } }
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }
}
