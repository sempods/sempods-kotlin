package org.sempods.client.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** What the slot operations put on the wire, and which answers they take. */
class SempodsPodSlotsContractTest : MockPodTest() {

  /** A request as OkHttp wrote it: MockServer decodes the path and the query, and may re-read a body it records. */
  private class Sent(val method: String, val url: HttpUrl, val headers: Headers, val body: ByteArray?)

  private val sent = CopyOnWriteArrayList<Sent>()

  private val client = sempodsClient {
    addNetworkInterceptor { chain ->
      val request = chain.request()
      val body = request.body
      val bytes = body?.let { Buffer().also(it::writeTo).readByteArray() }
      sent += Sent(request.method, request.url, request.headers, bytes)
      chain.proceed(if (body == null) request else request.newBuilder().method(request.method, bytes!!.toRequestBody(body.contentType())).build())
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

  private fun slots(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice"), auth), client).slots()

  private val bob = "did:web:bob.example"

  private val knows = "http://xmlns.com/foaf/0.1/knows"

  private val target = "urn:x:ab~"

  private val slotPath = "/alice/_system/resources/ZGlkOndlYjpib2IuZXhhbXBsZQ/aHR0cDovL3htbG5zLmNvbS9mb2FmLzAuMS9rbm93cw"

  private val edgePath = "$slotPath/dXJuOng6YWJ-"

  private val contacts = "https://pods.example/alice/_system/contexts/contacts"

  private val awkward = "https://pods.example/alice/_system/contexts/a&b=c+d#e f/ü"

  private val inContacts = SempodsWriteOptions.inContext(contacts)

  private val values = """[{"@id":"https://pods.example/alice/contacts/carol"}]"""

  private val carol = """{"@id":"https://pods.example/alice/contacts/carol"}"""

  private fun answer(status: Int, body: String = "", vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status)
    if (body.isNotEmpty()) response.withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request()).respond(response)
  }

  @Test
  fun `a read asks for JSON-LD at the slot route and selects nothing by default`() {
    answer(200, values, "ETag" to "\"s1\"")

    val text = slots().getJson(bob, knows)
    val bytes = slots().getBytes(bob, knows, SempodsReadOptions.defaults())

    assertEquals(listOf("GET", "GET"), sent.map { it.method })
    assertEquals(listOf(slotPath, slotPath), sent.map { it.url.encodedPath })
    assertEquals(listOf(null, null), sent.map { it.url.encodedQuery })
    assertEquals(listOf("application/ld+json", "application/ld+json"), sent.map { it.headers["Accept"] })
    assertEquals(values, text.body)
    assertEquals("\"s1\"", text.headers["ETag"])
    assertContentEquals(values.toByteArray(), bytes.body)
    server.retrieveRecordedRequests(request()).forEach { assertEquals(setOf("accept"), it.headersBeyondTransport()) }
  }

  @Test
  fun `a read sends its selection as context parameters with include_contexts, and none sends nothing`() {
    answer(200, values)

    slots().getJson(bob, knows, SempodsReadOptions.of(SempodsContextSelection.of(awkward, contacts, awkward)).withIncludeContexts(true))

    val url = sent.single().url
    assertEquals(listOf("context", "context", "include_contexts"), url.encodedQuery.orEmpty().split('&').map { it.substringBefore('=') })
    assertEquals(listOf(awkward, contacts), url.queryParameterValues("context"))
    listOf("&b", "=c", "+d", "#e", " f").forEach { raw -> assertFalse(url.encodedQuery.orEmpty().contains(raw), url.encodedQuery) }

    sent.clear()
    listOf(SempodsContextSelection.none(), SempodsContextSelection.of(emptyList())).forEach { selection ->
      val absent = slots().getBytes(bob, knows, SempodsReadOptions.of(selection).withIfNoneMatch("\"s1\""))
      assertEquals(404, absent.status)
      assertEquals(0, absent.headers.size)
      assertNull(absent.body)
    }
    assertTrue(sent.isEmpty())
  }

  @Test
  fun `a read takes 404 without a body, and 304 only when it sent If-None-Match`() {
    answer(404, "nothing visible")

    val absent = slots().getJson(bob, knows)
    assertEquals(404, absent.status)
    assertNull(absent.body)

    server.reset()
    answer(304, "", "ETag" to "\"s1\"")
    val unchanged = slots().getJson(bob, knows, SempodsReadOptions.defaults().withIfNoneMatch("\"s1\""))
    assertEquals(304, unchanged.status)
    assertNull(unchanged.body)
    assertEquals("\"s1\"", sent.last().headers["If-None-Match"])
    assertEquals(304, assertThrows<SempodsStatusException> { slots().getJson(bob, knows) }.status)
  }

  @Test
  fun `put and add send JSON-LD as given into their one context, with their conditions as given`() {
    answer(204)

    slots().put(bob, knows, SempodsContent.of(values), inContacts.withIfMatch("\"s1\""))
    slots().add(bob, knows, SempodsContent.of(carol), SempodsWriteOptions.inContext(awkward).withIfNoneMatch("*"))

    val (put, add) = sent.toList()
    assertEquals("PUT", put.method)
    assertEquals("POST", add.method)
    listOf(put, add).forEach {
      assertEquals(slotPath, it.url.encodedPath)
      assertEquals("application/ld+json", it.headers["Content-Type"])
    }
    assertContentEquals(values.toByteArray(), put.body)
    assertContentEquals(carol.toByteArray(), add.body)
    assertEquals(listOf(contacts), put.url.queryParameterValues("context"))
    assertEquals(listOf(awkward), add.url.queryParameterValues("context"))
    assertFalse(add.url.encodedQuery.orEmpty().contains("&b"), add.url.encodedQuery)
    assertEquals("\"s1\"", put.headers["If-Match"])
    assertNull(put.headers["If-None-Match"])
    assertEquals("*", add.headers["If-None-Match"])
    assertNull(add.headers["If-Match"])
  }

  @Test
  fun `clear and removeEdge send a DELETE without content to the slot and to the edge`() {
    answer(200, """{"outcome":"cleared"}""")

    slots().clear(bob, knows, inContacts.withIfMatch("\"s1\""))
    slots().removeEdge(bob, knows, target, inContacts)

    val (clear, remove) = sent.toList()
    assertEquals(listOf("DELETE", "DELETE"), sent.map { it.method })
    assertEquals(slotPath, clear.url.encodedPath)
    assertEquals(edgePath, remove.url.encodedPath)
    listOf(clear, remove).forEach {
      assertNull(it.headers["Content-Type"])
      assertEquals("0", it.headers["Content-Length"])
      assertEquals(listOf(contacts), it.url.queryParameterValues("context"))
    }
    assertEquals("\"s1\"", clear.headers["If-Match"])
    assertNull(remove.headers["If-Match"])
  }

  @Test
  fun `each write takes today's answers and 204, with the body and headers as the pod sent them`() {
    val location = "$origin$edgePath"
    val writes = listOf<Triple<String, List<Pair<Int, String>>, () -> SempodsResponse<ByteArray>>>(
      Triple("put", listOf(200 to "", 204 to "")) { slots().put(bob, knows, SempodsContent.of(values), inContacts) },
      Triple("add", listOf(201 to """{"outcome":"created"}""", 200 to """{"outcome":"already_present"}""", 204 to "")) {
        slots().add(bob, knows, SempodsContent.of(carol), inContacts)
      },
      Triple("clear", listOf(200 to """{"outcome":"cleared"}""", 200 to """{"outcome":"already_empty"}""", 204 to "")) {
        slots().clear(bob, knows, inContacts)
      },
      Triple("removeEdge", listOf(200 to """{"outcome":"removed"}""", 200 to """{"outcome":"already_absent"}""", 204 to "")) {
        slots().removeEdge(bob, knows, target, inContacts)
      },
    )

    writes.forEach { (name, answers, write) ->
      answers.forEach { (status, body) ->
        server.reset()
        answer(status, body, "ETag" to "\"s2\"", "Location" to location)

        val written = write()

        assertEquals(status, written.status, name)
        assertContentEquals(body.toByteArray(), written.body, name)
        assertEquals("\"s2\"", written.headers["ETag"], name)
        assertEquals(location, written.headers["Location"], name)
      }
    }
  }

  @Test
  fun `412 answers only a conditional put, add or clear`() {
    answer(412, "precondition failed", "ETag" to "\"s3\"")
    val stale = inContacts.withIfMatch("\"s1\"")

    listOf(
      slots().put(bob, knows, SempodsContent.of(values), stale),
      slots().add(bob, knows, SempodsContent.of(carol), stale),
      slots().clear(bob, knows, inContacts.withIfNoneMatch("*")),
    ).forEach {
      assertEquals(412, it.status)
      assertNull(it.body)
      assertEquals("\"s3\"", it.headers["ETag"])
    }
    listOf<() -> Any>(
      { slots().put(bob, knows, SempodsContent.of(values), inContacts) },
      { slots().add(bob, knows, SempodsContent.of(carol), inContacts) },
      { slots().clear(bob, knows, inContacts) },
      { slots().removeEdge(bob, knows, target, inContacts) },
    ).forEach { assertEquals(412, assertThrows<SempodsStatusException> { it() }.status) }
  }

  @Test
  fun `an edge removal with a condition is refused, and nothing is sent`() {
    listOf(inContacts.withIfMatch("\"s1\""), inContacts.withIfNoneMatch("*")).forEach { conditional ->
      val refused = assertThrows<IllegalArgumentException> { slots().removeEdge(bob, knows, target, conditional) }
      assertEquals(
        "An edge removal takes no condition: the pod ignores If-Match there (SPS-CRUD-054); leave If-Match and If-None-Match unset.",
        refused.message,
      )
    }
    assertTrue(sent.isEmpty())
  }

  @Test
  fun `a blank subject, predicate or target is refused, and nothing is sent`() {
    assertEquals("A subject IRI must not be blank.", assertThrows<IllegalArgumentException> { slots().getJson(" ", knows) }.message)
    assertEquals(
      "A predicate IRI must not be blank.",
      assertThrows<IllegalArgumentException> { slots().add(bob, "", SempodsContent.of(carol), inContacts) }.message,
    )
    assertEquals(
      "A target IRI must not be blank.",
      assertThrows<IllegalArgumentException> { slots().removeEdge(bob, knows, "\t", inContacts.withIfMatch("\"s1\"")) }.message,
    )
    assertTrue(sent.isEmpty())
  }

  @ParameterizedTest
  @ValueSource(ints = [400, 401, 403, 404, 406, 415, 500])
  fun `any other status is a failure that keeps its headers and quotes neither the selection nor the target context`(status: Int) {
    answer(status, "refused: see logs", "Retry-After" to "7")
    val secret = "https://pods.example/alice/_system/contexts/SECRET-7f3a"
    val inSecret = SempodsWriteOptions.inContext(secret)

    listOf<Triple<String, String, () -> Any>>(
      Triple("GET", slotPath) { slots().getJson(bob, knows, SempodsReadOptions.of(SempodsContextSelection.of(secret))) },
      Triple("PUT", slotPath) { slots().put(bob, knows, SempodsContent.of(values), inSecret) },
      Triple("POST", slotPath) { slots().add(bob, knows, SempodsContent.of(carol), inSecret) },
      Triple("DELETE", slotPath) { slots().clear(bob, knows, inSecret) },
      Triple("DELETE", edgePath) { slots().removeEdge(bob, knows, target, inSecret) },
    ).filterNot { (method, _, _) -> method == "GET" && status == 404 }.forEach { (method, path, call) ->
      val failure = assertThrows<SempodsStatusException>("$method $path") { call() }
      assertEquals(status, failure.status)
      assertEquals("7", failure.headers["Retry-After"])
      assertEquals("$method $origin$path answered $status, which this operation does not accept.", failure.message)
      assertFalse("SECRET" in failure.toString(), failure.toString())
    }
  }

  @Test
  fun `the operations authenticate as the session does and add no credential of their own`() {
    answer(200, values)

    slots().clear(bob, knows, inContacts)
    slots(SempodsRequestAuth.apiKeyHeader("X-Api-Key", "key-a")).getJson(bob, knows)
    slots(SempodsRequestAuth.bearer("token-b")).add(bob, knows, SempodsContent.of(carol), inContacts)

    val (anonymous, apiKey, bearer) = server.retrieveRecordedRequests(request()).toList()
    assertEquals(emptySet(), anonymous.headersBeyondTransport())
    assertEquals("key-a", apiKey.getFirstHeader("X-Api-Key"))
    assertEquals(setOf("accept", "x-api-key"), apiKey.headersBeyondTransport())
    assertEquals("Bearer token-b", bearer.getFirstHeader("Authorization"))
    assertEquals(setOf("content-type", "authorization"), bearer.headersBeyondTransport())
  }
}
