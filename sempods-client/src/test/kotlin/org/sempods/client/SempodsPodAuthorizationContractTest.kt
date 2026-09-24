package org.sempods.client

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import tools.jackson.databind.json.JsonMapper

/** A public client's side of a pod's OAuth: registering, the authorization URL, and redeeming its code. */
class SempodsPodAuthorizationContractTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val json = JsonMapper()

  private val alice get() = SempodsPodBase.of("$origin/alice")

  private val authorization get() = SempodsPodAuthorization(SempodsSession(alice), client)

  private val tokens get() = SempodsPodTokens(SempodsSession(alice), client)

  private fun answer(path: String, status: Int, body: String) {
    server.`when`(request().withPath(path)).respond(response().withStatusCode(status).withBody(body))
  }

  @Test
  fun `a public client is registered with RFC 7591 metadata and no credential`() {
    answer(
      "/alice/_system/auth/register",
      201,
      """{"client_id":"dyn:abc","client_name":"Installer","redirect_uris":["http://127.0.0.1/cb"],"token_endpoint_auth_method":"none"}""",
    )

    val registered = authorization.registerClient("Installer", listOf("http://127.0.0.1/cb"))

    assertEquals(SempodsPublicClient.of("dyn:abc", "Installer", listOf("http://127.0.0.1/cb")), registered.body)
    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("POST", sent.method.value)
    assertEquals("application/json", sent.getFirstHeader("Content-Type"))
    assertEquals(
      json.readTree("""{"client_name":"Installer","redirect_uris":["http://127.0.0.1/cb"],"grant_types":["authorization_code"],""" +
        """"response_types":["code"],"token_endpoint_auth_method":"none"}"""),
      json.readTree(String(sent.body.rawBytes, Charsets.UTF_8)),
    )
    assertEquals(setOf("accept", "content-type"), sent.headersBeyondTransport())
  }

  @Test
  fun `a refused registration keeps the pod's error document`() {
    val error = """{"error":"invalid_redirect_uri","error_description":"redirect_uri must be https"}"""
    answer("/alice/_system/auth/register", 400, error)

    val failure = assertThrows<SempodsStatusException> { authorization.registerClient("Installer", listOf("http://example.org/cb")) }

    assertEquals(400, failure.status)
    assertEquals(error, failure.bodyExcerpt)
  }

  @Test
  fun `the authorization URL is the pod's own, with the code flow and the PKCE challenge`() {
    val pkce = SempodsPkce.of("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

    val url = authorization.authorizationUrl("dyn:abc", "http://127.0.0.1:4711/cb", "service-clients:install", "s 1", pkce)

    assertEquals("$origin/alice/_system/auth/authorize", url.newBuilder().query(null).build().toString())
    assertEquals(
      mapOf(
        "response_type" to "code",
        "client_id" to "dyn:abc",
        "redirect_uri" to "http://127.0.0.1:4711/cb",
        "scope" to "service-clients:install",
        "state" to "s 1",
        "code_challenge" to "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        "code_challenge_method" to "S256",
      ),
      url.queryParameterNames.associateWith { url.queryParameter(it) },
    )
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty(), "building the URL sent a request")
  }

  @Test
  fun `a code is redeemed with its verifier, anonymously, and the token has no refresh`() {
    answer(
      "/alice/_system/auth/token",
      200,
      """{"access_token":"tok-1","token_type":"Bearer","expires_in":3600,"scope":"service-clients:install"}""",
    )

    val token = tokens.authorizationCode("dyn:abc", "c-1", "http://127.0.0.1:4711/cb", "v".repeat(43))

    assertEquals(SempodsTokenResponse.of("tok-1", "Bearer", Duration.ofHours(1), "service-clients:install"), token.body)
    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals(
      "grant_type=authorization_code&code=c-1&redirect_uri=http%3A%2F%2F127.0.0.1%3A4711%2Fcb&client_id=dyn%3Aabc&code_verifier=" + "v".repeat(43),
      sent.bodyAsString,
    )
    assertEquals(setOf("accept", "content-type"), sent.headersBeyondTransport())
  }

  @Test
  fun `a spent or mismatched code is the pod's invalid_grant`() {
    val error = """{"error":"invalid_grant","error_description":"PKCE verification failed"}"""
    answer("/alice/_system/auth/token", 400, error)

    val failure = assertThrows<SempodsStatusException> { tokens.authorizationCode("dyn:abc", "c-1", "http://127.0.0.1/cb", "v".repeat(43)) }

    assertEquals(400, failure.status)
    assertEquals(error, failure.bodyExcerpt)
  }
}
