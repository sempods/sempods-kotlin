package org.sempods.client

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** The raw bodies: what the server sent, unchanged, from the same execution the typed result uses. */
class SempodsPodMetadataRawBodyTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun metadata() = SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client).metadata()

  @Test
  fun `the text and the bytes are the body as sent, with its unknown members, layout and escapes`() {
    val body = "{\n  \"zeta\" : [ 1,  2 ],\n\t\"dateModified\":\"2026\\u002d05-20T10:15:30Z\" ,\"alpha\":{}\n}\n"
    server.`when`(request()).respond(response().withStatusCode(200).withBody(body))

    val text = metadata().dateModifiedJson().body
    val bytes = metadata().dateModifiedBytes().body

    assertEquals(body, text)
    assertContentEquals(body.toByteArray(), bytes)
  }

  @Test
  fun `malformed JSON arrives unchanged raw, and only the typed reading refuses it`() {
    val body = """{"dateModified": "2026-05-20T10:15:30Z",,, oops"""
    server.`when`(request()).respond(response().withStatusCode(200).withBody(body))

    assertEquals(body, metadata().dateModifiedJson().body)
    assertContentEquals(body.toByteArray(), metadata().dateModifiedBytes().body)
    assertThrows<SempodsDecodingException> { metadata().dateModified() }
  }

  @Test
  fun `bytes that are not UTF-8 arrive as they were sent`() {
    val body = byteArrayOf(0x7b, 0xff.toByte(), 0xfe.toByte(), 0x7d)
    server.`when`(request()).respond(response().withStatusCode(200).withBody(body))

    assertContentEquals(body, metadata().dateModifiedBytes().body)
  }

  @Test
  fun `the text is decoded in the charset the answer names`() {
    server.`when`(request()).respond(
      response().withStatusCode(200)
        .withHeader("Content-Type", "application/json; charset=ISO-8859-1")
        .withBody(byteArrayOf(0x22, 0xe9.toByte(), 0x22)),
    )

    assertEquals("\"é\"", metadata().dateModifiedJson().body)
  }

  @ParameterizedTest
  @ValueSource(ints = [200, 404])
  fun `repeated headers survive success and absence alike`(status: Int) {
    server.`when`(request()).respond(
      response().withStatusCode(status)
        .withHeader("Link", "<a>; rel=x", "<b>; rel=y")
        .withBody("""{"dateModified":null}"""),
    )

    listOf(metadata().dateModified(), metadata().dateModifiedJson(), metadata().dateModifiedBytes()).forEach {
      assertEquals(status, it.status)
      assertEquals(listOf("<a>; rel=x", "<b>; rel=y"), it.headers.values("Link"))
    }
  }
}
