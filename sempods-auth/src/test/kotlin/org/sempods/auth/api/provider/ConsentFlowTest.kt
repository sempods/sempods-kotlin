package org.sempods.auth.api.provider

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.sempods.auth.SempodsAuthCollections
import org.sempods.auth.SempodsAuthIntegrationTest
import org.sempods.auth.api.login.providerCallbackEndpoint
import org.sempods.auth.api.webid.webIdEndpoint
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.DidWebRedirectPolicy
import org.sempods.auth.core.OidcPrompt
import org.sempods.auth.core.Pkce
import org.sempods.auth.login.JwtIssuer
import org.sempods.auth.login.LoginService
import org.sempods.auth.login.StateStore
import org.sempods.auth.oidc.OidcClaims
import org.sempods.auth.oidc.OidcProviderClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agreement step (sempods-kotlin#440) and the claims it releases (#435), driven from the
 * provider callback through `/consent` to `/token`, as a browser and a client would.
 *
 * The upstream provider is a stand-in returning real claims; everything after it is the real
 * service against the test database.
 */
class ConsentFlowTest : SempodsAuthIntegrationTest() {

  private class FakeProvider(
    override val name: String,
    private val subject: String,
    private val email: String?,
    private val picture: String?,
  ) : OidcProviderClient {
    override val displayName = name

    override fun authorizeUrl(state: String, prompt: OidcPrompt?): String =
      "https://$name.example.invalid/authorize?state=$state"

    override suspend fun handleCallback(code: String, callbackParams: Map<String, String>) =
      OidcClaims(
        iss = "https://$name.example.invalid",
        sub = subject,
        email = email,
        displayName = "Alice Example",
        picture = picture,
      )
  }

  private val clientId = "did:web:console.example.invalid"
  private val redirect = "https://console.example.invalid/cb"
  private val verifier = Pkce.generateVerifier()

  /** A Google-like person: verified address and a picture. Same person on every sign-in. */
  private fun google(): FakeProvider {
    val subject = "sub-${uniqueHash()}"
    return FakeProvider("google", subject, email = "$subject@example.invalid", picture = "https://img.example.invalid/$subject.png")
  }

  private val stateStore: StateStore get() = injector.getInstance(StateStore::class.java)

  private fun withFlow(provider: OidcProviderClient, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
    testApplication {
      application {
        val completion = injector.getInstance(AuthorizationCompletion::class.java)
        providerCallbackEndpoint(
          stateStore = stateStore,
          providers = mapOf(provider.name to provider),
          loginService = injector.getInstance(LoginService::class.java),
          completion = completion,
          issuer = testConfig.idBaseUrl,
        )
        consentEndpoint(completion)
        openIdProviderEndpoint(
          issuer = testConfig.idBaseUrl,
          providers = mapOf(provider.name to provider),
          clientRedirectPolicy = DidWebRedirectPolicy(),
          stateStore = stateStore,
          authorizationCodeStore = injector.getInstance(AuthorizationCodeStore::class.java),
          jwtIssuer = injector.getInstance(JwtIssuer::class.java),
          loginService = injector.getInstance(LoginService::class.java),
        )
        webIdEndpoint(webIdProfileDao, testConfig)
      }
      block(createClient { followRedirects = false })
    }

  /** The callback for an authorization request parked as `/authorize` parks one. */
  private suspend fun signIn(
    http: HttpClient,
    provider: OidcProviderClient,
    scope: String,
    promptConsent: Boolean = false,
  ): HttpResponse {
    val state = stateStore.generate(
      StateStore.PendingAuthorize(
        clientId = clientId,
        redirectUri = redirect,
        clientState = "client-state",
        nonce = "n",
        codeChallenge = Pkce.challengeFor(verifier),
        codeChallengeMethod = Pkce.METHOD_S256,
        scopes = scope.split(" ").toSet(),
        promptConsent = promptConsent,
      ),
    )
    return http.get("/login/oidc/${provider.name}/callback?state=$state&code=upstream-code")
  }

  private suspend fun answer(http: HttpClient, screen: HttpResponse, decision: String): HttpResponse {
    val key = consentKey(screen.bodyAsText())
    return http.submitForm(
      "/consent",
      parameters {
        append("consent", key)
        append("decision", decision)
      },
    )
  }

  private fun consentKey(html: String): String =
    Regex("name=\"consent\" value=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: error("no consent form in $html")

  private fun isScreen(response: HttpResponse) = response.status == HttpStatusCode.OK

  /** The id_token's claims for the code a redirect carries. */
  private suspend fun claimsFor(http: HttpClient, redirect: HttpResponse): JWTClaimsSet {
    assertEquals(HttpStatusCode.Found, redirect.status)
    val location = Url(redirect.headers[HttpHeaders.Location]!!)
    assertEquals("console.example.invalid", location.host)
    val code = location.parameters["code"] ?: error("no code in $location")
    val body = http.post("/token") {
      contentType(ContentType.Application.FormUrlEncoded)
      setBody(
        "grant_type=authorization_code&code=$code&client_id=${clientId.encodeURLParameter()}" +
          "&redirect_uri=${this@ConsentFlowTest.redirect.encodeURLParameter()}&code_verifier=$verifier",
      )
    }.bodyAsText()
    val idToken = Regex("\"id_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: error("no id_token in $body")
    return SignedJWT.parse(idToken).jwtClaimsSet
  }

  // ─── the agreement screen ─────────────────────────────────────────────────

  @Test
  fun `the first request for claims shows the screen, naming the client by its verified host`() {
    val google = google()
    withFlow(google) { http ->
      val screen = signIn(http, google, "openid email")

      assertTrue(isScreen(screen))
      val html = screen.bodyAsText()
      assertTrue("console.example.invalid asks for" in html, html)
      assertTrue("Your email address" in html, html)
      assertFalse("Your name and picture" in html, "only what was asked for is listed: $html")
      assertEquals("DENY", screen.headers["X-Frame-Options"])
      assertTrue("frame-ancestors 'none'" in screen.headers["Content-Security-Policy"].orEmpty())
      assertTrue("no-store" in screen.headers[HttpHeaders.CacheControl].orEmpty())
    }
  }

  @Test
  fun `allow is remembered, so the next request within it shows no screen`() {
    val google = google()
    withFlow(google) { http ->
      val first = answer(http, signIn(http, google, "openid profile email"), "allow")
      assertEquals(HttpStatusCode.Found, first.status)
      assertEquals("client-state", Url(first.headers[HttpHeaders.Location]!!).parameters["state"])

      assertEquals(HttpStatusCode.Found, signIn(http, google, "openid email").status, "within the agreement")
      assertEquals(HttpStatusCode.Found, signIn(http, google, "openid profile email").status)
    }
  }

  @Test
  fun `deny completes the sign-in without the claims, and is not remembered`() {
    val google = google()
    withFlow(google) { http ->
      val claims = claimsFor(http, answer(http, signIn(http, google, "openid profile email"), "deny"))

      assertTrue(claims.subject.startsWith("${testConfig.idBaseUrl}/e/"), "the client keeps the WebID")
      assertNull(claims.getClaim("email"))
      assertNull(claims.getClaim("name"))
      assertTrue(isScreen(signIn(http, google, "openid email")), "the next request asks again")
    }
  }

  @Test
  fun `prompt=consent shows the screen again despite an agreement`() {
    val google = google()
    withFlow(google) { http ->
      answer(http, signIn(http, google, "openid email"), "allow")

      assertTrue(isScreen(signIn(http, google, "openid email", promptConsent = true)))
    }
  }

  @Test
  fun `a request outside the agreement shows the screen again`() {
    val google = google()
    withFlow(google) { http ->
      answer(http, signIn(http, google, "openid email"), "allow")

      val screen = signIn(http, google, "openid profile email")
      assertTrue(isScreen(screen))
      assertTrue("Your name and picture" in screen.bodyAsText())
    }
  }

  @Test
  fun `a removed decision shows the screen again`() {
    val google = google()
    withFlow(google) { http ->
      val signedIn = claimsFor(http, answer(http, signIn(http, google, "openid email"), "allow"))
      injector.getInstance(MongoDatabase::class.java)
        .getCollection(SempodsAuthCollections.OAUTH_CONSENT_DECISIONS)
        .deleteMany(Filters.eq("webId", signedIn.subject))

      assertTrue(isScreen(signIn(http, google, "openid email")))
    }
  }

  @Test
  fun `openid alone never shows the screen`() {
    val google = google()
    withFlow(google) { http ->
      assertEquals(HttpStatusCode.Found, signIn(http, google, "openid").status)
      assertEquals(HttpStatusCode.Found, signIn(http, google, "openid", promptConsent = true).status, "nothing to agree to")
    }
  }

  @Test
  fun `an answer without the form's key is refused as forged`() {
    val google = google()
    withFlow(google) { http ->
      val response = http.submitForm("/consent", parameters { append("decision", "allow") })

      assertEquals(HttpStatusCode.Forbidden, response.status)
    }
  }

  @Test
  fun `an answer is spent on first use, and a key nobody issued finds nothing`() {
    val google = google()
    withFlow(google) { http ->
      val screen = signIn(http, google, "openid email")
      val key = consentKey(screen.bodyAsText())
      val form = parameters {
        append("consent", key)
        append("decision", "allow")
      }

      assertEquals(HttpStatusCode.Found, http.submitForm("/consent", form).status)
      assertEquals(HttpStatusCode.BadRequest, http.submitForm("/consent", form).status)
      val unknown = http.submitForm("/consent", parameters {
        append("consent", "never-issued")
        append("decision", "allow")
      })
      assertEquals(HttpStatusCode.BadRequest, unknown.status)
    }
  }

  @Test
  fun `a malformed decision spends nothing`() {
    val google = google()
    withFlow(google) { http ->
      val screen = signIn(http, google, "openid email")
      val key = consentKey(screen.bodyAsText())

      val bad = http.submitForm("/consent", parameters {
        append("consent", key)
        append("decision", "maybe")
      })
      assertEquals(HttpStatusCode.BadRequest, bad.status)
      assertEquals(HttpStatusCode.Found, answer(http, screen, "allow").status)
    }
  }

  // ─── what is released ─────────────────────────────────────────────────────

  @Test
  fun `with agreement, profile and email release name, picture and the verified address`() {
    val google = google()
    withFlow(google) { http ->
      val claims = claimsFor(http, answer(http, signIn(http, google, "openid profile email"), "allow"))

      assertEquals("Alice Example", claims.getStringClaim("name"))
      assertTrue(claims.getStringClaim("picture").startsWith("https://img.example.invalid/"))
      assertTrue(claims.getStringClaim("email").endsWith("@example.invalid"))
      assertEquals(true, claims.getBooleanClaim("email_verified"))
    }
  }

  @Test
  fun `openid alone releases none of them`() {
    val google = google()
    withFlow(google) { http ->
      val claims = claimsFor(http, signIn(http, google, "openid"))

      listOf("name", "picture", "email", "email_verified").forEach { assertNull(claims.getClaim(it), it) }
    }
  }

  @Test
  fun `email alone releases no profile claims`() {
    val google = google()
    withFlow(google) { http ->
      val claims = claimsFor(http, answer(http, signIn(http, google, "openid email"), "allow"))

      assertTrue(claims.getStringClaim("email").endsWith("@example.invalid"))
      assertNull(claims.getClaim("name"))
      assertNull(claims.getClaim("picture"))
    }
  }

  @Test
  fun `a provider without a picture releases no picture`() {
    val subject = "sub-${uniqueHash()}"
    val apple = FakeProvider("apple", subject, email = "$subject@example.invalid", picture = null)
    withFlow(apple) { http ->
      val claims = claimsFor(http, answer(http, signIn(http, apple, "openid profile"), "allow"))

      assertEquals("Alice Example", claims.getStringClaim("name"))
      assertNull(claims.getClaim("picture"))
    }
  }

  @Test
  fun `a person without a verified address signs in with email and gets neither email claim`() {
    val noEmail = FakeProvider("apple", "sub-${uniqueHash()}", email = null, picture = null)
    withFlow(noEmail) { http ->
      val claims = claimsFor(http, answer(http, signIn(http, noEmail, "openid email"), "allow"))

      assertTrue(claims.subject.startsWith("${testConfig.idBaseUrl}/oidc/"), claims.subject)
      assertNull(claims.getClaim("email"))
      assertNull(claims.getClaim("email_verified"))
    }
  }

  @Test
  fun `the WebID document shows neither the address nor the upstream picture`() {
    val google = google()
    withFlow(google) { http ->
      val webId = claimsFor(http, signIn(http, google, "openid")).subject
      val path = Url(webId).encodedPath

      for (accept in listOf("text/turtle", "application/ld+json", "text/html")) {
        val document = http.get(path) { header(HttpHeaders.Accept, accept) }.bodyAsText()
        assertTrue("Alice Example" in document, "the public name is there: $document")
        assertFalse("@example.invalid" in document, "$accept: $document")
        assertFalse("img.example.invalid" in document, "$accept: $document")
      }
    }
  }
}
