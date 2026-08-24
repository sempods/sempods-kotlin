package org.sempods.auth.api.login

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.forms.submitForm
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.sempods.auth.SempodsAuthCollections
import org.sempods.auth.SempodsAuthIntegrationTest
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.OidcPrompt
import org.sempods.auth.login.LoginService
import org.sempods.auth.login.StateStore
import org.sempods.auth.oidc.OidcClaims
import org.sempods.auth.oidc.OidcProviderClient
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where Google and Apple answer: `GET|POST /login/oidc/{provider}/callback`.
 *
 * This route had **no test at all** — the only thing that ever installed the endpoint was
 * `LoginEndpointTest`, and every case there drove `GET /login` instead. It is also the route with
 * the least room to be wrong: its path is registered in Apple's and Google's developer consoles,
 * so it cannot be moved, and a person who has just proved who they are is standing on it.
 *
 * The provider is a stand-in so nothing here touches the network, but it returns real claims —
 * unlike the fake in `LoginEndpointTest`, which throws, which is precisely why the callback ended
 * up uncovered. `LoginService` and the code store are the real ones, per this module's convention.
 */
class ProviderCallbackEndpointTest : SempodsAuthIntegrationTest() {

  private class FakeProvider(
    override val name: String,
    override val displayName: String,
    private val subject: String,
  ) : OidcProviderClient {

    override fun authorizeUrl(state: String, prompt: OidcPrompt?): String =
      "https://$name.example.invalid/authorize?state=$state"

    override suspend fun handleCallback(code: String, callbackParams: Map<String, String>) =
      OidcClaims(
        iss = "https://$name.example.invalid",
        sub = subject,
        email = "$subject@example.invalid",
        displayName = "Test Person",
      )
  }

  private val clientId = "did:web:pod.example.invalid"
  private val redirect = "https://pod.example.invalid/cb"

  private fun google() = FakeProvider("google", "Google", subject = "sub-${uniqueHash()}")

  private val stateStore: StateStore get() = injector.getInstance(StateStore::class.java)
  private val codeStore: AuthorizationCodeStore get() = injector.getInstance(AuthorizationCodeStore::class.java)

  private fun withCallback(
    vararg providers: OidcProviderClient,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
  ) = testApplication {
    application {
      providerCallbackEndpoint(
        stateStore = stateStore,
        providers = providers.associateBy { it.name },
        loginService = injector.getInstance(LoginService::class.java),
        authorizationCodeStore = codeStore,
        issuer = testConfig.idBaseUrl,
      )
    }
    block(createClient { followRedirects = false })
  }

  /** An authorization request parked exactly as `/authorize` parks one. */
  private fun parkAuthorizeRequest(clientState: String? = "client-state"): String =
    stateStore.generate(
      pending = StateStore.PendingAuthorize(
        clientId = clientId,
        redirectUri = redirect,
        clientState = clientState,
        nonce = "the-nonce",
        codeChallenge = "the-challenge",
        codeChallengeMethod = "S256",
        scopes = setOf("openid"),
      ),
    )

  // ─── what the route refuses ───────────────────────────────────────────────

