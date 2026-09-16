package org.sempods.client.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Headers
import okhttp3.HttpUrl
import okio.Buffer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** What the context operations put on the wire, and which answers they take. */
class SempodsPodContextsContractTest : MockPodTest() {

  /** A request as OkHttp wrote it: MockServer decodes the path and the query, and may re-read a body it records. */
  private class Sent(val method: String, val url: HttpUrl, val headers: Headers, val body: ByteArray?)

  private val sent = CopyOnWriteArrayList<Sent>()

  private val client = sempodsClient {
    addNetworkInterceptor { chain ->
      val request = chain.request()
      val bytes = request.body?.let { Buffer().also(it::writeTo).readByteArray() }
      sent += Sent(request.method, request.url, request.headers, bytes)
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

  private fun contexts(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice"), auth), client).contexts()

  /** A context of this pod, as the pod gives it. */
  private fun context(path: String) = "$origin/alice/_system/contexts/$path"

  // The server is started for the class, so everything naming it is read per test, not at construction.
  private val tasks get() = context("apps/example/tasks")

  private val catalogue
    get() = """{"@id":"$origin/alice/_system/contexts","@type":["http://www.w3.org/ns/sparql-service-description#GraphCollection"]}"""

  private val description
    get() = """{"@id":"$tasks","@type":["http://www.w3.org/ns/sparql-service-description#NamedGraph"]}"""

  private fun answer(status: Int, body: String = "", vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status)
    if (body.isNotEmpty()) response.withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request()).respond(response)
  }

  @Test
  fun `the catalogue is read at the registry route, asking for canonical JSON-LD`() {
    answer(200, catalogue, "ETag" to "\"c1\"")

    val text = contexts().listText()
    val bytes = contexts().listBytes()

    assertEquals(listOf("GET", "GET"), sent.map { it.method })
    assertEquals(listOf("/alice/_system/contexts", "/alice/_system/contexts"), sent.map { it.url.encodedPath })
    assertEquals(listOf(null, null), sent.map { it.url.encodedQuery })
    assertEquals(listOf("application/ld+json", "application/ld+json"), sent.map { it.headers["Accept"] })
    assertEquals(catalogue, text.body)
    assertEquals("\"c1\"", text.headers["ETag"])
    assertContentEquals(catalogue.toByteArray(), bytes.body)
    server.retrieveRecordedRequests(request()).forEach { assertEquals(setOf("accept"), it.headersBeyondTransport()) }
  }

  @Test
  fun `a description is read at the context's own IRI, in the format it is asked for`() {
    answer(200, description)

    contexts().getText(tasks)
    contexts().getBytes(tasks, SempodsGraphFormat.N_QUADS)

    assertEquals(listOf("GET", "GET"), sent.map { it.method })
    assertEquals(listOf("/alice/_system/contexts/apps/example/tasks"), sent.map { it.url.encodedPath }.distinct())
    assertEquals(listOf("application/ld+json", "application/n-quads"), sent.map { it.headers["Accept"] })
  }

  @Test
  fun `a registry read carries an entity tag and nothing else, and takes 304 only when it sent one`() {
    answer(304, "", "ETag" to "\"c1\"")

    val unchanged = contexts().listText(SempodsGraphFormat.N_QUADS, "\"c1\"")
    assertEquals(304, unchanged.status)
    assertNull(unchanged.body)
    assertEquals("\"c1\"", sent.single().headers["If-None-Match"])
    assertEquals(setOf("accept", "if-none-match"), server.retrieveRecordedRequests(request()).single().headersBeyondTransport())

    assertEquals(304, assertThrows<SempodsStatusException> { contexts().getText(tasks) }.status)
    assertThrows<IllegalArgumentException> { contexts().listText(SempodsGraphFormat.JSON_LD, " ") }
  }

  @Test
  fun `a context this session cannot see answers as one that was never registered`() {
    answer(404, "no such context")

    listOf(contexts().getText(tasks), contexts().listText()).forEach { absent ->
      assertEquals(404, absent.status)
      assertNull(absent.body)
      assertNull(absent.headers["ETag"])
    }
  }

  @Test
  fun `a creation puts its fields at the context IRI and asks for the description back`() {
    val location = "$origin/alice/_system/contexts/apps/example/tasks"
    answer(201, description, "Location" to location, "ETag" to "\"c2\"")

    val created = contexts().create(tasks, SempodsContextCreate.fields().withLabel("Tasks").withPublic(false))

    val put = sent.single()
    assertEquals("PUT", put.method)
    assertEquals("/alice/_system/contexts/apps/example/tasks", put.url.encodedPath)
    assertEquals(null, put.url.encodedQuery)
    assertEquals("application/json", put.headers["Content-Type"]?.substringBefore(';'))
    assertEquals("application/ld+json", put.headers["Accept"])
    assertEquals("""{"label":"Tasks","public":false}""", String(put.body!!))
    assertEquals(201, created.status)
    assertEquals(location, created.headers["Location"])
    assertEquals("\"c2\"", created.headers["ETag"])
    assertContentEquals(description.toByteArray(), created.body)

    server.reset()
    answer(200, description)
    assertEquals(200, contexts().create(tasks, SempodsContextCreate.fields(), SempodsGraphFormat.N_QUADS).status)
    assertEquals("application/n-quads", sent.last().headers["Accept"])
    assertEquals("{}", String(sent.last().body!!))
  }

  @Test
  fun `a field is sent only when it is set, escaped, and encoded JSON goes out byte for byte`() {
    answer(201, description)

    contexts().create(tasks, SempodsContextCreate.fields().withDescription("Tâches \"öffentlich\" \\ ✓"))
    contexts().create(tasks, SempodsContextCreate.fields().withLabel("").withPublic(true))
    contexts().create(tasks, SempodsContextCreate.json("""{"public" : true, "extra": [1]}"""))

    assertEquals(
      listOf(
        """{"description":"Tâches \"öffentlich\" \\ ✓"}""",
        """{"label":"","public":true}""",
        """{"public" : true, "extra": [1]}""",
      ),
      sent.map { String(it.body!!) },
    )
  }

  @Test
  fun `a removal sends a DELETE without a body and asks for no representation`() {
    answer(204)

    val removed = contexts().delete(tasks)

    val sent = sent.single()
    assertEquals("DELETE", sent.method)
    assertEquals("/alice/_system/contexts/apps/example/tasks", sent.url.encodedPath)
    assertEquals(null, sent.url.encodedQuery)
    assertNull(sent.headers["Accept"])
    assertEquals("0", sent.headers["Content-Length"])
    assertEquals(204, removed.status)

    // The last context a caller can see stays (SPS-CTX-029), and one that is gone answers 404.
    listOf(409, 404).forEach { status ->
      server.reset()
      answer(status, "no")
      assertEquals(status, contexts().delete(tasks).status)
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "https://pods.example/alice/_system/contexts/tasks",
      "/alice/_system/contexts",
      "/alice/_system/contexts/",
      "/alice/_system/contextsx/tasks",
      "/alice/events/1",
      "/alice/_system/contexts/tasks?v=1",
      "/alice/_system/contexts/tasks#top",
      "/alice/_system/contexts/ta%2Fsks",
      "/alice/_system/contexts/tasks;v=1",
      "/alice/_system/contexts/a b",
      "/alice/_system/contexts/tasks/",
      "/alice/_system/contexts/a//b",
      "/alice/_system/contexts/a/../b",
      "/alice/_system/contexts/./a",
    ],
  )
  fun `an IRI that is no context of this pod is refused before a request is built`(iri: String) {
    val named = if (iri.startsWith("/")) "$origin$iri" else iri
    answer(200, description)

    assertThrows<IllegalArgumentException> { contexts().create(named) }
    assertThrows<IllegalArgumentException> { contexts().getText(named) }
    assertThrows<IllegalArgumentException> { contexts().getBytes(named, SempodsGraphFormat.N_QUADS) }
    assertThrows<IllegalArgumentException> { contexts().delete(named) }
    assertTrue(sent.isEmpty(), "nothing is sent for '$named'")
  }

  @Test
  fun `a context of this pod is taken as the pod gave it`() {
    answer(200, description)

    listOf("tasks", "a!\$&'()*+,=:@-._~b", "apps/example/tasks", "2026-sommer", "grüße").forEach { path ->
      contexts().getText(context(path))
    }

    assertEquals(
      listOf("tasks", "a!\$&'()*+,=:@-._~b", "apps/example/tasks", "2026-sommer", "gr%C3%BC%C3%9Fe")
        .map { "/alice/_system/contexts/$it" },
      sent.map { it.url.encodedPath },
    )
  }

  @Test
  fun `every context request carries the session's credential, composed as the session composes it`() {
    answer(200, catalogue)
    val gateway = SempodsRequestAuth.bearer("t-1").andThen(SempodsRequestAuth.apiKeyHeader("X-Gateway", "g-1"))

    contexts(gateway).listText()
    contexts(gateway).getText(tasks)
    contexts(gateway).create(tasks)
    contexts(SempodsRequestAuth.apiKeyHeader("X-Api-Key", "k-1")).listText()

    assertEquals(listOf("Bearer t-1", "Bearer t-1", "Bearer t-1", null), sent.map { it.headers["Authorization"] })
    assertEquals(listOf("g-1", "g-1", "g-1", null), sent.map { it.headers["X-Gateway"] })
    assertEquals(listOf(null, null, null, "k-1"), sent.map { it.headers["X-Api-Key"] })
  }

  @Test
  fun `a status the operation does not list keeps its headers and never quotes the body`() {
    answer(204, "", "ETag" to "\"c3\"")

    val refused = assertThrows<SempodsStatusException> { contexts().create(tasks) }
    assertEquals(204, refused.status)
    assertEquals("\"c3\"", refused.headers["ETag"])

    server.reset()
    answer(406, "no representation this caller accepts")
    assertEquals(406, assertThrows<SempodsStatusException> { contexts().listText(SempodsGraphFormat.N_QUADS) }.status)
  }
}
