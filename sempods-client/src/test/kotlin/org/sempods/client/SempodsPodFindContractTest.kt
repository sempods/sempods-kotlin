package org.sempods.client

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
 * What `find` puts on the wire, and which answers it takes.
 *
 * The route is the specification's (`spec/core/find.md`); these cases hold the client to the JSON body
 * a pod expects, to the difference between an omitted and an empty selection, and to the one answer
 * the route lists. The answers are passed through as text, so the specification's response examples
 * come back exactly as the pod sent them.
 */
class SempodsPodFindContractTest : MockPodTest() {

  /** A request as OkHttp wrote it, with whether its call carried the repeatable mark. */
  private class Sent(val contentType: String?, val body: String, val repeatable: Boolean)

  private val sent = CopyOnWriteArrayList<Sent>()

  private val client = sempodsClient {
    addNetworkInterceptor { chain ->
      val request = chain.request()
      val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
      sent += Sent(request.header("Content-Type"), body, SempodsRepeatable.isMarked(chain.call()))
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

  private val route = "/alice/_system/find"

  private fun find(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice"), auth), client).find()

  private val a = "https://pods.example/alice/_system/contexts/A"

  private val b = "https://pods.example/alice/_system/contexts/B"

  private fun answer(status: Int, body: String, vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status).withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request().withPath(route)).respond(response)
  }

  @ParameterizedTest
  @ValueSource(strings = ["JSON_LD", "N_QUADS"])
  fun `a find posts its query as JSON, marked repeatable, and accepts the format asked for`(name: String) {
    val format = SempodsGraphFormat.valueOf(name)
    answer(200, "")

    find().text(SempodsFindQuery.text("Grüße ✓"), format)
    find().bytes(SempodsFindQuery.text("Grüße ✓"), format)

    server.retrieveRecordedRequests(request()).forEach { recorded ->
      assertEquals("POST", recorded.method.value)
      assertEquals(route, recorded.path.value)
      assertTrue(recorded.queryStringParameterList.isEmpty())
      assertEquals(format.mediaType, recorded.getFirstHeader("Accept"))
      assertEquals(setOf("accept", "content-type"), recorded.headersBeyondTransport())
    }
    sent.forEach { wire ->
      assertEquals("application/json", wire.contentType)
      assertEquals("""{"text":"Grüße ✓"}""", wire.body)
      assertTrue(wire.repeatable)
    }
    assertEquals(2, sent.size)
  }

  @Test
  fun `leaving the format out asks for JSON-LD`() {
    answer(200, "[]")

    find().text(SempodsFindQuery.text("harbour"))

    assertEquals("application/ld+json", server.retrieveRecordedRequests(request()).single().getFirstHeader("Accept"))
  }

  @Test
  fun `the body carries only the members the query sets, in the route's names`() {
    answer(200, "[]")
    val article = "https://schema.org/Article"
    val book = "https://schema.org/Book"

    find().text(SempodsFindQuery.text("harbour"))
    find().text(SempodsFindQuery.text("harbour").withTypes(article, book, article))
    find().text(SempodsFindQuery.text("harbour").withSelection(SempodsContextSelection.of(a, b, a)))
    find().text(SempodsFindQuery.text("harbour").withIncludeContexts(true))
    find().text(SempodsFindQuery.text("harbour").withIncludeContexts(true).withIncludeContexts(false))
    find().text(SempodsFindQuery.text("harbour").withLimit(1))
    find().text(
      SempodsFindQuery.text("harbour").withLimit(7).withIncludeContexts(true)
        .withSelection(SempodsContextSelection.of(a)).withTypes(book),
    )

    assertEquals(
      listOf(
        """{"text":"harbour"}""",
        """{"text":"harbour","type":["$article","$book"]}""",
        """{"text":"harbour","contexts":["$a","$b"]}""",
        """{"text":"harbour","include_contexts":true}""",
        """{"text":"harbour"}""",
        """{"text":"harbour","limit":1}""",
        """{"text":"harbour","type":["$book"],"contexts":["$a"],"include_contexts":true,"limit":7}""",
      ),
      sent.map { it.body },
    )
  }

  @Test
  fun `an omitted selection sends no contexts, and none sends an empty list the pod answers with an empty graph`() {
    answer(200, "[]")

    val readable = find().text(SempodsFindQuery.text("harbour").withSelection(SempodsContextSelection.readable()))
    val none = find().text(SempodsFindQuery.text("harbour").withSelection(SempodsContextSelection.none()))
    val emptyOf = find().text(SempodsFindQuery.text("harbour").withSelection(SempodsContextSelection.of(emptyList())))

    assertEquals(
      listOf("""{"text":"harbour"}""", """{"text":"harbour","contexts":[]}""", """{"text":"harbour","contexts":[]}"""),
      sent.map { it.body },
    )
    listOf(readable, none, emptyOf).forEach { assertEquals("[]", it.body) }
  }

  @Test
  fun `the find authenticates as the session does and adds no credential of its own`() {
    answer(200, "[]")

    find().text(SempodsFindQuery.text("harbour"))
    find(SempodsRequestAuth.bearer("token-b")).text(SempodsFindQuery.text("harbour"))

    val (anonymous, bearer) = server.retrieveRecordedRequests(request()).toList()
    assertEquals(setOf("accept", "content-type"), anonymous.headersBeyondTransport())
    assertEquals("Bearer token-b", bearer.getFirstHeader("Authorization"))
    assertEquals(setOf("accept", "content-type", "authorization"), bearer.headersBeyondTransport())
  }

  /** JSON-LD answers from sempods-spec `examples/find/responses.yaml`, which the client hands over as they are. */
  @ParameterizedTest
  @ValueSource(strings = ["bare", "split", "independent", "stored-vocabulary", "empty"])
  fun `the specification's find answers come back as the text the pod sent`(name: String) {
    val document = SPEC_ANSWERS.getValue(name)
    answer(200, document, "Content-Type" to "application/ld+json", "X-Request-Id" to "r-1")

    val text = find().text(SempodsFindQuery.text("harbour").withIncludeContexts(true))
    val bytes = find().bytes(SempodsFindQuery.text("harbour").withIncludeContexts(true))

    assertEquals(200, text.status)
    assertEquals("r-1", text.headers["X-Request-Id"])
    assertEquals(document, text.body)
    assertContentEquals(document.toByteArray(), bytes.body)
  }

  @ParameterizedTest
  @ValueSource(ints = [400, 401, 404, 406, 500])
  fun `any other status is a failure that keeps its headers and quotes neither the text nor the selection`(status: Int) {
    answer(status, "refused: see logs", "WWW-Authenticate" to "Bearer realm=\"pod\"")
    val secret = "https://pods.example/alice/_system/contexts/SECRET-7f3a"
    val query = SempodsFindQuery.text("SECRET words").withSelection(SempodsContextSelection.of(secret))

    listOf<() -> Any>({ find().text(query) }, { find().bytes(query, SempodsGraphFormat.N_QUADS) }).forEach { call ->
      val failure = assertThrows<SempodsStatusException> { call() }
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
    answer(status, "[]")

    assertEquals(status, assertThrows<SempodsStatusException> { find().text(SempodsFindQuery.text("harbour")) }.status)
  }

  @Test
  fun `a query refuses a blank text, a blank type and a limit below one before anything is sent`() {
    assertThrows<IllegalArgumentException> { SempodsFindQuery.text(" ") }
    assertThrows<IllegalArgumentException> { SempodsFindQuery.text("harbour").withTypes("https://schema.org/Article", "") }
    assertThrows<IllegalArgumentException> { SempodsFindQuery.text("harbour").withLimit(0) }
    assertEquals(null, SempodsFindQuery.text("harbour").withLimit(3).withLimit(null).limit)
    assertFalse("harbour" in SempodsFindQuery.text("harbour").toString())
  }

  @Test
  fun `over a client without the sempods interceptors nothing is sent`() {
    val plain = OkHttpClient()
    try {
      val find = SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), plain).find()
      assertThrows<IOException> { find.text(SempodsFindQuery.text("harbour")) }
    } finally {
      plain.shutDown()
    }
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  private companion object {

    const val SPS = "https://schema.sempods.org/"

    const val ARTICLE = "https://pod.example/articles/42"

    const val CTX = "https://pod.example/_system/contexts/"

    val SPEC_ANSWERS = mapOf(
      "bare" to """{"@id":"${CTX}A","@graph":[{"@id":"$ARTICLE","${SPS}findResult":[{"@id":"_:m"}]}]}""",
      "split" to """[{"@id":"${CTX}A","@graph":[{"@id":"$ARTICLE","${SPS}findResult":[{"@id":"_:m"}]}]},""" +
        """{"@id":"${CTX}B","@graph":[{"@id":"_:m","https://schema.org/text":[{"@value":"Harbour lantern"}]}]}]""",
      "independent" to """[{"@id":"$ARTICLE","${SPS}findResult":[{"@id":"_:localA"},{"@id":"_:localB"}]},""" +
        """{"@id":"_:localA","https://schema.org/position":[{"@value":2}]},""" +
        """{"@id":"_:localB","https://schema.org/position":[{"@value":7}]}]""",
      "stored-vocabulary" to """[{"@id":"${CTX}A","@graph":[{"@id":"$ARTICLE","${SPS}findResult":[{"@id":"https://pod.example/notes/saved"}]}]},""" +
        """{"@id":"${CTX}B","@graph":[{"@id":"$ARTICLE","https://schema.org/description":[{"@value":"Harbour lantern"}]}]}]""",
      "empty" to "[]",
    )
  }
}
