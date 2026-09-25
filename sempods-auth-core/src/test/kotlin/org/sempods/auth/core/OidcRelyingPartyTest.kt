package org.sempods.auth.core

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import java.net.URI
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The client half, driven end to end against a provider that actually answers.
 *
 * The provider is a fake, but not a stub: it signs real tokens with a real key and serves a real
 * JWKS, so discovery feeding the endpoints, the verifier never leaving the server, and the token
 * being checked against the request that asked for it are all genuinely exercised. A stub that
 * returned a prepared identity would pass whether or not any of that held.
 *
 * No socket: every document the flow needs — discovery, JWKS, token — arrives through
 * [HttpTransport]. That is a property worth having, and `the JWKS is fetched through the transport`
 * below pins it: the SDK will happily fetch keys with its own HTTP client, which would put half
 * the provider traffic outside whatever policy the calling service put on its client.
 */
class OidcRelyingPartyTest {

  private val clientId = "did:web:pod.example.invalid"
  private val redirectUri = "https://pod.example.invalid/cb"

  /** Records what it was asked for, so a test can assert on the request as well as the answer. */
  private class RecordingTransport(private val issuer: String, private val provider: FakeProvider) : HttpTransport {
    val postedForms = mutableListOf<Map<String, String>>()
    val fetched = mutableListOf<String>()

    override fun get(url: String): String {
      fetched += url
      return when (url) {
        "$issuer/.well-known/openid-configuration" -> provider.discoveryDocument
        JWKS_URL -> jwks
        else -> error("unexpected GET: $url")
      }
    }

    override fun postForm(url: String, form: Map<String, String>): String {
      postedForms += form
      return provider.tokenResponseFor(form)
    }
  }

  private fun rp(provider: FakeProvider = FakeProvider(), trustsEquivalentIdentities: Boolean = false) =
    OidcRelyingParty.discover(
      ISSUER, clientId, redirectUri, RecordingTransport(ISSUER, provider), trustsEquivalentIdentities,
    )

  /** Completes one sign-in against [provider], answering with the nonce this flow sent. */
  private fun signIn(provider: FakeProvider, trusted: Boolean = true): OidcRelyingParty.VerifiedIdentity {
    val rp = rp(provider, trustsEquivalentIdentities = trusted)
    val started = rp.beginAuthorization()
    provider.nonceToEcho = started.nonce
    return rp.completeAuthorization("the-code", started.codeVerifier, started.nonce)
  }

  /** A provider whose token carries the equivalent-identity claim with [value], `null` included. */
  private fun claiming(value: Any?) = FakeProvider(claims = mapOf(EquivalentIdentities.CLAIM to value))

  // ─── discovery ────────────────────────────────────────────────────────────

  @Test
  fun `the endpoints come from the document, not from a convention`() {
    val rp = rp()

    assertEquals("$ISSUER/authorize", rp.metadata.authorizationEndpoint)
    assertEquals("$ISSUER/token", rp.metadata.tokenEndpoint)
  }

  @Test
  fun `a provider that advertises a different issuer is refused at wiring time`() {
    // Not one token at a time at verification, with nothing pointing at the cause.
    val lying = FakeProvider(advertisedIssuer = "https://someone.else.invalid")

    val failure = assertFails { OidcRelyingParty.discover(ISSUER, clientId, redirectUri, RecordingTransport(ISSUER, lying)) }

    assertContains(failure.message.orEmpty(), "someone.else.invalid")
  }

  @Test
  fun `a document missing what this client needs fails at discovery`() {
    assertFailsWith<IllegalArgumentException> {
      OidcProviderMetadata.parse("""{"issuer":"https://id.test","authorization_endpoint":"https://id.test/a"}""")
    }
  }

  // ─── the authorization request ────────────────────────────────────────────

