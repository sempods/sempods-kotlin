package org.sempods.client.core

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What the typed `dateModified` makes of a body, and what it refuses to make of one.
 *
 * The rule every case holds: a body that is not the route's document never becomes a timestamp, a
 * `null` or an empty answer, and the failure says where it went wrong without quoting the body.
 */
class SempodsPodMetadataDecodingTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun dateModified(body: String): SempodsResponse<SempodsPodDateModified> {
    server.`when`(request()).respond(response().withStatusCode(200).withHeader("X-Trace", "t-1").withBody(body))
    return SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client).metadata().dateModified()
  }

  private fun refused(body: String): SempodsDecodingException {
    val failure = assertThrows<SempodsDecodingException> { dateModified(body) }
    assertEquals(200, failure.status)
    assertEquals("t-1", failure.headers["X-Trace"])
    return failure
  }

  private val instant = Instant.parse("2026-05-20T10:15:30Z")

  @Test
  fun `escaped characters in a member's name and value decode to what they stand for`() {
    assertEquals(instant, dateModified("""{"dateModified":"2026\u002d05\u002d20T10:15:30Z"}""").body?.dateModified)
    assertEquals(instant, dateModified("""{"date\u004dodified":"2026-05-20T10:15:30Z"}""").body?.dateModified)
  }

  @Test
  fun `escaped control characters and surrogate pairs in another member do not disturb the one that counts`() {
    val body = """{"note":"\ud83d\ude00 \u0000 \u001f \n \t","dateModified":"2026-05-20T10:15:30Z"}"""

    assertEquals(instant, dateModified(body).body?.dateModified)
  }

  @Test
  fun `unknown members of every shape are ignored`() {
    val body = """{"a":1,"b":[1,{"c":null}],"d":{"e":"f"},"dateModified":null,"g":false}"""

    assertEquals(SempodsPodDateModified(null), dateModified(body).body)
  }

  @Test
  fun `a member that is missing and a member that is null read the same`() {
    assertNull(dateModified("{}").body?.dateModified)
    assertNull(dateModified("""{"dateModified":null}""").body?.dateModified)
  }

  @ParameterizedTest
  @ValueSource(strings = ["42", "true", "{}", "[]", "\"\"", "\"yesterday\"", "\"2026-05-20T10:15:30Z\\u0000\""])
  fun `a dateModified that is neither null nor an instant is refused at its pointer`(value: String) {
    val failure = refused("""{"dateModified":$value}""")

    assertTrue("/dateModified: expected" in failure.message.orEmpty(), failure.message)
  }

  @ParameterizedTest
  @ValueSource(strings = ["null", "[]", "\"x\"", "42", ""])
  fun `a body that is not one JSON object is refused`(body: String) {
    refused(body)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"dateModified":"2026-05-20T10:15:30Z"""",
      """{"dateModified":null,}""",
      """{/* c */"dateModified":null}""",
      """{'dateModified':null}""",
      """{"dateModified":null} {}""",
      """{"dateModified":null,"dateModified":"2026-05-20T10:15:30Z"}""",
      """{"n":NaN,"dateModified":null}""",
      "{\"note\":\"a\u0001b\",\"dateModified\":null}",
    ],
  )
  fun `malformed JSON is refused with its line and column`(body: String) {
    val failure = refused(body)

    assertTrue("malformed JSON at line 1, column " in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `nesting beyond the read limit is refused as a limit`() {
    val deep = "[".repeat(64) + "]".repeat(64)

    val failure = refused("""{"n":$deep,"dateModified":null}""")

    assertTrue("beyond this client's read limits" in failure.message.orEmpty(), failure.message)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"dateModified":SECRET-7f3a}""",
      """{"dateModified":"SECRET-7f3a"}""",
      """{"dateModified":{"token":"SECRET-7f3a"}}""",
      """{"dateModified":null,"dateModified":"SECRET-7f3a"}""",
      """{"SECRET-7f3a":1,"SECRET-7f3a":2}""",
    ],
  )
  fun `no failure quotes the body, anywhere on its cause chain`(body: String) {
    val failure = refused(body)

    generateSequence(failure as Throwable) { it.cause }.forEach {
      assertFalse("SECRET-7f3a" in it.toString(), "$it")
    }
  }
}