  @Test
  fun `an unknown provider on the callback path is refused, not substituted`() = withCallback(google()) { client ->
    // The `{provider}` segment picks which upstream is asked to interpret the code. Falling back to
    // whichever one happens to be configured would hand a code to the wrong issuer.
    val response = client.get("/login/oidc/facebook/callback?state=x&code=y")

    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `a callback with no state is refused`() = withCallback(google()) { client ->
    val response = client.get("/login/oidc/google/callback?code=y")

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `a state nobody issued is refused`() = withCallback(google()) { client ->
    val response = client.get("/login/oidc/google/callback?state=never-issued&code=y")

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `a replayed callback finds nothing`() = withCallback(google()) { client ->
    val state = parkAuthorizeRequest()
    val url = "/login/oidc/google/callback?state=$state&code=an-upstream-code"

    assertEquals(HttpStatusCode.Found, client.get(url).status, "the first callback completes")
    // One-time at the route, not merely in the store: a captured callback URL replayed later must
    // not mint a second authorization code for the same login.
    assertEquals(HttpStatusCode.BadRequest, client.get(url).status)
  }

  @Test
  fun `a callback with a state but no code is refused`() = withCallback(google()) { client ->
    val response = client.get("/login/oidc/google/callback?state=${parkAuthorizeRequest()}")

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `a state parked by the old flow is not resumable`() = withCallback(google()) { client ->
    // `GET /login` parked a `returnTo` and no authorization request. Such a row can be in flight
    // across the deploy that removed it, and there is nothing to answer it with — the store reads
    // it as no row at all. Written straight to Mongo because nothing can produce one any more.
    val state = "a-state-from-the-old-flow-${uniqueHash()}"
    injector.getInstance(MongoDatabase::class.java).getCollection(SempodsAuthCollections.OAUTH_LOGIN_STATES).insertOne(
      Document().apply {
        put("_id", sha256Hex(state))
        put("returnTo", "https://somewhere.example.invalid/cb")
        put("expiresAt", Date(System.currentTimeMillis() + 600_000))
      },
    )

    val response = client.get("/login/oidc/google/callback?state=$state&code=an-upstream-code")

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  // ─── what the route answers ───────────────────────────────────────────────

  @Test
  fun `a provider that declines comes back as an OAuth error at the client's own address`() =
    withCallback(google()) { client ->
      // Apple sends `error=user_cancelled_authorize` when someone backs out. That is a normal
      // outcome, and the client can only act on it if it arrives at the address the client named.
      val state = parkAuthorizeRequest()

      val response = client.get(
        "/login/oidc/google/callback?state=$state&error=user_cancelled_authorize" +
          "&error_description=the+person+changed+their+mind",
      )

      assertEquals(HttpStatusCode.Found, response.status)
      val location = Url(checkNotNull(response.headers["Location"]))
      assertEquals("pod.example.invalid", location.host)
      assertEquals("user_cancelled_authorize", location.parameters["error"])
      assertEquals("client-state", location.parameters["state"], "the client's own state must come back")
    }

  @Test
  fun `the answer to an authorization request is a code, never a token`() = withCallback(google()) { client ->
    val state = parkAuthorizeRequest()

    val response = client.get("/login/oidc/google/callback?state=$state&code=an-upstream-code")

    assertEquals(HttpStatusCode.Found, response.status)
    val location = Url(checkNotNull(response.headers["Location"]))
    assertEquals("https://pod.example.invalid/cb", "${location.protocol.name}://${location.host}${location.encodedPath}")
    assertEquals("client-state", location.parameters["state"])
    // This is the assertion that would fail if the removed `/login` half were ever restored: the
    // front channel carries a single-use code and nothing that authenticates anyone.
    val raw = checkNotNull(response.headers["Location"])
    assertFalse("token=" in raw, "no token may travel in the browser: $raw")
    assertFalse("id_token" in raw, raw)
    assertFalse("access_token" in raw, raw)

    // And the code is bound to everything the exchange has to re-check.
    val entry = assertNotNull(codeStore.consume(checkNotNull(location.parameters["code"])))
    assertEquals(clientId, entry.clientId)
    assertEquals(redirect, entry.redirectUri)
    assertEquals("the-challenge", entry.codeChallenge)
    assertEquals("S256", entry.codeChallengeMethod)
    assertEquals("the-nonce", entry.nonce)
    assertEquals(setOf("openid"), entry.scopes)
    assertTrue(entry.subject.startsWith(testConfig.idBaseUrl), "the subject is a WebID on this issuer: ${entry.subject}")
  }

  @Test
  fun `Apple's form_post callback is the same flow as Google's query one`() = withCallback(
    FakeProvider("apple", "Apple", subject = "apple-sub-${uniqueHash()}"),
  ) { client ->
    // Apple answers with `response_mode=form_post` — a cross-site POST with the parameters in the
    // body. It is the only reason `post("/callback")` exists, and nothing covered it.
    val state = parkAuthorizeRequest()

    val response = client.submitForm(
      url = "/login/oidc/apple/callback",
      formParameters = parameters {
        append("state", state)
        append("code", "an-upstream-code")
      },
    )

    assertEquals(HttpStatusCode.Found, response.status)
    val location = Url(checkNotNull(response.headers["Location"]))
    assertNotNull(location.parameters["code"], "a POST callback must mint a code just like a GET one")
    assertNull(location.parameters["token"])
  }

  @Test
  fun `a request without a client state comes back without one`() = withCallback(google()) { client ->
    // `state` is optional for the client. Echoing an empty one back would be a parameter the client
    // never sent.
    val state = parkAuthorizeRequest(clientState = null)

    val response = client.get("/login/oidc/google/callback?state=$state&code=an-upstream-code")

    val location = Url(checkNotNull(response.headers["Location"]))
    assertNotNull(location.parameters["code"])
    assertNull(location.parameters["state"])
  }
}
