package org.sempods.client.core

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What the typed SELECT and ASK results make of a body, and what they refuse to make of one.
 *
 * The rule every case holds: a document outside the SPARQL 1.1 Query Results JSON Format never becomes
 * a result, an empty result or `false`, and the failure says where without quoting the document.
 */
class SempodsSparqlResultsDecodingTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun sparql() = SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client).sparql()

  private fun answer(body: String) {
    server.reset()
    server.`when`(request()).respond(response().withStatusCode(200).withHeader("X-Trace", "t-1").withBody(body))
  }

  private fun selected(body: String): SempodsSparqlResults {
    answer(body)
    return assertNotNull(sparql().select("SELECT * WHERE { ?s ?p ?o }").body)
  }

  private fun refused(body: String, read: (SempodsPodSparql) -> Any = { it.select("SELECT * WHERE { ?s ?p ?o }") }): SempodsDecodingException {
    answer(body)
    val failure = assertThrows<SempodsDecodingException> { read(sparql()) }
    assertEquals(200, failure.status)
    assertEquals("t-1", failure.headers["X-Trace"])
    return failure
  }

  private fun results(vars: String, bindings: String) = """{"head":{"vars":$vars},"results":{"bindings":$bindings}}"""

  private fun term(term: String) = results("""["s"]""", """[{"s":$term}]""")

  @Test
  fun `the W3C example document reads as its terms, with unbound cells absent and unknown members ignored`() {
    val body = """{"head":{"vars":["book","title","n","b","unbound"],"link":["http://example.org/meta"]},""" +
      """"results":{"bindings":[{"book":{"type":"uri","value":"http://example.org/book/1"},""" +
      """"title":{"type":"literal","value":"Harry Potter","xml:lang":"en"},""" +
      """"n":{"type":"literal","value":"42","datatype":"http://www.w3.org/2001/XMLSchema#integer"},""" +
      """"b":{"type":"bnode","value":"r1"}},{}],"distinct":false},"extra":{"any":[1]}}"""

    val results = selected(body)

    assertEquals(listOf("book", "title", "n", "b", "unbound"), results.variables)
    assertEquals(2, results.solutions.size)
    val first = results.solutions.first()
    assertEquals(SempodsSparqlTerm(SempodsSparqlTermKind.IRI, "http://example.org/book/1", null, null), first["book"])
    assertEquals(SempodsSparqlTerm(SempodsSparqlTermKind.LITERAL, "Harry Potter", "en", null), first["title"])
    assertEquals(
      SempodsSparqlTerm(SempodsSparqlTermKind.LITERAL, "42", null, "http://www.w3.org/2001/XMLSchema#integer"),
      first["n"],
    )
    assertEquals(SempodsSparqlTerm(SempodsSparqlTermKind.BLANK_NODE, "r1", null, null), first["b"])
    assertNull(first["unbound"])
    assertEquals(emptyMap(), results.solutions.last().bindings)
    assertEquals(listOf("http://example.org/book/1"), results.column("book").map { it.value })
    assertEquals(emptyList(), results.column("unbound"))
  }

  @Test
  fun `a literal without a datatype keeps it absent, and an empty result is a result`() {
    assertNull(selected(term("""{"type":"literal","value":"plain"}""")).column("s").single().datatype)
    assertEquals(emptyList(), selected(results("""["s"]""", "[]")).solutions)
    assertEquals(emptyList(), selected(results("[]", "[{}]")).variables)
  }

  @Test
  fun `a name that is not a declared variable is refused`() {
    val results = selected(term("""{"type":"uri","value":"urn:a"}"""))

    assertThrows<IllegalArgumentException> { results.column("typo") }
    assertThrows<IllegalArgumentException> { results.solutions.single()["typo"] }
  }

  /** The pod decides how many variables and solutions there are; reading them must stay linear in that. */
  @Test
  fun `a result with a hundred thousand variables and solutions reads in linear time`() {
    val count = 100_000
    val vars = (0 until count).joinToString(",", "[", "]") { "\"v$it\"" }
    val bindings = (0 until count).joinToString(",", "[", "]") { """{"v$it":{"type":"literal","value":"x"}}""" }
    answer(results(vars, bindings))

    val results = assertTimeoutPreemptively(Duration.ofSeconds(10)) { assertNotNull(sparql().select("SELECT * WHERE { ?s ?p ?o }").body) }

    assertEquals(count, results.variables.size)
    assertEquals(count, results.solutions.size)
    val column = assertTimeoutPreemptively(Duration.ofSeconds(10)) { results.column("v${count - 1}") }
    assertEquals(listOf("x"), column.map { it.value })
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = [
      """{"results":{"bindings":[]}}|/head: expected an object, found no value""",
      """{"head":{"vars":"s"},"results":{"bindings":[]}}|/head/vars: expected an array of strings, found string""",
      """{"head":{"vars":["s",1]},"results":{"bindings":[]}}|/head/vars/1: expected a string, found number""",
      """{"head":{"vars":["s","s"]},"results":{"bindings":[]}}|/head/vars/1: repeats an earlier variable""",
      """{"head":{"vars":["s"]}}|/results: expected an object, found no value""",
      """{"head":{"vars":["s"]},"results":{"bindings":{}}}|/results/bindings: expected an array of objects, found object""",
      """{"head":{"vars":["s"]},"results":{"bindings":[1]}}|/results/bindings/0: expected an object, found number""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{},{"x":{"type":"uri","value":"urn:a"}}]}}|/results/bindings/1: binds a variable not in /head/vars""",
      """{"head":{"vars":["o","s"]},"results":{"bindings":[{"s":"v"}]}}|/results/bindings/0, the binding of /head/vars/1: expected an object, found string""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"value":"v"}}]}}|/results/bindings/0, the binding of /head/vars/0, member type: expected a string, found no value""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"triple","value":{}}}]}}|/results/bindings/0, the binding of /head/vars/0: type: expected uri, literal or bnode""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"typed-literal","value":"1"}}]}}|/results/bindings/0, the binding of /head/vars/0: type: expected uri, literal or bnode""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"uri","value":7}}]}}|/results/bindings/0, the binding of /head/vars/0, member value: expected a string, found number""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"literal","value":"v","xml:lang":"ar","its:dir":"rtl"}}]}}|/results/bindings/0, the binding of /head/vars/0: its:dir is SPARQL 1.2's base direction, outside the SPARQL 1.1 results format""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"uri","value":"urn:a","xml:lang":"en"}}]}}|/results/bindings/0, the binding of /head/vars/0: xml:lang and datatype belong to a literal""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"bnode","value":"b","datatype":"urn:t"}}]}}|/results/bindings/0, the binding of /head/vars/0: xml:lang and datatype belong to a literal""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"literal","value":"v","xml:lang":"en","datatype":"urn:t"}}]}}|/results/bindings/0, the binding of /head/vars/0: a literal carries xml:lang or datatype, and this one carries both""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"literal","value":"v","xml:lang":""}}]}}|/results/bindings/0, the binding of /head/vars/0: xml:lang: expected a language tag, found an empty string""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"literal","value":"v","datatype":5}}]}}|/results/bindings/0, the binding of /head/vars/0, member datatype: expected a string or null, found number""",
    ],
  )
  fun `a document outside the results format is refused where it breaks it`(body: String, expected: String) {
    val failure = refused(body)

    assertTrue(expected in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `an ASK answer is its boolean, and anything but a JSON boolean is refused`() {
    answer("""{"head":{},"boolean":false}""")
    assertEquals(false, sparql().ask("ASK {}").body)

    listOf(
      """{"head":{},"boolean":"true"}""" to "/boolean: expected a boolean, found string",
      """{"head":{},"boolean":null}""" to "/boolean: expected a boolean, found null",
      """{"head":{},"boolean":1}""" to "/boolean: expected a boolean, found number",
    ).forEach { (body, expected) ->
      val failure = refused(body) { it.ask("ASK {}") }
      assertTrue(expected in failure.message.orEmpty(), failure.message)
    }
  }

  @Test
  fun `a SELECT document is not an ASK answer, and an ASK document is not a SELECT result`() {
    val asAsk = refused(term("""{"type":"uri","value":"urn:a"}""")) { it.ask("ASK {}") }
    assertTrue("/boolean: expected a boolean, found no value" in asAsk.message.orEmpty(), asAsk.message)

    val asSelect = refused("""{"head":{},"boolean":true}""")
    assertTrue("/head/vars: expected an array of strings, found no value" in asSelect.message.orEmpty(), asSelect.message)
  }

  @ParameterizedTest
  @ValueSource(strings = ["null", "[]", "\"x\"", "", """{"head":{"vars":["s"]},"results":{"bindings":[}}""", """{"head":{},"head":{}}"""])
  fun `a body that is not one well-formed JSON object is refused`(body: String) {
    refused(body)
  }

  @Test
  fun `nesting beyond the read limit is refused as a limit`() {
    val deep = "[".repeat(64) + "]".repeat(64)

    val failure = refused("""{"head":{"vars":[],"x":$deep},"results":{"bindings":[]}}""")

    assertTrue("beyond this client's read limits" in failure.message.orEmpty(), failure.message)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"head":{"vars":["s"]},"results":{"bindings":[{"SECRET-7f3a":{"type":"uri","value":"urn:a"}}]}}""",
      """{"head":{"vars":["SECRET-7f3a"]},"results":{"bindings":[{"SECRET-7f3a":"SECRET-7f3a"}]}}""",
      """{"head":{"vars":["SECRET-7f3a"]},"results":{"bindings":[{"SECRET-7f3a":{"type":"SECRET-7f3a","value":"x"}}]}}""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"literal","value":{"SECRET-7f3a":1}}}]}}""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"uri","value":"x","xml:lang":"SECRET-7f3a"}}]}}""",
      """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"literal","value":"x","datatype":["SECRET-7f3a"]}}]}}""",
      """{"head":{"vars":["SECRET-7f3a","SECRET-7f3a"]},"results":{"bindings":[]}}""",
      """{"head":{"vars":[SECRET-7f3a]}}""",
    ],
  )
  fun `no failure quotes the body, anywhere on its cause chain`(body: String) {
    val failure = refused(body)

    generateSequence(failure as Throwable) { it.cause }.forEach {
      assertFalse("SECRET-7f3a" in it.toString(), "$it")
    }
  }
}
