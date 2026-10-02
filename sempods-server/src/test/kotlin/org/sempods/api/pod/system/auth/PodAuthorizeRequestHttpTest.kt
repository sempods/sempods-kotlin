package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDbo
import java.net.URI
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `/authorize` answers to a request it cannot read as written — a `response_type` or a
 * `prompt` outside the grammar — and what its answers keep of the address they go to.
 *
 * Repeated and empty parameters are [PodOAuthParameterMultiplicityHttpTest]'s.
 */
class PodAuthorizeRequestHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var flow: DelegatedAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  /** A pod, its owner signed in, and an app registered at [redirectUri]. */
  private inner class Browser(redirectUri: String = "http://localhost:5173/callback") {
    private val owner = sempodsTestFactory.newOwner()
    val pod: PodDbo = sempodsTestFactory.newPod(ownerUser = owner)
    val webId: String = webIdUriDeriver.deriveFromEmail(checkNotNull(owner.email))
    val app = flow.register(pod, redirectUri = redirectUri)
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

    fun authorize(params: List<Pair<String, String>>): TestHttpResponse =
      http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize?${query(params)}")
        .addHeader("Cookie", cookie)
        .setFollowRedirect(false)
        .execute()
  }

  /** [params] with [name] replaced by [value], or left out where [value] is null. */
  private fun List<Pair<String, String>>.with(name: String, value: String?) =
    filter { it.first != name } + listOfNotNull(value?.let { name to it })

  private fun query(params: List<Pair<String, String>>) =
    params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }

  private fun assertConsentPage(response: TestHttpResponse, case: String) {
    assertEquals(200, response.statusCode, "$case: ${response.getHeader("Location") ?: response.responseBody.take(200)}")
    assertTrue("id=\"consentForm\"" in response.responseBody, case)
  }

  private fun location(response: TestHttpResponse, case: String): String {
    assertEquals(303, response.statusCode, "$case: ${response.responseBody.take(200)}")
    return checkNotNull(response.getHeader("Location"))
  }

  private fun assertRedirectsWith(response: TestHttpResponse, expected: String, case: String) {
    val location = location(response, case)
    assertTrue(expected in location, "$case: $location")
  }

  @Test
  fun `a missing response_type is unsupported`() {
    val browser = Browser()

    assertRedirectsWith(
      browser.authorize(browser.authorization.with("response_type", null)),
      "error=unsupported_response_type",
      "no response_type",
    )
  }

  @Test
  fun `a prompt value outside OIDC's set is ignored`() {
    val browser = Browser()

    for (prompt in listOf("foo", "consent foo", "create")) {
      assertConsentPage(browser.authorize(browser.authorization.with("prompt", prompt)), "prompt '$prompt'")
    }
  }

  @Test
  fun `an answer rewrites the query the address was registered with`() {
    val registered = "http://localhost:5173/callback?a=1&a=2&b=x%20y"
    val browser = Browser(redirectUri = registered)

    // The code: the two `a` collapse into the last one, and the space is re-encoded.
    val code = location(flow.consent(browser.pod, browser.webId, browser.app, browser.cookie, state = "s"), "the code")
    assertEquals("a=2&b=x+y", URI(code).rawQuery.substringBefore("&code="), code)

    // An error the same way.
    val error = location(browser.authorize(browser.authorization.with("response_type", "token")), "an error")
    assertEquals("a=2&b=x+y", URI(error).rawQuery.substringBefore("&error="), error)
  }
}
