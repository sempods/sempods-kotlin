package org.sempods.client.core

import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What the pod-metadata operations put on the wire, and which answers they take.
 *
 * The route is the reference server's; these cases hold the client to the request that server
 * expects and to the two answers it gives.
 */
class SempodsPodMetadataContractTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val route = "/alice/_system/meta/date-modified"

  private fun metadata(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice"), auth), client).metadata()

  private val operations: Map<String, (SempodsPodMetadata) -> Any> = mapOf(
    "exists" to { it.exists() },
    "dateModified" to { it.dateModified() },
    "dateModifiedJson" to { it.dateModifiedJson() },
    "dateModifiedBytes" to { it.dateModifiedBytes() },
  )

  private fun answer(status: Int, body: String, vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status).withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request().withPath(route)).respond(response)
  }

  /** The header names a request carries beyond those OkHttp adds to any request it frames. */
  private fun HttpRequest.headersBeyondTransport(): Set<String> =
    headerList.map { it.name.value.lowercase() }.toSet() -
      setOf("host", "connection", "accept-encoding", "user-agent", "content-length")

  @ParameterizedTest
  @ValueSource(strings = ["exists", "dateModified", "dateModifiedJson", "dateModifiedBytes"])
  fun `every operation sends a GET without query or body that accepts JSON`(operation: String) {
    answer(200, """{"dateModified":null}""")

    operations.getValue(operation)(metadata())

    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("GET", sent.method.value)
    assertEquals(route, sent.path.value)
    assertTrue(sent.queryStringParameterList.isEmpty(), "query: ${sent.queryStringParameterList}")
    assertEquals(0, sent.bodyAsRawBytes?.size ?: 0)
    assertEquals("application/json", sent.getFirstHeader("Accept"))
    assertEquals(setOf("accept"), sent.headersBeyondTransport())
  }

  @Test
  fun `the operations authenticate as the session does and add no credential of their own`() {
    answer(200, """{"dateModified":null}""")

    metadata().dateModified()
    metadata(SempodsRequestAuth.apiKeyHeader("X-Api-Key", "key-a")).dateModifiedJson()
    metadata(SempodsRequestAuth.bearer("token-b")).exists()

    val (anonymous, apiKey, bearer) = server.retrieveRecordedRequests(request()).toList()
    assertEquals(setOf("accept"), anonymous.headersBeyondTransport())
    assertEquals(setOf("accept", "x-api-key"), apiKey.headersBeyondTransport())
    assertEquals("key-a", apiKey.getFirstHeader("X-Api-Key"))
    assertEquals(setOf("accept", "authorization"), bearer.headersBeyondTransport())
    assertEquals("Bearer token-b", bearer.getFirstHeader("Authorization"))
  }

  @Test
  fun `a written pod answers with its instant, typed and as the text that was sent`() {
    val body = """{"dateModified":"2026-05-20T10:15:30.123456Z"}"""
    answer(200, body, "ETag" to "\"v1\"")

    val typed = metadata().dateModified()
    assertEquals(200, typed.status)
    assertEquals(Instant.parse("2026-05-20T10:15:30.123456Z"), typed.body?.dateModified)
    assertEquals("\"v1\"", typed.headers["ETag"])

    val raw = metadata().dateModifiedJson()
    assertEquals(200, raw.status)
    assertEquals(body, raw.body)
    assertTrue(metadata().exists())
  }

  @ParameterizedTest
  @ValueSource(strings = ["""{"dateModified":null}""", "{}"])
  fun `a pod never written to has no dateModified, whether the member is null or missing`(body: String) {
    answer(200, body)

    val typed = metadata().dateModified()

    assertEquals(200, typed.status)
    assertEquals(SempodsPodDateModified(null), typed.body)
    assertTrue(metadata().exists())
  }

  @Test
  fun `an unknown pod is an answer without a body, and its status and headers are kept`() {
    answer(404, """{"error":"no such pod"}""", "X-Request-Id" to "r-404")

    assertFalse(metadata().exists())
    listOf(metadata().dateModified(), metadata().dateModifiedJson(), metadata().dateModifiedBytes()).forEach {
      assertEquals(404, it.status)
      assertEquals("r-404", it.headers["X-Request-Id"])
      assertNull(it.body)
    }
  }

  @ParameterizedTest
  @ValueSource(ints = [401, 500, 503])
  fun `any other status is a failure that keeps the answer's headers and an excerpt of its body`(status: Int) {
    answer(status, "refused: see logs", "Retry-After" to "7", "WWW-Authenticate" to "Bearer realm=\"pod\"")

    operations.forEach { (name, operation) ->
      val failure = assertThrows<SempodsStatusException>(name) { operation(metadata()) }
      assertEquals(status, failure.status)
      assertEquals("7", failure.headers["Retry-After"])
      assertEquals("Bearer realm=\"pod\"", failure.headers["WWW-Authenticate"])
      assertEquals("refused: see logs", failure.bodyExcerpt)
      assertEquals("GET $origin$route answered $status, which this operation does not accept.", failure.message)
    }
  }

  @Test
  fun `over a client without the sempods interceptors nothing is sent`() {
    val plain = OkHttpClient()
    try {
      val metadata = SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), plain).metadata()
      assertThrows<IOException> { metadata.exists() }
    } finally {
      plain.shutDown()
    }
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }
}
