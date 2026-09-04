package org.sempods.auth.api.provider

import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.sempods.auth.SempodsAuthIntegrationTest
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.ClientRedirectPolicy
import org.sempods.auth.core.DidWebRedirectPolicy
import org.sempods.auth.core.OidcPrompt
import org.sempods.auth.core.OidcProviderMetadata
import org.sempods.auth.core.Pkce
import org.sempods.auth.login.JwtIssuer
import org.sempods.auth.login.LoginService
import org.sempods.auth.login.StateStore
import org.sempods.auth.oidc.OidcClaims
import org.sempods.auth.oidc.OidcProviderClient
import org.sempods.commons.logging.CapturedLog
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/authorize` and `/token` — the flow that replaced `?return_to=`.
 *
 * Two properties carry most of the weight here, and both are invisible when they break:
 *
 * - **Nothing reaches an address before that address is proven.** An error redirect to whatever
 *   the request supplied would make this an open redirector on the issuer's own origin, and it is
 *   exactly the shape the pod server had.
 * - **The token never travels in the front channel.** What comes back through the browser is a
 *   single-use code; the token is fetched over a back channel, bound to a PKCE verifier only the
 *   real client holds.
 */
class OpenIdProviderEndpointTest : SempodsAuthIntegrationTest() {

  private class FakeProvider(override val name: String, override val displayName: String) : OidcProviderClient {
    override fun authorizeUrl(state: String, prompt: OidcPrompt?): String =
      "https://$name.example.invalid/authorize?state=$state"

    override suspend fun handleCallback(code: String, callbackParams: Map<String, String>): OidcClaims =
      throw UnsupportedOperationException("not exercised here")
  }

  /** Named so it cannot be shadowed by `ApplicationTestBuilder.client`, which is an HttpClient. */
  private val podClientId = "did:web:pod.example.invalid"
  private val redirect = "https://pod.example.invalid/cb"
  private val verifier = Pkce.generateVerifier()
  private val challenge = Pkce.challengeFor(verifier)

  private fun authorizeUrl(
    clientId: String = podClientId,
    redirectUri: String = redirect,
    responseType: String = "code",
    scope: String = "openid",
    codeChallenge: String? = challenge,
    method: String? = Pkce.METHOD_S256,
    extra: String = "",
  ) = buildString {
    append("/authorize?client_id=").append(clientId.encodeURLParameter())
    append("&redirect_uri=").append(redirectUri.encodeURLParameter())
    append("&response_type=").append(responseType)
    append("&scope=").append(scope.encodeURLParameter())
    append("&state=client-state")
    codeChallenge?.let { append("&code_challenge=").append(it) }
    method?.let { append("&code_challenge_method=").append(it) }
    append(extra)
  }

  private fun withProvider(
    vararg providers: OidcProviderClient,
    stateStore: StateStore = injector.getInstance(StateStore::class.java),
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
  ) = testApplication {
    application {
      openIdProviderEndpoint(
        issuer = testConfig.idBaseUrl,
        providers = providers.associateBy { it.name },
        clientRedirectPolicy = DidWebRedirectPolicy(),
        stateStore = stateStore,
        authorizationCodeStore = injector.getInstance(AuthorizationCodeStore::class.java),
        jwtIssuer = injector.getInstance(JwtIssuer::class.java),
        loginService = injector.getInstance(LoginService::class.java),
      )
    }
    block(createClient { followRedirects = false })
  }

  private val google = FakeProvider("google", "Google")
  private val apple = FakeProvider("apple", "Apple")

  // ─── discovery ────────────────────────────────────────────────────────────

  @Test
  fun `the discovery document names the endpoints actually served`() = withProvider(google) { http ->
    val body = http.get("/.well-known/openid-configuration").bodyAsText()

    // Read the way a client reads it. Substring matching would also pass on a document no parser
    // accepts, and would fail on one that is merely spelled differently — JSON may escape `/`,
    // and this one does. Parsing with the core's own reader closes the loop besides: what this
    // service publishes is what the client half of the same codebase can consume.
    val metadata = OidcProviderMetadata.parse(body)

    assertEquals(testConfig.idBaseUrl, metadata.issuer)
    assertEquals("${testConfig.idBaseUrl}/authorize", metadata.authorizationEndpoint)
    assertEquals("${testConfig.idBaseUrl}/token", metadata.tokenEndpoint)
    assertEquals("${testConfig.idBaseUrl}/.well-known/jwks.json", metadata.jwksUri)
    assertTrue("\"response_types_supported\":[\"code\"]" in body, body)
    assertTrue("\"code_challenge_methods_supported\":[\"S256\"]" in body, body)

    // Advertised is one thing; mounted is another. An endpoint named in the document and not
    // served is worse than one that is merely undocumented.
    assertEquals(HttpStatusCode.Found, http.get(authorizeUrl()).status)
  }

  // ─── nothing before the address is proven ─────────────────────────────────

  @Test
  fun `a foreign redirect address gets no redirect, not even an error one`() = withProvider(google) { http ->
    val response = http.get(authorizeUrl(redirectUri = "https://evil.example/grab"))

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertNull(response.headers["Location"], "an unproven address must never appear in Location")
  }

  @Test
  fun `an unknown client gets no redirect either`() = withProvider(google) { http ->
    val response = http.get(authorizeUrl(clientId = "dyn:not-registered-here"))

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertNull(response.headers["Location"])
  }

  @Test
  fun `a client_id carrying a line break is refused, so no log line can be forged`() =
    withProvider(google) { http ->
      // `client_id` is a query parameter, and it names the subject of every line this endpoint
      // writes. Held to RFC 6749 Appendix A.1 at the entrance rather than escaped at each of them
      // — `ClientId`, and `docs/logging.md` §"Three rules" for what that buys.
      val forged = "did:web:pod.example.invalid\n2026-01-01 21:00:00,000 WARN  [ktor] forged"

      val lines = CapturedLog.linesFrom("org.sempods.auth.provider") {
        val response = http.get(authorizeUrl(clientId = forged))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(response.headers["Location"], "an unproven address must never appear in Location")
      }

      assertTrue(lines.none { "forged" in it }, "the refused value reached the log: $lines")
    }

  @Test
  fun `once the address is proven, errors travel to it`() = withProvider(google) { http ->
    val response = http.get(authorizeUrl(responseType = "token"))

    assertEquals(HttpStatusCode.Found, response.status)
    val location = response.headers["Location"].orEmpty()
    assertTrue(location.startsWith(redirect), location)
    assertTrue("unsupported_response_type" in location, location)
    assertTrue("state=client-state" in location, location)
  }

  // ─── what the request must carry ──────────────────────────────────────────

  @Test
  fun `PKCE is required, and only S256`() = withProvider(google) { http ->
    // No exemption for static clients. The pod grants one to `did:web:` and this provider does
    // not: an intercepted code with no verifier is redeemable by whoever intercepted it.
    assertTrue("invalid_request" in http.get(authorizeUrl(codeChallenge = null)).headers["Location"].orEmpty())
    assertTrue("invalid_request" in http.get(authorizeUrl(method = "plain")).headers["Location"].orEmpty())
  }

  @Test
  fun `the openid scope is what makes this an identity request`() = withProvider(google) { http ->
    assertTrue("invalid_scope" in http.get(authorizeUrl(scope = "profile")).headers["Location"].orEmpty())
  }

  @Test
  fun `prompt=none cannot be satisfied by a provider that holds no session`() = withProvider(google) { http ->
    val location = http.get(authorizeUrl(extra = "&prompt=none")).headers["Location"].orEmpty()

    assertTrue("login_required" in location, location)
  }

  // ─── provider selection ───────────────────────────────────────────────────

  @Test
  fun `one provider needs no choosing, several get a chooser that carries the request`() {
    withProvider(google) { http ->
      val location = http.get(authorizeUrl()).headers["Location"].orEmpty()
      assertTrue(location.startsWith("https://google.example.invalid/authorize"), location)
    }
    withProvider(google, apple) { http ->
      val response = http.get(authorizeUrl())
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()

      // Every parameter has to survive the extra hop, or the second leg is a different request.
      assertTrue("provider=apple" in body, body)
      assertTrue("code_challenge=$challenge" in body, body)
      assertTrue("state=client-state" in body, body)
    }
  }

  @Test
  fun `an unknown provider is refused rather than substituted`() = withProvider(google, apple) { http ->
    // Carried over from the deleted `/login` test, because the rule is the same and only its shape
    // changed: falling back to a configured provider would authenticate the person somewhere other
    // than where the caller asked — quietly, and with a valid token to show for it.
    val location = http.get(authorizeUrl(extra = "&provider=facebook")).headers["Location"].orEmpty()

    assertTrue(location.startsWith(redirect), "the refusal goes to the proven address: $location")
    assertTrue("error=invalid_request" in location, location)
  }

  @Test
  fun `a deployment with no provider says so to the client, at its own address`() = withProvider { http ->
    // A valid deployment state — someone running their own identity service owns no Google project.
    // The client learns it as an OAuth error rather than a page it cannot act on, and it travels by
    // redirect only because the address was proven first.
    val location = http.get(authorizeUrl()).headers["Location"].orEmpty()

    assertTrue(location.startsWith(redirect), "the error goes to the proven address: $location")
    assertTrue("error=server_error" in location, location)
    assertTrue("state=client-state" in location, "the client's own state must come back: $location")
  }

  // ─── the token exchange ───────────────────────────────────────────────────

  @Test
  fun `a code is exchanged for an id_token audienced to the client that asked`() = withProvider(google) { http ->
    val store = injector.getInstance(AuthorizationCodeStore::class.java)
    val webId = "${testConfig.idBaseUrl}/e/${uniqueHash()}"
    val code = store.issue(
      subject = webId,
      realm = testConfig.idBaseUrl,
      clientId = podClientId,
      scopes = setOf("openid"),
      redirectUri = redirect,
      codeChallenge = challenge,
      codeChallengeMethod = Pkce.METHOD_S256,
      nonce = "n-1",
    )

    val body = postToken(http, code = code, verifier = verifier)

    val idToken = Regex("\"id_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
      ?: error("no id_token in $body")
    val claims = SignedJWT.parse(idToken).jwtClaimsSet
    assertEquals(listOf(podClientId), claims.audience, "the claim that makes a stolen copy worthless")
    assertEquals(webId, claims.subject)
    assertEquals("n-1", claims.getStringClaim("nonce"))
    assertTrue("\"token_type\":\"Bearer\"" in body, body)
  }

  @Test
  fun `a code is single use`() = withProvider(google) { http ->
    val store = injector.getInstance(AuthorizationCodeStore::class.java)
    val code = store.issue(
      subject = "${testConfig.idBaseUrl}/e/${uniqueHash()}",
      realm = testConfig.idBaseUrl,
      clientId = podClientId,
      scopes = setOf("openid"),
      redirectUri = redirect,
      codeChallenge = challenge,
      codeChallengeMethod = Pkce.METHOD_S256,
    )

    assertTrue("id_token" in postToken(http, code, verifier))
    assertTrue("invalid_grant" in postToken(http, code, verifier), "a replayed code must find nothing")
  }

  @Test
  fun `the wrong verifier does not redeem a code`() = withProvider(google) { http ->
    val store = injector.getInstance(AuthorizationCodeStore::class.java)
    val code = store.issue(
      subject = "${testConfig.idBaseUrl}/e/${uniqueHash()}",
      realm = testConfig.idBaseUrl,
      clientId = podClientId,
      scopes = setOf("openid"),
      redirectUri = redirect,
      codeChallenge = challenge,
      codeChallengeMethod = Pkce.METHOD_S256,
    )

    // What PKCE is for: whoever intercepted the code in the browser does not hold the verifier.
    assertTrue("invalid_grant" in postToken(http, code, Pkce.generateVerifier()))
  }

  @Test
  fun `a padded verifier is not quietly straightened out`() = withProvider(google) { http ->
    // Every other form value on this endpoint is trimmed, and this one deliberately is not.
    // RFC 7636 §4.1's alphabet has no whitespace, so trimming would accept a verifier the rule
    // forbids — and accept it here while the hosted MCP token endpoint, which passes the value as
    // sent, refused the very same request. One request, one answer, whichever endpoint hears it.
    val store = injector.getInstance(AuthorizationCodeStore::class.java)
    val code = store.issue(
      subject = "${testConfig.idBaseUrl}/e/${uniqueHash()}",
      realm = testConfig.idBaseUrl,
      clientId = podClientId,
      scopes = setOf("openid"),
      redirectUri = redirect,
      codeChallenge = challenge,
      codeChallengeMethod = Pkce.METHOD_S256,
    )
    assertTrue("invalid_grant" in postToken(http, code, "%20$verifier%20"))
  }

  @Test
  fun `a token response is never cached`() = withProvider(google) { http ->
    val store = injector.getInstance(AuthorizationCodeStore::class.java)
    val code = store.issue(
      subject = "${testConfig.idBaseUrl}/e/${uniqueHash()}",
      realm = testConfig.idBaseUrl,
      clientId = podClientId,
      scopes = setOf("openid"),
      redirectUri = redirect,
      codeChallenge = challenge,
      codeChallengeMethod = Pkce.METHOD_S256,
    )

    // RFC 6749 §5.1. Strict clients drop tokens that arrive without it.
    val response = tokenRequest(http, code, verifier)
    assertNotNull(response.headers["Cache-Control"])
    assertTrue("no-store" in response.headers["Cache-Control"].orEmpty())
  }

  private suspend fun tokenRequest(http: HttpClient, code: String, verifier: String): HttpResponse =
    http.post("/token") {
      contentType(ContentType.Application.FormUrlEncoded)
      setBody(
        "grant_type=authorization_code&code=$code&client_id=${podClientId.encodeURLParameter()}" +
          "&redirect_uri=${redirect.encodeURLParameter()}&code_verifier=$verifier",
      )
    }

  private suspend fun postToken(http: HttpClient, code: String, verifier: String): String =
    tokenRequest(http, code, verifier).bodyAsText()

  // ─── what a client may not put in someone else's token ────────────────────

  @Test
  fun `a scope the provider does not serve is refused, not carried`() = withProvider(google) { http ->
    // The discovery document promises `scopes_supported: ["openid"]`. Ignoring anything else
    // would let a client-chosen value ride into the authorization code, and from there into
    // whatever reads it next.
    val location = http.get(authorizeUrl(scope = "openid urn:sempods:e:deadbeef"))
      .headers["Location"].orEmpty()

    assertTrue("invalid_scope" in location, location)
  }

  @Test
  fun `also_known_as comes from the profile, never from the request`() = withProvider(google) { http ->
    // The chain this closes: `also_known_as` is what a pod resolves grants and ownership against,
    // and the pod's verifier does not check `aud`. A client that could get a `urn:sempods:e:<other
    // person>` into the claim could present the token to any pod and be that person — without
    // luring anyone, since it authenticates as itself.
    val store = injector.getInstance(AuthorizationCodeStore::class.java)
    val code = store.issue(
      subject = "${testConfig.idBaseUrl}/e/${uniqueHash()}",
      realm = testConfig.idBaseUrl,
      clientId = podClientId,
      // Straight into the store, as if the scope check above had been bypassed.
      scopes = setOf("openid", "urn:sempods:e:victimhash"),
      redirectUri = redirect,
      codeChallenge = challenge,
      codeChallengeMethod = Pkce.METHOD_S256,
    )

    val body = postToken(http, code, verifier)
    val idToken = Regex("\"id_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
      ?: error("no id_token in $body")
    val claims = SignedJWT.parse(idToken).jwtClaimsSet

    val aliases = claims.getStringListClaim("also_known_as").orEmpty()
    assertTrue(
      aliases.none { "victimhash" in it },
      "a requested value must never become an identity alias, got: $aliases",
    )
  }

  @Test
  fun `an email-based identity keeps its deterministic URN`() = withProvider(google) { http ->
    // The half that grant-before-login stands on. A pod owner invites `bob@example.com` before Bob
    // has ever signed in; the grant is recorded against `urn:sempods:e:<sha256(email)>`, which any
    // pod can compute without asking this service. If the token omits it, the invitation resolves
    // to nothing and does so silently — `identity-service.md` §"Email → Grant Flow".
    //
    // A first-time user has an empty profile, so reading `linkedIdentities` alone yields nothing:
    // the URN has to be derived from the subject.
    val hash = "a".repeat(64)
    val webId = "${testConfig.idBaseUrl}/e/$hash"
    val store = injector.getInstance(AuthorizationCodeStore::class.java)
    val code = store.issue(
      subject = webId,
      realm = testConfig.idBaseUrl,
      clientId = podClientId,
      scopes = setOf("openid"),
      redirectUri = redirect,
      codeChallenge = challenge,
      codeChallengeMethod = Pkce.METHOD_S256,
    )

    val body = postToken(http, code, verifier)
    val idToken = Regex("\"id_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
      ?: error("no id_token in $body")
    val aliases = SignedJWT.parse(idToken).jwtClaimsSet.getStringListClaim("also_known_as").orEmpty()

    assertTrue("urn:sempods:e:$hash" in aliases, "the Layer-0 form must be present, got: $aliases")
  }

  @Test
  fun `the token response carries an access token, and it cannot pass as the identity one`() =
    withProvider(google) { http ->
      val webId = "${testConfig.idBaseUrl}/e/${uniqueHash()}"
      val store = injector.getInstance(AuthorizationCodeStore::class.java)
      val code = store.issue(
        subject = webId,
        realm = testConfig.idBaseUrl,
        clientId = podClientId,
        scopes = setOf("openid"),
        redirectUri = redirect,
        codeChallenge = challenge,
        codeChallengeMethod = Pkce.METHOD_S256,
      )

      val body = postToken(http, code, verifier)

      // RFC 6749 §5.1 makes it required, and libraries that validate the shape — `oauth4webapi`,
      // which this project's own browser SDK uses — reject a response without it.
      val accessToken = Regex("\"access_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        ?: error("no access_token in $body")
      val access = SignedJWT.parse(accessToken)

      // Both tokens come from the same issuer with the same key, and a pod verifying an identity
      // JWT checks issuer, expiry and signature — all three of which this one also satisfies. Two
      // things have to keep them apart before a claim is read.
      assertEquals("at+jwt", access.header.type.toString(), "RFC 9068 marks a JWT access token")
      assertEquals(
        listOf(testConfig.idBaseUrl),
        access.jwtClaimsSet.audience,
        "an access token names the resource; the id_token names the client",
      )
      assertNull(
        access.jwtClaimsSet.getClaim("also_known_as"),
        "an access token is not an identity assertion and must carry no aliases",
      )
    }
}