  @Test
  fun `the authorization request carries what the provider requires`() {
    val started = rp().beginAuthorization()
    val query = requireNotNull(URI(started.authorizationUrl).rawQuery)

    assertTrue(started.authorizationUrl.startsWith("$ISSUER/authorize"), started.authorizationUrl)
    assertTrue("response_type=code" in query, query)
    assertTrue("scope=openid" in query, query)
    assertTrue("code_challenge_method=S256" in query, query)
    // A `did:web:` client id is all colons; unescaped it would end the parameter early.
    assertTrue("client_id=did%3Aweb%3Apod.example.invalid" in query, query)
  }

  @Test
  fun `the verifier never appears in the front channel`() {
    val started = rp().beginAuthorization()

    assertTrue(started.codeVerifier !in started.authorizationUrl)
    assertTrue(Pkce.challengeFor(started.codeVerifier) in started.authorizationUrl)
  }

  @Test
  fun `each start is its own`() {
    val a = rp().beginAuthorization()
    val b = rp().beginAuthorization()

    // Reusing any of the three would make two logins indistinguishable to the checks below.
    assertTrue(a.state != b.state && a.nonce != b.nonce && a.codeVerifier != b.codeVerifier)
  }

  // ─── the exchange ─────────────────────────────────────────────────────────

  @Test
  fun `a full round trip yields the identity the provider asserted`() {
    val provider = FakeProvider(claims = mapOf(EquivalentIdentities.CLAIM to listOf(ALIAS)))
    val transport = RecordingTransport(ISSUER, provider)
    val rp = OidcRelyingParty.discover(
      ISSUER, clientId, redirectUri, transport, trustsEquivalentIdentities = true,
    )
    val started = rp.beginAuthorization()
    provider.nonceToEcho = started.nonce

    val identity = rp.completeAuthorization("the-code", started.codeVerifier, started.nonce)

    assertEquals("$ISSUER/e/abc", identity.webId)
    assertEquals(setOf(ALIAS), identity.equivalentIdentities)
    // The exchange must present the verifier and no secret — these clients have none.
    val form = transport.postedForms.single()
    assertEquals(started.codeVerifier, form["code_verifier"])
    assertEquals("authorization_code", form["grant_type"])
    assertTrue("client_secret" !in form)
  }

  @Test
  fun `the JWKS is fetched through the transport, not by the SDK`() {
    // The signature check needs keys. Where it gets them decides whether the calling service's
    // HTTP policy — DNS vetting, timeouts, rate limits — applies to that fetch at all.
    val provider = FakeProvider()
    val transport = RecordingTransport(ISSUER, provider)
    val rp = OidcRelyingParty.discover(ISSUER, clientId, redirectUri, transport)
    val started = rp.beginAuthorization()
    provider.nonceToEcho = started.nonce

    rp.completeAuthorization("the-code", started.codeVerifier, started.nonce)

    assertContains(transport.fetched, JWKS_URL)
  }

  @Test
  fun `a token answering a different request is refused`() {
    // Replay: a token for an earlier login of the same person at the same client, still signed and
    // unexpired. Only the nonce separates it from this one.
    val provider = FakeProvider()
    val rp = OidcRelyingParty.discover(ISSUER, clientId, redirectUri, RecordingTransport(ISSUER, provider))
    val started = rp.beginAuthorization()
    provider.nonceToEcho = "the-nonce-of-an-earlier-login"

    assertFails { rp.completeAuthorization("the-code", started.codeVerifier, started.nonce) }
  }

  @Test
  fun `a token minted for another client, or another issuer, is refused`() {
    for (provider in listOf(
      FakeProvider(tokenAudience = "did:web:someone.else"),
      FakeProvider(tokenIssuer = "https://evil.invalid"),
    )) {
      val rp = OidcRelyingParty.discover(ISSUER, clientId, redirectUri, RecordingTransport(ISSUER, provider))
      val started = rp.beginAuthorization()
      // Correct nonce on purpose: only the claim under test may be the reason it fails.
      provider.nonceToEcho = started.nonce

      assertFails { rp.completeAuthorization("c", started.codeVerifier, started.nonce) }
    }
  }

