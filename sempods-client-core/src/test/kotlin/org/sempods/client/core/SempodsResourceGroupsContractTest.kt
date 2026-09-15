package org.sempods.client.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
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

/**
 * What the resource and subject operations put on the wire, and which answers they take.
 *
 * The two groups differ in the address alone, so every case runs over both.
 */
class SempodsResourceGroupsContractTest : MockPodTest() {

  /** A request as OkHttp wrote it: MockServer decodes the path and the query, and may re-read a body it records. */
  private class Sent(val method: String, val url: HttpUrl, val headers: Headers, val body: ByteArray?, val oneShot: Boolean)

  private val sent = CopyOnWriteArrayList<Sent>()

  private val client = sempodsClient {
    addNetworkInterceptor { chain ->
      val request = chain.request()
      val body = request.body
      val bytes = body?.let { Buffer().also(it::writeTo).readByteArray() }
      sent += Sent(request.method, request.url, request.headers, bytes, body?.isOneShot() == true)
      // A one-shot body is spent once read here, so the attempt goes on with what was read.
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

  /** One group's operations, and the path it sends for [event]. */
  private interface Group {
    val path: String

    fun getText(iri: String, format: SempodsGraphFormat, options: SempodsReadOptions): SempodsResponse<String>

    fun getBytes(iri: String, format: SempodsGraphFormat, options: SempodsReadOptions): SempodsResponse<ByteArray>

    fun put(iri: String, format: SempodsGraphFormat, content: SempodsContent, options: SempodsWriteOptions): SempodsResponse<ByteArray>

    fun patch(iri: String, content: SempodsContent, options: SempodsWriteOptions): SempodsResponse<ByteArray>

    fun delete(iri: String, options: SempodsWriteOptions): SempodsResponse<ByteArray>
  }

  private fun pod(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice"), auth), client)

  private fun group(name: String, auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()): Group {
    val pod = pod(auth)
    return when (name) {
      RESOURCES -> object : Group {
        val resources = pod.resources()
        override val path = "/alice/events/gr%C3%BC%C3%9Fe"
        override fun getText(iri: String, format: SempodsGraphFormat, options: SempodsReadOptions) = resources.getText(iri, format, options)
        override fun getBytes(iri: String, format: SempodsGraphFormat, options: SempodsReadOptions) = resources.getBytes(iri, format, options)
        override fun put(iri: String, format: SempodsGraphFormat, content: SempodsContent, options: SempodsWriteOptions) =
          resources.put(iri, format, content, options)
        override fun patch(iri: String, content: SempodsContent, options: SempodsWriteOptions) = resources.patch(iri, content, options)
        override fun delete(iri: String, options: SempodsWriteOptions) = resources.delete(iri, options)
      }
      else -> object : Group {
        val subjects = pod.subjects()
        override val path = "/alice/_system/resources/" + Base64.getUrlEncoder().withoutPadding().encodeToString(event.toByteArray())
        override fun getText(iri: String, format: SempodsGraphFormat, options: SempodsReadOptions) = subjects.getText(iri, format, options)
        override fun getBytes(iri: String, format: SempodsGraphFormat, options: SempodsReadOptions) = subjects.getBytes(iri, format, options)
        override fun put(iri: String, format: SempodsGraphFormat, content: SempodsContent, options: SempodsWriteOptions) =
          subjects.put(iri, format, content, options)
        override fun patch(iri: String, content: SempodsContent, options: SempodsWriteOptions) = subjects.patch(iri, content, options)
        override fun delete(iri: String, options: SempodsWriteOptions) = subjects.delete(iri, options)
      }
    }
  }

  /** Under the pod, so both groups can address it. */
  private val event get() = "$origin/alice/events/grüße"

  private val tasks = "https://pods.example/alice/_system/contexts/tasks"

  private val awkward = "https://pods.example/alice/_system/contexts/a&b=c+d#e f/ü"

  private val inTasks = SempodsWriteOptions.inContext(tasks)

  private val jsonLd = """{"@id":"https://pods.example/alice/events/1","https://schema.org/name":"One"}"""

  private fun answer(status: Int, body: String = "", vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status)
    if (body.isNotEmpty()) response.withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request()).respond(response)
  }

  private fun assertNotSentRaw(url: HttpUrl) {
    listOf("&b", "=c", "+d", "#e", " f").forEach { raw ->
      assertFalse(url.encodedQuery.orEmpty().contains(raw), "'$raw' went out unencoded: ${url.encodedQuery}")
    }
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `a read asks for its format at the group's address and selects nothing by default`(name: String) {
    answer(200, jsonLd)
    val group = group(name)

    group.getText(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults())
    group.getBytes(event, SempodsGraphFormat.N_QUADS, SempodsReadOptions.of(SempodsContextSelection.readable()))

    assertEquals(listOf("GET", "GET"), sent.map { it.method })
    assertEquals(listOf(group.path, group.path), sent.map { it.url.encodedPath })
    assertEquals(listOf(null, null), sent.map { it.url.encodedQuery })
    assertEquals(listOf("application/ld+json", "application/n-quads"), sent.map { it.headers["Accept"] })
    server.retrieveRecordedRequests(request()).forEach { assertEquals(setOf("accept"), it.headersBeyondTransport()) }
  }

  @Test
  fun `leaving the format and the options out reads JSON-LD of what the session may read, and writes without context or condition`() {
    answer(200, jsonLd)
    val pod = pod()

    pod.resources().getText(event)
    pod.subjects().getBytes(event)
    pod.resources().put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of("{}"))
    pod.subjects().patch(event, SempodsContent.of("{}"))
    pod.resources().delete(event)

    assertEquals(listOf("application/ld+json", "application/ld+json", null, null, null), sent.map { it.headers["Accept"] })
    sent.forEach {
      assertNull(it.url.encodedQuery)
      assertNull(it.headers["If-Match"])
      assertNull(it.headers["If-None-Match"])
    }
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `a selection sends each context once as a context parameter, encoded as a query component`(name: String) {
    answer(200, jsonLd)

    group(name).getText(
      event,
      SempodsGraphFormat.JSON_LD,
      SempodsReadOptions.of(SempodsContextSelection.of(awkward, tasks, awkward)).withIncludeContexts(true),
    )

    val url = sent.single().url
    assertEquals(listOf("context", "context", "include_contexts"), url.encodedQuery.orEmpty().split('&').map { it.substringBefore('=') })
    assertEquals(listOf(awkward, tasks), url.queryParameterValues("context"))
    assertEquals(listOf("true"), url.queryParameterValues("include_contexts"))
    assertNotSentRaw(url)
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `none sends nothing and answers 404 without headers or body, whatever else the read asks`(name: String) {
    answer(200, jsonLd)
    val group = group(name)
    val nothing = listOf(
      SempodsReadOptions.of(SempodsContextSelection.none()),
      SempodsReadOptions.of(SempodsContextSelection.of(emptyList())),
      SempodsReadOptions.of(SempodsContextSelection.none()).withIncludeContexts(true).withIfNoneMatch("\"v1\""),
    )

    nothing.forEach { options ->
      listOf(group.getText(event, SempodsGraphFormat.JSON_LD, options), group.getBytes(event, SempodsGraphFormat.JSON_LD, options)).forEach {
        assertEquals(404, it.status)
        assertEquals(0, it.headers.size)
        assertNull(it.body)
      }
    }

    assertTrue(sent.isEmpty())
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  @Test
  fun `none answers over a client without the sempods interceptors, over which nothing else goes out`() {
    val plain = OkHttpClient()
    try {
      val pod = SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), plain)
      assertEquals(404, pod.resources().getText(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.of(SempodsContextSelection.none())).status)
      assertThrows<IOException> { pod.subjects().getText(event) }
      assertThrows<IOException> { pod.resources().delete(event, inTasks) }
    } finally {
      plain.shutDown()
    }
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `N-Quads grouped by context is refused before anything is sent, none included`(name: String) {
    val group = group(name)

    listOf(SempodsContextSelection.readable(), SempodsContextSelection.none()).forEach { selection ->
      val refused = assertThrows<IllegalArgumentException> {
        group.getText(event, SempodsGraphFormat.N_QUADS, SempodsReadOptions.of(selection).withIncludeContexts(true))
      }
      assertEquals(
        "include_contexts groups JSON-LD by context; N-Quads already carries each statement's context (SPS-CRUD-028).",
        refused.message,
      )
    }
    assertTrue(sent.isEmpty())
  }

  @Test
  fun `an address the group refuses is reported ahead of the options, and nothing is sent`() {
    val grouped = SempodsReadOptions.of(SempodsContextSelection.none()).withIncludeContexts(true)

    val outside = assertThrows<IllegalArgumentException> { pod().resources().getText("did:web:bob.example", SempodsGraphFormat.N_QUADS, grouped) }
    val blank = assertThrows<IllegalArgumentException> { pod().subjects().getText(" ", SempodsGraphFormat.N_QUADS, grouped) }
    assertThrows<IllegalArgumentException> { pod().resources().delete("$origin/alice/_system/contexts/tasks", inTasks) }
    assertThrows<IllegalArgumentException> { pod().subjects().put("", SempodsGraphFormat.JSON_LD, SempodsContent.of("{}"), inTasks) }

    assertEquals("'did:web:bob.example' is not under the pod '$origin/alice'; it is reached through subjects().", outside.message)
    assertEquals("A subject IRI must not be blank.", blank.message)
    assertTrue(sent.isEmpty())
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `a read takes 200 with the headers as sent, and 404 without a body`(name: String) {
    answer(200, jsonLd, "Content-Type" to "application/ld+json", "ETag" to "\"abc-jsonld\"", "Vary" to "Accept")
    val group = group(name)

    val text = group.getText(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults())
    assertEquals(200, text.status)
    assertEquals(jsonLd, text.body)
    assertEquals("\"abc-jsonld\"", text.headers["ETag"])
    assertEquals("Accept", text.headers["Vary"])
    assertEquals("application/ld+json", text.headers["Content-Type"])
    assertContentEquals(jsonLd.toByteArray(), group.getBytes(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults()).body)

    server.reset()
    answer(404, "not found")
    val absent = group.getText(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults())
    assertEquals(404, absent.status)
    assertNull(absent.body)
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `304 answers only a read that sent If-None-Match, which goes out as given`(name: String) {
    answer(304, "", "ETag" to "W/\"abc\"", "Vary" to "Accept")
    val group = group(name)

    val unchanged = group.getText(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults().withIfNoneMatch("W/\"abc\", \"def\""))

    assertEquals(304, unchanged.status)
    assertNull(unchanged.body)
    assertEquals("W/\"abc\"", unchanged.headers["ETag"])
    assertEquals("Accept", unchanged.headers["Vary"])
    assertEquals("W/\"abc\", \"def\"", sent.single().headers["If-None-Match"])
    assertEquals(304, assertThrows<SempodsStatusException> { group.getBytes(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults()) }.status)
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `put sends the content as given, typed by the format alone`(name: String) {
    answer(204)
    val group = group(name)
    val text = """{"@id":"urn:x","https://schema.org/name":"Grüße ✓"}"""
    val quads = "<urn:x> <https://schema.org/name> \"One\" .\n".toByteArray()
    val given = quads.copyOf()
    val copied = SempodsContent.of(given)
    given.fill(0)

    group.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of(text), SempodsWriteOptions.defaults())
    group.put(event, SempodsGraphFormat.N_QUADS, copied, SempodsWriteOptions.defaults())
    group.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of(ByteArrayInputStream(text.toByteArray())), SempodsWriteOptions.defaults())

    assertEquals(listOf("PUT", "PUT", "PUT"), sent.map { it.method })
    assertEquals(listOf(group.path, group.path, group.path), sent.map { it.url.encodedPath })
    assertEquals(listOf("application/ld+json", "application/n-quads", "application/ld+json"), sent.map { it.headers["Content-Type"] })
    assertContentEquals(text.toByteArray(Charsets.UTF_8), sent[0].body)
    assertContentEquals(quads, sent[1].body)
    assertContentEquals(text.toByteArray(Charsets.UTF_8), sent[2].body)
    assertEquals("${text.toByteArray().size}", sent[0].headers["Content-Length"])
    assertEquals("chunked", sent[2].headers["Transfer-Encoding"])
    assertEquals(listOf(false, false, true), sent.map { it.oneShot })
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `patch sends a JSON merge patch, and delete sends no content`(name: String) {
    answer(204)
    val group = group(name)
    val patch = """{"https://schema.org/name":null}"""

    group.patch(event, SempodsContent.of(patch), SempodsWriteOptions.defaults())
    group.delete(event, SempodsWriteOptions.defaults())

    val (patched, deleted) = sent.toList()
    assertEquals("PATCH", patched.method)
    assertEquals("application/merge-patch+json", patched.headers["Content-Type"])
    assertContentEquals(patch.toByteArray(), patched.body)
    assertEquals("DELETE", deleted.method)
    assertNull(deleted.headers["Content-Type"])
    assertEquals("0", deleted.headers["Content-Length"])
    assertEquals(listOf(group.path, group.path), sent.map { it.url.encodedPath })
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `a write sends one context parameter with a context and none without, and its conditions as given`(name: String) {
    answer(204)
    val group = group(name)
    val inAwkward = SempodsWriteOptions.inContext(awkward)

    group.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of("{}"), inAwkward.withIfNoneMatch("*"))
    group.patch(event, SempodsContent.of("{}"), inAwkward.withIfMatch("\"abc-jsonld\""))
    group.delete(event, SempodsWriteOptions.defaults().withIfMatch("W/\"x\"").withIfNoneMatch("\"y\""))

    val (put, patch, delete) = sent.toList()
    listOf(put, patch).forEach {
      assertEquals(listOf(awkward), it.url.queryParameterValues("context"))
      assertEquals(setOf("context"), it.url.queryParameterNames)
      assertNotSentRaw(it.url)
    }
    assertEquals("*", put.headers["If-None-Match"])
    assertNull(put.headers["If-Match"])
    assertEquals("\"abc-jsonld\"", patch.headers["If-Match"])
    assertNull(patch.headers["If-None-Match"])
    assertNull(delete.url.encodedQuery)
    assertEquals("W/\"x\"", delete.headers["If-Match"])
    assertEquals("\"y\"", delete.headers["If-None-Match"])
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `writes take their listed successes with Location and ETag as sent`(name: String) {
    val group = group(name)
    val location = "/alice/_system/resources/ZGlkOndlYjpib2IuZXhhbXBsZQ"
    val writes = listOf<Pair<List<Int>, () -> SempodsResponse<ByteArray>>>(
      listOf(200, 201, 204) to { group.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of("{}"), inTasks) },
      listOf(200, 204) to { group.patch(event, SempodsContent.of("{}"), inTasks) },
      listOf(200, 204) to { group.delete(event, inTasks) },
    )

    writes.forEach { (statuses, write) ->
      statuses.forEach { status ->
        server.reset()
        answer(status, if (status == 204) "" else "done", "Location" to location, "ETag" to "\"v2\"")

        val written = write()

        assertEquals(status, written.status)
        assertEquals(location, written.headers["Location"])
        assertEquals("\"v2\"", written.headers["ETag"])
        assertContentEquals(if (status == 204) ByteArray(0) else "done".toByteArray(), written.body)
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `404 answers a patch or a delete, and is a failure to a put`(name: String) {
    answer(404, "no such context")
    val group = group(name)

    listOf(group.patch(event, SempodsContent.of("{}"), inTasks), group.delete(event, inTasks)).forEach {
      assertEquals(404, it.status)
      assertNull(it.body)
    }
    assertEquals(404, assertThrows<SempodsStatusException> { group.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of("{}"), inTasks) }.status)
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `412 answers only a conditional write`(name: String) {
    answer(412, "precondition failed", "ETag" to "\"v3\"")
    val group = group(name)
    val stale = inTasks.withIfMatch("\"v1\"")

    listOf(
      group.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of("{}"), inTasks.withIfNoneMatch("*")),
      group.patch(event, SempodsContent.of("{}"), stale),
      group.delete(event, stale),
    ).forEach {
      assertEquals(412, it.status)
      assertNull(it.body)
      assertEquals("\"v3\"", it.headers["ETag"])
    }
    listOf<() -> Any>(
      { group.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of("{}"), inTasks) },
      { group.patch(event, SempodsContent.of("{}"), inTasks) },
      { group.delete(event, inTasks) },
    ).forEach { assertEquals(412, assertThrows<SempodsStatusException> { it() }.status) }
  }

  @ParameterizedTest
  @ValueSource(ints = [400, 401, 403, 406, 415, 429, 500])
  fun `any other status is a failure that keeps its headers and quotes neither the selection nor the target context`(status: Int) {
    answer(status, "refused: see logs", "Retry-After" to "7", "WWW-Authenticate" to "Bearer realm=\"pod\"")
    val secret = "https://pods.example/alice/_system/contexts/SECRET-7f3a"

    listOf(RESOURCES, SUBJECTS).forEach { name ->
      val group = group(name)
      listOf<Pair<String, () -> Any>>(
        "GET" to { group.getText(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.of(SempodsContextSelection.of(secret))) },
        "GET" to { group.getBytes(event, SempodsGraphFormat.N_QUADS, SempodsReadOptions.of(SempodsContextSelection.of(secret))) },
        "PUT" to { group.put(event, SempodsGraphFormat.JSON_LD, SempodsContent.of("{}"), SempodsWriteOptions.inContext(secret)) },
        "PATCH" to { group.patch(event, SempodsContent.of("{}"), SempodsWriteOptions.inContext(secret).withIfMatch("\"v1\"")) },
        "DELETE" to { group.delete(event, SempodsWriteOptions.inContext(secret)) },
      ).forEach { (method, call) ->
        val failure = assertThrows<SempodsStatusException>("$name $method") { call() }
        assertEquals(status, failure.status)
        assertEquals("7", failure.headers["Retry-After"])
        assertEquals("Bearer realm=\"pod\"", failure.headers["WWW-Authenticate"])
        assertEquals("$method $origin${group.path} answered $status, which this operation does not accept.", failure.message)
        assertFalse("SECRET" in failure.toString(), failure.toString())
      }
    }
  }

  @Test
  fun `the operations authenticate as the session does and add no credential of their own`() {
    answer(200, jsonLd)

    group(RESOURCES).delete(event, SempodsWriteOptions.defaults())
    group(SUBJECTS, SempodsRequestAuth.apiKeyHeader("X-Api-Key", "key-a")).getText(event, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults())
    group(RESOURCES, SempodsRequestAuth.bearer("token-b")).patch(event, SempodsContent.of("{}"), SempodsWriteOptions.defaults())

    val (anonymous, apiKey, bearer) = server.retrieveRecordedRequests(request()).toList()
    assertEquals(emptySet(), anonymous.headersBeyondTransport())
    assertEquals("key-a", apiKey.getFirstHeader("X-Api-Key"))
    assertEquals(setOf("accept", "x-api-key"), apiKey.headersBeyondTransport())
    assertEquals("Bearer token-b", bearer.getFirstHeader("Authorization"))
    assertEquals(setOf("content-type", "authorization"), bearer.headersBeyondTransport())
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `stream content serves one request`(name: String) {
    answer(204)
    val group = group(name)
    val stream = SempodsContent.of(ByteArrayInputStream("{}".toByteArray()))

    group.put(event, SempodsGraphFormat.JSON_LD, stream, inTasks)
    val again = assertThrows<IllegalStateException> { group.patch(event, stream, inTasks) }

    assertEquals("This stream content was already sent; a stream can be sent once.", again.message)
    assertEquals(1, sent.size)
  }

  private companion object {
    const val RESOURCES = "resources"
    const val SUBJECTS = "subjects"
  }
}
