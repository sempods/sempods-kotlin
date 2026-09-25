package org.sempods.mcp.pods

import okhttp3.OkHttpClient
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
import java.net.URI
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
  private lateinit var calls: OkHttpClient
  // allowLocal=true: the simulated pod runs on localhost.
  private lateinit var client: PodOAuthClient
  // allowLocal=false: used to prove the SSRF guard fires on discovered/fetched URLs.
  private lateinit var strictClient: PodOAuthClient
  private val podKey = JwtTestSupport.generateKey("pod-k1")

  @BeforeEach
  fun setup() {
    // The full hardened outbound stack (pin + no-redirect), relaxed so loopback passes.
    // The full hardened stack (resolve-and-pin + no redirects), relaxed so loopback passes.
    calls = testPodCalls()
    client = PodOAuthClient(calls, jacksonObjectMapper(), PodUrlPolicy(allowLocal = true))
    strictClient = PodOAuthClient(calls, jacksonObjectMapper(), PodUrlPolicy(allowLocal = false))
    server = ClientAndServer.startClientAndServer(0)
    val authBase = "http://localhost:${server.port}/pod/_system/auth"
    base = "http://localhost:${server.port}/pod"

    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/jwks.json"))
      .respond(response().withStatusCode(200).withBody(JWKSet(podKey.toPublicJWK()).toString()))

    // The pod is its own issuer (SPS-AUTH-065/066); only its auth routes sit under `_system/auth`.
    servesResourceMetadata("""{"resource":"$base","authorization_servers":["$base"]}""")
    servesAsMetadata(
      """{"issuer":"$base","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token","registration_endpoint":"$authBase/register","jwks_uri":"$authBase/jwks.json"}""",
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
  fun `the authorization server's scope list wins over the resource's`() = runBlocking {
    // Both documents may carry `scopes_supported` (RFC 9728 §2, RFC 8414 §2) and they can disagree.
    // The authorization server is the party that answers `invalid_scope`, so its list is the one
    // that decides what may be asked for — including when it omits what the resource advertises.
    val authBase = "$base/_system/auth"
    servesResourceMetadata("""{"resource":"$base","authorization_servers":["$base"],"scopes_supported":["public-read"]}""")
    servesAsMetadata(
      """{"issuer":"$base","authorization_endpoint":"$authBase/authorize",""" +
        """"token_endpoint":"$authBase/token","scopes_supported":["offline_access",42,"  "]}""",
    )

    val metadata = client.discoverMetadata(base)

    assertEquals(
      setOf("offline_access"), metadata.scopesSupported,
      "the AS list decides, and a non-string or blank entry in it is not a scope",
    )
  }

  @Test
  fun `the resource's scope list is used when the authorization server publishes none`() = runBlocking {
    // The common shape: an AS metadata document that says nothing about scopes at all. Silence is
    // not a refusal — RFC 8414 §2 makes the member optional — so the resource's list stands.
    val authBase = "$base/_system/auth"
    servesResourceMetadata("""{"resource":"$base","authorization_servers":["$base"],"scopes_supported":["offline_access"]}""")
    servesAsMetadata("""{"issuer":"$base","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token"}""")

    val metadata = client.discoverMetadata(base)

    assertEquals(setOf("offline_access"), metadata.scopesSupported)
  }

  @Test
  fun `an authorization server that publishes an empty scope list is taken at its word`() = runBlocking {
    // Present-and-empty is the AS speaking, not silence, so the resource's list does not revive it.
    val authBase = "$base/_system/auth"
    servesResourceMetadata("""{"resource":"$base","authorization_servers":["$base"],"scopes_supported":["offline_access"]}""")
    servesAsMetadata(
      """{"issuer":"$base","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token","scopes_supported":[]}""",
    )

    val metadata = client.discoverMetadata(base)

    assertEquals(emptySet(), metadata.scopesSupported)
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
    // The convention routes are the pod's own, under either of its issuers.
    for (issuer in listOf(base, "$base/_system/auth")) {
      server.reset()
      servesResourceMetadata("""{"resource":"$base","authorization_servers":["$issuer"]}""")
      server.`when`(request().withMethod("GET").withPath(URI(issuer).path + "/.well-known/oauth-authorization-server"))
        .respond(response().withStatusCode(404))

      val metadata = client.discoverMetadata(base)
      assertEquals(issuer, metadata.issuer)
      assertEquals("$base/_system/auth/authorize", metadata.authorizationEndpoint, "authorize is the pod's route by convention")
      assertEquals("$base/_system/auth/token", metadata.tokenEndpoint, "token is the pod's route by convention")
      assertNull(metadata.registrationEndpoint, "the convention path has no DCR (a static did:web client is used)")
      assertNull(metadata.jwksUri, "the convention path advertises no JWKS (subject trusted via the TLS token)")
    }
  }

  @Test
  fun `discovery propagates a transient AS-metadata failure instead of downgrading to convention`() = runBlocking {
    // A full pod whose AS-metadata endpoint transiently 5xxs must NOT be silently reclassified as a
    // minimal convention pod (which would bind wrong endpoints + drop JWKS verification) — it fails.
    server.clear(request().withMethod("GET").withPath("/pod/.well-known/oauth-authorization-server"))
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-authorization-server"))
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
  fun `AS metadata naming the issuer it was discovered for connects on that issuer`() = runBlocking {
    val metadata = client.discoverMetadata(base)

    assertEquals(base, metadata.issuer)
  }

  @Test
  fun `a pod that names its auth route as issuer connects on that issuer`() = runBlocking {
    // The pod server before the issuer switch of #193: its issuer is `{pod}/_system/auth`, and its
    // AS metadata sits below that. Accepted until `podIssuers` drops the form.
    val authBase = "$base/_system/auth"
    server.clear(request().withMethod("GET").withPath("/pod/.well-known/oauth-authorization-server"))
    servesResourceMetadata("""{"resource":"$base","authorization_servers":["$authBase"]}""")
    servesAsMetadata(
      """{"issuer":"$authBase","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token"}""",
      at = "/pod/_system/auth/.well-known/oauth-authorization-server",
    )

    val metadata = client.discoverMetadata(base)

    assertEquals(authBase, metadata.issuer)
    assertEquals("$authBase/token", metadata.tokenEndpoint)
  }

  @Test
  fun `AS metadata naming another issuer is refused, and the error says which issuer was expected`() = runBlocking {
    // RFC 8414 §3.3: a document whose issuer is not the one it was fetched for MUST NOT be used.
    // The first case is the one that matters most: another pod on the same origin.
    val authBase = "$base/_system/auth"
    val otherPod = "http://localhost:${server.port}/other"
    // The label, the document's `issuer` member, and what the error says the pod declared.
    val cases = listOf(
      Triple("another pod", """"issuer":"$otherPod",""", "issuer '$otherPod'"),
      // The issuer is the pod, even though the auth routes live below it (SPS-AUTH-066).
      Triple("the auth route", """"issuer":"$authBase",""", "issuer '$authBase'"),
      Triple("a query", """"issuer":"$base?tenant=a",""", "issuer '$base?tenant=a'"),
      Triple("absent", "", "no issuer"),
      Triple("not a string", """"issuer":42,""", "no issuer"),
    )
    for ((label, issuerMember, declared) in cases) {
      servesAsMetadata(
        """{$issuerMember"authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token"}""",
      )

      val failure = assertFailsWith<PodOAuthException>(label) { client.discoverMetadata(base) }

      assertEquals("pod AS metadata declares $declared, expected '$base'", failure.message, label)
      assertNull(failure.oauthErrorCode, "$label: the pod sent no OAuth error, so there is no code to report")
    }
  }

  @Test
  fun `a terminating slash on either issuer still connects, on the issuer without it`() = runBlocking {
    // Both spellings fetch the same document, and the refresh pin compares the spelling without it.
    val authBase = "$base/_system/auth"
    val cases = listOf(
      "on the resource's entry" to ("$base/" to base),
      "on the declared issuer" to (base to "$base/"),
      "on both" to ("$base/" to "$base/"),
    )
    for ((label, issuers) in cases) {
      val (listed, declared) = issuers
      servesResourceMetadata("""{"resource":"$base","authorization_servers":["$listed"]}""")
      servesAsMetadata(
        """{"issuer":"$declared","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token"}""",
      )

      val metadata = client.discoverMetadata(base)

      assertEquals(base, metadata.issuer, label)
      assertEquals("$authBase/token", metadata.tokenEndpoint, label)
    }
  }

  @Test
  fun `resource metadata describing another resource is refused (SPS-AUTH-068)`() = runBlocking {
    // The pod asked for is the reference. A document cannot move the connection to another pod,
    // and RFC 9728 §3.3 compares `resource` exactly.
    val otherPod = "http://localhost:${server.port}/other"
    // The label, the document's `resource` member, and what the error says the document named.
    val cases = listOf(
      Triple("another pod", """"resource":"$otherPod",""", "resource '$otherPod'"),
      Triple("a terminating slash", """"resource":"$base/",""", "resource '$base/'"),
      Triple("absent", "", "no resource"),
      Triple("not a string", """"resource":42,""", "no resource"),
    )
    for ((label, member, named) in cases) {
      servesResourceMetadata("""{$member"authorization_servers":["$base"]}""")

      val failure = assertFailsWith<PodOAuthException>(label) { client.discoverMetadata(base) }

      assertEquals("pod protected-resource metadata names $named, expected '$base'", failure.message, label)
    }
    assertAsMetadataNeverFetched()
  }

  @Test
  fun `an authorization server other than the pod is refused before anything is fetched from it`() = runBlocking {
    // Alice's document cannot send the client to Bob's issuer (SPS-AUTH-068), and a pod names
    // itself alone (SPS-AUTH-065).
    val otherPod = "http://localhost:${server.port}/other"
    val cases = listOf(
      "another pod" to """"authorization_servers":["$otherPod"]""",
      "another pod's auth route" to """"authorization_servers":["$otherPod/_system/auth"]""",
      "a second entry" to """"authorization_servers":["$base","$otherPod"]""",
      "no entry" to """"authorization_servers":[]""",
      "not an array" to """"authorization_servers":"$base"""",
      "not a string" to """"authorization_servers":[42]""",
      "absent" to """"name":"Alice"""",
    )
    for ((label, member) in cases) {
      servesResourceMetadata("""{"resource":"$base",$member}""")

      assertFailsWith<PodOAuthException>(label) { client.discoverMetadata(base) }
    }
    assertAsMetadataNeverFetched()
  }

  @Test
  fun `resource metadata that is not a JSON object is refused`() = runBlocking {
    for (body in listOf("not json", "[]", """"$base"""", "")) {
      servesResourceMetadata(body)

      val failure = assertFailsWith<PodOAuthException>(body) { client.discoverMetadata(base) }

      assertEquals("pod protected-resource metadata is not a JSON object", failure.message, body)
    }
  }

  @Test
  fun `members this client does not read are ignored in both documents (SPS-AUTH-047)`() = runBlocking {
    val authBase = "$base/_system/auth"
    servesResourceMetadata(
      """{"resource":"$base","authorization_servers":["$base"],"bearer_methods_supported":["header"],""" +
        """"public_contexts":3,"name":"Alice","x-extension":{"nested":[1,2]}}""",
    )
    servesAsMetadata(
      """{"issuer":"$base","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token",""" +
        """"response_types_supported":["code"],"x-extension":{"nested":true}}""",
    )

    val metadata = client.discoverMetadata(base)

    assertEquals(base, metadata.issuer)
    assertEquals("$authBase/token", metadata.tokenEndpoint)
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

  /** Replaces the protected-resource metadata document the simulated pod serves. */
  private fun servesResourceMetadata(body: String) {
    server.clear(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody(body))
  }

  /** Replaces the AS metadata document the simulated pod serves [at] a path. */
  private fun servesAsMetadata(body: String, at: String = "/pod/.well-known/oauth-authorization-server") {
    server.clear(request().withMethod("GET").withPath(at))
    server.`when`(request().withMethod("GET").withPath(at))
      .respond(response().withStatusCode(200).withBody(body))
  }

  private fun assertAsMetadataNeverFetched() {
    val fetched = server.retrieveRecordedRequests(request().withPath(".*/oauth-authorization-server")).map { it.path }
    assertTrue(fetched.isEmpty(), "no authorization-server metadata may be fetched, but was: $fetched")
  }

  /** An AS whose authorization endpoint is multi-tenant and keeps a query of its own. */
  private fun tenantScopedMetadata() =
    plainMetadata().let { it.copy(authorizationEndpoint = "${it.authorizationEndpoint}?tenant=a") }

  private fun plainMetadata() = PodOAuthMetadata(
    issuer = base,
    authorizationEndpoint = "$base/_system/auth/authorize",
    tokenEndpoint = "$base/_system/auth/token",
    registrationEndpoint = "$base/_system/auth/register",
    jwksUri = "$base/_system/auth/jwks.json",
  )
}