  @Test
  fun `an expired token is refused`() {
    val provider = FakeProvider(expiresAt = Date(System.currentTimeMillis() - 60_000))
    val stale = OidcRelyingParty.discover(ISSUER, clientId, redirectUri, RecordingTransport(ISSUER, provider))
    val started = stale.beginAuthorization()
    provider.nonceToEcho = started.nonce

    assertFails { stale.completeAuthorization("c", started.codeVerifier, started.nonce) }
  }

  @Test
  fun `an error response says what the provider said`() {
    val refusing = rp(FakeProvider(error = "invalid_grant" to "code is expired"))
    val started = refusing.beginAuthorization()

    val failure = assertFails { refusing.completeAuthorization("c", started.codeVerifier, started.nonce) }

    assertContains(failure.message.orEmpty(), "invalid_grant")
    assertContains(failure.message.orEmpty(), "code is expired")
  }

  // ─── equivalent identities ────────────────────────────────────────────────

  @Test
  fun `an omitted or empty claim asserts no equivalent identity`() {
    assertEquals(emptySet(), signIn(FakeProvider()).equivalentIdentities)
    assertEquals(emptySet(), signIn(claiming(emptyList<String>())).equivalentIdentities)
  }

  @Test
  fun `the claim is a set, whatever its order, duplicates or repetition of sub`() {
    val other = "http://other.example.invalid/people/alice"

    val identity = signIn(claiming(listOf(other, ALIAS, "$ISSUER/e/abc", ALIAS)))

    assertEquals(setOf(ALIAS, other), identity.equivalentIdentities)
  }

  @Test
  fun `a malformed claim refuses the sign-in`() {
    for (value in listOf(null, ALIAS, mapOf("id" to ALIAS), listOf(ALIAS, "urn:example:alice"), listOf("/alice"))) {
      // Refused by this code as a decision. A typed read would throw the library's `ParseException`.
      val failure = assertFailsWith<IllegalStateException>("value $value") { signIn(claiming(value)) }
      assertContains(failure.message.orEmpty(), EquivalentIdentities.CLAIM)
    }
  }

  @Test
  fun `a provider trusted for login alone adds no equivalent identity`() {
    val identity = signIn(claiming(listOf(ALIAS)), trusted = false)

    assertEquals("$ISSUER/e/abc", identity.webId, "the login itself stands")
    assertEquals(emptySet(), identity.equivalentIdentities)
  }

  @Test
  fun `a provider trusted for login alone is still refused a malformed claim`() {
    assertFailsWith<IllegalStateException> { signIn(claiming(listOf("urn:example:alice")), trusted = false) }
  }

  @Test
  fun `the registered also_known_as claim asserts no equivalent identity`() {
    // A human pseudonym in OIDC's registered claims, whatever shape it arrives in.
    val provider = FakeProvider(claims = mapOf("also_known_as" to listOf(ALIAS, "urn:sempods:e:abc")))

    assertEquals(emptySet(), signIn(provider).equivalentIdentities)
  }

  @Test
  fun `signature, audience, issuer, expiry and nonce still decide first`() {
    // `SPS-OIDC-006` runs before the claim is read. With a valid claim the token is still refused.
    // With a malformed one the check still gives the reason, because the claim is read from a
    // validated token only.
    for (claimed in listOf(listOf(ALIAS), listOf("urn:example:alice"))) {
      val claims = mapOf(EquivalentIdentities.CLAIM to claimed)
      for (provider in listOf(
        FakeProvider(claims = claims, tokenAudience = "did:web:someone.else"),
        FakeProvider(claims = claims, tokenIssuer = "https://evil.invalid"),
        FakeProvider(claims = claims, expiresAt = Date(System.currentTimeMillis() - 60_000)),
        FakeProvider(claims = claims, signingKey = strangerKeyPair.private as RSAPrivateKey),
      )) {
        val failure = assertFails { signIn(provider) }
        assertTrue(EquivalentIdentities.CLAIM !in failure.message.orEmpty(), "refused for the check: $failure")
      }

      val replayed = FakeProvider(claims = claims)
      val rp = rp(replayed, trustsEquivalentIdentities = true)
      val started = rp.beginAuthorization()
      replayed.nonceToEcho = "the-nonce-of-an-earlier-login"
      val failure = assertFails { rp.completeAuthorization("c", started.codeVerifier, started.nonce) }
      assertTrue(EquivalentIdentities.CLAIM !in failure.message.orEmpty(), "refused for the nonce: $failure")
    }
  }

