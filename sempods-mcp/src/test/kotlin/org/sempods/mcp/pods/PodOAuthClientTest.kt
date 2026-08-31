package org.sempods.mcp.pods

import org.sempods.client.SempodsHttpTransport
import org.sempods.client.net.SempodsOutboundGuard
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import org.sempods.mcp.auth.JwtTestSupport
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.StringBody.subString
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [PodOAuthClient] through the full pod contract (discover → register → exchange →
 * refresh) against a MockServer-simulated pod, including refresh-token rotation. No Mongo.
 */
class PodOAuthClientTest {

  private lateinit var server: ClientAndServer
  private lateinit var base: String
  private lateinit var transport: SempodsHttpTransport
  // allowLocal=true: the simulated pod runs on localhost.
  private lateinit var client: PodOAuthClient
  // allowLocal=false: used to prove the SSRF guard fires on discovered/fetched URLs.
  private lateinit var strictClient: PodOAuthClient
  private val podKey = JwtTestSupport.generateKey("pod-k1")

  @BeforeEach
  fun setup() {
    // The full hardened M6.2 stack (pin + no-redirect), relaxed so loopback passes.
    // The full hardened stack (resolve-and-pin + no redirects), relaxed so loopback passes.
    transport = SempodsHttpTransport(guard = SempodsOutboundGuard(PodUrlPolicy(allowLocal = true).rules))
    client = PodOAuthClient(transport, jacksonObjectMapper(), PodUrlPolicy(allowLocal = true))
    strictClient = PodOAuthClient(transport, jacksonObjectMapper(), PodUrlPolicy(allowLocal = false))
    server = ClientAndServer.startClientAndServer(0)
    val authBase = "http://localhost:${server.port}/pod/_system/auth"
    base = "http://localhost:${server.port}/pod"

    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/jwks.json"))
      .respond(response().withStatusCode(200).withBody(JWKSet(podKey.toPublicJWK()).toString()))

    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody("""{"resource":"$base","authorization_servers":["$authBase"]}"""))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(
        response().withStatusCode(200).withBody(
          """{"issuer":"$authBase","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token","registration_endpoint":"$authBase/register","jwks_uri":"$authBase/jwks.json"}""",
        ),
      )
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/register"))
      .respond(response().withStatusCode(201).withBody("""{"client_id":"dyn:testclient"}"""))
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=authorization_code")))
      .respond(response().withStatusCode(200).withBody("""{"access_token":"at1","token_type":"Bearer","expires_in":3600,"refresh_token":"rt1","scope":"public-read"}"""))
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=refresh_token")))
      .respond(response().withStatusCode(200).withBody("""{"access_token":"at2","token_type":"Bearer","expires_in":3600,"refresh_token":"rt2","scope":"public-read"}"""))
  }

  @AfterEach
  fun teardown() {
    server.stop()
  }

  @Test
  fun `discovery unions the scope lists of both metadata documents`() = runBlocking {
    // A pod may publish `scopes_supported` in the protected-resource metadata (RFC 9728 §2), in the
    // AS metadata (RFC 8414 §2), or in both, and the two are read by different parsers here. What
    // the caller needs is one answer to "may I ask for this", so the union is what it gets.
    val authBase = "$base/_system/auth"
    server.reset()
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(
        response().withStatusCode(200).withBody(
          """{"resource":"$base","authorization_servers":["$authBase"],"scopes_supported":["public-read"]}""",
        ),
      )
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(
        response().withStatusCode(200).withBody(
          """{"issuer":"$authBase","authorization_endpoint":"$authBase/authorize",""" +
            """"token_endpoint":"$authBase/token","scopes_supported":["offline_access",42,"  "]}""",
        ),
      )

    val metadata = client.discoverMetadata(base)

    assertEquals(
      setOf("public-read", "offline_access"), metadata.scopesSupported,
      "both documents contribute, and a non-string or blank entry is not a scope",
    )
  }

  @Test
  fun `a pod that advertises no scopes answers the empty set, not a failure`() = runBlocking {
    // The pods that exist today. Discovery must not become the place a connect starts failing.
    val metadata = client.discoverMetadata(base)

    assertEquals(emptySet(), metadata.scopesSupported)
  }

  @Test
  fun `discover register exchange refresh against a simulated pod`() = runBlocking {
    val metadata = client.discoverMetadata(base)
    assertEquals("$base/_system/auth/token", metadata.tokenEndpoint)
    assertEquals("$base/_system/auth/register", metadata.registrationEndpoint)

    val clientId = client.registerClient(metadata, "https://mcp.test/_system/ui/pods/callback", "0.2.0-test")
    assertEquals("dyn:testclient", clientId)

    val authUrl = client.buildAuthorizeUrl(metadata, clientId, "https://mcp.test/cb", "chal", "state1", scope = null)
    assertTrue("response_type=code" in authUrl && "code_challenge_method=S256" in authUrl && "client_id=dyn" in authUrl, authUrl)

    val tokens = client.exchangeCode(metadata, "code123", "https://mcp.test/cb", clientId, "verifier")
    assertEquals("at1", tokens.accessToken)
    assertEquals("rt1", tokens.refreshToken)
    assertEquals(3600, tokens.expiresInSeconds)

    val refreshed = client.refresh(metadata, "rt1", clientId)
    assertEquals("at2", refreshed.accessToken)
    assertEquals("rt2", refreshed.refreshToken, "pod rotates the refresh token")
  }

  @Test
  fun `discovery falls back to the sempods convention when the pod publishes no RFC 8414 metadata`() = runBlocking {
    // A minimal pod (did:web static-client model): RFC 9728 only; the AS metadata endpoint 404s.
    server.reset()
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody("""{"resource":"$base","authorization_servers":["$base/_system/auth"]}"""))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(response().withStatusCode(404))

    val metadata = client.discoverMetadata(base)
    assertEquals("$base/_system/auth", metadata.issuer)
    assertEquals("$base/_system/auth/authorize", metadata.authorizationEndpoint, "authorize is derived from the issuer by convention")
    assertEquals("$base/_system/auth/token", metadata.tokenEndpoint, "token is derived from the issuer by convention")
    assertNull(metadata.registrationEndpoint, "the convention path has no DCR (a static did:web client is used)")
    assertNull(metadata.jwksUri, "the convention path advertises no JWKS (subject trusted via the TLS token)")
  }

  @Test
  fun `discovery propagates a transient AS-metadata failure instead of downgrading to convention`() = runBlocking {
    // A full pod whose AS-metadata endpoint transiently 5xxs must NOT be silently reclassified as a
    // minimal convention pod (which would bind wrong endpoints + drop JWKS verification) — it fails.
    server.reset()
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody("""{"resource":"$base","authorization_servers":["$base/_system/auth"]}"""))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(response().withStatusCode(503))
    assertFailsWith<PodOAuthException> { client.discoverMetadata(base) }
    Unit
  }

  @Test
  fun `SSRF guard rejects a localhost pod when local pods are disallowed`() = runBlocking {
    // The simulated pod is on localhost; a strict (public) policy must refuse to fetch it.
    assertFailsWith<PodOAuthException> { strictClient.discoverMetadata(base) }
    Unit
  }

  @Test
  fun `verifyAccessTokenSubject verifies against the JWKS, falls back to trusted-unverified without one`() = runBlocking {
    val metadata = client.discoverMetadata(base)
    val webId = "https://id.test/e/pod-user"
    val now = Instant.now()
    val claims = JWTClaimsSet.Builder()
      .issuer("$base/").subject(webId)
      .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build()
    val podSigned = JwtTestSupport.sign(podKey, claims)

    // JWKS advertised + pod-signed → Readable, marked verified.
    val verified = client.verifyAccessTokenSubject(metadata, podSigned)
    assertEquals(webId, (verified as PodOAuthClient.SubjectOutcome.Readable).subject.webId)
    assertTrue(verified.subject.verified, "a JWKS-validated token must be marked verified")

    // No JWKS advertised → trust the directly-fetched token's subject, but mark it unverified.
    val trusted = client.verifyAccessTokenSubject(metadata.copy(jwksUri = null), podSigned)
    assertEquals(webId, (trusted as PodOAuthClient.SubjectOutcome.Readable).subject.webId)
    assertFalse(trusted.subject.verified, "without a JWKS the subject must be marked unverified")

    // JWKS advertised + fetched, but the token was signed by a foreign key → definitive
    // VerificationFailed (a tamper/misconfig signal), NOT the inconclusive Unreadable.
    val foreign = JwtTestSupport.sign(JwtTestSupport.generateKey("pod-k1"), claims)
    assertEquals(PodOAuthClient.SubjectOutcome.VerificationFailed, client.verifyAccessTokenSubject(metadata, foreign))
    // An opaque (non-JWT) token cannot be read at all → Unreadable.
    assertEquals(PodOAuthClient.SubjectOutcome.Unreadable, client.verifyAccessTokenSubject(metadata, "opaque-not-a-jwt"))
  }

  @Test
  fun `a key rotated under the same kid is re-fetched rather than called a bad signature`() = runBlocking {
    // The one case nimbus's own refresh-on-unknown-kid does not cover: it re-fetches when the key
    // SELECTOR comes back empty, and a rotated key keeping its `kid` still satisfies the selector.
    // The stale key is handed over, the good token fails against it, and without a second opinion
    // that reads as tampering — which on the refresh path discards a refresh token the pod has
    // already rotated, so the connection dies until someone reconnects it by hand.
    val metadata = client.discoverMetadata(base)
    val now = Instant.now()
    fun tokenSignedBy(key: com.nimbusds.jose.jwk.RSAKey) = JwtTestSupport.sign(
      key,
      JWTClaimsSet.Builder().issuer("$base/").subject("https://id.test/e/pod-user")
        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build(),
    )

    // Warm the cache against the current key.
    assertTrue(client.verifyAccessTokenSubject(metadata, tokenSignedBy(podKey)) is PodOAuthClient.SubjectOutcome.Readable)

    // The pod rotates, keeping the same `kid`. The cached JWKS is now a set of one wrong key.
    val rotated = JwtTestSupport.generateKey(podKey.keyID)
    server.clear(request().withMethod("GET").withPath("/pod/_system/auth/jwks.json"))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/jwks.json"))
      .respond(response().withStatusCode(200).withBody(JWKSet(rotated.toPublicJWK()).toString()))

    val outcome = client.verifyAccessTokenSubject(metadata, tokenSignedBy(rotated))

    assertTrue(
      outcome is PodOAuthClient.SubjectOutcome.Readable && outcome.subject.verified,
      "a rotated key must be picked up rather than read as a tampered token, but was $outcome",
    )
  }

  @Test
  fun `verifyAccessTokenSubject is inconclusive when the advertised JWKS has no usable key`() = runBlocking {
    val metadata = client.discoverMetadata(base)
    // Re-point the advertised JWKS at an EMPTY set — models a JWKS with no key we can verify against
    // (empty, kid-mismatch, or a non-RSA/EdDSA pod). We could not ATTEMPT verification, so the result
    // is Unreadable (tolerated on refresh), NOT the tamper-signal VerificationFailed.
    server.clear(request().withMethod("GET").withPath("/pod/_system/auth/jwks.json"))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/jwks.json"))
      .respond(response().withStatusCode(200).withBody("""{"keys":[]}"""))
    val now = Instant.now()
    val token = JwtTestSupport.sign(
      podKey,
      JWTClaimsSet.Builder().subject("https://id.test/e/x")
        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build(),
    )
    assertEquals(PodOAuthClient.SubjectOutcome.Unreadable, client.verifyAccessTokenSubject(metadata, token))
  }

  @Test
  fun `a token response is accepted when the pod omits token_type or names an unknown one`() = runBlocking {
    // nimbus refuses both, where the hand-written parse refused neither. Both pods worked before
    // and must keep working: the token itself is what matters, and `token_type` is echoed back.
    val metadata = client.discoverMetadata(base)
    // The third element is the word the pod is entitled to get back: normalising is what nimbus
    // parses, not what the caller is told the pod said.
    val cases = listOf(
      Triple("omitted", null, "Bearer"),
      Triple("unknown", """"token_type":"mac",""", "mac"),
      // Padded: the known-value check runs on the trimmed word, but nimbus reads the raw member,
      // so a value that only *looks* familiar has to be normalised in the copy as well.
      Triple("padded", """"token_type":" Bearer ",""", "Bearer"),
      Triple("lowercase", """"token_type":"bearer",""", "bearer"),
    )
    for ((label, tokenType, expectedType) in cases) {
      server.clear(request().withMethod("POST").withPath("/pod/_system/auth/token"))
      server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token"))
        .respond(
          response().withStatusCode(200)
            .withBody("""{"access_token":"at-$label",${tokenType.orEmpty()}"refresh_token":"rt1","scope":"public-read"}"""),
        )
      val tokens = client.exchangeCode(metadata, "c", "https://mcp.test/cb", "dyn:c", "v")
      assertEquals("at-$label", tokens.accessToken, label)
      assertEquals("rt1", tokens.refreshToken, label)
      assertEquals(expectedType, tokens.tokenType, "$label: the pod's own word survives the normalisation")
      assertNull(tokens.expiresInSeconds, "no expires_in must stay absent, not become zero")
    }
  }

  @Test
  fun `expires_in keeps its three distinct meanings`() = runBlocking {
    // The distinction nimbus cannot express: it answers 0 both for an absent `expires_in` and for
    // an explicit one. Downstream they are opposites — `PodTokenProvider.isDue` never refreshes a
    // token whose expiry is unknown, so folding an explicit 0 into null would leave an already
    // stale token in use with a perfectly good refresh token beside it.
    val metadata = client.discoverMetadata(base)
    val cases = listOf(
      Triple("absent", "", null),
      Triple("explicit zero", ""","expires_in":0""", 0L),
      Triple("negative", ""","expires_in":-5""", -5L),
      // Not a JSON number, so not a lifetime — and, crucially, not a reason to refuse the whole
      // response either: nimbus rejects both outright, and each is a pod that connected before.
      Triple("string", ""","expires_in":"3600"""", null),
      Triple("json null", ""","expires_in":null""", null),
    )
    for ((label, member, expected) in cases) {
      server.clear(request().withMethod("POST").withPath("/pod/_system/auth/token"))
      server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token"))
        .respond(
          response().withStatusCode(200)
            .withBody("""{"access_token":"at","token_type":"Bearer"$member,"refresh_token":"rt"}"""),
        )
      val tokens = client.exchangeCode(metadata, "c", "https://mcp.test/cb", "dyn:c", "v")
      assertEquals(expected, tokens.expiresInSeconds, label)
      assertEquals("at", tokens.accessToken, "$label must still yield its token")
      assertEquals("rt", tokens.refreshToken, "$label must still yield its refresh token")
    }
  }

  @Test
  fun `a failure whose body is not an OAuth error document carries no error code`() = runBlocking {
    // The same failure path reports a metadata or JWKS fetch, where the body is not an OAuth error
    // document and often not JSON at all. A code invented there would make `isRetryablePodFailure`
    // read a proxy's HTML as a pod verdict.
    server.reset()
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(502).withBody("<html><body>Bad Gateway</body></html>"))

    val failure = assertFailsWith<PodOAuthException> { client.discoverMetadata(base) }
    assertNull(failure.oauthErrorCode, "a non-OAuth body must not yield an error code")
    assertFalse(failure.isDeadGrant)
  }

  @Test
  fun `AS metadata whose issuer carries a query keeps the issuer the pod itself named`() = runBlocking {
    // RFC 8414 §2 forbids a query on the issuer, and nimbus enforces it. A pod that gets this
    // wrong still connects — on the RFC 9728 issuer, which is the one it published as a resource.
    val authBase = "$base/_system/auth"
    server.clear(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(
        response().withStatusCode(200).withBody(
          """{"issuer":"$authBase?tenant=a","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token"}""",
        ),
      )

    val metadata = client.discoverMetadata(base)
    assertEquals(authBase, metadata.issuer, "the unusable issuer is dropped, not carried")
    assertEquals("$authBase/authorize", metadata.authorizationEndpoint)
  }

  @Test
  fun `buildAuthorizeUrl merges a query the authorization endpoint carries of its own`() {
    // RFC 6749 §3.1 lets the authorization endpoint carry a query component. The hand-built URL
    // this replaced appended a second `?`, which made everything from `response_type` on part of
    // the last existing parameter's value — a multi-tenant AS got an unusable request.
    val metadata = tenantScopedMetadata()
    val url = client.buildAuthorizeUrl(metadata, "dyn:c", "https://mcp.test/cb", "chal", "state1", scope = null)

    assertEquals(1, url.count { it == '?' }, url)
    val query = Url(url).parameters
    assertEquals("a", query["tenant"], "the endpoint's own parameter survives")
    assertEquals("code", query["response_type"])
    assertEquals("dyn:c", query["client_id"])
  }

  @Test
  fun `buildAuthorizeUrl carries every parameter, encoded, for a plain endpoint`() {
    // The shape the single caller (WebUiEndpoint) depends on. Written against the hand-built URL
    // A6 replaced and kept green across the move, so a parameter that silently changes name,
    // encoding or presence is caught.
    // The scope value has the shape that matters rather than a particular app's name:
    // `apps/calendar#manage` carries both a slash and a fragment marker, which is exactly what a
    // naive re-encoding truncates.
    val metadata = plainMetadata()
    val url = client.buildAuthorizeUrl(
      metadata,
      clientId = "dyn:client/one",
      redirectUri = "https://mcp.test/_system/ui/pods/callback",
      codeChallenge = "chal+lenge",
      state = "st/ate 1",
      scope = "public-read apps/calendar#manage",
    )

    assertTrue(url.startsWith("${metadata.authorizationEndpoint}?"), url)
    val query = Url(url).parameters
    assertEquals("code", query["response_type"])
    assertEquals("dyn:client/one", query["client_id"])
    assertEquals("https://mcp.test/_system/ui/pods/callback", query["redirect_uri"])
    assertEquals("chal+lenge", query["code_challenge"])
    assertEquals("S256", query["code_challenge_method"])
    assertEquals("st/ate 1", query["state"])
    assertEquals("public-read apps/calendar#manage", query["scope"])
  }

  @Test
  fun `buildAuthorizeUrl omits scope entirely when none is requested`() {
    val url = client.buildAuthorizeUrl(plainMetadata(), "dyn:c", "https://mcp.test/cb", "chal", "state1", scope = null)
    assertFalse("scope=" in url, url)
  }

  /** An AS whose authorization endpoint is multi-tenant and keeps a query of its own. */
  private fun tenantScopedMetadata() =
    plainMetadata().let { it.copy(authorizationEndpoint = "${it.authorizationEndpoint}?tenant=a") }

  private fun plainMetadata() = PodOAuthMetadata(
    issuer = "$base/_system/auth",
    authorizationEndpoint = "$base/_system/auth/authorize",
    tokenEndpoint = "$base/_system/auth/token",
    registrationEndpoint = "$base/_system/auth/register",
    jwksUri = "$base/_system/auth/jwks.json",
  )
}
