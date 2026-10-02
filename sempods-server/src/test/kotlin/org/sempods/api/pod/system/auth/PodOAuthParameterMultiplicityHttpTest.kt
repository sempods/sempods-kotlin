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
 * What `/authorize` and `/token` answer to a parameter sent twice, sent empty, or not decodable.
 *
 * RFC 6749 §3.1: a parameter "MUST NOT be included more than once", and §4.1.2.1 names
 * `invalid_request` for a request that does. Both routes refuse one, even sent twice alike. An empty
 * parameter is an absent one (§3.1 as well).
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
  fun `authorize refuses a parameter sent twice, directly while the client is unknown and at its address after`() {
    val browser = Browser()
    val request = browser.authorization

    // Two clients or two addresses: there is no one address to answer at.
    for ((name, value) in listOf("client_id" to browser.app.clientId, "redirect_uri" to browser.app.redirectUri)) {
      for (second in listOf(value, browser.other.clientId.takeIf { name == "client_id" } ?: browser.other.redirectUri)) {
        assertPlain400(
          browser.authorize(request.with(name, value, second)),
          "invalid_request: client_id and redirect_uri must each be sent once",
          "$name sent twice",
        )
      }
    }

    // Any other of its parameters, even sent twice alike, is an error the client hears.
    for (name in listOf("response_type", "state", "code_challenge", "code_challenge_method", "prompt", "scope")) {
      val value = request.firstOrNull { it.first == name }?.second ?: "public-read"
      val refused = browser.authorize(request.with(name, value, value))
      assertRedirectsWith(refused, "error=invalid_request", "$name sent twice")
      assertRedirectsWith(refused, "error_description=$name+included+more+than+once", "$name sent twice")
      assertRedirectsWith(refused, "&state=first", "$name sent twice")
    }
    val two = browser.authorize(request.with("prompt", "consent", "consent").with("scope", "public-read", "public-read"))
    assertRedirectsWith(two, "error_description=prompt%2C+scope+included+more+than+once", "prompt and scope sent twice")

    // A parameter `/authorize` does not read is ignored, however often it comes.
    assertConsentPage(browser.authorize(request + ("resource" to "a") + ("resource" to "b")), "an unread parameter sent twice")
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
    // An empty `state` is no state: the answer carries none back.
    val withoutState = request.filter { it.first != "state" }
    for (page in listOf(browser.authorize(withoutState + ("state" to "")), browser.authorize(withoutState, raw = "&state"))) {
      val answer = flow.submit(DelegatedAccessFlow.ConsentPage.of(page), browser.cookie, action = "cancel")
      val location = checkNotNull(answer.getHeader("Location"))
      assertTrue("state=" !in location, location)
    }
    // An empty `prompt` or `scope` renders the screen the request without it renders.
    for (name in listOf("prompt", "scope")) {
      val absent = DelegatedAccessFlow.ConsentPage.of(browser.authorize(request.filter { it.first != name }))
      val empty = DelegatedAccessFlow.ConsentPage.of(browser.authorize(request.with(name, "")))
      assertEquals(absent.offered to absent.ticked, empty.offered to empty.ticked, name)
    }
  }

  @Test
  fun `authorize refuses a code_challenge no S256 verifier can match`() {
    // RFC 7636 §4.2: an S256 challenge is the base64url of a SHA-256 digest, 43 characters. Anything
    // else would park a code that no exchange can redeem.
    val browser = Browser()
    for (challenge in listOf("short", "a".repeat(128), "~" + "a".repeat(42), "a".repeat(42) + "=")) {
      assertRedirectsWith(
        browser.authorize(browser.authorization.with("code_challenge", challenge)),
        "error_description=code_challenge+must+be+an+S256+challenge",
        "code_challenge '$challenge'",
      )
    }
    assertConsentPage(browser.authorize(browser.authorization), "the RFC 7636 example challenge")
  }

  @Test
  fun `token refuses a form parameter sent twice before acting on any of them`() {
    val browser = Browser()
    for (name in listOf("grant_type", "code", "redirect_uri", "client_id", "code_verifier")) {
      val exchange = browser.exchange()
      val value = exchange.first { it.first == name }.second

      assertTokenError(browser.token(query(exchange.with(name, value, value))), "$name included more than once", "$name sent twice")
      // The code was not touched: the same exchange, sent once, still redeems it.
      val redeemed = browser.token(query(exchange))
      assertEquals(200, redeemed.statusCode, "$name: ${redeemed.responseBody}")
    }
    // The refresh grant's own fields too, and several at once are named together.
    val refresh = listOf("grant_type" to "refresh_token", "refresh_token" to "a", "client_id" to browser.app.clientId)
    assertTokenError(browser.token(query(refresh.with("refresh_token", "a", "b"))), "refresh_token included more than once", "refresh_token sent twice")
    assertTokenError(browser.token(query(refresh + ("scope" to "public-read") + ("scope" to "public-read"))), "scope included more than once", "scope sent twice")
    assertTokenError(
      browser.token(query(refresh.with("refresh_token", "a", "a") + ("scope" to "x") + ("scope" to "x"))),
      "refresh_token, scope included more than once",
      "refresh_token and scope sent twice",
    )
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
