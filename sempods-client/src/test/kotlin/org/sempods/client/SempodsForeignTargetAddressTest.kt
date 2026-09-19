package org.sempods.client

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** The URIs and options a foreign target refuses before it builds a request. */
class SempodsForeignTargetAddressTest : MockPodTest() {

  private val client = sempodsClient()

  private val foreign get() = SempodsForeignTarget(client)

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  @AfterEach
  fun nothingWasSent() {
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty(), "a refusal sends nothing")
  }

  private fun refused(uri: String) = assertThrows<IllegalArgumentException> {
    server.`when`(request()).respond(response().withStatusCode(200))
    foreign.getText(uri, "text/turtle")
  }

  @ParameterizedTest
  @ValueSource(strings = ["", " ", "\t"])
  fun `a blank URI is refused`(uri: String) {
    refused(uri)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "file:///etc/passwd",
      "ftp://files.example/card",
      "urn:isbn:978-3-16-148410-0",
      "did:web:bob.example",
      "mailto:bob@bob.example",
      "data:text/plain,hello",
      "people/bob/card",
      "//bob.example/card",
      "http://",
    ],
  )
  fun `a URI that is not an absolute http or https URL is refused`(uri: String) {
    refused(uri)
  }

  @Test
  fun `a URI with userinfo is refused, and the refusal quotes no password`() {
    listOf("https://bob:hunter2@bob.example/card", "https://trusted.example@evil.example/card").forEach { uri ->
      val message = refused(uri).message.orEmpty()
      assertFalse("hunter2" in message, message)
      assertFalse("trusted.example" in message, "the host that is dialled is the one named: $message")
    }
  }

  @Test
  fun `a string that is no URL is named by its scheme alone`() {
    val message = refused("ftp://bob:hunter2@files.example/card").message.orEmpty()

    assertTrue("'ftp:'" in message, message)
    assertFalse("hunter2" in message, message)
  }

  @ParameterizedTest
  @ValueSource(strings = ["", " "])
  fun `a blank accept is refused`(accept: String) {
    assertThrows<IllegalArgumentException> { foreign.getText("$origin/card", accept) }
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, 0, 21])
  fun `a redirect budget outside 1 to 20 is refused`(maxRedirects: Int) {
    assertThrows<IllegalArgumentException> { foreign.followingRedirects(maxRedirects) }
  }
}
