package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.auth.ConsentTransactionStore
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.net.UrlUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDbo
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `state` on each of the pod's browser routes: what the client sent comes back, and nothing else
 * does. The rule and its reasons are [suppliedState][org.sempods.pods.oauth.flows.suppliedState]'s.
 *
 * Every assertion reads the **decoded** query, because the wire spelling is allowed to differ: a
 * `+` arrives as `%2B` and leaves as `%2B`, while a space leaves as `+`. A raw substring would pass
 * comparisons the client fails.
 */
class PodOAuthStateHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var consentTransactionStore: ConsentTransactionStore

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  /** Values a client may legally send that a normalising server would change. */
  private val opaqueValues = listOf(
    " padded ",
    "   ",
    "a+b",
    "one&two=three",
    "100%done",
  )

  @Test
  fun `a code redirect gives back the state the client sent`() {
    val (pod, person) = podWithOwner()

    for (state in opaqueValues) {
      val response = consent(pod, person, state)

      assertEquals(303, response.statusCode, response.responseBody)
      assertEquals(listOf(state), statesIn(response), "state=${quoted(state)}")
    }
  }

  @Test
  fun `an error redirect gives back the state the client sent`() {
    val (pod, person) = podWithOwner()

    for (state in opaqueValues) {
      // `response_type` is checked after the address is validated, so this failure travels by
      // redirect to the client's own address.
      val response = get(authorizeUrl(pod, state, responseType = "token"), signIn(pod.name, person).cookie)

      val location = checkNotNull(response.getHeader("Location"))
      assertTrue("error=unsupported_response_type" in location, location)
      assertEquals(listOf(state), statesIn(response), "state=${quoted(state)}")
    }
  }

  @Test
  fun `a state the client never sent is not invented`() {
    val (pod, person) = podWithOwner()

    // Omitted, and sent with no value — RFC 6749 §3.1 makes those the same request.
    for (state in listOf(null, "")) {
      assertNoState(consent(pod, person, state), "the code redirect for state=${quoted(state)}")
      val failed = get(authorizeUrl(pod, state, responseType = "token"), signIn(pod.name, person).cookie)
      assertNoState(failed, "the error redirect for state=${quoted(state)}")
    }
  }

  private fun assertNoState(response: TestHttpResponse, case: String) {
    val found = statesIn(response)
    assertTrue(found.isEmpty(), "$case answered with ${found.map(::quoted)}")
  }

  @Test
  fun `the consent screen hands the state back to the form unchanged`() {
    // A trim here hides behind a correct redirect helper: the browser posts the shortened value,
    // and the code redirect echoes that faithfully.
    val (pod, person) = podWithOwner()

    for (state in opaqueValues) {
      val page = get(authorizeUrl(pod, state, prompt = "consent"), signIn(pod.name, person).cookie)

      assertEquals(200, page.statusCode, page.responseBody)
      assertEquals(state, stateInForm(page.responseBody), "state=${quoted(state)}")
    }
  }

  @Test
  fun `a state parked across the sign-in comes back whole`() {
    // Nobody is signed in, so the request is parked and resumed at `oidc/callback`: the value
    // comes back out of a stored record.
    val (pod, person) = podWithOwner()

    for (state in opaqueValues) {
      val page = http.prepareGet(authorizeUrl(pod, state, prompt = "consent")).executeSignedInAs(person)

      assertEquals(200, page.statusCode, page.responseBody)
      assertEquals(state, stateInForm(page.responseBody), "state=${quoted(state)}")
    }
  }

  @Test
  fun `a parked request that fails reports the state it parked`() {
    // A login that came back unusable, answered by `PodOAuthErrorResponses.renderToParked` off the
    // parked record — the one error path that never sees the current request.
    val (pod, person) = podWithOwner()

    for (state in opaqueValues) {
      val response = http.prepareGet(authorizeUrl(pod, state))
        // A token answering some other login: signed and unexpired, and rejected for its nonce.
        .executeSignedInAs(person, nonce = "the-nonce-of-another-login")

      val location = checkNotNull(response.getHeader("Location"))
      assertTrue("error=server_error" in location, location)
      assertEquals(listOf(state), statesIn(response), "state=${quoted(state)}")
    }
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private companion object {
    const val CLIENT_ID = "did:web:localhost%3A5173"
    const val REDIRECT_URI = "http://localhost:5173/callback"
  }

  private fun podWithOwner(): Pair<PodDbo, String> {
    val owner = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = owner)
    return pod to webIdUriDeriver.deriveFromEmail(checkNotNull(owner.email))
  }

  /**
   * The request as a client spells it. The query is built here because OkHttp's `addQueryParameter`
   * would send a literal `+`, where a client meaning a `+` has to send `%2B`.
   */
  private fun authorizeUrl(
    pod: PodDbo,
    state: String?,
    responseType: String = "code",
    prompt: String? = null,
  ): String = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize" +
    "?response_type=$responseType&client_id=${enc(CLIENT_ID)}&redirect_uri=${enc(REDIRECT_URI)}" +
    (state?.let { "&state=${enc(it)}" } ?: "") +
    (prompt?.let { "&prompt=$it" } ?: "")

  private fun get(url: String, cookie: String): TestHttpResponse =
    http.prepareGet(url).addHeader("Cookie", cookie).setFollowRedirect(false).execute()

  private fun consent(pod: PodDbo, webId: String, state: String?): TestHttpResponse =
    http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, webId).cookie)
      .setBody(
        "client_id=${enc(CLIENT_ID)}&redirect_uri=${enc(REDIRECT_URI)}&scope=public-read" +
          "&csrf=${enc(consentTransactionStore.issue(pod.name, webId, null))}" +
          (state?.let { "&state=${enc(it)}" } ?: ""),
      )
      .setFollowRedirect(false).execute()

  /**
   * Every `state` in a redirect, decoded — an empty list where the answer carries none.
   *
   * A list, because "exactly one" is half of what is asserted: a builder that appends leaves the
   * client reading whichever it finds first.
   */
  private fun statesIn(response: TestHttpResponse): List<String> {
    val location = checkNotNull(response.getHeader("Location")) {
      "no redirect: ${response.statusCode} ${response.responseBody}"
    }
    return URI(location).rawQuery.orEmpty().split("&")
      .filter { it == "state" || it.startsWith("state=") }
      .map { UrlUtil.urlDecode(it.substringAfter("=", "")) }
  }

  /** The `state` the rendered form will post back, with the template's escaping undone. */
  private fun stateInForm(html: String): String {
    val field = checkNotNull(Regex("""<input[^>]*name="state"[^>]*>""").find(html)) {
      "the consent page carries no state field"
    }.value
    val value = checkNotNull(Regex("""value="([^"]*)"""").find(field)) { "no value in $field" }.groupValues[1]
    return value.replace("&lt;", "<").replace("&gt;", ">")
      .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
  }

  private fun quoted(state: String?): String = state?.let { "'$it'" } ?: "(absent)"

}
