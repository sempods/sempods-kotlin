package org.sempods.client.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What the typed context results make of a body, and what they refuse to make of one.
 *
 * The rule every case holds: a body that is not the schema's document never becomes a context or an
 * empty listing, and the failure says where it went wrong without quoting the body.
 */
class SempodsPodContextsDecodingTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun contexts() = SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client).contexts()

  private val tasks = "http://pods.example/alice/_system/contexts/tasks"

  private fun listed(body: String): SempodsContextList {
    server.reset()
    server.`when`(request()).respond(response().withStatusCode(200).withHeader("X-Trace", "t-1").withBody(body))
    return assertNotNull(contexts().list().body)
  }

  private fun refused(body: String): SempodsDecodingException {
    val failure = assertThrows<SempodsDecodingException> { listed(body) }
    assertEquals(200, failure.status)
    assertEquals("t-1", failure.headers["X-Trace"])
    return failure
  }

  @Test
  fun `escaped characters in member names and values decode to what they stand for`() {
    val body = """{"contexts":[{"contextUri":"$tasks","label":"Tâches 😀","permissions":["read"]}]}"""

    assertEquals(
      SempodsContext(tasks, "Tâches 😀", null, null, setOf(SempodsContextPermission.READ)),
      listed(body).contexts.single(),
    )
  }

  @Test
  fun `members the schema does not name are ignored at every level, whatever their shape`() {
    val body = """{"a":[1,{"b":null}],"pod_base_url":"x","writable_contexts":42,""" +
      """"contexts":[{"contextUri":"$tasks","source":"grant","context_iri":{"c":1},"permissions":["read"]}]}"""

    assertEquals(
      SempodsContextList(null, null, listOf(SempodsContext(tasks, null, null, null, setOf(SempodsContextPermission.READ))), emptyList()),
      listed(body),
    )
  }

  @Test
  fun `a listing in names the schema does not use is refused at its first entry's contextUri`() {
    val failure = refused("""{"pod_base_url":"x","contexts":[{"context_iri":"$tasks"}],"writable_contexts":[]}""")

    assertTrue("/contexts/0/contextUri: expected a string, found no value" in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `a member that is missing and a member that is null read the same`() {
    val empty = SempodsContextList(null, null, emptyList(), emptyList())

    assertEquals(empty, listed("{}"))
    assertEquals(empty, listed("""{"podBaseUrl":null,"authenticated":null,"contexts":null,"writableContexts":null}"""))
    assertEquals(
      SempodsContext(tasks, null, "", null, emptySet()),
      listed("""{"contexts":[{"contextUri":"$tasks","label":null,"description":"","public":null,"permissions":null}]}""").contexts.single(),
    )
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = [
      """{"authenticated":"true"}|/authenticated: expected a boolean or null, found string""",
      """{"podBaseUrl":1}|/podBaseUrl: expected a string or null, found number""",
      """{"contexts":{}}|/contexts: expected an array of objects or null, found object""",
      """{"contexts":[42]}|/contexts/0: expected an object, found number""",
      """{"contexts":[{"contextUri":null}]}|/contexts/0/contextUri: expected a string, found null""",
      """{"contexts":[{"contextUri":"x"},{"contextUri":"y","permissions":["read",7]}]}|/contexts/1/permissions/1: expected a string, found number""",
      """{"contexts":[{"contextUri":"x","public":"false"}]}|/contexts/0/public: expected a boolean or null, found string""",
      """{"contexts":[{"contextUri":"x","label":["a"]}]}|/contexts/0/label: expected a string or null, found array""",
      """{"writableContexts":[null]}|/writableContexts/0: expected a string, found null""",
    ],
  )
  fun `a member holding another type than its schema's is refused at its pointer`(body: String, expected: String) {
    val failure = refused(body)

    assertTrue(expected in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `a permission this client does not know is left out`() {
    val context = listed("""{"contexts":[{"contextUri":"$tasks","permissions":["read","append","WRITE","manage"]}]}""").contexts.single()

    assertEquals(setOf(SempodsContextPermission.READ, SempodsContextPermission.MANAGE), context.permissions)
  }

  @Test
  fun `a create answer without contextUri is refused`() {
    server.`when`(request()).respond(response().withStatusCode(201).withBody("{}"))

    val failure = assertThrows<SempodsDecodingException> { contexts().create("$origin/alice/_system/contexts/tasks") }

    assertEquals(201, failure.status)
    assertTrue("/contextUri: expected a string, found no value" in failure.message.orEmpty(), failure.message)
  }

  @ParameterizedTest
  @ValueSource(strings = ["null", "[]", "\"x\"", ""])
  fun `a body that is not one JSON object is refused`(body: String) {
    refused(body)
  }

  @ParameterizedTest
  @ValueSource(strings = ["""{"contexts":[}""", """{"contexts":[],"contexts":[]}""", """{"contexts":[],}"""])
  fun `malformed JSON is refused with its line and column`(body: String) {
    val failure = refused(body)

    assertTrue("malformed JSON at line 1, column " in failure.message.orEmpty(), failure.message)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"contexts":[{"contextUri":{"token":"SECRET-7f3a"}}]}""",
      """{"contexts":[{"contextUri":"x","permissions":[{"SECRET-7f3a":1}]}]}""",
      """{"contexts":[{"contextUri":"x","label":["SECRET-7f3a"]}]}""",
      """{"SECRET-7f3a":1,"SECRET-7f3a":2}""",
      """{"contexts":[SECRET-7f3a]}""",
    ],
  )
  fun `no failure quotes the body, anywhere on its cause chain`(body: String) {
    val failure = refused(body)

    generateSequence(failure as Throwable) { it.cause }.forEach {
      assertFalse("SECRET-7f3a" in it.toString(), "$it")
    }
  }
}
