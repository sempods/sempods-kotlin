package org.sempods.client

import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** What a token request puts on the wire, which answers it takes, and what it never carries. */
class SempodsPodTokensContractTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val route = "/alice/_system/auth/token"

  private val secret = "s3cr3t-value"

  private val token = "tok-credential-value"

  private val alice get() = SempodsPodBase.of("$origin/alice")

  private fun tokens(auth: SempodsRequestAuth = SempodsRequestAuth.clientSecretBasic("notes-app", secret)) =
    SempodsPodTokens(SempodsSession(alice, auth), client)

  private val operations: Map<String, (SempodsPodTokens) -> SempodsResponse<*>> = mapOf(
    "clientCredentials" to { it.clientCredentials() },
    "clientCredentialsJson" to { it.clientCredentialsJson() },
    "clientCredentialsBytes" to { it.clientCredentialsBytes() },
  )

  private fun answer(status: Int, body: String, vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status).withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request().withPath(route)).respond(response)
  }

  private fun tokenDocument(extra: String = ""","expires_in":900,"scope":"public-read"""") =
    """{"access_token":"$token","token_type":"Bearer"$extra}"""

  @ParameterizedTest
  @ValueSource(strings = ["clientCredentials", "clientCredentialsJson", "clientCredentialsBytes"])
  fun `every operation posts the client_credentials form with the client's credential and nothing more`(operation: String) {
    answer(200, tokenDocument())

    operations.getValue(operation)(tokens())

    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("POST", sent.method.value)
    assertEquals(route, sent.path.value)
    assertTrue(sent.queryStringParameterList.isEmpty(), "query: ${sent.queryStringParameterList}")
    assertEquals("application/x-www-form-urlencoded", sent.getFirstHeader("Content-Type"))
    assertEquals("grant_type=client_credentials", sent.bodyAsString)
    assertEquals("application/json", sent.getFirstHeader("Accept"))
    assertEquals(setOf("accept", "authorization", "content-type"), sent.headersBeyondTransport())
  }

  @Test
  fun `the client's id and secret are form-encoded before they are joined and Base64-encoded`() {
    answer(200, tokenDocument())

    tokens(SempodsRequestAuth.clientSecretBasic("notes app:ü", "se:cret+x ü")).clientCredentials()

    val encoded = "notes+app%3A%C3%BC:se%3Acret%2Bx+%C3%BC"
    val expected = "Basic " + Base64.getEncoder().encodeToString(encoded.toByteArray(Charsets.UTF_8))
    assertEquals(expected, server.retrieveRecordedRequests(request()).single().getFirstHeader("Authorization"))
  }

  @Test
  fun `a deployment can decorate the client's credential or replace it`() {
    answer(200, tokenDocument())

    tokens(SempodsRequestAuth.clientSecretBasic("notes-app", secret).andThen(SempodsRequestAuth.apiKeyHeader("X-Gateway-Key", "g-1")))
      .clientCredentials()
    tokens(SempodsRequestAuth.apiKeyHeader("X-Client-Key", "k-1")).clientCredentials()

    val (decorated, replaced) = server.retrieveRecordedRequests(request()).toList()
    assertTrue(decorated.getFirstHeader("Authorization").startsWith("Basic "))
    assertEquals("g-1", decorated.getFirstHeader("X-Gateway-Key"))
    assertEquals(setOf("accept", "content-type", "x-client-key"), replaced.headersBeyondTransport())
  }

  @Test
  fun `a pod session mints through the endpoint, which never carries the pod's bearer or asks for it`() {
    server.`when`(request().withPath(route), Times.once()).respond(response().withStatusCode(200).withBody(tokenDocument().replace(token, "tok-1")))
    server.`when`(request().withPath(route), Times.once()).respond(response().withStatusCode(200).withBody(tokenDocument().replace(token, "tok-2")))
    server.`when`(request().withPath("/alice/x").withHeader("Authorization", "Bearer tok-1")).respond(response().withStatusCode(401))
    server.`when`(request().withPath("/alice/x").withHeader("Authorization", "Bearer tok-2")).respond(response().withStatusCode(200))

    val minted = AtomicInteger()
    val applied = AtomicInteger()
    // One slot and no queue: a token request that needed a slot of its own would be refused.
    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { narrow ->
      val clientSession = SempodsSession(alice, SempodsRequestAuth.clientSecretBasic("notes-app", secret))
      val refreshable = SempodsRequestAuth.refreshable(
        SempodsCredentialSupplier { _, attempt ->
          minted.incrementAndGet()
          checkNotNull(SempodsPodTokens(clientSession, attempt.calls(narrow)).clientCredentials().body).accessToken
        },
      )
      val podBearer = object : SempodsRequestAuth {
        override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) {
          applied.incrementAndGet()
          refreshable.apply(request, attempt)
        }

        override fun recover(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) =
          refreshable.recover(facts, attempt)
      }
      val pod = SempodsSession(alice, podBearer)

      narrow.newCall(pod.newRequest("GET", "x").build()).execute().use { assertEquals(200, it.code) }
    }

    val tokenRequests = server.retrieveRecordedRequests(request().withPath(route)).toList()
    val dataRequests = server.retrieveRecordedRequests(request().withPath("/alice/x")).toList()
    assertEquals(2, minted.get(), "one initial mint and one after the refusal")
    assertEquals(2, tokenRequests.size)
    tokenRequests.forEach { assertTrue(it.getFirstHeader("Authorization").startsWith("Basic "), it.getFirstHeader("Authorization")) }
    assertEquals(listOf("Bearer tok-1", "Bearer tok-2"), dataRequests.map { it.getFirstHeader("Authorization") })
    assertEquals(dataRequests.size, applied.get(), "the pod's bearer was applied to a request other than the pod's")
  }

  @Test
  fun `a token response is typed, and the text is the body as it was sent`() {
    answer(200, tokenDocument(""","expires_in":900,"scope":"public-read","refresh_token_hint":{"x":1}"""), "Cache-Control" to "no-store")

    val typed = tokens().clientCredentials()
    assertEquals(200, typed.status)
    assertEquals("no-store", typed.headers["Cache-Control"])
    assertEquals(SempodsTokenResponse.of(token, "Bearer", Duration.ofSeconds(900), "public-read"), typed.body)

    val raw = tokens().clientCredentialsJson()
    assertEquals(tokenDocument(""","expires_in":900,"scope":"public-read","refresh_token_hint":{"x":1}"""), raw.body)
  }

  @ParameterizedTest
  @ValueSource(strings = ["", ""","expires_in":null""", ""","scope":null"""])
  fun `a response without a lifetime or a scope reads as null for each`(extra: String) {
    answer(200, tokenDocument(extra))

    val body = checkNotNull(tokens().clientCredentials().body)

    assertEquals(token, body.accessToken)
    assertNull(body.expiresIn)
    assertNull(body.scope)
  }

  @Test
  fun `any success is read as the token response`() {
    answer(201, tokenDocument())

    val answered = tokens().clientCredentials()

    assertEquals(201, answered.status)
    assertEquals(token, answered.body?.accessToken)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"token_type":"Bearer","scope":"tok-credential-value"}""",
      """{"access_token":"tok-credential-value","token_type":7}""",
      """{"access_token":"tok-credential-value","token_type":"Bearer","expires_in":1.5}""",
      """{"access_token":"tok-credential-value","token_type":"Bearer","expires_in":-1}""",
      """{"access_token":"tok-credential-value","token_type":"Bearer","expires_in":"900"}""",
      """["tok-credential-value"]""",
    ],
  )
  fun `a body that is not a token response is a decoding failure that quotes nothing from it`(body: String) {
    answer(200, body)

    val failure = assertThrows<SempodsDecodingException> { tokens().clientCredentials() }

    assertEquals(200, failure.status)
    assertFalse(failure.message!!.contains(token), failure.message)
    assertEquals(body, tokens().clientCredentialsJson().body)
  }

  @Test
  fun `a success without a body is a decoding failure, and a raw read returns the empty body`() {
    answer(204, "")

    assertThrows<SempodsDecodingException> { tokens().clientCredentials() }
    assertEquals("", tokens().clientCredentialsJson().body)
    assertContentEquals(ByteArray(0), tokens().clientCredentialsBytes().body)
  }

  @ParameterizedTest
  @ValueSource(ints = [400, 401, 429, 503])
  fun `every other status is a failure that keeps the headers and the error document, and names no credential`(status: Int) {
    val error = """{"error":"invalid_client","error_description":"unknown client_id or invalid secret"}"""
    answer(status, error, "WWW-Authenticate" to "Basic realm=\"alice\"", "Retry-After" to "3")

    operations.forEach { (name, operation) ->
      val failure = assertThrows<SempodsStatusException>(name) { operation(tokens()) }
      assertEquals(status, failure.status)
      assertEquals("Basic realm=\"alice\"", failure.headers["WWW-Authenticate"])
      assertEquals("3", failure.headers["Retry-After"])
      assertEquals(error, failure.bodyExcerpt)
      assertEquals("POST $origin$route answered $status, which this operation does not accept.", failure.message)
    }
  }

  @Test
  fun `a token response names no credential when it is printed`() {
    val printed = SempodsTokenResponse.of(token, "Bearer", Duration.ofSeconds(900), "public-read").toString()

    assertFalse(printed.contains(token), printed)
    assertEquals("SempodsTokenResponse(tokenType=Bearer, expiresIn=PT15M, scope=public-read)", printed)
  }
}
