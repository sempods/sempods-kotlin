package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `/token` answers to a request it cannot read as written: a grant it does not offer, a value
 * outside its parameter's syntax, and HTTP Basic credentials that do not parse.
 *
 * Repeated and empty parameters are [PodOAuthParameterMultiplicityHttpTest]'s.
 */
class PodTokenRequestHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var flow: DelegatedAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var serviceClients: PodServiceClientStore

  /** A pod, its owner signed in, and the app it authorizes. */
  private inner class Browser {
    private val owner = sempodsTestFactory.newOwner()
    val pod: PodDbo = sempodsTestFactory.newPod(ownerUser = owner)
    val webId: String = webIdUriDeriver.deriveFromEmail(checkNotNull(owner.email))
    val app = flow.register(pod)
    val cookie = signIn(pod.name, webId).cookie

    /** A fresh authorization code for [app], and the exchange that redeems it. */
    fun exchange(): List<Pair<String, String>> = listOf(
      "grant_type" to "authorization_code",
      "code" to flow.codeFrom(flow.consent(pod, webId, app, cookie, state = "code")),
      "redirect_uri" to app.redirectUri,
      "client_id" to app.clientId,
      "code_verifier" to DelegatedAccessFlow.CODE_VERIFIER,
    )

    fun token(params: List<Pair<String, String>>, authorization: String? = null): TestHttpResponse =
      token(pod, params, authorization)
  }

  private fun token(pod: PodDbo, params: List<Pair<String, String>>, authorization: String? = null): TestHttpResponse =
    http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/token")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .apply { authorization?.let { addHeader("Authorization", it) } }
      .setBody(params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" })
      .execute()

  /** [params] with [name] replaced by [value]. */
  private fun List<Pair<String, String>>.with(name: String, value: String) =
    filter { it.first != name } + (name to value)

  private fun assertTokenError(response: TestHttpResponse, error: String, description: String, case: String) {
    assertEquals(400, response.statusCode, "$case: ${response.responseBody}")
    assertTrue("\"error\":\"$error\"" in response.responseBody, "$case: ${response.responseBody}")
    assertTrue(description in response.responseBody, "$case: ${response.responseBody}")
  }

  private fun assertIssued(response: TestHttpResponse, case: String) {
    assertEquals(200, response.statusCode, "$case: ${response.responseBody}")
    assertTrue("\"access_token\"" in response.responseBody, case)
  }


  // ── The grant ──────────────────────────────────────────────────────────────

  @Test
  fun `token names a missing grant_type a malformed request, and every grant it does not offer unsupported`() {
    val browser = Browser()
    val exchange = browser.exchange()

    // RFC 6749 §5.2: a required parameter that is missing is `invalid_request`.
    assertTokenError(browser.token(exchange.filter { it.first != "grant_type" }), "invalid_request", "grant_type", "no grant_type")
    for (grant in listOf("password", "urn:ietf:params:oauth:grant-type:device_code", "custom")) {
      assertTokenError(
        browser.token(exchange.with("grant_type", grant)),
        "unsupported_grant_type", "only authorization_code, refresh_token and client_credentials are supported",
        "grant_type '$grant'",
      )
    }
    // None of them touched the code, and a grant type is trimmed like the form's other values.
    assertIssued(browser.token(exchange.with("grant_type", " authorization_code ")), "a padded grant_type")
  }

  // ── The authorization code's own parameters ────────────────────────────────

  @Test
  fun `a code_verifier outside RFC 7636's syntax is refused before the code is spent`() {
    val browser = Browser()
    val exchange = browser.exchange()
    for (verifier in listOf("short", "a".repeat(129), "a".repeat(42) + "!", " ${DelegatedAccessFlow.CODE_VERIFIER}")) {
      assertTokenError(browser.token(exchange.with("code_verifier", verifier)), "invalid_request", "Illegal code verifier", "code_verifier '$verifier'")
    }
    assertIssued(browser.token(exchange), "the exchange as sent")
  }

  @Test
  fun `a redirect_uri that is no URI is refused before the code is spent`() {
    val browser = Browser()
    val exchange = browser.exchange()

    assertTokenError(
      browser.token(exchange.with("redirect_uri", "http://localhost:5173/a b")),
      "invalid_request", "Invalid redirect_uri parameter",
      "redirect_uri with a space",
    )
    assertIssued(browser.token(exchange), "the exchange as sent")
  }

  @Test
  fun `a code exchange reads client_id from the form, whatever the Authorization header says`() {
    val browser = Browser()

    assertIssued(browser.token(browser.exchange(), authorization = basic("did:web:elsewhere.example:x")), "Basic")
    assertIssued(browser.token(browser.exchange(), authorization = "Basic !!!"), "malformed Basic")
  }

  // ── Client Credentials ─────────────────────────────────────────────────────

  private inner class Service {
    val pod: PodDbo = sempodsTestFactory.newPod()
    val registered = serviceClients.register(
      pod = pod.hosted,
      clientId = "notes-app",
      scopes = setOf("${sempodsUriBuilder.buildContext(pod.name, "apps/notes")}#manage"),
      label = "notes-app",
    )
    val clientId = registered.registration.clientId
    val secret = registered.secret

    fun token(authorization: String?, params: List<Pair<String, String>> = listOf("grant_type" to "client_credentials")) =
      token(pod, params, authorization)
  }

  /** `Basic` over [credentials] as written: no percent-encoding of either half. */
  private fun basic(credentials: String, scheme: String = "Basic"): String =
    "$scheme " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))

  private fun assertChallenged(service: Service, response: TestHttpResponse, case: String) {
    assertEquals(401, response.statusCode, "$case: ${response.responseBody}")
    assertTrue("\"error\":\"invalid_client\"" in response.responseBody, "$case: ${response.responseBody}")
    assertEquals("""Basic realm="${service.pod.name}"""", response.getHeader("WWW-Authenticate"), case)
  }

  @Test
  fun `client_credentials reads HTTP Basic case-insensitively, percent-decoded and its client id trimmed`() {
    val service = Service()

    assertIssued(service.token(basicHeader(service.clientId, service.secret)), "as encoded")
    assertIssued(service.token(basic("${service.clientId}:${service.secret}", scheme = "basic")), "lower-case scheme")
    assertIssued(service.token(basic("notes%2Dapp:${service.secret}")), "a percent-encoded client id")
    assertIssued(service.token(basic(" notes-app :${service.secret}")), "a client id padded with spaces")
  }

  @Test
  fun `client_credentials answers credentials it cannot read with the Basic challenge`() {
    val service = Service()
    val secret = service.secret

    val unreadable = mapOf(
      "no header" to null,
      "a bearer" to "Bearer $secret",
      "not base64" to "Basic !!!",
      "no colon" to basic("notes-app$secret"),
      "no secret" to basic("notes-app:"),
      "no client id" to basic(":$secret"),
      "the wrong secret" to basic("notes-app:wrong"),
    )
    for ((case, header) in unreadable) {
      assertChallenged(service, service.token(header), case)
    }
    // `client_secret_post` is not a method this pod offers: the form's credentials are not read.
    assertChallenged(
      service,
      service.token(null, listOf("grant_type" to "client_credentials", "client_id" to "notes-app", "client_secret" to secret)),
      "credentials in the form",
    )
  }
}
