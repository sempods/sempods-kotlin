package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDbo
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `/authorize` and `/token` do today with a parameter sent twice, sent empty, or not decodable.
 *
 * Characterization, not specification: RFC 6749 §3.1 says a parameter "MUST NOT be included more
 * than once", and this server does not refuse one that is. It reads the first value and ignores the
 * rest. A protocol library parsing these requests may decide differently, and #154 treats a
 * stricter answer as a behaviour change of its own, so these tests say what changes when it does.
 */
class PodOAuthParameterMultiplicityHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var flow: DelegatedAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  /** A pod, its owner signed in, the app it authorizes and a second app it does not. */
  private inner class Browser {
    private val owner = sempodsTestFactory.newOwner()
    val pod: PodDbo = sempodsTestFactory.newPod(ownerUser = owner)
    val webId: String = webIdUriDeriver.deriveFromEmail(checkNotNull(owner.email))
    val app = flow.register(pod)
    val other = flow.register(pod, redirectUri = "http://localhost:5174/other")
    val cookie = signIn(pod.name, webId).cookie

    /** The request every case varies one parameter of: answered with the consent page. */
    val authorization = listOf(
      "response_type" to "code",
      "client_id" to app.clientId,
      "redirect_uri" to app.redirectUri,
      "state" to "first",
      "code_challenge" to DelegatedAccessFlow.CODE_CHALLENGE,
      "code_challenge_method" to "S256",
      "prompt" to "consent",
    )

    fun authorize(params: List<Pair<String, String>>, raw: String = ""): TestHttpResponse =
      http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize?${query(params)}$raw")
        .addHeader("Cookie", cookie)
        .setFollowRedirect(false)
        .execute()

    /** A fresh authorization code for [app], and the exchange that redeems it. */
    fun exchange(): List<Pair<String, String>> = listOf(
      "grant_type" to "authorization_code",
      "code" to flow.codeFrom(flow.consent(pod, webId, app, cookie, state = "code")),
      "redirect_uri" to app.redirectUri,
      "client_id" to app.clientId,
      "code_verifier" to DelegatedAccessFlow.CODE_VERIFIER,
    )

    fun token(body: String): TestHttpResponse =
      http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/token")
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .setBody(body)
        .execute()
  }

  /** [params] with [name] replaced by [values], each sent in that order. */
  private fun List<Pair<String, String>>.with(name: String, vararg values: String) =
    filter { it.first != name } + values.map { name to it }

  private fun query(params: List<Pair<String, String>>) =
    params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }

  private fun assertConsentPage(response: TestHttpResponse, case: String) {
    assertEquals(200, response.statusCode, "$case: ${response.getHeader("Location") ?: response.responseBody.take(200)}")
    assertTrue("id=\"consentForm\"" in response.responseBody, case)
  }

  private fun assertRedirectsWith(response: TestHttpResponse, expected: String, case: String) {
    assertEquals(303, response.statusCode, "$case: ${response.responseBody.take(200)}")
    val location = checkNotNull(response.getHeader("Location"))
    assertTrue(expected in location, "$case: $location")
  }

  private fun assertPlain400(response: TestHttpResponse, expected: String, case: String) {
    assertEquals(400, response.statusCode, case)
    assertEquals(expected, response.responseBody, case)
  }

  private fun assertTokenError(response: TestHttpResponse, expected: String, case: String) {
    assertEquals(400, response.statusCode, "$case: ${response.responseBody}")
    assertTrue(expected in response.responseBody, "$case: ${response.responseBody}")
  }

  @Test
  fun `authorize reads the first of a repeated parameter and ignores the rest`() {
    val browser = Browser()
    val request = browser.authorization

    // A second value that would fail changes nothing: the first decides.
    val second = mapOf(
      "response_type" to "token",
      "client_id" to browser.other.clientId,
      "redirect_uri" to browser.other.redirectUri,
      "state" to "second",
      "code_challenge" to "x",
      "code_challenge_method" to "plain",
      "prompt" to "none",
      "scope" to "not-a-scope",
    )
    for ((name, value) in second) {
      val first = request.firstOrNull { it.first == name }?.second ?: "public-read"
      assertConsentPage(browser.authorize(request.with(name, first, value)), "$name sent first valid, then '$value'")
      assertConsentPage(browser.authorize(request.with(name, first, first)), "$name sent twice alike")
    }

    // And a first value that fails decides too, whatever follows it.
    assertRedirectsWith(browser.authorize(request.with("response_type", "token", "code")), "error=unsupported_response_type", "response_type token, code")
    assertPlain400(browser.authorize(request.with("client_id", browser.other.clientId, browser.app.clientId)), "redirect_uri not allowed for this client_id", "client_id other, app")
    assertPlain400(browser.authorize(request.with("redirect_uri", browser.other.redirectUri, browser.app.redirectUri)), "redirect_uri not allowed for this client_id", "redirect_uri other, app")
    assertRedirectsWith(browser.authorize(request.with("code_challenge_method", "plain", "S256")), "error=invalid_request", "code_challenge_method plain, S256")
    assertRedirectsWith(browser.authorize(request.with("prompt", "none", "consent")), "error=consent_required", "prompt none, consent")
    assertRedirectsWith(
      browser.authorize(request.with("response_type", "token").with("state", "second", "first")),
      "&state=second",
      "state second, first",
    )
  }

  @Test
  fun `authorize reads an empty parameter as an absent one`() {
    val browser = Browser()
    val request = browser.authorization

    assertRedirectsWith(browser.authorize(request.with("response_type", "")), "error=unsupported_response_type", "response_type")
    assertPlain400(browser.authorize(request.with("client_id", "")), "client_id must be a did:web or dyn: identity", "client_id")
    assertPlain400(browser.authorize(request.with("redirect_uri", "")), "missing redirect_uri", "redirect_uri")
    assertRedirectsWith(browser.authorize(request.with("code_challenge", "")), "code_challenge+is+required", "code_challenge")
    assertRedirectsWith(browser.authorize(request.with("code_challenge_method", "")), "code_challenge_method+must+be+S256", "code_challenge_method")
    for (name in listOf("state", "prompt", "scope")) {
      assertConsentPage(browser.authorize(request.with(name, "")), name)
    }
    // A parameter without `=` is the same request.
    assertConsentPage(browser.authorize(request, raw = "&state"), "state without =")
  }

  @Test
  fun `authorize takes a code_challenge it cannot check and leaves the refusal to the exchange`() {
    // RFC 7636 §4.2 allows 43 to 128 characters of a fixed alphabet. `/authorize` checks presence
    // only, so a challenge no verifier can ever match parks a code that `/token` then refuses.
    val browser = Browser()

    assertConsentPage(browser.authorize(browser.authorization.with("code_challenge", "short")), "a five-character challenge")
  }

  @Test
  fun `token reads the first of a repeated form parameter and ignores the rest`() {
    val browser = Browser()
    val second = mapOf(
      "grant_type" to "refresh_token",
      "code" to "not-a-code",
      "redirect_uri" to browser.other.redirectUri,
      "client_id" to browser.other.clientId,
      "code_verifier" to "x".repeat(43),
    )
    for ((name, value) in second) {
      val exchange = browser.exchange()
      val first = exchange.first { it.first == name }.second
      val answer = browser.token(query(exchange.with(name, first, value)))
      assertEquals(200, answer.statusCode, "$name sent first valid, then '$value': ${answer.responseBody}")
    }

    val firstDecides = mapOf(
      "grant_type" to "missing refresh_token",
      "code" to "invalid or expired authorization code",
      "redirect_uri" to "redirect_uri mismatch",
      "client_id" to "client_id mismatch",
      "code_verifier" to "PKCE verification failed",
    )
    for ((name, error) in firstDecides) {
      val exchange = browser.exchange()
      val first = exchange.first { it.first == name }.second
      assertTokenError(browser.token(query(exchange.with(name, second.getValue(name), first))), error, "$name sent wrong first")
    }
  }

  @Test
  fun `token reads an empty form parameter as an absent one`() {
    val browser = Browser()
    val missing = mapOf(
      "grant_type" to "unsupported_grant_type",
      "code" to "missing code",
      "redirect_uri" to "missing redirect_uri",
      "client_id" to "missing or malformed client_id",
      "code_verifier" to "missing code_verifier",
    )
    for ((name, error) in missing) {
      assertTokenError(browser.token(query(browser.exchange().with(name, ""))), error, name)
    }
  }

  @Test
  fun `a query or form that does not decode is refused before any OAuth answer`() {
    val browser = Browser()

    val authorize = browser.authorize(browser.authorization, raw = "&state=%zz")
    assertEquals(400, authorize.statusCode)
    assertEquals(null, authorize.getHeader("Location"))

    val token = browser.token("grant_type=authorization_code&code=%zz")
    assertEquals(400, token.statusCode)
    assertTrue("error" !in token.responseBody, "no OAuth error body: ${token.responseBody}")
  }
}
