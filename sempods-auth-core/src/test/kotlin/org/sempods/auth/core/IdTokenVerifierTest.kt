package org.sempods.auth.core

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The checks an `id_token` has to survive before it becomes a sempods identity.
 *
 * Worth testing against a real JWKS rather than by inspection, because every failure mode here is
 * silent: a token that skips the issuer check still produces a perfectly valid-looking WebID, just
 * one its bearer is not entitled to. `iss` and `sub` are hashed into that WebID
 * ([org.sempods.auth.login.LoginService]), so accepting a foreign one is impersonation, not a
 * cosmetic slip.
 *
 * The JWKS is served from a local server so nothing here depends on Google or Apple being
 * reachable, or on their keys not having rotated.
 */
class IdTokenVerifierTest {

  private val audience = "test-client-id"
  private val issuer = "https://issuer.example.invalid"

  private fun signedToken(
    iss: String = issuer,
    aud: String = audience,
    sub: String = "user-1",
    expiresAt: Date? = Date(System.currentTimeMillis() + 600_000),
    extraClaims: Map<String, Any?> = emptyMap(),
  ): String {
    val claims = JWTClaimsSet.Builder().issuer(iss).audience(aud).subject(sub)
      .apply {
        expiresAt?.let { expirationTime(it) }
        extraClaims.forEach { (name, value) -> claim(name, value) }
      }
      .build()
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims)
    jwt.sign(RSASSASigner(keyPair.private as RSAPrivateKey))
    return jwt.serialize()
  }

  private fun verifier(
    canonicalIssuer: String = issuer,
    alsoAccepted: Set<String> = emptySet(),
  ) = IdTokenVerifier(jwksUrl, canonicalIssuer, audience, alsoAccepted)

  private fun rejects(idToken: String, verifier: IdTokenVerifier = verifier()) =
    runCatching { verifier.verify(idToken) }.isFailure

  @Test
  fun `a well-formed token from the expected issuer is accepted`() {
    val claims = verifier().verify(signedToken())

    assertEquals("user-1", claims.subject)
    assertEquals(issuer, claims.issuer)
  }

  @Test
  fun `a token from another issuer is refused`() {
    // The one that matters: same signature scheme, same audience, different minter.
    assertTrue(rejects(signedToken(iss = "https://attacker.example.invalid")))
  }

  @Test
  fun `an alternative spelling of the issuer is accepted and normalised`() {
    // Google mints both `https://accounts.google.com` and the bare host. The same person must not
    // end up with two WebIDs depending on which one arrived.
    val claims = verifier(alsoAccepted = setOf("issuer.example.invalid"))
      .verify(signedToken(iss = "issuer.example.invalid"))

    assertEquals(issuer, claims.issuer, "must be normalised before it reaches the WebID hash")
  }

  @Test
  fun `a token minted for a different client is refused`() {
    assertTrue(rejects(signedToken(aud = "someone-elses-client-id")))
  }

  @Test
  fun `an expired token is refused, and so is one that never expires`() {
    assertTrue(rejects(signedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))))
    // Nimbus validates an `exp` it finds but does not insist on one — without requiring the claim,
    // a token minted without it would verify forever.
    assertTrue(rejects(signedToken(expiresAt = null)))
  }

  @Test
  fun `a token signed by someone else is refused`() {
    val foreign = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(),
      JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("user-1")
        .expirationTime(Date(System.currentTimeMillis() + 600_000)).build(),
    ).apply { sign(RSASSASigner(foreign.private as RSAPrivateKey)) }

    assertTrue(rejects(jwt.serialize()))
  }

  @Test
  fun `an email counts only once the provider says it is verified`() {
    // Apple spells this as a string, Google as a boolean, and `getBooleanClaim` throws on the
    // former — so the shape of one field decides whether a login lands in the email namespace or
    // the anonymous one.
    fun emailFrom(verified: Any?) = IdTokenVerifier.verifiedEmail(
      verifier().verify(signedToken(extraClaims = mapOf("email" to "a@b.invalid", "email_verified" to verified)))
    )

    assertEquals("a@b.invalid", emailFrom(true))
    assertEquals("a@b.invalid", emailFrom("true"))
    assertEquals("a@b.invalid", emailFrom("TRUE"))
    assertNull(emailFrom(false), "an unverified address must not mint an email-namespace WebID")
    assertNull(emailFrom("false"))
    assertNull(emailFrom(null))
  }

  @Test
  fun `a blank email is no email`() {
    val claims = verifier().verify(
      signedToken(extraClaims = mapOf("email" to "   ", "email_verified" to true))
    )

    assertNull(IdTokenVerifier.verifiedEmail(claims))
  }

  @Test
  fun `anything other than a yes reads as no`() {
    assertTrue(IdTokenVerifier.isTrue(true))
    assertTrue(IdTokenVerifier.isTrue("true"))
    assertFalse(IdTokenVerifier.isTrue(false))
    assertFalse(IdTokenVerifier.isTrue("yes"))
    assertFalse(IdTokenVerifier.isTrue(1))
    assertFalse(IdTokenVerifier.isTrue(null))
  }

  companion object {

    private const val KEY_ID = "test-key"

    private val keyPair = KeyPairGenerator.getInstance("RSA")
      .apply { initialize(2048) }
      .generateKeyPair()

    private val jwks = JWKSet(
      RSAKey.Builder(keyPair.public as RSAPublicKey).keyID(KEY_ID).build()
    ).toString()

    /**
     * The JDK's own HTTP server rather than a framework one: this module deliberately has no HTTP
     * stack, and a test dependency would be the first crack in that.
     */
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/jwks.json") { exchange ->
        val body = jwks.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      start()
    }

    /** Port 0 means "pick one" — the actual one is only known once the socket is bound. */
    private val jwksUrl: String = "http://127.0.0.1:${server.address.port}/jwks.json"

    @JvmStatic
    @AfterAll
    fun stopServer() {
      server.stop(0)
    }
  }
}
