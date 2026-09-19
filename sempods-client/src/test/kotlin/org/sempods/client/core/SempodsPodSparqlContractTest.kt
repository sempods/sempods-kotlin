package org.sempods.client.core

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What the SPARQL operations put on the wire, and which answers they take.
 *
 * The route is the specification's; these cases hold the client to the request a pod expects, to the
 * dataset parameters a selection becomes, and to the one answer the route lists.
 */
class SempodsPodSparqlContractTest : MockPodTest() {

  /** A request as OkHttp wrote it: MockServer decodes the query string and may re-read a body it records. */
  private class Sent(val url: HttpUrl, val contentType: String?, val body: ByteArray)

  private val sent = CopyOnWriteArrayList<Sent>()

  private val client = sempodsClient {
    addNetworkInterceptor { chain ->
      val request = chain.request()
      val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
      sent += Sent(request.url, request.header("Content-Type"), body)
      chain.proceed(request)
    }
  }

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  @BeforeEach
  fun forgetRequests() {
    sent.clear()
  }

  private val route = "/alice/_system/sparql/query"

  private val query = "SELECT ?s WHERE { ?s <https://schema.org/name> \"Grüße ✓\" }"

  private fun sparql(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice"), auth), client).sparql()

  private val event = "https://pods.example/alice/events/1"

  private val selectDocument = """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"uri","value":"$event"}}]}}"""

  private val askDocument = """{"head":{},"boolean":true}"""

  private val quads = "<$event> <https://schema.org/name> \"One\" <https://pods.example/alice/_system/contexts/tasks> .\n"

  private class Operation(val accept: String, val body: String, val call: (SempodsPodSparql, String, SempodsContextSelection) -> Any)

  private val operations: Map<String, Operation> by lazy {
    mapOf(
      "select" to Operation(RESULTS, selectDocument) { s, q, selection -> s.select(q, selection) },
      "ask" to Operation(RESULTS, askDocument) { s, q, selection -> s.ask(q, selection) },
      "resultsJson" to Operation(RESULTS, selectDocument) { s, q, selection -> s.resultsJson(q, selection) },
      "resultsBytes" to Operation(RESULTS, askDocument) { s, q, selection -> s.resultsBytes(q, selection) },
      "graphText JSON_LD" to Operation("application/ld+json", "{}") { s, q, selection -> s.graphText(q, SempodsGraphFormat.JSON_LD, selection) },
      "graphText N_QUADS" to Operation("application/n-quads", quads) { s, q, selection -> s.graphText(q, SempodsGraphFormat.N_QUADS, selection) },
      "graphBytes N_QUADS" to Operation("application/n-quads", quads) { s, q, selection -> s.graphBytes(q, SempodsGraphFormat.N_QUADS, selection) },
    )
  }

  private fun answer(status: Int, body: String, vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status).withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request().withPath(route)).respond(response)
  }

  @ParameterizedTest
  @ValueSource(strings = ["select", "ask", "resultsJson", "resultsBytes", "graphText JSON_LD", "graphText N_QUADS", "graphBytes N_QUADS"])
  fun `every operation posts the query as application-sparql-query and accepts what it reads`(name: String) {
    val operation = operations.getValue(name)
    answer(200, operation.body)

    operation.call(sparql(), query, SempodsContextSelection.readable())

    val recorded = server.retrieveRecordedRequests(request()).single()
    assertEquals("POST", recorded.method.value)
    assertEquals(route, recorded.path.value)
    assertEquals(operation.accept, recorded.getFirstHeader("Accept"))
    assertEquals(setOf("accept", "content-type"), recorded.headersBeyondTransport())
    val wire = sent.single()
    assertEquals("application/sparql-query", wire.contentType)
    assertContentEquals(query.toByteArray(Charsets.UTF_8), wire.body)
    assertNull(wire.url.encodedQuery)
  }

  @Test
  fun `leaving the selection out sends no dataset parameter, as readable does`() {
    answer(200, askDocument)

    sparql().ask(query)
    sparql().resultsJson(query)
    sparql().graphText(query, SempodsGraphFormat.N_QUADS)

    assertEquals(listOf(null, null, null), sent.map { it.url.encodedQuery })
  }

  @Test
  fun `none sends one present but empty default-graph-uri`() {
    answer(200, askDocument)

    sparql().ask(query, SempodsContextSelection.none())
    sparql().ask(query, SempodsContextSelection.of(emptyList()))

    sent.forEach {
      assertEquals("default-graph-uri=", it.url.encodedQuery)
      assertEquals(listOf(""), it.url.queryParameterValues("default-graph-uri"))
      assertEquals(emptyList(), it.url.queryParameterValues("named-graph-uri"))
    }
  }

  @Test
  fun `of sends its contexts once each, in both dataset parameters, encoded as query components`() {
    answer(200, askDocument)
    val awkward = "https://pods.example/alice/_system/contexts/a&b=c+d#e f/ü"
    val plain = "https://pods.example/alice/_system/contexts/plain"

    sparql().ask(query, SempodsContextSelection.of(awkward, plain, awkward))

    val url = sent.single().url
    assertEquals(listOf("default-graph-uri", "default-graph-uri", "named-graph-uri", "named-graph-uri"), url.queryParameterNames.let { names ->
      url.encodedQuery.orEmpty().split('&').map { it.substringBefore('=') }.also { assertEquals(setOf("default-graph-uri", "named-graph-uri"), names) }
    })
    assertEquals(listOf(awkward, plain), url.queryParameterValues("default-graph-uri"))
    assertEquals(listOf(awkward, plain), url.queryParameterValues("named-graph-uri"))
    listOf("&b", "=c", "+d", "#e", " f").forEach { raw ->
      assertFalse(url.encodedQuery.orEmpty().contains(raw), "'$raw' went out unencoded: ${url.encodedQuery}")
    }
  }

  @Test
  fun `the operations authenticate as the session does and add no credential of their own`() {
    answer(200, askDocument)

    sparql().ask(query)
    sparql(SempodsRequestAuth.apiKeyHeader("X-Api-Key", "key-a")).resultsJson(query)
    sparql(SempodsRequestAuth.bearer("token-b")).resultsBytes(query)

    val (anonymous, apiKey, bearer) = server.retrieveRecordedRequests(request()).toList()
    assertEquals(setOf("accept", "content-type"), anonymous.headersBeyondTransport())
    assertEquals("key-a", apiKey.getFirstHeader("X-Api-Key"))
    assertEquals(setOf("accept", "content-type", "x-api-key"), apiKey.headersBeyondTransport())
    assertEquals("Bearer token-b", bearer.getFirstHeader("Authorization"))
    assertEquals(setOf("accept", "content-type", "authorization"), bearer.headersBeyondTransport())
  }

  @Test
  fun `typed and raw results read the same answer, the raw ones as it was sent`() {
    answer(200, selectDocument, "Content-Type" to "application/sparql-results+json", "X-Request-Id" to "r-1")

    val typed = sparql().select(query)
    assertEquals(200, typed.status)
    assertEquals("r-1", typed.headers["X-Request-Id"])
    assertEquals(listOf("s"), assertNotNull(typed.body).variables)
    assertEquals(listOf(SempodsSparqlTerm.of(SempodsSparqlTermKind.IRI, event, null, null)), typed.body?.column("s"))
    assertEquals(selectDocument, sparql().resultsJson(query).body)
    assertContentEquals(selectDocument.toByteArray(), sparql().resultsBytes(query).body)

    server.reset()
    answer(200, askDocument)
    assertEquals(true, sparql().ask("ASK { ?s ?p ?o }").body)

    server.reset()
    answer(200, quads, "Content-Type" to "application/n-quads")
    assertEquals(quads, sparql().graphText("CONSTRUCT WHERE { ?s ?p ?o }", SempodsGraphFormat.N_QUADS).body)
    assertContentEquals(quads.toByteArray(), sparql().graphBytes("CONSTRUCT WHERE { ?s ?p ?o }", SempodsGraphFormat.N_QUADS).body)
  }

  @ParameterizedTest
  @ValueSource(ints = [400, 401, 404, 406, 500])
  fun `any other status is a failure that keeps its headers and quotes neither the query nor the selection`(status: Int) {
    answer(status, "refused: see logs", "WWW-Authenticate" to "Bearer realm=\"pod\"")
    val secret = "https://pods.example/alice/_system/contexts/SECRET-7f3a"

    operations.forEach { (name, operation) ->
      val failure = assertThrows<SempodsStatusException>(name) {
        operation.call(sparql(), "SELECT ?SECRET WHERE { ?SECRET ?p ?o }", SempodsContextSelection.of(secret))
      }
      assertEquals(status, failure.status)
      assertEquals("Bearer realm=\"pod\"", failure.headers["WWW-Authenticate"])
      assertEquals("refused: see logs", failure.bodyExcerpt)
      assertEquals("POST $origin$route answered $status, which this operation does not accept.", failure.message)
      assertFalse("SECRET" in failure.toString(), failure.toString())
    }
  }

  @ParameterizedTest
  @ValueSource(ints = [201, 204])
  fun `a success the route does not list is refused like any other status`(status: Int) {
    answer(status, askDocument)

    operations.forEach { (name, operation) ->
      assertEquals(status, assertThrows<SempodsStatusException>(name) { operation.call(sparql(), query, SempodsContextSelection.readable()) }.status)
    }
  }

  @Test
  fun `an empty query is sent as it is, and the pod's refusal is the answer`() {
    answer(400, "Missing SPARQL query")

    val failure = assertThrows<SempodsStatusException> { sparql().resultsJson("") }

    assertEquals(400, failure.status)
    assertEquals(0, sent.single().body.size)
  }

  @Test
  fun `over a client without the sempods interceptors nothing is sent`() {
    val plain = OkHttpClient()
    try {
      val sparql = SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), plain).sparql()
      assertThrows<IOException> { sparql.ask(query) }
    } finally {
      plain.shutDown()
    }
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  private companion object {
    const val RESULTS = "application/sparql-results+json"
  }
}
