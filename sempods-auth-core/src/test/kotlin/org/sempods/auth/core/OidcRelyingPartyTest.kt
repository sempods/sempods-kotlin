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

  private fun rp(provider: FakeProvider = FakeProvider()) =
    OidcRelyingParty.discover(ISSUER, clientId, redirectUri, RecordingTransport(ISSUER, provider))

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
    val provider = FakeProvider()
    val transport = RecordingTransport(ISSUER, provider)
    val rp = OidcRelyingParty.discover(ISSUER, clientId, redirectUri, transport)
    val started = rp.beginAuthorization()
    provider.nonceToEcho = started.nonce

    val identity = rp.completeAuthorization("the-code", started.codeVerifier, started.nonce)

    assertEquals("$ISSUER/e/abc", identity.webId)
    assertContains(identity.alsoKnownAs, "urn:sempods:e:abc")
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
      val idToken = signedIdToken(nonce = nonceToEcho, issuer = tokenIssuer, audience = tokenAudience, expiresAt = expiresAt)
      return """{"access_token":"at","token_type":"Bearer","expires_in":900,"id_token":"$idToken"}"""
    }
  }

  companion object {
    private const val ISSUER = "https://id.example.invalid"
    private const val KEY_ID = "rp-test-key"

    private const val JWKS_URL = "$ISSUER/.well-known/jwks.json"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val jwks = JWKSet(RSAKey.Builder(keyPair.public as RSAPublicKey).keyID(KEY_ID).build()).toString()

    private fun signedIdToken(nonce: String, issuer: String, audience: String, expiresAt: Date?): String {
      val claims = JWTClaimsSet.Builder()
        .issuer(issuer).subject("$ISSUER/e/abc").audience(audience)
        .claim("also_known_as", listOf("urn:sempods:e:abc"))
        .issueTime(Date())
        .apply { expiresAt?.let { expirationTime(it) }; if (nonce.isNotEmpty()) claim("nonce", nonce) }
        .build()
      return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).type(JOSEObjectType.JWT).build(), claims)
        .apply { sign(RSASSASigner(keyPair.private as RSAPrivateKey)) }
        .serialize()
    }

  }
}
