package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.FakeIdServerTransport
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.okhttp.getAll
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.toPodId
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.PodSignOutStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signing out of a pod from its consent screen: what ends, what does not, and for whom.
 *
 * A sign-out ends everything the person holds on the pod — every sign-in, every app's connection,
 * every code and access token already issued — and leaves their grants, other people and other pods
 * alone. Every test builds a pod of its own.
 */
class PodSignOutHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var refreshTokenStore: PodRefreshTokenStore

  @Inject
  private lateinit var consentDecisionStore: PodConsentDecisionStore

  @Inject
  private lateinit var consentTransactionStore: ConsentTransactionStore

  @Inject
  private lateinit var signOutStore: PodSignOutStore

  @Inject
  private lateinit var podServiceClientStore: PodServiceClientStore

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var podDao: PodDao

  private class App(val clientId: String, val redirectUri: String)

  private val appA = App("did:web:localhost%3A5173", "http://localhost:5173/callback")
  private val appB = App("did:web:localhost%3A5174", "http://localhost:5174/callback")

  @Test
  fun `signing out answers the app with access_denied and withdraws the session cookie`() {
    val (pod, person) = podWithOwner()

    val response = signOut(pod, person)

    assertEquals(307, response.statusCode, response.responseBody)
    val location = checkNotNull(response.getHeader("Location"))
    assertTrue(location.startsWith(appA.redirectUri), location)
    assertTrue("error=access_denied" in location && "state=bye" in location, location)
    val withdrawn = response.headers.getAll("Set-Cookie").single { it.startsWith("sempods_pod_session=;") }
    assertTrue("Max-Age=0" in withdrawn, withdrawn)
    assertTrue("Path=${pathPrefix()}/${pod.name}/" in withdrawn, withdrawn)
  }

  @Test
  fun `a session from before the sign-out is sent to sign in again`() {
    val (pod, person) = podWithOwner()
    val cookie = signIn(pod.name, person).cookie

    signOut(pod, person)

    val again = authorize(pod, cookie)
    assertTrue(isLoginRedirect(again), "a signed-out session must not reach consent: ${again.statusCode}")
    assertNull(again.sessionCookie(), "a signed-out session must not be renewed")

    val silent = authorize(pod, cookie, prompt = "none")
    assertTrue("error=login_required" in checkNotNull(silent.getHeader("Location")), silent.getHeader("Location"))
  }

  @Test
  fun `the consent form refuses a session from before the sign-out`() {
    val (pod, person) = podWithOwner()

    signOut(pod, person)

    val response = consent(pod, person, appA, state = "late")
    assertEquals(401, response.statusCode, response.responseBody)
  }

  @Test
  fun `a sign-out ends the person's sign-in in every other browser too`() {
    val (pod, person) = podWithOwner()
    val elsewhere = sessionCookieSignedInAt(pod.name, person, Instant.now().minus(1, ChronoUnit.HOURS))
    assertEquals(200, authorize(pod, elsewhere).statusCode, "the other browser is signed in beforehand")

    signOut(pod, person)

    assertTrue(isLoginRedirect(authorize(pod, elsewhere)), "the other browser must sign in again")
  }

  @Test
  fun `every app's connection ends, whichever lifetime it was granted on`() {
    val (pod, person) = podWithOwner()
    val durable = connect(pod, person, appA, durable = true)
    val session = connect(pod, person, appB, durable = false)

    signOut(pod, person)

    for ((app, tokens) in listOf(appA to durable, appB to session)) {
      val plaintext = tokens.refreshToken
      assertEquals(RefreshTokenStore.LookupState.REVOKED, refreshTokenStore.lookup(plaintext).state, app.clientId)
      val refreshed = refresh(pod, app, plaintext)
      assertEquals(400, refreshed.statusCode, refreshed.responseBody)
      assertTrue("invalid_grant" in refreshed.responseBody, refreshed.responseBody)
    }
  }

  @Test
  fun `an access token issued before the sign-out is refused on a resource and on the pod's MCP endpoint`() {
    val (pod, person) = podWithOwner()
    val accessToken = connect(pod, person, appA).accessToken
    assertEquals(200, dateModified(pod, accessToken).statusCode, "the token works beforehand")
    assertEquals(200, mcpToolsList(pod, accessToken).statusCode, "the token works beforehand")

    signOut(pod, person)

    val refused = dateModified(pod, accessToken)
    assertEquals(401, refused.statusCode, refused.responseBody)
    assertNotNull(refused.getHeader("WWW-Authenticate"), "a 401 names how to get a new token")
    assertEquals(401, mcpToolsList(pod, accessToken).statusCode)
  }

  @Test
  fun `another person's token and a service client's token keep working`() {
    val (pod, person) = podWithOwner()
    val somebodyElse = mintScopedToken(pod.name, emptyList(), webId = "${FakeIdServerTransport.ISSUER}/e/somebody-else")
    val service = mintServiceToken(pod)

    signOut(pod, person)

    assertEquals(200, dateModified(pod, somebodyElse).statusCode, "a sign-out is one person's")
    assertEquals(200, dateModified(pod, service).statusCode, "a service client names no person")
  }

  @Test
  fun `a code issued before the sign-out is refused when it is exchanged after it`() {
    val (pod, person) = podWithOwner()
    val code = codeFrom(consent(pod, person, appA, state = "pending"))

    signOut(pod, person)

    val exchanged = exchangeCode(pod, appA, code)
    assertEquals(400, exchanged.statusCode, exchanged.responseBody)
    assertTrue("invalid_grant" in exchanged.responseBody, exchanged.responseBody)
  }

  @Test
  fun `signing in again after a sign-out works, and the access granted before it still stands`() {
    val (pod, person) = podWithOwner()
    connect(pod, person, appA)
    signOut(pod, person)
    awaitSecondAfter(checkNotNull(signOutStore.signedOutAt(checkNotNull(pod.id), listOf(person))))

    // No consent screen: the grants and the answer survived the sign-out, so the app is let back in
    // silently once the person has proved themselves again.
    val signedIn = http.prepareGet(authorizeUrl(pod))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", appA.clientId)
      .addQueryParam("redirect_uri", appA.redirectUri)
      .addQueryParam("state", "back")
      .executeSignedInAs(person)
    assertEquals(303, signedIn.statusCode, signedIn.responseBody)

    val exchanged = exchangeCode(pod, appA, codeFrom(signedIn))
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    assertEquals(200, dateModified(pod, Tokens.of(exchanged).accessToken).statusCode)
    assertTrue(
      podGrantsDao.fetchGrantStrings(checkNotNull(pod.id), appA.clientId, listOf(person)).isNotEmpty(),
      "the grants outlive a sign-out",
    )
  }

  @Test
  fun `a sign-out reaches the person's aliases and their derivable twins`() {
    val (pod, person) = podWithOwner()
    val alias = "https://alias.example/profile#me"
    val twin = checkNotNull(webIdUriDeriver.derivableAliases(person).firstOrNull { it != person }) {
      "the owner's WebID must have a derivable twin for this test to mean anything"
    }
    val familyUnderTwin = seedFamily(pod, appA, twin)
    val aliasToken = mintScopedToken(pod.name, emptyList(), webId = alias)
    val aliasSession = sessionCookieSignedInAt(pod.name, alias, Instant.now().minus(1, ChronoUnit.HOURS))

    signOut(pod, person, cookie = signIn(pod.name, person, alsoKnownAs = listOf(alias)).cookie)

    assertEquals(RefreshTokenStore.LookupState.REVOKED, refreshTokenStore.lookup(familyUnderTwin).state)
    assertEquals(401, dateModified(pod, aliasToken).statusCode, "a token issued under the alias")
    assertTrue(isLoginRedirect(authorize(pod, aliasSession)), "a session signed in as the alias")
  }

  @Test
  fun `a sign-out on one pod leaves the same person's other pod alone`() {
    val owner = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = owner)
    val other = sempodsTestFactory.newPod(ownerUser = owner)
    val person = webIdUriDeriver.deriveFromEmail(checkNotNull(owner.email))
    val there = connect(other, person, appA)

    signOut(pod, person)

    assertEquals(200, dateModified(other, there.accessToken).statusCode)
    assertEquals(200, refresh(other, appA, there.refreshToken).statusCode)
    assertEquals(200, authorize(other, signIn(other.name, person).cookie).statusCode)
  }

  @Test
  fun `a sign-out without the screen's token, or with another person's, ends nothing`() {
    val (pod, person) = podWithOwner()
    val tokens = connect(pod, person, appA)

    assertEquals(403, signOut(pod, person, csrf = null).statusCode, "no token")
    val strangers = consentTransactionStore.issue(pod.name, "${FakeIdServerTransport.ISSUER}/e/somebody-else")
    assertEquals(403, signOut(pod, person, csrf = strangers).statusCode, "somebody else's token")

    assertEquals(200, dateModified(pod, tokens.accessToken).statusCode)
    assertEquals(200, authorize(pod, signIn(pod.name, person).cookie).statusCode)
    assertEquals(200, refresh(pod, appA, tokens.refreshToken).statusCode)
  }

  @Test
  fun `a page rendered before the app was disconnected can still sign out`() {
    // That check stops an old page writing grants back. A sign-out writes none, and refusing it would
    // leave the person signed in with the button in front of them doing nothing.
    val (pod, person) = podWithOwner()
    connect(pod, person, appA)
    val renderedBefore = formToken(pod, person, appA)
    assertEquals(307, consent(pod, person, appA, state = "gone", action = "disconnect").statusCode)

    val response = signOut(pod, person, csrf = renderedBefore)

    assertEquals(307, response.statusCode, response.responseBody)
    assertTrue("signed+out" in checkNotNull(response.getHeader("Location")), response.getHeader("Location"))
    assertTrue(isLoginRedirect(authorize(pod, signIn(pod.name, person).cookie)))
  }

  @Test
  fun `a sign-in in the very second of a sign-out counts as signed out, and one a second later does not`() {
    // `auth_time` is whole seconds, so the same second cannot say which came first — and the answer
    // that fails closed is the one a sign-out owes.
    val (pod, person) = podWithOwner()
    signOut(pod, person)
    val signedOutAt = checkNotNull(signOutStore.signedOutAt(checkNotNull(pod.id), listOf(person)))

    val sameSecond = sessionCookieSignedInAt(pod.name, person, signedOutAt.truncatedTo(ChronoUnit.SECONDS))
    assertTrue(isLoginRedirect(authorize(pod, sameSecond)))

    val secondLater = sessionCookieSignedInAt(pod.name, person, signedOutAt.plusSeconds(1))
    assertEquals(200, authorize(pod, secondLater).statusCode)
  }

  @Test
  fun `the consent screen offers a sign-out to whoever is signed in`() {
    val (pod, person) = podWithOwner()

    val page = authorize(pod, signIn(pod.name, person).cookie)

    assertEquals(200, page.statusCode)
    assertTrue("id=\"signOutBtn\"" in page.responseBody && "value=\"signout\"" in page.responseBody)
  }

  @Test
  fun `a sign-out follows the pod the name resolves to now`() {
    // This process caches name to id, and no replica clears another's deletion. Resolving the name
    // in the sign-out would end the pod that is gone while the person is looking at its successor,
    // and report success for both.
    val owner = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = owner)
    val person = webIdUriDeriver.deriveFromEmail(checkNotNull(owner.email))
    val tokens = connect(pod, person, appA)
    assertEquals(200, dateModified(pod, tokens.accessToken).statusCode, "warms this process's cache")

    // What another replica's delete and create leaves behind: the same name, a new row, and a cache
    // entry nothing here cleared.
    podDao.delete(pod.name)
    val recreated = sempodsTestFactory.newPod(name = pod.name, ownerUser = owner, createPublicContext = false)
    assertNotEquals(pod.id, recreated.id)
    val family = seedFamily(recreated, appA, person)

    signOut(recreated, person)

    assertEquals(RefreshTokenStore.LookupState.REVOKED, refreshTokenStore.lookup(family).state)
    assertNotNull(signOutStore.signedOutAt(checkNotNull(recreated.id), listOf(person)), "under the new id")
  }

  @Test
  fun `the sweep reaches a row already rotated, so a rotation in flight finds its predecessor gone`() {
    // The one interleaving a test can stage: the rotation has marked its row and not yet inserted the
    // successor when the sign-out sweeps. The others — a token signed before a check, a code issued
    // after a generation read — sit between two statements of one request, and no test reaches them.
    val (pod, person) = podWithOwner()
    val plaintext = seedFamily(pod, appA, person)
    val previous = checkNotNull(refreshTokenStore.lookup(plaintext).token)
    assertTrue(refreshTokenStore.markRotated(previous.tokenHash))

    signOut(pod, person)

    refreshTokenStore.issueInFamily(previous, previous.scopes)
    assertTrue(refreshTokenStore.noLongerStands(previous.tokenHash), "the successor's predecessor must read revoked")
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private fun podWithOwner(): Pair<PodDbo, String> {
    val owner = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = owner)
    return pod to webIdUriDeriver.deriveFromEmail(checkNotNull(owner.email))
  }

  private fun pathPrefix(): String = java.net.URI(SempodsModule.config.apiBaseUrl).rawPath.orEmpty().removeSuffix("/")

  private fun authorizeUrl(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize"

  private fun tokenUrl(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/token"

  private fun authorize(pod: PodDbo, cookie: String, prompt: String = "consent"): TestHttpResponse =
    http.prepareGet(authorizeUrl(pod))
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", appA.clientId)
      .addQueryParam("redirect_uri", appA.redirectUri)
      .addQueryParam("state", "probe")
      .addQueryParam("prompt", prompt)
      .addHeader("Cookie", cookie)
      .setFollowRedirect(false).execute()

  private fun isLoginRedirect(response: TestHttpResponse): Boolean =
    response.getHeader("Location")?.startsWith(FakeIdServerTransport.ISSUER) == true

  /** The token a page rendered right now would carry, for [app]. */
  private fun formToken(pod: PodDbo, webId: String, app: App): String =
    consentTransactionStore.issue(
      pod.name,
      webId,
      consentDecisionStore.find(checkNotNull(pod.id).toPodId(), app.clientId, listOf(webId))?.generation,
    )

  private fun consent(
    pod: PodDbo,
    webId: String,
    app: App,
    state: String,
    durable: Boolean = false,
    action: String? = null,
    cookie: String = signIn(pod.name, webId).cookie,
    csrf: String? = formToken(pod, webId, app),
  ): TestHttpResponse =
    http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", cookie)
      .setBody(
        "client_id=${enc(app.clientId)}&redirect_uri=${enc(app.redirectUri)}&state=$state" +
          (csrf?.let { "&csrf=${enc(it)}" } ?: "") +
          (action?.let { "&action=$it" } ?: "&scope=public-read") +
          (if (durable) "&durable=1" else ""),
      )
      .setFollowRedirect(false).execute()

  private fun signOut(
    pod: PodDbo,
    webId: String,
    cookie: String = signIn(pod.name, webId).cookie,
    csrf: String? = formToken(pod, webId, appA),
  ): TestHttpResponse = consent(pod, webId, appA, state = "bye", action = "signout", cookie = cookie, csrf = csrf)

  private class Tokens(val accessToken: String, val refreshToken: String) {
    companion object {
      fun of(response: TestHttpResponse): Tokens {
        val body = JsonMappers.default().readValue(response.responseBody, Map::class.java)
        return Tokens(body["access_token"] as String, body["refresh_token"] as String)
      }
    }
  }

  /** Consent, then the exchange — an app connected the way a browser connects it. */
  private fun connect(pod: PodDbo, webId: String, app: App, durable: Boolean = false): Tokens {
    val exchanged = exchangeCode(pod, app, codeFrom(consent(pod, webId, app, state = "connect", durable = durable)))
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    return Tokens.of(exchanged)
  }

  private fun codeFrom(response: TestHttpResponse): String {
    val location = checkNotNull(response.getHeader("Location")) { "no redirect: ${response.statusCode} ${response.responseBody}" }
    return Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1) ?: error("no code in $location")
  }

  private fun exchangeCode(pod: PodDbo, app: App, code: String): TestHttpResponse = postForm(
    tokenUrl(pod),
    "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(app.redirectUri)}&client_id=${enc(app.clientId)}",
  )

  private fun refresh(pod: PodDbo, app: App, refreshToken: String): TestHttpResponse = postForm(
    tokenUrl(pod),
    "grant_type=refresh_token&refresh_token=${enc(refreshToken)}&client_id=${enc(app.clientId)}",
  )

  private fun postForm(url: String, body: String): TestHttpResponse =
    http.preparePost(url)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody(body)
      .execute()

  /** A family seeded under [webId] with a grant behind it, as a code exchange would leave it. */
  private fun seedFamily(pod: PodDbo, app: App, webId: String): String {
    val grants = setOf("${sempodsTestFactory.publicContextUri(pod.name)}#read")
    podGrantsDao.addGrants(podId = checkNotNull(pod.id), appId = app.clientId, webId = webId, grants = grants, grantedBy = webId)
    return refreshTokenStore.issueNewFamily(
      pod = checkNotNull(pod.id).toPodId(),
      podName = pod.name,
      clientId = app.clientId,
      webId = webId,
      scopes = emptySet(),
      lifetime = PodRefreshTokenStore.Lifetime.SESSION,
    ).plaintext
  }

  private fun dateModified(pod: PodDbo, accessToken: String): TestHttpResponse =
    http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/meta/date-modified")
      .addHeader("Authorization", "Bearer $accessToken")
      .execute()

  private fun mcpToolsList(pod: PodDbo, accessToken: String): TestHttpResponse =
    http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/mcp")
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $accessToken")
      .setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
      .execute()

  /** A `client_credentials` token through the token endpoint, for a client registered on [pod]. */
  private fun mintServiceToken(pod: PodDbo): String {
    val registered = podServiceClientStore.register(
      podId = checkNotNull(pod.id),
      podBaseUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/",
      clientId = "notes-app",
      scopes = setOf("${sempodsTestFactory.publicContextUri(pod.name)}#read"),
    )
    val basic = Base64.getEncoder().encodeToString(
      "${enc(registered.dbo.clientId)}:${enc(registered.plaintextSecret)}".toByteArray(Charsets.UTF_8),
    )
    val response = http.preparePost(tokenUrl(pod))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", "Basic $basic")
      .setBody("grant_type=client_credentials")
      .execute()
    assertEquals(200, response.statusCode, response.responseBody)
    return JsonMappers.default().readValue(response.responseBody, Map::class.java)["access_token"] as String
  }

  /**
   * Waits until the clock has passed [instant]'s second — at most one second, and a condition rather
   * than a guess: a sign-in within the second of a sign-out counts as signed out.
   */
  private fun awaitSecondAfter(instant: Instant) {
    while (Instant.now().epochSecond <= instant.epochSecond) Thread.sleep(20)
  }

  private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