  // ─── the provider ─────────────────────────────────────────────────────────

  /** Answers like an OpenID Provider, and can be told to answer wrongly. */
  private class FakeProvider(
    advertisedIssuer: String = ISSUER,
    /**
     * What the provider stored with the code and will echo in the token.
     *
     * A real token endpoint never sees the request's nonce — it looks up what it kept. Modelling
     * that is what makes the replay test mean something: if the fake echoed whatever it was asked
     * for, no mismatch could ever occur and the test would pass vacuously.
     */
    var nonceToEcho: String = "",
    private val tokenIssuer: String = ISSUER,
    private val tokenAudience: String = "did:web:pod.example.invalid",
    private val expiresAt: Date? = Date(System.currentTimeMillis() + 600_000),
    private val error: Pair<String, String>? = null,
    /** Further claims, written verbatim — a `null` value included. */
    private val claims: Map<String, Any?> = emptyMap(),
    private val signingKey: RSAPrivateKey = keyPair.private as RSAPrivateKey,
  ) {
    val discoveryDocument = """
      {"issuer":"$advertisedIssuer","authorization_endpoint":"$ISSUER/authorize",
       "token_endpoint":"$ISSUER/token","jwks_uri":"$JWKS_URL"}
    """.trimIndent()

    fun tokenResponseFor(form: Map<String, String>): String {
      error?.let { (code, description) ->
        return """{"error":"$code","error_description":"$description"}"""
      }
      // The nonce the client sent is not visible to a token endpoint, so a provider echoes what it
      // stored with the code. Echoing the *request's* nonce would make the replay test vacuous.
      val idToken = signedIdToken(
        nonce = nonceToEcho, issuer = tokenIssuer, audience = tokenAudience, expiresAt = expiresAt,
        claims = claims, signingKey = signingKey,
      )
      return """{"access_token":"at","token_type":"Bearer","expires_in":900,"id_token":"$idToken"}"""
    }
  }

  companion object {
    private const val ISSUER = "https://id.example.invalid"
    private const val KEY_ID = "rp-test-key"

    private const val JWKS_URL = "$ISSUER/.well-known/jwks.json"

    private const val ALIAS = "https://other.example.invalid/alice#me"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val strangerKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val jwks = JWKSet(RSAKey.Builder(keyPair.public as RSAPublicKey).keyID(KEY_ID).build()).toString()

    private fun signedIdToken(
      nonce: String,
      issuer: String,
      audience: String,
      expiresAt: Date?,
      claims: Map<String, Any?>,
      signingKey: RSAPrivateKey,
    ): String {
      val claimsSet = JWTClaimsSet.Builder()
        .issuer(issuer).subject("$ISSUER/e/abc").audience(audience)
        .issueTime(Date())
        .apply { expiresAt?.let { expirationTime(it) }; if (nonce.isNotEmpty()) claim("nonce", nonce) }
        .apply { claims.forEach { (name, value) -> claim(name, value) } }
        .serializeNullClaims(true)
        .build()
      val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).type(JOSEObjectType.JWT).build()
      return SignedJWT(header, claimsSet).apply { sign(RSASSASigner(signingKey)) }.serialize()
    }

  }
}
