package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.FakeIdServerTransport
import org.sempods.SempodsUriBuilder
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.api.pod.system.auth.DynamicClientRegistrationDao
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.okhttp.getAll
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodAuthEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var podWebIdGrantsDao: PodWebIdGrantsDao

  @Inject
  private lateinit var dynamicClientRegistrationDao: DynamicClientRegistrationDao

  @Inject
  private lateinit var authorizationCodeStore: AuthorizationCodeStore

  @Inject
  private lateinit var refreshTokenStore: PodRefreshTokenStore

  @Inject
  private lateinit var consentDecisionStore: org.sempods.pods.oauth.PodConsentDecisionStore

  @Inject
  private lateinit var consentTransactionStore: org.sempods.auth.ConsentTransactionStore

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val testClientId = "did:web:localhost%3A5173"
  private val testRedirectUri = "http://localhost:5173/callback"

  // Any valid-looking PKCE pair is sufficient for /authorize — the actual challenge/verifier
  // pair is checked only at /token. Tests that exercise /authorize for `dyn:` clients must
  // include these to satisfy the mandatory-PKCE rule (PR-review finding #2).
  private val testCodeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
  private val testCodeChallengeMethod = "S256"

  /** Registers a `dyn:` client via DCR and returns its client_id. */
  private fun registerDynamicClient(podName: String, redirectUri: String = "http://localhost:5173/callback"): String {
    val response = http.preparePost(registerUrl(podName))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$redirectUri"],"client_name":"Dyn Client"}""")
      .execute()
    assertEquals(201, response.statusCode, response.responseBody)
    @Suppress("UNCHECKED_CAST")
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    return body["client_id"] as String
  }

  @Test
  fun `the address recorded on a registration is the one the proxy appended, not the one the caller wrote`() {
    // A reverse proxy appends what it saw, so everything left of the last entry is text the client
    // chose. Reading the first entry would put a value of the caller's own choosing on an audit
    // row — a forgery with a plausible shape, and nothing in the row would say so.
    val pod = sempodsTestFactory.newPod()
    val response = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7")
      .setBody("""{"redirect_uris":["http://localhost:5173/callback"]}""")
      .execute()
    assertEquals(201, response.statusCode, response.responseBody)

    @Suppress("UNCHECKED_CAST")
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    val stored = assertNotNull(
      dynamicClientRegistrationDao.findByClientId(checkNotNull(pod.id), body["client_id"] as String)
    )
    assertEquals("203.0.113.7", stored.remoteAddr)
  }

  private fun authorizeUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/auth/authorize"

  // Consent-created contexts land in the reserved namespace like every other context since
  // iteration 2 — the dialog no longer hangs free user input straight under the pod root.
  private fun contextUri(podName: String, contextPath: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/${SempodsUriBuilder.CONTEXT_PATH_PREFIX}$contextPath"

  private fun createContextViaDao(podId: ObjectId, podName: String, contextPath: String) {
    podContextsDao.create(
      podId = podId,
      contextUri = contextUri(podName, contextPath),
      label = null,
      description = null,
      createdBy = "test",
    )
  }

  // ─── prompt parameter tests ───────────────────────────────────────────────

  @Test
  fun `authorize without prompt should show consent UI when no grants exist`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .executeSignedInAs(ownerWebId)

    // Should render consent HTML page (200)
    assertEquals(200, response.statusCode)
    assertTrue(response.responseBody.contains("consent"), "TestHttpResponse should contain consent UI")
  }

  @Test
  fun `authorize without prompt should auto-grant when grants exist`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)

    // Pre-populate grants
    val grantedScope = "${contextUri(pod.name, contextPath)}#read"
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = ownerWebId,
      grants = listOf(grantedScope),
      grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .executeSignedInAs(ownerWebId)

    // Should redirect with auth code (303)
    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location, "Should have Location header")
    val locationUri = URI(location)
    assertTrue(locationUri.query.contains("code="), "Location should contain auth code")
    assertTrue(locationUri.query.contains("state=test-state"), "Location should contain state")
  }

  @Test
  fun `a token the id-server cannot be made to vouch for ends the flow, it does not restart it`() {
    // A login that comes back unusable — expired token, wrong key, unreachable JWKS — must reach
    // the client as an error. Sending the user back to sign in again would loop on whatever made
    // it unusable in the first place.
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      // A token answering some other request: still signed by the id-server, still unexpired, and
      // rejected because its nonce is not the one this flow sent.
      .executeSignedInAs("${FakeIdServerTransport.ISSUER}/e/somebody", nonce = "the-nonce-of-another-login")

    // Must redirect to the app with an OAuth error — not back into a login.
    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.startsWith(testRedirectUri), "Should redirect to app's redirect_uri, not to login")
    // A token that fails verification is an unexpected condition, not a refusal: the id-server
    // answered and the answer was unusable. RFC 6749 §4.1.2.1 calls that `server_error`.
    assertTrue(location.contains("error=server_error"), "Should contain server_error: $location")
  }

  @Test
  fun `authorize with prompt=none should return login_required when no JWT`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "none")
      .setFollowRedirect(false)
      .execute()

    // Should redirect with login_required error
    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("error=login_required"), "Should contain login_required error")
  }

  @Test
  fun `prompt=none never becomes interactive, however much is granted`() {
    // OIDC Core 1.0 §3.1.2.1: `prompt=none` promises the client that nothing will be shown. With
    // no session the pod cannot say who is asking without a round trip to the id-server, which is
    // interactive by definition — so the answer is `login_required`, never a consent screen. The
    // grants below make the point: they would auto-grant on an ordinary request, and still do not
    // buy an answer here.
    //
    // The counterpart is `prompt=none succeeds once the pod remembers the sign-in`, which sends
    // the cookie this one deliberately withholds.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = ownerWebId,
      grants = listOf("${contextUri(pod.name, "public/tasks")}#read"),
      grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "none")
      .setFollowRedirect(false)
      .execute()

    assertEquals(307, response.statusCode)
    val location = checkNotNull(response.getHeader("Location"))
    assertTrue(location.contains("error=login_required"), "must be login_required, got: $location")
    assertFalse(location.contains("code="), "prompt=none must not mint a code without a session")
  }

  @Test
  fun `authorize should auto-grant when grants exist`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)

    // Pre-populate grants
    val grantedScope = "${contextUri(pod.name, contextPath)}#read"
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = ownerWebId,
      grants = listOf(grantedScope),
      grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .executeSignedInAs(ownerWebId)

    // Should redirect with auth code (303)
    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("code="), "Should contain auth code")
  }

  @Test
  fun `authorize preserves a public-read-only grant`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = ownerWebId,
      grants = listOf("public-read"),
      grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .executeSignedInAs(ownerWebId)

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    val code = Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1)
      ?: error("no code in $location")
    val entry = authorizationCodeStore.consume(code)
      ?: error("authorization code not found")
    assertEquals(setOf("public-read"), entry.scopes)
  }

  @Test
  fun `authorize preserves public-read in a mixed grant`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)
    val readScope = "${contextUri(pod.name, contextPath)}#read"

    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = ownerWebId,
      grants = listOf("public-read", readScope),
      grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .executeSignedInAs(ownerWebId)

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    val code = Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1)
      ?: error("no code in $location")
    val entry = authorizationCodeStore.consume(code)
      ?: error("authorization code not found")
    // Slim auth code (token-slimming): only feature scopes travel in the code/token.
    // public-read is preserved here; the per-context grant stays in the durable store.
    assertEquals(setOf("public-read"), entry.scopes)
    val grants = podGrantsDao.fetchGrantStrings(checkNotNull(pod.id), testClientId, listOf(ownerWebId))
    assertTrue(grants.contains(readScope), "context grant must remain in the durable grant store")
    assertTrue(grants.contains("public-read"), "public-read grant must remain in the durable grant store")
  }

  // ─── R6: error_uri on OAuth errors ────────────────────────────────────────

  @Test
  fun `an error redirect carries error_uri exactly when a docs address is configured`() {
    // R6: an error redirect may carry `error_uri` so SDKs can route the user to recovery
    // instructions, the fragment anchoring per-error guidance.
    //
    // Whether it does is deployment configuration (`SEMPODS_OAUTH_ERROR_DOC_BASE`), and this
    // test JVM sets nothing — so what it pins is the *absence*: no configured address, no
    // parameter, rather than a link to a page nobody serves. The shape of the value when one
    // is configured is `SempodsConfigTest`, which can build the rule without an injector.
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      // Any error that reaches the client through a redirect will do; a failed login is the
      // cheapest one to provoke.
      .executeSignedInAs(signAs = null)

    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    val decoded = java.net.URLDecoder.decode(location, "UTF-8")
    val configuredBase = SempodsModule.config.oauthErrorDocBase
    if (configuredBase == null) {
      assertTrue(
        !decoded.contains("error_uri="),
        "with no documentation address configured the redirect must omit error_uri: $decoded",
      )
    } else {
      assertTrue(
        decoded.contains("error_uri=$configuredBase#access_denied"),
        "error_uri must be the configured base + error-code anchor: $decoded",
      )
    }
  }

  @Test
  fun `a redirect_uri carrying its own error_uri does not get it back`() {
    // A registered `redirect_uri` may have a query of its own — `RedirectUri` preserves it — so
    // `error_uri` is one parameter this server does not merely *set*. On a deployment that
    // configured no documentation address, skipping the write would hand a client registered as
    // `…/cb?error_uri=…` its own value back, arriving indistinguishably from one this server
    // chose. Overwrite or delete; never leave somebody else's.
    val pod = sempodsTestFactory.newPod()
    val redirectUri = "http://localhost:5173/callback?error_uri=https://wrong.example"
    val dynClientId = registerDynamicClient(pod.name, redirectUri)

    val response = http.prepareGet(authorizeUrl(pod.name))
      // Rejected before PKCE is looked at, so this needs no code_challenge — and it is a redirect
      // error, which is the only kind that carries `error_uri` at all.
      .addQueryParam("response_type", "token")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", redirectUri)
      .execute()

    assertEquals(307, response.statusCode)
    val location = java.net.URLDecoder.decode(assertNotNull(response.getHeader("Location")), "UTF-8")
    assertTrue(
      location.contains("error=unsupported_response_type"),
      "expected the redirect error this case provokes: $location",
    )
    if (SempodsModule.config.oauthErrorDocBase == null) {
      assertTrue(
        !location.contains("error_uri="),
        "the client's own error_uri must not survive when the deployment configured none: $location",
      )
    } else {
      assertTrue(
        location.contains("error_uri=${SempodsModule.config.oauthErrorDocBase}#"),
        "the client's own error_uri must be overwritten by the configured one: $location",
      )
    }
  }

  @Test
  fun `a dyn client whose registration is gone is told about the registration, not the format`() {
    // Production, 2026-08-21: a credential revoke dropped the pod's DCR rows, and every client
    // still holding a `dyn:` id from before was answered "client_id must be a did:web or dyn:
    // identity" — a complaint about a format that was perfectly fine. The person had no way to
    // read that as "register again", so the only way out was to disconnect and reconnect the pod.
    val pod = sempodsTestFactory.newPod()
    val dynClientId = registerDynamicClient(pod.name)
    assertEquals(
      1L,
      dynamicClientRegistrationDao.deleteByPod(checkNotNull(pod.id)),
      "the registration this client is about to present must actually be gone",
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .setFollowRedirect(false)
      .execute()

    assertEquals(400, response.statusCode, response.responseBody)
    // Still a *direct* 400: the redirect address is not yet known to belong to this client, so an
    // unknown client may not be reported by redirecting there (see the ordering note in authorize).
    assertTrue(response.getHeader("Location").isNullOrBlank(), "must not be delivered by redirect")
    val body = response.responseBody
    assertTrue("invalid_client" in body, "the RFC 6749 code names the actual problem: $body")
    assertTrue("registration_endpoint" in body, "and it must name the way out: $body")
    assertFalse(
      "must be a did:web or dyn" in body,
      "the format was never the problem — that complaint belongs to a malformed id: $body",
    )
  }

  @Test
  fun `a client_id that is neither identity scheme still gets the format complaint`() {
    // The counter-case, so the split above cannot collapse the other way: a genuinely malformed
    // client_id must not be answered with advice about re-registering.
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", "https://apps.example.org/mcp")
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .setFollowRedirect(false)
      .execute()

    assertEquals(400, response.statusCode, response.responseBody)
    assertTrue("must be a did:web or dyn" in response.responseBody, response.responseBody)
    assertFalse("invalid_client" in response.responseBody, response.responseBody)
  }

  @Test
  fun `every callback outcome withdraws the pin it consumed`() {
    // Cookie names carry the flow's `state`, so an abandoned pin is no longer overwritten by the
    // next attempt — it lingers for its full fifteen minutes. Cancelled sign-ins would otherwise
    // pile up on the callback path until the browser evicts cookies, and the session is one of the
    // cookies it could evict. Every terminal answer has to withdraw its own.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    fun startAndCallback(clientState: String, callbackQuery: (String) -> String): TestHttpResponse {
      val started = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", testClientId)
        .addQueryParam("redirect_uri", testRedirectUri)
        .addQueryParam("state", clientState)
        .setFollowRedirect(false).execute()
      val query = org.sempods.commons.net.UrlUtil.queryParams(
        URI(checkNotNull(started.getHeader("Location"))).rawQuery, decodeParams = true,
      )
      val pin = checkNotNull(
        started.headers.getAll("Set-Cookie").firstOrNull { it.startsWith("sempods_pod_login_") },
      ).substringBefore(';')
      fakeIdServerExpect(ownerWebId, checkNotNull(query["nonce"]))
      return http.prepareGet("${checkNotNull(query["redirect_uri"])}?${callbackQuery(checkNotNull(query["state"]))}")
        .addHeader("Cookie", pin).setFollowRedirect(false).execute()
    }

    fun withdrawnPins(response: TestHttpResponse) =
      response.headers.getAll("Set-Cookie")
        .filter { it.startsWith("sempods_pod_login_") && ("Max-Age=0" in it || "max-age=0" in it) }

    // The provider declined.
    val declined = startAndCallback("cancelled") { "state=${java.net.URLEncoder.encode(it, "UTF-8")}&error=user_cancelled_authorize" }
    assertTrue(withdrawnPins(declined).isNotEmpty(), "a declined login must withdraw its pin: ${declined.headers.getAll("Set-Cookie")}")

    // The provider came back with neither a code nor an error.
    val noCode = startAndCallback("no-code") { "state=${java.net.URLEncoder.encode(it, "UTF-8")}" }
    assertTrue(withdrawnPins(noCode).isNotEmpty(), "a codeless callback must withdraw its pin")

    // And the successful one, which already did.
    val ok = startAndCallback("fine") { "state=${java.net.URLEncoder.encode(it, "UTF-8")}&code=c" }
    assertTrue(withdrawnPins(ok).isNotEmpty(), "a completed login must withdraw its pin too")
  }

  @Test
  fun `a technical callback failure is not answered as a refusal`() {
    // `access_denied` used to answer four different things, and a client reading it the reasonable
    // way — "they said no" — suppressed a retry that a transient outage would have survived. RFC
    // 6749 §4.1.2.1 has two codes for the technical paths, and separating them is a change to what
    // every client receives, which is why it lands before the OAuth surface is published.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    var lastDescription: String? = null
    fun callbackWith(clientState: String, extraQuery: (String) -> String): String {
      val started = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", testClientId)
        .addQueryParam("redirect_uri", testRedirectUri)
        .addQueryParam("state", clientState)
        .setFollowRedirect(false).execute()
      val query = org.sempods.commons.net.UrlUtil.queryParams(
        URI(checkNotNull(started.getHeader("Location"))).rawQuery, decodeParams = true,
      )
      val pin = checkNotNull(
        started.headers.getAll("Set-Cookie").firstOrNull { it.startsWith("sempods_pod_login_") },
      ).substringBefore(';')
      fakeIdServerExpect(ownerWebId, checkNotNull(query["nonce"]))
      val answer = http.prepareGet(
        "${checkNotNull(query["redirect_uri"])}?${extraQuery(checkNotNull(query["state"]))}",
      ).addHeader("Cookie", pin).setFollowRedirect(false).execute()
      val location = checkNotNull(answer.getHeader("Location")) { "expected a redirect back to the client" }
      val params = org.sempods.commons.net.UrlUtil.queryParams(URI(location).rawQuery, decodeParams = true)
      lastDescription = params["error_description"]
      return checkNotNull(params["error"]) { "the redirect carried no error: $location" }
    }

    fun state(raw: String) = java.net.URLEncoder.encode(raw, "UTF-8")

    // A person declining stays a refusal — that is the one meaning the code should keep.
    assertEquals(
      "access_denied",
      callbackWith("declined") { "state=${state(it)}&error=access_denied" },
      "a refusal upstream must still read as a refusal",
    )

    // Apple spells the same refusal differently, and it is a refusal.
    assertEquals(
      "access_denied",
      callbackWith("cancelled") { "state=${state(it)}&error=user_cancelled_authorize" },
      "Apple's cancel must read as a refusal",
    )

    // A provider outage is retryable and says so.
    assertEquals(
      "temporarily_unavailable",
      callbackWith("upstream-down") { "state=${state(it)}&error=temporarily_unavailable" },
      "a technical failure upstream must not be flattened into a refusal",
    )

    // The relying-party faults. These mean *this pod* sent a bad authorization request; nobody
    // declined anything, and the client can neither fix it nor be blamed for it.
    for (upstream in listOf("invalid_request", "unauthorized_client", "invalid_scope", "unsupported_response_type", "server_error")) {
      assertEquals(
        "server_error",
        callbackWith("rp-$upstream") { "state=${state(it)}&error=$upstream" },
        "'$upstream' is a fault, not a decision",
      )
    }

    // And the default for a code this pod does not know: a fault rather than a refusal, because
    // an unrecognised string is no evidence that a person said no, and claiming one they did not
    // make is the worse of the two mistakes.
    assertEquals(
      "server_error",
      callbackWith("odd") { "state=${state(it)}&error=something_new_from_a_provider" },
      "an unknown upstream code must not be reported as a decision",
    )
    // Reclassifying must not cost the detail an operator needs: whatever class the client is told,
    // the code the provider actually sent is still in the description.
    assertEquals(
      "something_new_from_a_provider",
      lastDescription,
      "the upstream code must survive the reclassification",
    )


    // Neither a code nor an error: nobody refused anything, the callback is malformed.
    assertEquals(
      "server_error",
      callbackWith("no-code") { "state=${state(it)}" },
      "a codeless callback is this server's problem, not a decision the person made",
    )
  }
  @Test
  fun `a junk state on the callback is a 400, not a cookie-name crash`() {
    // `state` is attacker-supplied and now reaches a cookie *name* — the pin is withdrawn before
    // the state store is consulted, so that every outcome expires it. A name is not a place where
    // arbitrary input belongs: a space or a separator makes rendering `Set-Cookie` throw, and the
    // intended 400 becomes a 500 on a public endpoint.
    val pod = sempodsTestFactory.newPod()
    val callback = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/oidc/callback"

    for (junk in listOf("%20", "a;b", "a=b", "a,b", "a%09b", "ä", "x".repeat(4096))) {
      val response = http.prepareGet("$callback?state=$junk&code=c")
        .setFollowRedirect(false).execute()

      assertEquals(400, response.statusCode, "state='$junk' body=${response.responseBody}")
    }
  }

  @Test
  fun `a login started elsewhere cannot be completed in this browser`() {
    // Login-CSRF / session fixation, the pod's version. The attacker starts a sign-in, gets the
    // callback URL opened in someone else's browser, and — without the pin — that browser comes
    // away holding a session for the attacker's identity, on the attacker's terms. The `state`
    // alone cannot stop it: it is a bearer, and it is in the URL.
    val pod = sempodsTestFactory.newPod()
    val started = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "fixation")
      .setFollowRedirect(false).execute()
    val query = org.sempods.commons.net.UrlUtil.queryParams(URI(checkNotNull(started.getHeader("Location"))).rawQuery, decodeParams = true)
    fakeIdServerExpect("${FakeIdServerTransport.ISSUER}/e/the-attacker", checkNotNull(query["nonce"]))

    // The victim's browser holds no pin for this flow.
    val victim = http.prepareGet(
      "${checkNotNull(query["redirect_uri"])}?state=${java.net.URLEncoder.encode(checkNotNull(query["state"]), "UTF-8")}&code=c",
    ).setFollowRedirect(false).execute()

    assertEquals(400, victim.statusCode, "body=${victim.responseBody}")
    assertNull(victim.sessionCookie(), "no session may be established for a sign-in this browser did not start")
  }

  @Test
  fun `a consent page cannot be submitted twice`() {
    // A submission writes the ticked selection as *the* grant set (`replaceAppGrants`). A form
    // that can be posted again therefore restores a selection the person has since narrowed —
    // consent to A and B, re-consent to A alone, resubmit the old page, and B is back with a fresh
    // code to go with it.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val browser = signIn(pod.name, ownerWebId)
    val token = browser.csrf

    fun submit() = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", browser.cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=once&csrf=${java.net.URLEncoder.encode(token, "UTF-8")}&scope=public-read",
      )
      .setFollowRedirect(false).execute()

    assertEquals(303, submit().statusCode, "the first submission consents")
    assertEquals(403, submit().statusCode, "a replayed page must not consent again")
  }

  @Test
  fun `the lifetime control grants the refresh token, not the scope in the request`() {
    // I1 and I2 together, from the two sides that matter. A client can ask — it preselects the
    // control and nothing more — and a client that never asked is still one the person can grant.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val clear = exchangeCode(pod, codeFrom(submitConsent(pod, ownerWebId, state = "clear")))
    assertNull(clear["refresh_token"], "an unticked control must not produce one: $clear")

    val ticked = exchangeCode(pod, codeFrom(submitConsent(pod, ownerWebId, state = "ticked", durable = true)))
    assertNotNull(ticked["refresh_token"], "a ticked control grants it, whatever the client asked: $ticked")
  }

  @Test
  fun `offline_access in the request only preselects the control`() {
    // The other half of I1, on the page rather than in the token: what the client asks decides how
    // the box is rendered, and a person can clear it.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    fun render(scope: String?) = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "render")
      .addQueryParam("prompt", "consent")
      .apply { if (scope != null) addQueryParam("scope", scope) }
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute().responseBody

    val asked = render("offline_access")
    assertTrue(
      Regex("id=\"durableToggle\"[^>]*checked").containsMatchIn(asked),
      "a request that asks must arrive with the control ticked",
    )
    assertFalse(
      Regex("id=\"durableToggle\"[^>]*checked").containsMatchIn(render(null)),
      "a request that does not ask must arrive with it clear",
    )
  }

  @Test
  fun `an authorization made before the control mints no new refresh token`() {
    // I3: an absent decision is not a grant. A static client whose grants predate the dialog takes
    // the auto-grant branch, which renders nothing — so reading the silence as consent would let it
    // mint ninety-day credentials for ever, with nobody ever seeing the lifetime.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id), appId = testClientId, webId = ownerWebId,
      grants = listOf("${contextUri(pod.name, "public/tasks")}#read"), grantedBy = ownerWebId,
    )

    val autoGranted = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "legacy")
      .addQueryParam("prompt", "none")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute()
    assertEquals(303, autoGranted.statusCode, autoGranted.responseBody)

    val body = exchangeCode(pod, codeFrom(autoGranted))
    assertNull(body["refresh_token"], "nothing recorded is not a grant: $body")
  }

  @Test
  fun `withholding the durable connection revokes what the app already held`() {
    // I7: the choice has to take effect on what exists, not only on what is minted next. Without
    // this, somebody unticks the control, keeps their context grants, and changes nothing they can
    // observe — the family they just declined keeps rotating.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    val held = seedRefreshToken(pod, webId = ownerWebId)

    assertEquals(303, submitConsent(pod, ownerWebId, state = "withheld").statusCode)

    val refreshed = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token&refresh_token=${enc(held.plaintext)}&client_id=${enc(testClientId)}",
    )
    assertEquals(400, refreshed.statusCode, "the declined family must be dead: ${refreshed.responseBody}")
    assertTrue("invalid_grant" in refreshed.responseBody, refreshed.responseBody)
  }

  @Test
  fun `removing access leaves neither the grants nor the refresh token`() {
    // I11. The client is still told `access_denied` — the request really was denied — and what
    // changed is that the denial now has an effect.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    val held = seedRefreshToken(pod, webId = ownerWebId)

    val response = submitConsent(pod, ownerWebId, state = "gone", disconnect = true)

    assertEquals(307, response.statusCode, response.responseBody)
    assertTrue("error=access_denied" in checkNotNull(response.getHeader("Location")))
    assertTrue(
      podGrantsDao.fetchGrantStrings(checkNotNull(pod.id), testClientId, listOf(ownerWebId)).isEmpty(),
      "the app must hold no grant afterwards",
    )
    val refreshed = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token&refresh_token=${enc(held.plaintext)}&client_id=${enc(testClientId)}",
    )
    assertEquals(400, refreshed.statusCode, refreshed.responseBody)
  }

  @Test
  fun `the way out is not offered where there is nothing to remove`() {
    // The narrowing I11 needs: reporting a disconnect of something never connected is the same lie
    // as reporting nothing when something ended.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    fun renderedPage() = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "offer")
      .addQueryParam("prompt", "consent")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute().responseBody

    assertFalse("disconnectBtn" in renderedPage(), "a first authorization has nothing to disconnect")

    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id), appId = testClientId, webId = ownerWebId,
      grants = listOf("${contextUri(pod.name, "public/tasks")}#read"), grantedBy = ownerWebId,
    )
    assertTrue("disconnectBtn" in renderedPage(), "an app that holds something can be removed")
  }

  @Test
  fun `a code cannot pick up a consent granted after it`() {
    // I9. The code is a request, not an authority. Disconnect, reconnect with the durable control
    // ticked, and an outstanding code from the earlier consent would otherwise mint a family — and
    // carry the scopes of a consent that has since been replaced.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val stale = codeFrom(submitConsent(pod, ownerWebId, state = "first"))
    submitConsent(pod, ownerWebId, state = "again", durable = true)

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=authorization_code&code=${enc(stale)}" +
        "&redirect_uri=${enc(testRedirectUri)}&client_id=${enc(testClientId)}",
    )
    assertEquals(400, response.statusCode, response.responseBody)
    assertTrue("invalid_grant" in response.responseBody, response.responseBody)
  }

  @Test
  fun `removing access does not first create the context the form was carrying`() {
    // Choosing the way out is the opposite of an authorization, so nothing is built for one. The
    // form can carry a context the person typed before changing their mind.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    seedRefreshToken(pod, webId = ownerWebId)

    val browser = signIn(pod.name, ownerWebId)
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", browser.cookie)
      .setBody(
        "client_id=${enc(testClientId)}&redirect_uri=${enc(testRedirectUri)}" +
          "&state=bail&csrf=${enc(browser.csrf)}&action=disconnect" +
          "&new_context=notes&new_context_scope=${enc("notes#read")}",
      )
      .setFollowRedirect(false).execute()

    assertEquals(307, response.statusCode, response.responseBody)
    assertNull(
      podContextsDao.fetchByContextUri(checkNotNull(pod.id), contextUri(pod.name, "notes")),
      "a disconnect must not build the context the form was carrying",
    )
  }

  @Test
  fun `an empty submission does not build the context it was carrying either`() {
    // The other route to the way out, and it must not have side effects the named one avoids: with
    // nothing ticked anywhere, no selection can survive the creation, so creating one would build a
    // context for an authorization that is not happening.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    seedRefreshToken(pod, webId = ownerWebId)

    val browser = signIn(pod.name, ownerWebId)
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", browser.cookie)
      .setBody(
        "client_id=${enc(testClientId)}&redirect_uri=${enc(testRedirectUri)}" +
          "&state=empty&csrf=${enc(browser.csrf)}&new_context=notes",
      )
      .setFollowRedirect(false).execute()

    assertEquals(307, response.statusCode, response.responseBody)
    assertNull(
      podContextsDao.fetchByContextUri(checkNotNull(pod.id), contextUri(pod.name, "notes")),
      "an empty submission must not build a context on its way out",
    )
    assertTrue(
      podGrantsDao.fetchGrantStrings(checkNotNull(pod.id), testClientId, listOf(ownerWebId)).isEmpty(),
      "and it still ends the authorization",
    )
  }

  @Test
  fun `the first consent an authorization receives supersedes the codes issued before it`() {
    // The other end of I9. An authorization that predates the control mints codes carrying no
    // generation; the moment somebody answers, those codes are as stale as any other — otherwise
    // one of them redeems against the answer, with the scopes of the silence that came before.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id), appId = testClientId, webId = ownerWebId,
      grants = listOf("${contextUri(pod.name, "public/tasks")}#read"), grantedBy = ownerWebId,
    )

    val silent = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "before")
      .addQueryParam("prompt", "none")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute()
    val stale = codeFrom(silent)

    submitConsent(pod, ownerWebId, state = "answered", durable = true)

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=authorization_code&code=${enc(stale)}" +
        "&redirect_uri=${enc(testRedirectUri)}&client_id=${enc(testClientId)}",
    )
    assertEquals(400, response.statusCode, response.responseBody)
    assertTrue("invalid_grant" in response.responseBody, response.responseBody)
  }

  @Test
  fun `an auto-granted code still redeems after the person has consented once`() {
    // The counter-case, and the bug the binding introduced: the generation embedded at auto-grant
    // has to be the subject's own, not the highest an alias of theirs happens to carry, or a
    // perfectly ordinary silent re-authorization is refused as superseded.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    submitConsent(pod, ownerWebId, state = "consented", durable = true)

    val silent = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "again")
      .addQueryParam("prompt", "none")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute()
    assertEquals(303, silent.statusCode, silent.responseBody)

    val body = exchangeCode(pod, codeFrom(silent))
    assertNotNull(body["refresh_token"], "the durable consent still stands: $body")
  }

  @Test
  fun `a page rendered before a disconnect cannot write its grants back`() {
    // I13. Several consent screens may coexist by design, and single-use only stops the same page
    // being posted twice. A page opened before the app was disconnected would otherwise submit its
    // own older selection as the authoritative new state.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    submitConsent(pod, ownerWebId, state = "first", durable = true)
    val staleForm = renderedFormToken(pod, ownerWebId)
    submitConsent(pod, ownerWebId, state = "gone", disconnect = true)

    val late = submitConsent(pod, ownerWebId, state = "late", durable = true, csrf = staleForm)

    assertEquals(403, late.statusCode, late.responseBody)
    assertTrue(
      podGrantsDao.fetchGrantStrings(checkNotNull(pod.id), testClientId, listOf(ownerWebId)).isEmpty(),
      "the disconnect must stand",
    )
  }

  @Test
  fun `a refusal on record ends the family even where the withdrawal missed it`() {
    // I10, rotation half, at the point the race would leave behind: a family that exists while the
    // decision says the person refused. The sweep sees the rows of its own moment; rotation inserts
    // one after it, so the refusal has to be asked again rather than assumed to have been applied.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    val held = seedRefreshToken(pod, webId = ownerWebId)
    consentDecisionStore.record(checkNotNull(pod.id), testClientId, ownerWebId, durable = false)

    val refreshed = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token&refresh_token=${enc(held.plaintext)}&client_id=${enc(testClientId)}",
    )

    assertEquals(400, refreshed.statusCode, refreshed.responseBody)
    assertTrue("invalid_grant" in refreshed.responseBody, refreshed.responseBody)
    assertTrue(
      refreshTokenStore.findByFamily(held.token.familyId).all { it.revokedAt != null },
      "the family goes with the refusal, not just this rotation",
    )
  }

  @Test
  fun `the response names the durable connection where the client never asked for it`() {
    // I17. The person can grant what the client did not request, so RFC 6749 §3.3's "say what was
    // granted where it differs" is the client's only way to learn it did get one.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val body = exchangeCode(pod, codeFrom(submitConsent(pod, ownerWebId, state = "granted", durable = true)))

    assertNotNull(body["refresh_token"])
    assertTrue(
      (body["scope"] as String).split(" ").contains("offline_access"),
      "the granted scope has to name it: ${body["scope"]}",
    )
    val claim = com.nimbusds.jwt.SignedJWT.parse(body["access_token"] as String).jwtClaimsSet
    assertFalse(
      (claim.getStringClaim("scope") ?: "").contains("offline_access"),
      "and the bearer's own claim stays slim: ${claim.getStringClaim("scope")}",
    )
  }

  @Test
  fun `an app whose grants sit under an alias can still be disconnected`() {
    // I8, lookup half. The pod stores whichever WebID authenticated; asking about one leaves an
    // alias-held authorization reading as a first authorization, which hides the way out exactly
    // where somebody needs it.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val canonical = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val alias = "https://id.test/oidc/alias-of-owner"
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id), appId = testClientId, webId = alias,
      grants = listOf("${contextUri(pod.name, "public/tasks")}#read"), grantedBy = alias,
    )

    val page = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "alias")
      .addQueryParam("prompt", "consent")
      .addHeader("Cookie", signIn(pod.name, canonical, alsoKnownAs = listOf(alias)).cookie)
      .setFollowRedirect(false).execute().responseBody

    assertTrue("disconnectBtn" in page, "an alias-held authorization is one this person can end")
  }

  @Test
  fun `a rotation keeps naming the durable connection it hands back`() {
    // The response is where a client reads what it was granted, so a view refreshed from the newest
    // one must not watch the durable connection vanish at the first rotation — it is still holding
    // a successor.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    val held = seedRefreshToken(pod, webId = ownerWebId)

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token&refresh_token=${enc(held.plaintext)}&client_id=${enc(testClientId)}",
    )

    assertEquals(200, response.statusCode, response.responseBody)
    @Suppress("UNCHECKED_CAST")
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    assertNotNull(body["refresh_token"])
    assertTrue(
      (body["scope"] as String).split(" ").contains("offline_access"),
      "a rotation still hands back a durable connection: ${body["scope"]}",
    )
  }

  @Test
  fun `a client may echo the granted scope back on the next refresh`() {
    // The standard thing to do with a `scope` in a token response is to send it again, and the
    // response now names the durable connection — so refusing that echo would tell a client its own
    // granted scope is not covered. `offline_access` is not a feature scope and cannot be
    // down-scoped to; it names the connection this request is already proving it holds.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")
    val held = seedRefreshToken(pod, webId = ownerWebId, scopes = setOf("public-read"))

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token&refresh_token=${enc(held.plaintext)}&client_id=${enc(testClientId)}" +
        "&scope=${enc("public-read offline_access")}",
    )

    assertEquals(200, response.statusCode, response.responseBody)
    @Suppress("UNCHECKED_CAST")
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    assertEquals("public-read offline_access", body["scope"], "what it echoed is what it gets back")
    assertEquals(
      "public-read",
      com.nimbusds.jwt.SignedJWT.parse(body["access_token"] as String).jwtClaimsSet.getStringClaim("scope"),
      "and the bearer carries the feature scope alone",
    )
    assertNotNull(body["refresh_token"])
  }

  /** Submits the consent form the way the rendered page does. */
  private fun submitConsent(
    pod: org.sempods.pods.mongo.persist.PodDbo,
    webId: String,
    state: String,
    durable: Boolean = false,
    disconnect: Boolean = false,
    csrf: String? = null,
  ): org.sempods.commons.okhttp.TestHttpResponse {
    val browser = signIn(pod.name, webId)
    return http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", browser.cookie)
      .setBody(
        "client_id=${enc(testClientId)}&redirect_uri=${enc(testRedirectUri)}" +
          "&state=$state&csrf=${enc(csrf ?: renderedFormToken(pod, webId))}" +
          (if (disconnect) "&action=disconnect" else "&scope=public-read") +
          (if (durable) "&durable=1" else ""),
      )
      .setFollowRedirect(false).execute()
  }

  /** The token a freshly rendered page would carry: bound to the consent standing right now. */
  private fun renderedFormToken(pod: org.sempods.pods.mongo.persist.PodDbo, webId: String): String =
    consentTransactionStore.issue(
      pod.name,
      webId,
      consentDecisionStore.find(checkNotNull(pod.id), testClientId, listOf(webId))?.generation,
    )

  private fun codeFrom(response: org.sempods.commons.okhttp.TestHttpResponse): String {
    val location = checkNotNull(response.getHeader("Location")) { "no redirect: ${response.responseBody}" }
    return Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1) ?: error("no code in $location")
  }

  @Suppress("UNCHECKED_CAST")
  private fun exchangeCode(pod: org.sempods.pods.mongo.persist.PodDbo, code: String): Map<String, Any?> {
    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=authorization_code&code=${enc(code)}" +
        "&redirect_uri=${enc(testRedirectUri)}&client_id=${enc(testClientId)}",
    )
    assertEquals(200, response.statusCode, response.responseBody)
    return JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
  }

  private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

  @Test
  fun `a consent token from one person is not spendable by another`() {
    // Binding to the session is what makes the token useless once it leaves the page it was
    // rendered in — a screenshot, a shared terminal, a bug report.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val stranger = signIn(pod.name, "${FakeIdServerTransport.ISSUER}/e/somebody-else")

    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", stranger.cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=stolen&csrf=${java.net.URLEncoder.encode(signIn(pod.name, ownerWebId).csrf, "UTF-8")}" +
          "&scope=public-read",
      )
      .setFollowRedirect(false).execute()

    assertEquals(403, response.statusCode, "body=${response.responseBody}")
  }

  @Test
  fun `two sign-ins running at once in one browser both complete`() {
    // Two tabs, two apps, neither finished. With one fixed pin cookie the second redirect
    // overwrites the first pin, the first callback fails *and clears the shared cookie*, and the
    // second then finds nothing either — one concurrent login breaks both.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    fun start(clientState: String): Pair<Map<String, String>, String> {
      val started = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", testClientId)
        .addQueryParam("redirect_uri", testRedirectUri)
        .addQueryParam("state", clientState)
        .setFollowRedirect(false).execute()
      val query = org.sempods.commons.net.UrlUtil.queryParams(
        URI(checkNotNull(started.getHeader("Location"))).rawQuery, decodeParams = true,
      )
      val pin = checkNotNull(
        started.headers.getAll("Set-Cookie").firstOrNull { it.startsWith("sempods_pod_login_") },
      ).substringBefore(';')
      return query to pin
    }

    // Both started before either came back — the browser now holds both pins.
    val (firstQuery, firstPin) = start("tab-one")
    val (secondQuery, secondPin) = start("tab-two")
    val bothPins = "$firstPin; $secondPin"

    fun complete(query: Map<String, String>): TestHttpResponse {
      fakeIdServerExpect(ownerWebId, checkNotNull(query["nonce"]))
      return http.prepareGet(
        "${checkNotNull(query["redirect_uri"])}?state=${java.net.URLEncoder.encode(checkNotNull(query["state"]), "UTF-8")}&code=c",
      ).addHeader("Cookie", bothPins).setFollowRedirect(false).execute()
    }

    val firstPage = complete(firstQuery)
    val secondPage = complete(secondQuery)
    assertEquals(200, firstPage.statusCode, "the first tab must complete")
    assertEquals(200, secondPage.statusCode, "and so must the second, which the first must not have disarmed")

    // …and both consent screens must still be submittable. Completing the callbacks is not the
    // interesting half: the second sign-in replaces the per-pod session cookie, so a consent token
    // derived from the session would leave only the last page usable.
    fun submit(page: TestHttpResponse, clientState: String): Int {
      val token = checkNotNull(Regex("""name="csrf" value="([^"]+)"""").find(page.responseBody)) {
        "consent page carries no token"
      }.groupValues[1]
      return http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        // The browser holds the session the *second* callback established.
        .addHeader("Cookie", checkNotNull(secondPage.sessionCookie()))
        .setBody(
          "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
            "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
            "&state=$clientState&csrf=${java.net.URLEncoder.encode(token, "UTF-8")}&scope=public-read",
        )
        .setFollowRedirect(false).execute().statusCode
    }

    assertEquals(303, submit(firstPage, "tab-one"), "the older tab's consent must still be submittable")
    assertEquals(303, submit(secondPage, "tab-two"), "and so must the newer one's")
  }

  @Test
  fun `an arbitrary session cookie is refused, not answered with a 500`() {
    // The cookie is attacker-supplied and reaches a public endpoint. A JWT whose *custom* claim
    // carries the wrong JSON type parses fine — `JWTClaimsSet.parse` only type-checks the
    // registered claims — and Nimbus' typed getter then throws, so reading it before the signature
    // turned any cookie into a 500. Nothing may interpret those claims until they are known ours.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val header = java.util.Base64.getUrlEncoder().withoutPadding()
      .encodeToString("""{"alg":"RS256"}""".toByteArray())
    val payload = java.util.Base64.getUrlEncoder().withoutPadding()
      .encodeToString("""{"iss":"x","sub":"y","token_use":[],"also_known_as":"not-a-list"}""".toByteArray())

    for (cookie in listOf("$header.$payload.bogus-signature", "not-a-jwt-at-all", "a.b.c")) {
      val response = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", testClientId)
        .addQueryParam("redirect_uri", testRedirectUri)
        .addQueryParam("state", "junk-cookie")
        .addHeader("Cookie", "sempods_pod_session=$cookie")
        .setFollowRedirect(false).execute()

      // Treated as no session at all: off to the id-server, exactly like a first visit.
      assertEquals(307, response.statusCode, "cookie='$cookie' body=${response.responseBody}")
      assertTrue(
        checkNotNull(response.getHeader("Location")).startsWith(FakeIdServerTransport.ISSUER),
        "an unusable cookie must mean 'not signed in', not an error",
      )
    }
  }

  @Test
  fun `a completed sign-in is remembered, so the next authorize needs no round trip`() {
    // What the session is for. Without it every authorization runs the whole way to the id-server
    // again, and `prompt=none` can never succeed.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val first = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "s1")
      .executeSignedInAs(ownerWebId)
    val session = checkNotNull(first.sessionCookie())

    // Second authorization, same browser: straight to consent, no id-server in between.
    val second = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "s2")
      .addHeader("Cookie", session)
      .setFollowRedirect(false).execute()

    assertEquals(200, second.statusCode, "a remembered sign-in must reach consent directly")
    assertTrue(second.responseBody.contains("consent"), second.responseBody)
  }

  @Test
  fun `prompt=none succeeds once the pod remembers the sign-in`() {
    // The item this closes: silent re-authorization was impossible while the pod kept nothing
    // between requests, and it only ever appeared to work when the identity sat in the URL.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id), appId = testClientId, webId = ownerWebId,
      grants = listOf("${contextUri(pod.name, contextPath)}#read"), grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "silent")
      .addQueryParam("prompt", "none")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute()

    assertEquals(303, response.statusCode, "body=${response.responseBody}")
    val location = checkNotNull(response.getHeader("Location"))
    assertTrue("code=" in location, "a remembered sign-in must answer prompt=none with a code: $location")
  }

  @Test
  fun `prompt=none still fails for a dynamic client, session or not`() {
    // A `dyn:` client always gets the consent screen, so it never auto-grants — and the AI clients
    // reaching a pod through the hosted MCP service are all `dyn:`. The session removes the round
    // trip to the id-server; it does not make silent authorization possible for them.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)
    val dynClientId = registerDynamicClient(pod.name)
    // …and a grant, so the only thing standing between this and a code is the client being `dyn:`.
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id), appId = dynClientId, webId = ownerWebId,
      grants = listOf("${contextUri(pod.name, contextPath)}#read"), grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", "http://localhost:5173/callback")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .addQueryParam("state", "silent-dyn")
      .addQueryParam("prompt", "none")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute()

    assertEquals(307, response.statusCode)
    val location = checkNotNull(response.getHeader("Location"))
    assertTrue("error=consent_required" in location, location)
    assertFalse("code=" in location, "a dynamic client must not be auto-granted: $location")
  }

  @Test
  fun `prompt=none fails when the session carries no grants for the client`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "silent-no-grants")
      .addQueryParam("prompt", "none")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute()

    assertEquals(307, response.statusCode)
    assertTrue("error=consent_required" in checkNotNull(response.getHeader("Location")))
  }

  @Test
  fun `prompt=login is not satisfied by a session`() {
    // The person asked to prove themselves again; a cookie is exactly what they are bypassing.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "reauth")
      .addQueryParam("prompt", "login")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setFollowRedirect(false).execute()

    assertEquals(307, response.statusCode)
    assertTrue(
      checkNotNull(response.getHeader("Location")).startsWith("${FakeIdServerTransport.ISSUER}/authorize"),
      "must go back to the id-server despite the session",
    )
  }

  @Test
  fun `the consent form is refused without its CSRF token, and with somebody else's`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val browser = signIn(pod.name, ownerWebId)
    val other = signIn(pod.name, "${FakeIdServerTransport.ISSUER}/e/somebody-else")

    fun post(csrf: String?) = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", browser.cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=csrf" + (csrf?.let { "&csrf=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "") +
          "&scope=public-read",
      )
      .setFollowRedirect(false).execute()

    assertEquals(403, post(null).statusCode, "a form with no token must be refused")
    // A token from another session is as good as none — the value is compared, not its presence.
    assertEquals(403, post(other.csrf).statusCode)
  }

  // ─── R1: prompt=login / prompt=select_account ─────────────────────────────

  @Test
  fun `the whole flow, from an unauthenticated authorize to a redeemable code`() {
    // Sign-in end to end, with nothing shortcut: `/authorize` parks the request and sends the
    // browser to the id-server; the callback exchanges a code for an identity over the back
    // channel and establishes a session on this pod; the consent screen carries that session's
    // CSRF token and nothing that authenticates anyone; submitting both mints a code.
    //
    // Also what keeps `signIn` honest — every other consent test mints its session directly, and
    // this is the one that proves the rendered page and the real cookie work together.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)

    val consentPage = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "end-to-end")
      .executeSignedInAs(ownerWebId)

    assertEquals(200, consentPage.statusCode)
    val html = consentPage.responseBody
    val csrf = Regex("""name="csrf" value="([^"]+)"""").find(html)?.groupValues?.get(1)
      ?: error("the consent form carries no CSRF token: $html")
    // The identity is named for the user to read, but nothing on the page authenticates anyone.
    assertFalse(html.contains("""name="token""""), "the form must not carry an identity token")
    // The sign-in established a session here; the form is only submittable together with it.
    val sessionCookie = checkNotNull(consentPage.sessionCookie()) {
      "a completed sign-in must establish a session on the pod"
    }

    val submitted = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", sessionCookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=end-to-end" +
          "&csrf=${java.net.URLEncoder.encode(csrf, "UTF-8")}" +
          "&scope=${java.net.URLEncoder.encode("${contextUri(pod.name, contextPath)}#read", "UTF-8")}",
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, submitted.statusCode)
    val code = Regex("[?&]code=([^&]+)").find(checkNotNull(submitted.getHeader("Location")))?.groupValues?.get(1)
      ?: error("no authorization code in ${submitted.getHeader("Location")}")
    assertEquals(ownerWebId, authorizationCodeStore.consume(code)?.subject, "the code must belong to who signed in")
  }

  @Test
  fun `a replayed login callback finds no parked request`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val started = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "replay")
      .setFollowRedirect(false)
      .execute()
    val query = org.sempods.commons.net.UrlUtil.queryParams(URI(checkNotNull(started.getHeader("Location"))).rawQuery, decodeParams = true)
    fakeIdServerExpect(ownerWebId, checkNotNull(query["nonce"]))
    val callback = "${checkNotNull(query["redirect_uri"])}?state=${java.net.URLEncoder.encode(checkNotNull(query["state"]), "UTF-8")}&code=c"
    // The same browser throughout, pin included — the replay being tested is of the *state*, not
    // of a callback opened somewhere else, which the pin refuses for its own reason.
    val pin = checkNotNull(started.headers.getAll("Set-Cookie").firstOrNull { it.startsWith("sempods_pod_login_") }).substringBefore(';')

    fun call() = http.prepareGet(callback).addHeader("Cookie", pin).setFollowRedirect(false).execute()
    assertEquals(200, call().statusCode, "the first callback completes the login")
    // The parked request is consumed with the state. A captured callback URL, replayed later,
    // must not sign anyone in a second time.
    assertEquals(400, call().statusCode, "a replayed callback must find nothing")
  }

  @Test
  fun `the login redirect is an authorization request, and it forwards prompt`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "login")
      .setFollowRedirect(false)
      .execute()

    assertEquals(307, response.statusCode)
    val location = checkNotNull(response.getHeader("Location"))
    val query = org.sempods.commons.net.UrlUtil.queryParams(URI(location).rawQuery, decodeParams = true)
    assertTrue(location.startsWith("${FakeIdServerTransport.ISSUER}/authorize"), "must go to the id-server's authorize endpoint: $location")
    assertEquals("code", query["response_type"], "the implicit flow is gone")
    assertEquals("S256", query["code_challenge_method"], "PKCE is not optional")
    assertNotNull(query["nonce"], "the token must be bound to this request")
    assertEquals("login", query["prompt"], "prompt must reach the id-server: $location")
    // The parameter that let the id-server deliver an identity token to an address of the
    // caller's choosing. Nothing replaces it: the pod's callback is fixed, and the request that
    // is waiting for it lives on the server.
    assertNull(query["return_to"], "no return_to may appear in an authorization request: $location")
    assertEquals(
      "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/oidc/callback",
      query["redirect_uri"],
      "the callback is this server's own, not something the request named",
    )
  }

  @Test
  fun `authorize with prompt=select_account forwards select_account to login redirect`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "select_account")
      .setFollowRedirect(false)
      .execute()

    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    // URL-encoded value of `select_account` is `select_account` (underscores need no encoding).
    assertTrue(location.contains("prompt=select_account"), "Login URL must carry prompt=select_account: $location")
  }

  @Test
  fun `a completed prompt=login does not ask for another one`() {
    // R1 hardening, restated for the flow that replaced `return_to`. `prompt=login` is satisfied
    // by the sign-in it triggers, so the request that resumes after the callback must not carry it
    // any more — otherwise the pod would forward it again and the user would loop through the
    // id-server forever. The old shape had the same bug in a different place: a stale `token=`
    // left in `return_to`, which JAX-RS then preferred over the fresh one.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "login")
      .executeSignedInAs(ownerWebId)

    // The consent screen, not a second trip to the id-server.
    assertEquals(200, response.statusCode, "a second login redirect means prompt=login survived the first")
    assertTrue(response.responseBody.contains("consent"), "should reach consent after the login it asked for")
  }

  @Test
  fun `prompt=login re-authenticates even for someone the pod already knows`() {
    // R1 core: the user's intent is to re-authenticate, so the flow goes to the id-server rather
    // than continuing on whatever identity is around. What "already knows" means changed with the
    // flow — it used to be a token in the URL — but the rule did not.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email)),
      grants = listOf("public-read"),
      grantedBy = "test",
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "login")
      .setFollowRedirect(false)
      .execute()

    assertEquals(307, response.statusCode)
    val location = checkNotNull(response.getHeader("Location"))
    val query = org.sempods.commons.net.UrlUtil.queryParams(URI(location).rawQuery, decodeParams = true)
    assertTrue(location.startsWith("${FakeIdServerTransport.ISSUER}/authorize"), "must reach the id-server: $location")
    assertEquals("login", query["prompt"], "prompt=login must reach the id-server: $location")
  }

  @Test
  fun `prompt=login and prompt=consent are both honoured`() {
    // OIDC §3.1.2.1: prompt values combine. `login` causes the re-auth and is satisfied by it;
    // `consent` is not, so it has to survive the round trip and make the consent screen render
    // afterwards. Both used to ride in a `return_to` the id-server echoed back; they now ride in
    // the parked request, which is why "survives" is asserted on the outcome rather than on a URL.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)
    // A grant that would auto-grant on its own. `prompt=consent` must override that.
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = ownerWebId,
      grants = listOf("${contextUri(pod.name, contextPath)}#read"),
      grantedBy = ownerWebId,
    )

    val first = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "login consent")
      .setFollowRedirect(false)
      .execute()

    assertEquals(307, first.statusCode)
    val query = org.sempods.commons.net.UrlUtil.queryParams(URI(checkNotNull(first.getHeader("Location"))).rawQuery, decodeParams = true)
    assertEquals("login", query["prompt"], "the id-server is asked for the re-auth, and only for that")

    // Complete the login the way the browser would, and land on consent rather than on a code.
    val afterLogin = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "login consent")
      .executeSignedInAs(ownerWebId)

    assertEquals(200, afterLogin.statusCode, "prompt=consent must survive the login it did not satisfy")
    assertTrue(afterLogin.responseBody.contains("consent"), "should render the consent screen, not auto-grant")
  }

  @Test
  fun `authorize with prompt=none combined with login returns invalid_request per spec`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "none login")
      .setFollowRedirect(false)
      .execute()

    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.startsWith(testRedirectUri), "Spec error must redirect to app: $location")
    assertTrue(location.contains("error=invalid_request"), "prompt=none must not combine: $location")
  }

  // ─── R2.1 scope-param plumbing (PR1) ──────────────────────────────────────

  @Test
  fun `authorize with scope=public-read and prompt=consent renders the consent UI with public-read preselected`() {
    // R7: an explicit prompt=consent request renders the consent UI with the
    // public-read toggle pre-checked. The silent-issuance path is bypassed,
    // so the user can confirm the active identity (or change it) before the
    // token is issued.
    val pod = sempodsTestFactory.newPod()
    val anyUser = sempodsTestFactory.newOwner()
    val webId = webIdUriDeriver.deriveFromEmail(checkNotNull(anyUser.email))

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("scope", "public-read")
      .addQueryParam("prompt", "consent")
      .executeSignedInAs(webId)

    assertEquals(200, response.statusCode)
    assertTrue(
      response.contentType?.startsWith("text/html") == true,
      "Should render consent HTML, was: ${response.contentType}",
    )
    val body = response.responseBody
    assertTrue(body.contains("Public-read access"), "Body must show public-read section: $body")
    assertTrue(
      body.contains("id=\"publicReadToggle\""),
      "Body must contain the public-read toggle: $body",
    )
    // Toggle must be pre-checked when the request scope said public-read.
    val toggleBlock = body.substringAfter("id=\"publicReadToggle\"").substringBefore("/>")
    assertTrue(toggleBlock.contains("checked"), "publicReadToggle must be checked: $toggleBlock")
  }

  @Test
  fun `authorize with scope=public-read and valid JWT renders consent UI`() {
    // R7 additive model: authenticated public-read is a normal user-controlled
    // grant. It must go through consent so the public-read choice can be
    // persisted and later refreshed like any other grant.
    val pod = sempodsTestFactory.newPod()
    val anyUser = sempodsTestFactory.newOwner()
    val webId = webIdUriDeriver.deriveFromEmail(checkNotNull(anyUser.email))

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("scope", "public-read")
      .executeSignedInAs(webId)

    assertEquals(200, response.statusCode)
    assertTrue(response.contentType?.startsWith("text/html") == true)
    val body = response.responseBody
    assertTrue(body.contains("Public-read access"), "Body must show public-read section: $body")
    val toggleBlock = body.substringAfter("id=\"publicReadToggle\"").substringBefore("/>")
    assertTrue(toggleBlock.contains("checked"), "publicReadToggle must be checked: $toggleBlock")
  }

  @Test
  fun `authorize without JWT and additive public-read scope returns login_required`() {
    // R7 additive model: `public-read <context>#read` is structurally valid.
    // Without an identity, prompt=none must therefore fail as login_required,
    // not as invalid_scope.
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "none")
      .addQueryParam(
        "scope",
        "public-read ${contextUri(pod.name, "public/tasks")}#read",
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("error=login_required"), "Should report login_required: $location")
    assertTrue(
      !location.contains("error=invalid_scope"),
      "Additive public-read scopes must not be rejected as invalid_scope",
    )
  }

  @Test
  fun `a failed login is not laundered into an anonymous public-read code`() {
    // Regression: `scope=public-read` has an anonymous path, and a login that failed must not fall
    // into it. The person tried to identify themselves and it did not work — answering with a
    // token anyway, under an anonymous subject, would look to the client like a successful
    // authorization.
    val pod = sempodsTestFactory.newPod()
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("scope", "public-read")
      .executeSignedInAs(signAs = null)

    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    // `server_error` and not `access_denied`: the sign-in did not complete, which is this server's
    // problem to answer for — nobody declined anything, and a client must not record a decision.
    assertTrue(location.contains("error=server_error"), "a failed login must surface as an error: $location")
    assertFalse(location.contains("code="), "no authorization code may be issued: $location")
  }

  @Test
  fun `authorize with scope=public-read combined with other scopes renders consent UI`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam(
        "scope",
        "public-read ${contextUri(pod.name, "public/tasks")}#read",
      )
      .executeSignedInAs(ownerWebId)

    assertEquals(200, response.statusCode)
    assertTrue(response.contentType?.startsWith("text/html") == true)
    val body = response.responseBody
    assertTrue(body.contains("Public-read access"), "Body must show public-read section: $body")
    val toggleBlock = body.substringAfter("id=\"publicReadToggle\"").substringBefore("/>")
    assertTrue(toggleBlock.contains("checked"), "publicReadToggle must be checked: $toggleBlock")
    assertTrue(
      body.contains("${contextUri(pod.name, "public/tasks")}#read"),
      "Per-context scope must remain selectable alongside public-read: $body",
    )
  }

  // ─── R2.0 soft-fail (PR1) ─────────────────────────────────────────────────

  @Test
  fun `non-owner without grants on pod with public contexts gets consent UI with public-read preselected`() {
    // R7 hardening: a stranger reaching a pod with public-read content (but no
    // per-context grants of their own) must land in the interactive consent UI
    // with the public-read toggle pre-selected — NOT in the consent_required
    // error redirect that previous releases used.
    val pod = sempodsTestFactory.newPod()
    val nonOwnerUser = sempodsTestFactory.newOwner()
    val webId = webIdUriDeriver.deriveFromEmail(checkNotNull(nonOwnerUser.email))

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "r7-state")
      .executeSignedInAs(webId)

    assertEquals(200, response.statusCode)
    assertTrue(response.contentType?.startsWith("text/html") == true)
    val body = response.responseBody
    assertTrue(body.contains("Public-read access"), "Public-read section must be visible: $body")
    val toggleBlock = body.substringAfter("id=\"publicReadToggle\"").substringBefore("/>")
    assertTrue(toggleBlock.contains("checked"), "Public-read toggle must be pre-checked: $toggleBlock")
  }

  @Test
  fun `non-owner with an owner-level WebID grant can delegate that context at consent`() {
    // The unlock: an owner-level, app-independent grant in `webIdGrants` makes a
    // non-owner's `resolveUserGrants` non-empty, so the consent submission for that context
    // is honored (303 + auth code) and the app-delegated grant is persisted. Before wiring
    // the store, a non-owner resolved to an empty scope set and this returned access_denied
    // (see the regression test below).
    val pod = sempodsTestFactory.newPod()
    val nonOwnerUser = sempodsTestFactory.newOwner()
    val nonOwnerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(nonOwnerUser.email))

    // A private context the owner grants the non-owner read access to.
    createContextViaDao(checkNotNull(pod.id), pod.name, "private/reports")
    val grantedScope = "${contextUri(pod.name, "private/reports")}#read"
    podWebIdGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      webId = nonOwnerWebId,
      grants = listOf(grantedScope),
      grantedBy = "https://id.sempods.org/e/owner",
    )

    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"
    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, nonOwnerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=granted" +
          "&csrf=${java.net.URLEncoder.encode(signIn(pod.name, nonOwnerWebId).csrf, "UTF-8")}" +
          "&scope=${java.net.URLEncoder.encode(grantedScope, "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("code="), "Should redirect with an auth code, got: $location")

    // The app-delegated grant is now persisted for this (client, non-owner WebID).
    val savedGrants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(nonOwnerWebId),
    )
    assertTrue(savedGrants.contains(grantedScope), "delegated grant should be persisted, got: $savedGrants")
  }

  @Test
  fun `non-owner with a write-only WebID grant can delegate read-only access`() {
    // A `#write` grant implies read everywhere else in the authorization layer, so a
    // write-authority user must be able to delegate read-only (`#read`) access — not be
    // forced to over-grant `#write`. `resolveUserGrants` therefore surfaces the implied read.
    val pod = sempodsTestFactory.newPod()
    val nonOwnerUser = sempodsTestFactory.newOwner()
    val nonOwnerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(nonOwnerUser.email))

    createContextViaDao(checkNotNull(pod.id), pod.name, "private/reports")
    // Owner-level grant is write-only.
    podWebIdGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      webId = nonOwnerWebId,
      grants = listOf("${contextUri(pod.name, "private/reports")}#write"),
      grantedBy = "https://id.sempods.org/e/owner",
    )
    // The app delegation requested is read-only.
    val readScope = "${contextUri(pod.name, "private/reports")}#read"

    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"
    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, nonOwnerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=implied-read" +
          "&csrf=${java.net.URLEncoder.encode(signIn(pod.name, nonOwnerWebId).csrf, "UTF-8")}" +
          "&scope=${java.net.URLEncoder.encode(readScope, "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("code="), "Read-only delegation should succeed, got: $location")

    // Only read was delegated — the write authority was not forced onto the app.
    val savedGrants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(nonOwnerWebId),
    )
    assertEquals(setOf(readScope), savedGrants, "only the read scope should be delegated, got: $savedGrants")
  }

  @Test
  fun `non-owner without an owner-level WebID grant is denied delegating a private context`() {
    // Regression companion to the test above: the same consent submission WITHOUT the
    // owner-level grant must be rejected — `resolveUserGrants` is empty for the non-owner,
    // so the requested private-context scope is filtered out and no access is granted.
    val pod = sempodsTestFactory.newPod()
    val nonOwnerUser = sempodsTestFactory.newOwner()
    val nonOwnerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(nonOwnerUser.email))

    createContextViaDao(checkNotNull(pod.id), pod.name, "private/reports")
    val requestedScope = "${contextUri(pod.name, "private/reports")}#read"

    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"
    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, nonOwnerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=denied" +
          "&csrf=${java.net.URLEncoder.encode(signIn(pod.name, nonOwnerWebId).csrf, "UTF-8")}" +
          "&scope=${java.net.URLEncoder.encode(requestedScope, "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("error=access_denied"), "Should reject with access_denied, got: $location")

    val savedGrants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(nonOwnerWebId),
    )
    assertFalse(savedGrants.contains(requestedScope), "no grant should be persisted, got: $savedGrants")
  }

  @Test
  fun `consent submission with scope=public-read replaces stale per-context grants with public-read only`() {
    // R7 (additive model): a submission with only `scope=public-read` ticked
    // is processed via the standard `replaceGrants` — pre-existing
    // per-context grants get replaced by the new `{public-read}` set, since
    // the user did not re-tick them.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "main"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)

    val staleScopes = listOf(
      "${contextUri(pod.name, contextPath)}#read",
      "${contextUri(pod.name, contextPath)}#write",
    )
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = ownerWebId,
      grants = staleScopes,
      grantedBy = ownerWebId,
    )

    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"
    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=r7-additive" +
          "&csrf=${signIn(pod.name, ownerWebId).csrf}" +
          "&scope=public-read"
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("code="), "Should issue auth code: $location")

    // After submit: persisted grants = exactly { public-read } — the per-context
    // grants the user did not re-tick are revoked by replaceGrants.
    val remainingGrants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(ownerWebId),
    )
    assertEquals(setOf("public-read"), remainingGrants, "Grants must equal {public-read}, was: $remainingGrants")
  }

  @Test
  fun `consent submission combining scope=public-read with per-context scope persists both as additive grants`() {
    // R7 (additive model): public-read can be combined with per-context grants.
    // Both are persisted; the issued token carries both scopes.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "main"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)
    val readScope = "${contextUri(pod.name, contextPath)}#read"

    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"
    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=r7-additive" +
          "&csrf=${signIn(pod.name, ownerWebId).csrf}" +
          "&scope=public-read" +
          "&scope=${java.net.URLEncoder.encode(readScope, "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("code="), "Should issue auth code: $location")

    val grants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(ownerWebId),
    )
    assertEquals(setOf("public-read", readScope), grants, "Both scopes must be persisted, was: $grants")
  }

  @Test
  fun `consent submission with scope=public-read AND new_context creates the context and persists both grants`() {
    // R7 (additive model): public-read + new_context is a valid combination.
    // The new context is created, public-read + the new context's scopes
    // are both persisted.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val newContextPath = "additive-context"
    val newContextUri = contextUri(pod.name, newContextPath)
    val newContextRead = "$newContextUri#read"

    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"
    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=r7-additive" +
          "&csrf=${signIn(pod.name, ownerWebId).csrf}" +
          "&scope=public-read" +
          "&new_context=$newContextPath" +
          "&new_context_scope=${java.net.URLEncoder.encode("$newContextPath#read", "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("code="), "Should issue auth code: $location")

    val createdContexts = podContextsDao.fetchByPod(checkNotNull(pod.id))
    assertTrue(
      createdContexts.any { it.contextUri == newContextUri },
      "New context must have been created, was: $createdContexts",
    )
    val grants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(ownerWebId),
    )
    assertEquals(setOf("public-read", newContextRead), grants, "Both scopes must be persisted, was: $grants")
  }

  @Test
  fun `consent should create new contexts and include them in scopes`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"

    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${testClientId}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=test-state" +
          "&csrf=${signIn(pod.name, ownerWebId).csrf}" +
          "&new_context=public/tasks" +
          "&new_context=projects/notes" +
          "&new_context_scope=${java.net.URLEncoder.encode("public/tasks#read", "UTF-8")}" +
          "&new_context_scope=${java.net.URLEncoder.encode("projects/notes#write", "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    // Should redirect with auth code after creating contexts and granting scopes
    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location, "Should have Location header")
    assertTrue(location.contains("code="), "Location should contain auth code")

    // Verify contexts were actually created
    val podId = checkNotNull(pod.id)
    val contexts = podContextsDao.fetchByPod(podId)
    assertTrue(contexts.any { it.contextUri.endsWith("public/tasks") }, "public/tasks context should exist")
    assertTrue(contexts.any { it.contextUri.endsWith("projects/notes") }, "notes context should exist")
    // Both landed inside the reserved area — the dialog no longer writes into the resource
    // namespace, where a context IRI doubles as an addressable resource IRI.
    assertTrue(
      contexts.filter { it.contextUri.endsWith("tasks") || it.contextUri.endsWith("notes") }
        .all { it.contextUri.contains("/${SempodsUriBuilder.CONTEXT_PATH_PREFIX}") },
      "consent-created contexts must carry the reserved prefix: ${contexts.map { it.contextUri }}",
    )
  }

  @Test
  fun `a new context is granted with the value the rendered form actually posts`() {
    // The other consent tests hand-write the form body, which is how a mismatch between template
    // and endpoint stayed invisible: the browser posted `podBaseUrl + path + '#' + perm` while the
    // server had started prefixing, so every checkbox on a newly created context was filtered out
    // and the authorization ended in `access_denied`.
    //
    // So this test does not construct the value. It renders the real page and reads the two things
    // the template contributes — the field name and the shape of the value — out of the HTML. What
    // the JS does with them is one string concatenation; what it must not do is build an IRI, and
    // the assertion below fails if it starts again.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val page = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "form-truth")
      .executeSignedInAs(ownerWebId)
    assertEquals(200, page.statusCode, "the owner must get the consent dialog")
    val html = page.responseBody

    // The permission checkboxes of a pending context carry the relative path, so the pod URL must
    // not appear in the value the template ships — nor in the JS that fills it in.
    val scriptBody = html.substringAfter("window.addContext").substringBefore("</script>")
    assertTrue(
      scriptBody.contains("""cb.value = value + '#'"""),
      "the form must post `<relative-path>#<permission>`; the server is the only IRI builder:\n$scriptBody",
    )
    assertTrue(
      html.contains("""name="new_context_scope""""),
      "a pending context's checkboxes must post under their own field name:\n$html",
    )

    // Now submit exactly what that produces for the input `public/tasks`.
    val typed = "public/tasks"
    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"
    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=form-truth" +
          "&csrf=${signIn(pod.name, ownerWebId).csrf}" +
          "&new_context=${java.net.URLEncoder.encode(typed, "UTF-8")}" +
          "&new_context_scope=${java.net.URLEncoder.encode("$typed#read", "UTF-8")}" +
          "&new_context_scope=${java.net.URLEncoder.encode("$typed#write", "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode, "the flow must not end in access_denied: ${response.responseBody}")
    assertTrue(
      assertNotNull(response.getHeader("Location")).contains("code="),
      "an authorization that created a context and granted it must issue a code",
    )

    val expected = contextUri(pod.name, typed)
    val grants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(ownerWebId),
    )
    assertEquals(
      setOf("$expected#read", "$expected#write"),
      grants,
      "the ticked permissions must be persisted against the IRI the context actually got",
    )
  }

  @Test
  fun `consent skips a context whose name breaks the rules, without failing the authorization`() {
    // Mid-consent is the wrong moment to abort over a mistyped context name: the user loses the
    // whole flow. The context is skipped and simply not granted — the grant set is computed from
    // what exists.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"

    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${testClientId}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=test-state" +
          "&csrf=${signIn(pod.name, ownerWebId).csrf}" +
          "&new_context=ok/one" +
          "&new_context=apps/claimed" +
          "&new_context_scope=${java.net.URLEncoder.encode("ok/one#read", "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode, "the authorization must still complete")
    val contexts = podContextsDao.fetchByPod(checkNotNull(pod.id))
    assertTrue(contexts.any { it.contextUri.endsWith("ok/one") }, "the valid context must exist")
    assertTrue(
      contexts.none { it.contextUri.endsWith("apps/claimed") },
      "an owner must not claim a type root through the consent dialog: ${contexts.map { it.contextUri }}",
    )
  }

  @Test
  fun `the consent form carries the naming rules as data, not as a copy`() {
    // The dialog validates the name before submitting, because a name the server refuses is
    // otherwise just missing from the grant list with nothing on screen to explain it. Its check
    // duplicates the *shape* of `ContextPathRules` — unavoidable in a browser — but not the values:
    // those are shipped as attributes, so adding a type or renaming the reserved segment reaches
    // the form without anyone remembering to edit the template.
    //
    // Asserted against `ContextPathRules` itself rather than against literals, so this test fails
    // when the two drift rather than when the rules change.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val page = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "rules-as-data")
      .executeSignedInAs(ownerWebId)
    assertEquals(200, page.statusCode)
    val html = page.responseBody

    fun attribute(name: String): String? =
      Regex("""data-$name="([^"]*)"""").find(html)?.groupValues?.get(1)

    assertEquals(
      ContextPathRules.RESERVED_SEGMENT,
      attribute("reserved-segment"),
      "the reserved segment must reach the form",
    )
    // The whole set, compared as a set: a type missing here is a name the owner can type and only
    // find out about after authorizing.
    assertEquals(
      ContextPathRules.DELEGATION_TYPES,
      attribute("delegation-types")?.split(",")?.toSet(),
      "every reserved type must reach the form",
    )
    assertEquals(
      ContextPathRules.IMPLEMENTED_TYPES,
      attribute("implemented-types")?.split(",")?.toSet(),
      "the form has to tell 'reserved for later' apart from 'usable below a root'",
    )
    // And the check actually consults them, rather than the attributes being decoration.
    val script = html.substringAfter("function rejectionReason").substringBefore("function showError")
    listOf("reservedSegment", "delegationTypes", "implementedTypes").forEach {
      assertTrue(script.contains(it), "the form's check must read '$it' from the page:\n$script")
    }

    // The one rule the form cannot receive as data: which characters may appear at all. It carries
    // its own character class, and a class that is *laxer* than the server lets the owner authorize
    // a name that is then dropped without explanation — the very failure this validation exists to
    // prevent. So compare the two character by character, rather than trusting that the literal in
    // the template still matches what `java.net.URI` accepts.
    val charClass = assertNotNull(
      Regex("REJECTED_PATH_CHARS = /\\[(.*)]/").find(html)?.groupValues?.get(1),
      "the form must carry the character rule",
    )
    val formRejects = Regex("[" + charClass + "]")
    fun formAccepts(ch: String) = !formRejects.containsMatchIn(ch)
    val podBase = "${SempodsModule.config.apiBaseUrl}${pod.name}/"
    // `%`, `#` and `?` have their own branch on both sides, so they are not compared here.
    val sample = (' '..'~').filterNot { it in "%#?" }.map { it.toString() } + listOf("ü", "€")
    sample.forEach { ch ->
      val serverAccepts = ContextPathRules.addressabilityProblem(podBase, "a" + ch + "b") == null
      assertEquals(
        serverAccepts,
        formAccepts(ch),
        "the form and the server disagree about '" + ch + "': a laxer form drops a context silently, " +
            "a stricter one refuses a legal name",
      )
    }
  }

  @Test
  fun `consent refuses a context name that would not be addressable afterwards`() {
    // `new_context` is a form value: it reaches the endpoint decoded, so `foo%23bar` arrives as
    // `foo#bar` and string concatenation would have persisted `<pod>/_system/contexts/foo#bar`.
    // That row is unreachable through `_system/contexts/{path}` — created once, never removable —
    // and it makes `…foo#bar#read` ambiguous against the `<iri>#<permission>` scope grammar.
    //
    // `..` is the same class of defect from the other side: Jetty collapses it on the management
    // route, but nothing normalizes a form value, so only the shared rules catch it here.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"

    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=unaddressable" +
          "&csrf=${signIn(pod.name, ownerWebId).csrf}" +
          "&new_context=${java.net.URLEncoder.encode("foo#bar", "UTF-8")}" +
          "&new_context=${java.net.URLEncoder.encode("escape/../../elsewhere", "UTF-8")}" +
          "&new_context=fine/one" +
          "&new_context_scope=${java.net.URLEncoder.encode("foo#bar#read", "UTF-8")}" +
          "&new_context_scope=${java.net.URLEncoder.encode("fine/one#read", "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    // The valid one still goes through — a bad name is skipped, it does not fail the flow.
    assertEquals(303, response.statusCode, "the authorization must still complete")
    val contexts = podContextsDao.fetchByPod(checkNotNull(pod.id)).map { it.contextUri }
    assertTrue(contexts.any { it == contextUri(pod.name, "fine/one") }, "the valid context must exist: $contexts")
    assertTrue(contexts.none { it.contains("#") }, "no context IRI may carry a fragment: $contexts")
    assertTrue(contexts.none { it.contains("..") }, "no context IRI may carry a relative segment: $contexts")

    val grants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(ownerWebId),
    )
    assertEquals(
      setOf("${contextUri(pod.name, "fine/one")}#read"),
      grants,
      "only the context that was actually created may carry a grant",
    )
  }

  @Test
  fun `consent with only new contexts and no pre-existing scopes should work for owner`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"

    // Submit consent with a new context and scope for it — no pre-existing contexts on pod.
    // Encode every form value the way a real browser would; without this, JAX-RS decodes
    // already-encoded characters (e.g. `%3A` in `did:web:host%3Aport`) a second time and
    // grants end up persisted under a different appId than the one the caller fetches.
    val newContextUri = contextUri(pod.name, "my/data")
    val response = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
          "&state=fresh" +
          "&csrf=${java.net.URLEncoder.encode(signIn(pod.name, ownerWebId).csrf, "UTF-8")}" +
          "&new_context=${java.net.URLEncoder.encode("my/data", "UTF-8")}" +
          "&new_context_scope=${java.net.URLEncoder.encode("my/data#read", "UTF-8")}" +
          "&new_context_scope=${java.net.URLEncoder.encode("my/data#write", "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.contains("code="), "Should contain auth code")
    assertTrue(location.contains("state=fresh"), "Should contain state")

    // Verify grants for the newly created context are actually persisted.
    val savedGrants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webIds = listOf(ownerWebId),
    )
    assertTrue(
      savedGrants.contains("$newContextUri#read"),
      "read grant for new context should be persisted, got: $savedGrants",
    )
    assertTrue(
      savedGrants.contains("$newContextUri#write"),
      "write grant for new context should be persisted, got: $savedGrants",
    )
  }

  @Test
  fun `consent with new context for dyn client should persist grants and pre-check on re-authorize`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val dynRedirectUri = "http://localhost:5173/callback"
    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$dynRedirectUri"],"client_name":"Test Dyn Client"}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)
    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    val newContextUri = contextUri(pod.name, "private/notes")
    val consentUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent"
    val consentResponse = http.preparePost(consentUrl)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(pod.name, ownerWebId).cookie)
      .setBody(
        "client_id=${java.net.URLEncoder.encode(dynClientId, "UTF-8")}" +
          "&redirect_uri=${java.net.URLEncoder.encode(dynRedirectUri, "UTF-8")}" +
          "&state=dyn-state" +
          "&csrf=${signIn(pod.name, ownerWebId).csrf}" +
          "&code_challenge=$testCodeChallenge" +
          "&code_challenge_method=$testCodeChallengeMethod" +
          "&new_context=private/notes" +
          "&new_context_scope=${java.net.URLEncoder.encode("private/notes#read", "UTF-8")}" +
          "&new_context_scope=${java.net.URLEncoder.encode("private/notes#write", "UTF-8")}"
      )
      .setFollowRedirect(false)
      .execute()
    assertEquals(303, consentResponse.statusCode)

    // Grants should be persisted under the dyn client_id.
    val savedGrants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(pod.id),
      appId = dynClientId,
      webIds = listOf(ownerWebId),
    )
    assertTrue(
      savedGrants.contains("$newContextUri#read"),
      "read grant for new context should be persisted, got: $savedGrants",
    )
    assertTrue(
      savedGrants.contains("$newContextUri#write"),
      "write grant for new context should be persisted, got: $savedGrants",
    )

    // Re-authorize: consent UI should pre-check read+write for the new context.
    val reAuthResponse = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", dynRedirectUri)
      .addQueryParam("state", "re-auth")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .executeSignedInAs(ownerWebId)
    assertEquals(200, reAuthResponse.statusCode)
    val body = reAuthResponse.responseBody
    val readCheckedFragment = """value="$newContextUri#read" checked"""
    val writeCheckedFragment = """value="$newContextUri#write" checked"""
    assertTrue(
      body.contains(readCheckedFragment),
      "consent UI should pre-check read for new context, but no '$readCheckedFragment' in body",
    )
    assertTrue(
      body.contains(writeCheckedFragment),
      "consent UI should pre-check write for new context, but no '$writeCheckedFragment' in body",
    )
  }

  @Test
  fun `authorize with prompt=consent should show consent UI even with existing grants`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)

    // Pre-populate grants
    val grantedScope = "${contextUri(pod.name, contextPath)}#read"
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = ownerWebId,
      grants = listOf(grantedScope),
      grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "consent")
      .executeSignedInAs(ownerWebId)

    // Should show consent UI even though grants exist
    assertEquals(200, response.statusCode)
    assertTrue(response.responseBody.contains("consent"), "Should show consent UI")
  }

  // ─── Dynamic Client Registration (RFC 7591) ──────────────────────────────

  private fun registerUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/auth/register"

  @Test
  fun `register should issue client_id for valid redirect_uris`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["http://localhost:5173/callback"],"client_name":"Test Client"}""")
      .execute()

    assertEquals(201, response.statusCode)

    @Suppress("UNCHECKED_CAST")
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    val clientId = body["client_id"] as? String
    assertNotNull(clientId)
    assertTrue(clientId.startsWith("dyn:"), "client_id must start with 'dyn:'")
    assertEquals(listOf("http://localhost:5173/callback"), body["redirect_uris"])
    assertEquals("none", body["token_endpoint_auth_method"])
    assertEquals("Test Client", body["client_name"])
  }

  @Test
  fun `register without redirect_uris should return 400`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"client_name":"Test Client"}""")
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `register with identical fingerprint should dedup to the same client_id`() {
    val pod = sempodsTestFactory.newPod()

    fun postRegister(redirectUri: String) = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("User-Agent", "claude-code-cli/0.1")
      .setBody("""{"redirect_uris":["$redirectUri"],"client_name":"Claude Code"}""")
      .execute()

    val first = postRegister("http://localhost:52341/callback")
    assertEquals(201, first.statusCode)

    @Suppress("UNCHECKED_CAST")
    val firstBody = JsonMappers.default().readValue(first.responseBody, Map::class.java) as Map<String, Any?>
    val firstClientId = firstBody["client_id"] as String

    // Different loopback port, but same clientName + userAgent + URI shape → dedup hit.
    val second = postRegister("http://localhost:7123/callback")
    assertEquals(201, second.statusCode)

    @Suppress("UNCHECKED_CAST")
    val secondBody = JsonMappers.default().readValue(second.responseBody, Map::class.java) as Map<String, Any?>
    val secondClientId = secondBody["client_id"] as String

    assertEquals(firstClientId, secondClientId, "repeat registration with matching fingerprint must reuse the existing client_id")
  }

  @Test
  fun `register with a different clientName should mint a distinct client_id`() {
    val pod = sempodsTestFactory.newPod()

    fun postRegister(clientName: String) = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("User-Agent", "ua/1")
      .setBody("""{"redirect_uris":["http://localhost:5000/cb"],"client_name":"$clientName"}""")
      .execute()

    @Suppress("UNCHECKED_CAST")
    val first = JsonMappers.default().readValue(
      postRegister("Claude Code").responseBody, Map::class.java,
    ) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val second = JsonMappers.default().readValue(
      postRegister("Claude Desktop").responseBody, Map::class.java,
    ) as Map<String, Any?>

    assertNotEquals(first["client_id"], second["client_id"])
  }

  @Test
  fun `register with an identical body should dedup to the same client_id`() {
    // The fingerprint's whole job after the MCP-path retirement: a client with no persistent
    // client-state re-registers on every reconnect and must land on the same `dyn:` id, so the
    // consent the user gave stays attached to it.
    val pod = sempodsTestFactory.newPod()

    fun postRegister() = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("User-Agent", "chatgpt/1")
      .setBody("""{"redirect_uris":["https://chatgpt.com/connector/oauth/XYZ"],"client_name":"ChatGPT"}""")
      .execute()

    val first = JsonMappers.default().readValue(postRegister().responseBody, Map::class.java) as Map<*, *>
    val second = JsonMappers.default().readValue(postRegister().responseBody, Map::class.java) as Map<*, *>

    assertEquals(first["client_id"], second["client_id"], "an identical DCR body must dedup")
  }

  @Test
  fun `register below the registration endpoint should 404`() {
    // `/register/<mcpPath>` was the path-segment variant that forked fingerprints per MCP
    // surface. A pod has one surface and one registration endpoint; nothing routes below it.
    val pod = sempodsTestFactory.newPod()

    val response = http.preparePost("${registerUrl(pod.name)}/default")
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["https://example.com/cb"],"client_name":"X"}""")
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `register should reject non-http redirect_uris`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["ftp://example.com/cb"]}""")
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `authorize should accept dyn client_id with registered redirect_uri`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val dynRedirectUri = "http://localhost:5173/callback"
    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$dynRedirectUri"],"client_name":"Dynamic Client"}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)

    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", dynRedirectUri)
      .addQueryParam("state", "dyn-state")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .executeSignedInAs(ownerWebId)

    assertEquals(200, response.statusCode)
    assertTrue(response.responseBody.contains("consent"), "Should render consent UI for dynamic client")
  }

  @Test
  fun `authorize for dynamic client should always render consent even with existing grants`() {
    // RFC 7591 dynamic clients (Claude Code / Claude Desktop / ChatGPT etc.) hit /authorize
    // only when the user just triggered a flow (reconnect, explicit re-auth). Forcing the
    // consent dialog every time gives the user visibility and one-click review of grants;
    // existing grants are pre-checked so the common case is still a single click.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    val contextPath = "public/tasks"
    createContextViaDao(checkNotNull(pod.id), pod.name, contextPath)

    val dynRedirectUri = "http://localhost:5173/callback"
    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$dynRedirectUri"],"client_name":"Dynamic Client"}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)
    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    // Pre-populate grants — the same setup that auto-grants a `did:web:` client must NOT
    // auto-grant a `dyn:` client.
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = dynClientId,
      webId = ownerWebId,
      grants = listOf("${contextUri(pod.name, contextPath)}#read"),
      grantedBy = ownerWebId,
    )

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", dynRedirectUri)
      .addQueryParam("state", "dyn-state")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .executeSignedInAs(ownerWebId)

    assertEquals(200, response.statusCode, "Must render consent, not redirect with code")
    assertTrue(response.responseBody.contains("consent"), "TestHttpResponse should contain consent UI")
  }

  @Test
  fun `authorize should accept a loopback redirect_uri with a different port than registered`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["http://localhost:52341/callback"],"client_name":"Native MCP Client"}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)

    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    // RFC 8252 §7.3: loopback clients pick an ephemeral port per invocation — port must not
    // be part of the allow-list check.
    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", "http://localhost:65373/callback")
      .addQueryParam("state", "loopback-port-state")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .executeSignedInAs(ownerWebId)

    assertEquals(200, response.statusCode, "loopback with different ephemeral port must be accepted")
    assertTrue(response.responseBody.contains("consent"))
  }

  @Test
  fun `authorize should still reject non-loopback redirect_uri with wrong port`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["https://claude.ai:443/api/mcp/auth_callback"],"client_name":"Claude"}""")
      .execute()

    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", "https://claude.ai:8443/api/mcp/auth_callback")
      .addQueryParam("state", "non-loopback-state")
      .executeSignedInAs(ownerWebId)

    assertEquals(400, response.statusCode, "non-loopback host port mismatch must still be rejected")
  }

  @Test
  fun `authorize with dyn client_id should reject unregistered redirect_uri`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["http://localhost:5173/callback"]}""")
      .execute()
    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", "http://evil.example/cb")
      .executeSignedInAs(ownerWebId)

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `authorize should reject unknown dyn client_id`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", "dyn:unknown-client")
      .addQueryParam("redirect_uri", "http://localhost:5173/callback")
      .setFollowRedirect(false)
      .execute()

    // A direct 400, never a redirect: the client is not yet known to own the address it named, so
    // the refusal must not be delivered there. And it says which of the two things is wrong — the
    // registration, not the format.
    assertEquals(400, response.statusCode, response.responseBody)
    assertTrue(response.getHeader("Location").isNullOrBlank(), "must not be delivered by redirect")
    assertTrue("invalid_client" in response.responseBody, response.responseBody)
  }

  @Test
  fun `authorize should reject dyn client_id registered at a different pod`() {
    // clientIds are scoped to their issuing pod. Reusing pod-A's clientId at pod-B must fail
    // in exactly the same way as an unknown clientId — the lookup is pod-scoped by design.
    val podA = sempodsTestFactory.newPod()
    val podB = sempodsTestFactory.newPod()

    val dynRedirectUri = "http://localhost:5173/callback"
    val registerResponse = http.preparePost(registerUrl(podA.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$dynRedirectUri"]}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)

    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientIdFromPodA = registerBody["client_id"] as String

    val response = http.prepareGet(authorizeUrl(podB.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientIdFromPodA)
      .addQueryParam("redirect_uri", dynRedirectUri)
      .setFollowRedirect(false)
      .execute()

    // Same answer as an unknown clientId, and for the same reason: the lookup is pod-scoped, so
    // from pod-B's side this id simply has no registration behind it.
    assertEquals(400, response.statusCode, response.responseBody)
    assertTrue("invalid_client" in response.responseBody, response.responseBody)
  }

  @Test
  fun `authorize for dyn client without code_challenge should reject with invalid_request`() {
    // PR-review finding #2: dynamic clients are public clients (token_endpoint_auth_method=none).
    // Without PKCE an intercepted auth code can be redeemed by anyone — must be rejected.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val dynRedirectUri = "http://localhost:5173/callback"
    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$dynRedirectUri"],"client_name":"PKCE Probe"}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)
    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", dynRedirectUri)
      .addQueryParam("state", "no-pkce-state")
      .executeSignedInAs(ownerWebId)

    // oauthError redirects with error=invalid_request as a 307 to the redirect_uri.
    assertEquals(307, response.statusCode)
    val location = checkNotNull(response.getHeader("Location"))
    assertTrue(location.contains("error=invalid_request"), "Expected invalid_request in: $location")
  }

  @Test
  fun `authorize for dyn client with non-S256 code_challenge_method should reject`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val dynRedirectUri = "http://localhost:5173/callback"
    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$dynRedirectUri"],"client_name":"PKCE Probe Plain"}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)
    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    // PLAIN defeats PKCE's purpose — challenge is visible in the authorize URL.
    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", dynRedirectUri)
      .addQueryParam("state", "plain-pkce-state")
      .addQueryParam("code_challenge", "plaintext-challenge-not-hashed")
      .addQueryParam("code_challenge_method", "plain")
      .executeSignedInAs(ownerWebId)

    assertEquals(307, response.statusCode)
    val location = checkNotNull(response.getHeader("Location"))
    assertTrue(location.contains("error=invalid_request"), "Expected invalid_request in: $location")
  }

  @Test
  fun `a challenge this server cannot verify is refused at authorize, not at the exchange`() {
    // `S256` is case-sensitive (RFC 7636 §4.3) and it is the only method OAuth 2.1 allows, so
    // `plain`, `s256` and an absent method are all unverifiable here. They used to be accepted at
    // `/authorize`, minted into a code, and refused at `/token` — where the browser is gone and
    // the client can only report that something went wrong. Only `did:web:` clients could reach
    // that; `dyn:` ones were already checked.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    for (method in listOf("plain", "s256", "")) {
      val response = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", testClientId)
        .addQueryParam("redirect_uri", testRedirectUri)
        .addQueryParam("state", "unverifiable-$method")
        .addQueryParam("code_challenge", testCodeChallenge)
        .apply { if (method.isNotEmpty()) addQueryParam("code_challenge_method", method) }
        .executeSignedInAs(ownerWebId)

      assertEquals(307, response.statusCode, "method='$method'")
      val location = checkNotNull(response.getHeader("Location"))
      assertTrue(location.contains("error=invalid_request"), "method='$method' must be refused: $location")
      assertFalse(location.contains("code="), "method='$method' must not mint a code: $location")
    }

    // The one this server can verify still works.
    val accepted = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "verifiable")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .executeSignedInAs(ownerWebId)
    assertEquals(200, accepted.statusCode, "S256 must still reach consent")
  }

  @Test
  fun `a client that sends no challenge at all is unaffected`() {
    // PKCE stays optional for `did:web:` clients — their identity is the origin their redirect
    // address sits on. The check above is about a challenge that cannot be verified, not about
    // requiring one.
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "no-pkce")
      .executeSignedInAs(ownerWebId)

    assertEquals(200, response.statusCode)
  }

  @Test
  fun `register should accept full RFC 7591 metadata and echo it back`() {
    val pod = sempodsTestFactory.newPod()

    val body = """
      {
        "redirect_uris": ["http://localhost:5173/callback"],
        "client_name": "Claude Code",
        "client_uri": "https://claude.com/claude-code",
        "logo_uri": "https://claude.com/logo.png",
        "software_id": "01HMTEST-SOFTWARE-ID",
        "software_version": "1.2.3",
        "contacts": ["support@example.com", "ops@example.com"],
        "tos_uri": "https://claude.com/terms",
        "policy_uri": "https://claude.com/privacy"
      }
    """.trimIndent()

    val response = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(body)
      .execute()

    assertEquals(201, response.statusCode)

    @Suppress("UNCHECKED_CAST")
    val responseBody =
      JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    assertEquals("Claude Code", responseBody["client_name"])
    assertEquals("https://claude.com/claude-code", responseBody["client_uri"])
    assertEquals("https://claude.com/logo.png", responseBody["logo_uri"])
    assertEquals("01HMTEST-SOFTWARE-ID", responseBody["software_id"])
    assertEquals("1.2.3", responseBody["software_version"])
    assertEquals(listOf("support@example.com", "ops@example.com"), responseBody["contacts"])
    assertEquals("https://claude.com/terms", responseBody["tos_uri"])
    assertEquals("https://claude.com/privacy", responseBody["policy_uri"])
  }

  @Test
  fun `register should silently ignore optional fields with wrong types`() {
    val pod = sempodsTestFactory.newPod()

    // contacts as a plain string instead of list, software_id as number — RFC 7591 lets the
    // server ignore unknown / malformed metadata. We must not 400 just because a client got
    // a field shape wrong.
    val body = """
      {
        "redirect_uris": ["http://localhost:5173/callback"],
        "client_name": "Quirky Client",
        "contacts": "single@example.com",
        "software_id": 12345
      }
    """.trimIndent()

    val response = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(body)
      .execute()

    assertEquals(201, response.statusCode)

    @Suppress("UNCHECKED_CAST")
    val responseBody =
      JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

    assertEquals("Quirky Client", responseBody["client_name"])
    assertNull(responseBody["contacts"], "malformed contacts must not be echoed")
    assertNull(responseBody["software_id"], "malformed software_id must not be echoed")
  }

  @Test
  fun `authorize consent UI should surface client_name for dynamic clients`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    val dynRedirectUri = "http://localhost:5173/callback"
    val distinctiveName = "ACME Assistant ${TestUtil.randomId()}"
    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["$dynRedirectUri"],"client_name":"$distinctiveName"}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)

    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val dynClientId = registerBody["client_id"] as String

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", dynRedirectUri)
      .addQueryParam("state", "client-name-state")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .executeSignedInAs(ownerWebId)

    assertEquals(200, response.statusCode)
    val html = response.responseBody
    assertTrue(html.contains(distinctiveName), "consent UI must show client_name '$distinctiveName'")
  }

  @Test
  fun `touchLastAuthorized should set lastAuthorizedAt and noop for unknown clientId`() {
    val pod = sempodsTestFactory.newPod()

    val registerResponse = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"redirect_uris":["http://localhost:5173/cb"],"client_name":"Touch Probe"}""")
      .execute()
    assertEquals(201, registerResponse.statusCode)

    @Suppress("UNCHECKED_CAST")
    val registerBody =
      JsonMappers.default().readValue(registerResponse.responseBody, Map::class.java) as Map<String, Any?>
    val clientId = registerBody["client_id"] as String

    val fresh = assertNotNull(dynamicClientRegistrationDao.findByClientId(checkNotNull(pod.id), clientId))
    assertNull(fresh.lastAuthorizedAt, "fresh registration must not carry a lastAuthorizedAt")

    assertTrue(dynamicClientRegistrationDao.touchLastAuthorized(pod.id, clientId))
    val touched = assertNotNull(dynamicClientRegistrationDao.findByClientId(pod.id, clientId))
    assertNotNull(touched.lastAuthorizedAt, "touch must set lastAuthorizedAt")

    // did:web clients have no DCR row — touch must be a silent noop.
    val noop = dynamicClientRegistrationDao.touchLastAuthorized(pod.id, "did:web:apps.sempods.org:focus")
    assertEquals(false, noop)
  }

  @Test
  fun `register should persist the full DCR body verbatim for Stage-2 analysis`() {
    val pod = sempodsTestFactory.newPod()

    // Include a field we do NOT extract to top-level — it must still survive via rawRequest.
    val body = """
      {
        "redirect_uris": ["http://localhost:5173/callback"],
        "client_name": "Persistence Probe",
        "software_id": "probe-${TestUtil.randomId()}",
        "token_endpoint_auth_method": "none",
        "grant_types": ["authorization_code"],
        "scope": "openid offline_access"
      }
    """.trimIndent()

    val response = http.preparePost(registerUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("User-Agent", "integration-test/1.0")
      .setBody(body)
      .execute()
    assertEquals(201, response.statusCode)

    @Suppress("UNCHECKED_CAST")
    val responseBody =
      JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    val clientId = responseBody["client_id"] as String

    val persisted = assertNotNull(
      dynamicClientRegistrationDao.findByClientId(checkNotNull(pod.id), clientId),
      "registration must be findable by clientId after HTTP round-trip",
    )

    assertEquals("Persistence Probe", persisted.clientName)
    assertEquals(pod.id, persisted.registeredForPodId)
    assertEquals(pod.name, persisted.registeredForPodName)
    assertEquals("integration-test/1.0", persisted.userAgent)
    assertEquals(1, persisted.schemaVersion)

    // Verbatim body must include fields we don't extract — this is the forward-compat hook.
    assertEquals("none", persisted.rawRequest["token_endpoint_auth_method"])
    assertEquals(listOf("authorization_code"), persisted.rawRequest["grant_types"])
    assertEquals("openid offline_access", persisted.rawRequest["scope"])
  }

  // ─── /token — authorization_code & refresh_token ────────────────────────

  private fun tokenUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/auth/token"

  private fun postForm(url: String, body: String) =
    http.preparePost(url)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody(body)
      .execute()

  private fun seedRefreshToken(
    pod: org.sempods.pods.mongo.persist.PodDbo,
    clientId: String = testClientId,
    webId: String = "https://id.test/user",
    scopes: Set<String> = emptySet(),
  ): RefreshTokenStore.Issued<PodRefreshTokenStore.Owner> {
    val resolvedScopes = scopes.ifEmpty {
      setOf("${contextUri(pod.name, "public/tasks")}#read")
    }
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = clientId,
      webId = webId,
      grants = resolvedScopes,
      grantedBy = webId,
    )
    return refreshTokenStore.issueNewFamily(
      podId = checkNotNull(pod.id),
      podName = pod.name,
      clientId = clientId,
      webId = webId,
      scopes = resolvedScopes,
    )
  }

  @Test
  fun `authorize with scope=public-read prompt=none and no JWT issues anonymous auth code (R2_2)`() {
    // R2.2: with no ID session, scope=public-read + prompt=none mints a synthetic
    // anonymous webId at code-issue time. The full flow (token exchange + sub
    // verification) is exercised in the companion access-token test below.
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "none")
      .addQueryParam("scope", "public-read")
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    assertTrue(location.startsWith(testRedirectUri), "Should redirect to app callback")
    assertTrue(location.contains("code="), "Should carry auth code: $location")
    assertTrue(!location.contains("error="), "Should not be an error redirect: $location")
  }

  @Test
  fun `authorize with scope=public-read no JWT and prompt unset still redirects to login (R2_2)`() {
    // Without an explicit prompt=none, the caller is treated as wanting an
    // interactive flow — fall through to the login redirect. After login,
    // the request re-enters the public-read branch with a valid JWT.
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("scope", "public-read")
      .setFollowRedirect(false)
      .execute()

    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location")
    assertNotNull(location)
    // Login redirect goes to id base URL — neither the app callback nor an OAuth error.
    assertTrue(!location.startsWith(testRedirectUri), "Should redirect to login, not back to app: $location")
    assertTrue(!location.contains("error="), "Login redirect should not carry an OAuth error: $location")
  }

  @Test
  fun `public-read anonymous flow yields token whose sub is opaque urn-sempods-anon`() {
    // Full R2.2 flow: anonymous authorize → token exchange → access token whose
    // `sub` claim is a per-token opaque anonymous URN, not a user WebID.
    val pod = sempodsTestFactory.newPod()

    // Step 1: anonymous authorize.
    val authorizeResp = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "test-state")
      .addQueryParam("prompt", "none")
      .addQueryParam("scope", "public-read")
      .setFollowRedirect(false)
      .execute()
    assertEquals(303, authorizeResp.statusCode)
    val location = authorizeResp.getHeader("Location") ?: error("missing Location")
    val code = Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1)
      ?: error("no code in $location")

    // Step 2: token exchange.
    val tokenResp = postForm(
      tokenUrl(pod.name),
      "grant_type=authorization_code" +
        "&code=${java.net.URLEncoder.encode(code, "UTF-8")}" +
        "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )
    assertEquals(200, tokenResp.statusCode)
    @Suppress("UNCHECKED_CAST")
    val body =
      JsonMappers.default().readValue(tokenResp.responseBody, Map::class.java) as Map<String, Any?>
    assertEquals("public-read", body["scope"])
    assertNull(body["refresh_token"], "anonymous public-read tokens must not carry a refresh_token")

    // Step 3: decode access token, check sub claim.
    val accessToken = body["access_token"] as String
    val claims = com.nimbusds.jwt.SignedJWT.parse(accessToken).jwtClaimsSet
    val sub = claims.subject
    assertNotNull(sub)
    assertTrue(
      sub.startsWith("urn:sempods:anon:"),
      "anonymous sub must use the urn:sempods:anon: scheme, was: $sub",
    )
  }

  @Test
  fun `authenticated public-read authorization_code exchange returns normal token with refresh_token`() {
    // R7 additive model: authenticated public-read is a normal persisted grant.
    // A public-read-only auth code for a real WebID must therefore use the
    // standard token response with refresh-token rotation.
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.test/anyone"
    // In the order the endpoint uses it: the consent is recorded, and the code is issued under it.
    // A refresh token follows that decision, not the code — which is why the code carries the
    // generation it was minted under.
    val consent = consentDecisionStore.record(checkNotNull(pod.id), testClientId, webId, durable = true)
    val code = authorizationCodeStore.issue(
      realm = pod.name,
      clientId = testClientId,
      subject = webId,
      scopes = setOf("public-read"),
      redirectUri = testRedirectUri,
      codeChallenge = null,
      codeChallengeMethod = null,
      consentGeneration = consent.generation,
    )
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = webId,
      grants = setOf("public-read"),
      grantedBy = webId,
    )

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=authorization_code" +
        "&code=$code" +
        "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )

    assertEquals(200, response.statusCode)
    @Suppress("UNCHECKED_CAST")
    val body =
      JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    assertNotNull(body["access_token"])
    assertEquals("Bearer", body["token_type"])
    assertEquals(3600, body["expires_in"])
    // The response says what was granted, the durable connection included (RFC 6749 §3.3); the
    // bearer's own claim carries the feature scopes and nothing else.
    assertEquals("public-read offline_access", body["scope"])
    assertEquals(
      "public-read",
      com.nimbusds.jwt.SignedJWT.parse(body["access_token"] as String).jwtClaimsSet.getStringClaim("scope"),
    )
    assertNotNull(body["refresh_token"], "authenticated public-read tokens must carry a refresh_token")
  }

  @Test
  fun `authorization_code exchange returns both access_token and refresh_token`() {
    val pod = sempodsTestFactory.newPod()
    val scope = "${contextUri(pod.name, "public/tasks")}#read"
    val consent =
      consentDecisionStore.record(checkNotNull(pod.id), testClientId, "https://id.test/user", durable = true)
    val code = authorizationCodeStore.issue(
      realm = pod.name,
      clientId = testClientId,
      subject = "https://id.test/user",
      scopes = setOf(scope),
      redirectUri = testRedirectUri,
      codeChallenge = null,
      codeChallengeMethod = null,
      consentGeneration = consent.generation,
    )
    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=authorization_code" +
        "&code=$code" +
        "&redirect_uri=${java.net.URLEncoder.encode(testRedirectUri, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )

    assertEquals(200, response.statusCode)
    @Suppress("UNCHECKED_CAST")
    val body =
      JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    assertNotNull(body["access_token"])
    assertEquals("Bearer", body["token_type"])
    assertEquals(3600, body["expires_in"])
    val refreshToken = body["refresh_token"] as? String
    assertNotNull(refreshToken, "authorization_code exchange must return a refresh_token")
    assertTrue(refreshToken.startsWith("rt_"), "refresh token plaintext should carry the rt_ prefix")
    // The response names the granted durable connection and nothing else — the auth code carried a
    // context scope and no feature scope survived it. Slimming is a property of the token, so it is
    // asserted where it lives: the bearer's own claim.
    assertEquals("offline_access", body["scope"])
    assertEquals(
      "",
      com.nimbusds.jwt.SignedJWT.parse(body["access_token"] as String).jwtClaimsSet.getStringClaim("scope"),
    )
  }

  @Test
  fun `refresh_token grant rotates the token and returns new access_token and refresh_token`() {
    val pod = sempodsTestFactory.newPod()
    val issued = seedRefreshToken(pod)
    val originalHash = issued.token.tokenHash
    val familyId = issued.token.familyId

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${java.net.URLEncoder.encode(issued.plaintext, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )

    assertEquals(200, response.statusCode)
    @Suppress("UNCHECKED_CAST")
    val body =
      JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    assertNotNull(body["access_token"])
    val newRefresh = body["refresh_token"] as? String
    assertNotNull(newRefresh)
    assertNotEquals(issued.plaintext, newRefresh, "rotation must mint a fresh token")

    // Original token must now be marked rotated.
    val originalRow = refreshTokenStore.findByFamily(familyId).single { it.tokenHash == originalHash }
    assertNotNull(originalRow.rotatedAt, "rotated token must have rotatedAt set")

    // Successor must be active and share the same family.
    val familyRows = refreshTokenStore.findByFamily(familyId)
    assertEquals(2, familyRows.size, "family must now hold predecessor + successor")
    val successor = familyRows.single { it.tokenHash != originalHash }
    assertNull(successor.rotatedAt)
    assertNull(successor.revokedAt)
  }

  @Test
  fun `refresh_token reuse after rotation triggers family-wide revocation`() {
    val pod = sempodsTestFactory.newPod()
    val issued = seedRefreshToken(pod)

    // First refresh — legitimate rotation.
    val first = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${java.net.URLEncoder.encode(issued.plaintext, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )
    assertEquals(200, first.statusCode)

    // Second attempt with the old plaintext — must be rejected as reuse.
    val reuse = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${java.net.URLEncoder.encode(issued.plaintext, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )
    assertEquals(400, reuse.statusCode)
    assertTrue(reuse.responseBody.contains("invalid_grant"))

    // Both the predecessor and the successor must now be revoked — the thief (or stale
    // client) cannot use the in-flight child either.
    val familyRows = refreshTokenStore.findByFamily(issued.token.familyId)
    assertEquals(2, familyRows.size, "family should hold predecessor + successor")
    assertTrue(
      familyRows.all { it.revokedAt != null },
      "reuse must revoke every row in the family",
    )
  }

  @Test
  fun `refresh_token for unknown token returns invalid_grant`() {
    val pod = sempodsTestFactory.newPod()
    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=rt_definitely-not-a-real-token" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )
    assertEquals(400, response.statusCode)
    assertTrue(response.responseBody.contains("invalid_grant"))
  }

  @Test
  fun `refresh_token is rejected when all granted scopes have been revoked`() {
    val pod = sempodsTestFactory.newPod()
    val issued = seedRefreshToken(pod)
    val webId = issued.token.owner.webId

    // Owner revoked the consent in the meantime — grants are gone.
    podGrantsDao.replaceGrants(
      podId = checkNotNull(pod.id),
      appId = testClientId,
      webId = webId,
      grants = emptySet(),
      grantedBy = webId,
    )

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${java.net.URLEncoder.encode(issued.plaintext, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )
    assertEquals(400, response.statusCode)
    assertTrue(response.responseBody.contains("invalid_grant"))

    // Family must also be revoked, so even a stray rotation could not re-hydrate scopes.
    val familyRows = refreshTokenStore.findByFamily(issued.token.familyId)
    assertTrue(familyRows.all { it.revokedAt != null })
  }

  @Test
  fun `refresh_token bound to a different pod is rejected`() {
    val podA = sempodsTestFactory.newPod()
    val podB = sempodsTestFactory.newPod()
    val issued = seedRefreshToken(podA)

    val response = postForm(
      tokenUrl(podB.name),
      "grant_type=refresh_token" +
        "&refresh_token=${java.net.URLEncoder.encode(issued.plaintext, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}",
    )
    assertEquals(400, response.statusCode)
    assertTrue(response.responseBody.contains("invalid_grant"))
  }

  @Test
  fun `refresh_token bound to a different client_id is rejected`() {
    val pod = sempodsTestFactory.newPod()
    val issued = seedRefreshToken(pod)
    val otherClient = "did:web:apps.sempods.org:focus"

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${java.net.URLEncoder.encode(issued.plaintext, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(otherClient, "UTF-8")}",
    )
    assertEquals(400, response.statusCode)
    assertTrue(response.responseBody.contains("invalid_grant"))
  }

  @Test
  fun `refresh_token down-scope to a granted feature subset succeeds`() {
    // Slim tokens carry only feature scopes, so refresh down-scoping operates on feature
    // scopes (public-read). Context permissions resolve per request and are never in the token.
    val pod = sempodsTestFactory.newPod()
    val issued = seedRefreshToken(pod, scopes = setOf("public-read"))

    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${java.net.URLEncoder.encode(issued.plaintext, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
        "&scope=${java.net.URLEncoder.encode("public-read", "UTF-8")}",
    )
    assertEquals(200, response.statusCode)
    @Suppress("UNCHECKED_CAST")
    val body =
      JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    assertEquals("public-read", body["scope"], "down-scoped response must carry only the requested feature scope")
  }

  @Test
  fun `refresh_token request for a scope not previously granted returns invalid_scope`() {
    val pod = sempodsTestFactory.newPod()
    val readScope = "${contextUri(pod.name, "public/tasks")}#read"
    val issued = seedRefreshToken(pod, scopes = setOf(readScope))

    val unknownScope = "${contextUri(pod.name, "public/tasks")}#manage"
    val response = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${java.net.URLEncoder.encode(issued.plaintext, "UTF-8")}" +
        "&client_id=${java.net.URLEncoder.encode(testClientId, "UTF-8")}" +
        "&scope=${java.net.URLEncoder.encode(unknownScope, "UTF-8")}",
    )
    assertEquals(400, response.statusCode)
    assertTrue(response.responseBody.contains("invalid_scope"))
  }

  // ─── what may travel to an unvalidated address ────────────────────────────

  @Test
  fun `an unknown client_id is refused without redirecting anywhere`() {
    val pod = sempodsTestFactory.newPod(ownerUser = sempodsTestFactory.newOwner())

    // Before the validation order was fixed, this answered 307 to the supplied address: an
    // unknown client_id was reported *by redirect*, several lines before the check that rejects
    // the address. Only error parameters travelled, but it made the pod an open redirector on its
    // own origin — a link that launders through a host the user trusts.
    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", "not-a-known-client-scheme")
      .addQueryParam("redirect_uri", "https://evil.example/grab")
      .addQueryParam("state", "s")
      .setFollowRedirect(false)
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(
      response.getHeader("Location").isNullOrBlank(),
      "an unvalidated address must not appear in Location, got: ${response.getHeader("Location")}",
    )
  }

  @Test
  fun `a redirect_uri the client may not use is refused, even with a valid client_id`() {
    val pod = sempodsTestFactory.newPod(ownerUser = sempodsTestFactory.newOwner())

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", "https://evil.example/grab")
      .addQueryParam("state", "s")
      .setFollowRedirect(false)
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(response.getHeader("Location").isNullOrBlank())
  }

  @Test
  fun `only response_type=code is served`() {
    val pod = sempodsTestFactory.newPod(ownerUser = sempodsTestFactory.newOwner())

    // The AS metadata advertises `response_types_supported: ["code"]`. The parameter used to be
    // bound and never read, so `token` — the implicit grant this project does not implement —
    // reached the authorization-code path and got a code back.
    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "token")
      .addQueryParam("client_id", testClientId)
      .addQueryParam("redirect_uri", testRedirectUri)
      .addQueryParam("state", "s")
      .setFollowRedirect(false)
      .execute()

    // Now that the address is validated, the error may travel as RFC 6749 §4.1.2.1 asks.
    assertEquals(307, response.statusCode)
    val location = response.getHeader("Location").orEmpty()
    assertTrue(location.startsWith(testRedirectUri), location)
    assertTrue("unsupported_response_type" in location, location)
  }

  @Test
  fun `a did-web client is not answered over cleartext http on its own origin`() {
    val pod = sempodsTestFactory.newPod(ownerUser = sempodsTestFactory.newOwner())

    // `covers` matches host, port and path and says nothing about the scheme, so each of these
    // is the identifier's own origin over cleartext. The second is the quiet one: `:443`
    // normalizes to the port `covers` expects, and 443 is where TLS listens.
    for ((clientId, cleartext) in listOf(
      "did:web:example.org%3A8443" to "http://example.org:8443/cb",
      "did:web:example.org" to "http://example.org:443/cb",
      "did:web:apps.example.org:mcp" to "http://apps.example.org/mcp/cb",
    )) {
      val response = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", clientId)
        .addQueryParam("redirect_uri", cleartext)
        .addQueryParam("state", "s")
        .setFollowRedirect(false)
        .execute()

      assertEquals(400, response.statusCode, "should have been refused: $clientId -> $cleartext")
      assertTrue(response.getHeader("Location").isNullOrBlank(), cleartext)
    }
  }

  @Test
  fun `https on the origin a did-web client names is still answered`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    // The other side of the rule above — a non-default port and a path prefix included.
    for ((clientId, allowed) in listOf(
      "did:web:example.org%3A8443" to "https://example.org:8443/cb",
      "did:web:example.org" to "https://example.org/cb",
      "did:web:apps.example.org:mcp" to "https://apps.example.org/mcp/cb",
    )) {
      val response = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", clientId)
        .addQueryParam("redirect_uri", allowed)
        .addQueryParam("state", "s")
        .executeSignedInAs(ownerWebId)

      assertEquals(200, response.statusCode, "should have been served: $clientId -> $allowed")
      assertTrue(response.responseBody.contains("consent"), allowed)
    }
  }

  @Test
  fun `a did-web client may still be answered over http on loopback in development`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    // `example.org` does not cover `127.0.0.1`, so this reaches the development-only loopback
    // branch and nothing else. The suite runs outside a deployment, hence `Env.isDevelopment`.
    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", "did:web:example.org")
      .addQueryParam("redirect_uri", "http://127.0.0.1:51000/cb")
      .addQueryParam("state", "s")
      .executeSignedInAs(ownerWebId)

    assertEquals(200, response.statusCode, response.responseBody)
    assertTrue(response.responseBody.contains("consent"))
  }

  @Test
  fun `a native client on the IPv6 loopback address keeps its callback`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))
    createContextViaDao(checkNotNull(pod.id), pod.name, "public/tasks")

    // RFC 8252 §7.3 names `[::1]` alongside `127.0.0.1`, and `URI.getHost` returns it bracketed.
    // Registration and the ephemeral-port re-match both have to see through that.
    val dynClientId = registerDynamicClient(pod.name, "http://[::1]:51000/cb")

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", "http://[::1]:65373/cb")
      .addQueryParam("state", "ipv6-loopback-state")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .executeSignedInAs(ownerWebId)

    assertEquals(200, response.statusCode, response.responseBody)
    assertTrue(response.responseBody.contains("consent"))
  }

  @Test
  fun `a redirect_uri that is not a URI at all is a client error, not a server error`() {
    val pod = sempodsTestFactory.newPod(ownerUser = sempodsTestFactory.newOwner())

    // A malformed parameter is a client error, not a `URISyntaxException` reaching
    // `ApiExceptionMapper` as a 500. No `Location` on any of them either way.
    for (malformed in listOf(
      "https://example.org/%zz",
      "https://example.org/a b",
      "https://exa mple.org/cb",
      "http://[::1/cb",
      "::::",
    )) {
      val response = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", testClientId)
        .addQueryParam("redirect_uri", malformed)
        .addQueryParam("state", "s")
        .setFollowRedirect(false)
        .execute()

      assertEquals(400, response.statusCode, "should have been a client error: $malformed")
      assertTrue(response.getHeader("Location").isNullOrBlank(), malformed)
    }
  }

  @Test
  fun `an escaped path segment is not the same address as the two segments it spells`() {
    val ownerUser = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = ownerUser)
    val ownerWebId = webIdUriDeriver.deriveFromEmail(checkNotNull(ownerUser.email))

    // One path segment named `cb/admin` against two segments `cb` then `admin`. The `dyn:` match
    // compares canonical forms, and loopback ports are interchangeable (RFC 8252 §7.3), so the
    // path is the only thing left to disagree.
    val dynClientId = registerDynamicClient(pod.name, "http://localhost:51000/cb%2Fadmin")

    val response = http.prepareGet(authorizeUrl(pod.name))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", dynClientId)
      .addQueryParam("redirect_uri", "http://localhost:65373/cb/admin")
      .addQueryParam("state", "escaped-segment-state")
      .addQueryParam("code_challenge", testCodeChallenge)
      .addQueryParam("code_challenge_method", testCodeChallengeMethod)
      .executeSignedInAs(ownerWebId)

    assertEquals(400, response.statusCode, "an unregistered path must not be reachable through %2F")
  }

  @Test
  fun `register refuses a cleartext redirect_uri on a public host`() {
    val pod = sempodsTestFactory.newPod()

    // Refused while the client is asking, rather than at every login attempt afterwards.
    for (rejected in listOf(
      "http://apps.example.org/cb",
      "http://apps.example.org:8443/cb",
      "https://apps.example.org/cb#frag",
      // Bracketed, but not loopback: the brackets are stripped to compare, not to excuse.
      "http://[2001:db8::1]/cb",
    )) {
      val response = http.preparePost(registerUrl(pod.name))
        .addHeader("Content-Type", "application/json")
        .setBody("""{"redirect_uris":["$rejected"]}""")
        .execute()

      assertEquals(400, response.statusCode, "should have been refused: $rejected")
      assertTrue("invalid_redirect_uri" in response.responseBody, response.responseBody)
    }
  }

  @Test
  fun `a path-scoped did-web client cannot be answered on a sibling path`() {
    val pod = sempodsTestFactory.newPod(ownerUser = sempodsTestFactory.newOwner())

    // This endpoint compared host and port only, so a client scoped to one subtree could receive
    // a code meant for a neighbour on the same host. `/mcp-other` is the sharper half: it shares
    // four characters with `/mcp` and is a different service.
    for (foreign in listOf(
      "https://apps.example.org/other/cb",
      "https://apps.example.org/mcp-other/cb",
      "https://apps.example.org/cb",
    )) {
      val response = http.prepareGet(authorizeUrl(pod.name))
        .addQueryParam("response_type", "code")
        .addQueryParam("client_id", "did:web:apps.example.org:mcp")
        .addQueryParam("redirect_uri", foreign)
        .addQueryParam("state", "s")
        .setFollowRedirect(false)
        .execute()

      assertEquals(400, response.statusCode, "should have been refused: $foreign")
      assertTrue(response.getHeader("Location").isNullOrBlank(), foreign)
    }
  }
}
