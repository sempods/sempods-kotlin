package org.sempods.mcp.auth

import org.sempods.auth.core.JwtVerifier
import com.nimbusds.jwt.JWTClaimsSet
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceBearerVerifierTest {

  private val base = "https://mcp.test"
  private val key = JwtTestSupport.generateKey("s1")
  private val verifier = ServiceBearerVerifier(base, JwtVerifier.localKeys(listOf(key.toPublicJWK())))

  private fun accessToken(
    issuer: String = base,
    user: String = "https://id.test/e/abc",
    profile: String = "default",
    clientId: String = "dyn:abc",
    scope: String = "public-read",
    expiresInSeconds: Long = 3600,
  ): String {
    val now = Instant.now()
    val claims = JWTClaimsSet.Builder()
      .issuer(issuer)
      .subject(user)
      .claim("client_id", clientId)
      .claim("profile", profile)
      .claim("scope", scope)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
      .build()
    return JwtTestSupport.sign(key, claims)
  }

  @Test
  fun `valid token yields the session identity`() {
    val session = verifier.verify(accessToken())
    assertEquals("https://id.test/e/abc", session?.user)
    assertEquals("default", session?.profile)
    assertEquals("dyn:abc", session?.clientId)
    assertEquals(setOf("public-read"), session?.scopes)
  }

  @Test
  fun `token from a foreign issuer is rejected`() {
    assertNull(verifier.verify(accessToken(issuer = "https://evil.test")))
  }

  @Test
  fun `a named-profile token is accepted when its issuer matches the profile`() {
    val session = verifier.verify(accessToken(issuer = "$base/private", profile = "private"))
    assertEquals("private", session?.profile)
  }

  @Test
  fun `a token whose issuer and profile claim disagree is rejected`() {
    // Root issuer but a named profile claim → inconsistent, must not verify.
    assertNull(verifier.verify(accessToken(issuer = base, profile = "private")))
    // Named-profile issuer but the default profile claim → likewise inconsistent.
    assertNull(verifier.verify(accessToken(issuer = "$base/private", profile = "default")))
  }

  @Test
  fun `expired token is rejected`() {
    assertNull(verifier.verify(accessToken(expiresInSeconds = -10)))
  }

  @Test
  fun `absent bearer is rejected`() {
    assertNull(verifier.verify(null))
  }

  private fun tokenWithTyp(typ: String): String {
    val now = Instant.now()
    val claims = JWTClaimsSet.Builder()
      .issuer(base).subject("https://id.test/e/abc").claim("typ", typ)
      .jwtID(java.util.UUID.randomUUID().toString())
      .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build()
    return JwtTestSupport.sign(key, claims)
  }

  @Test
  fun `a web_session token is rejected as an MCP bearer`() {
    assertNull(verifier.verify(tokenWithTyp("web_session")))
  }

  @Test
  fun `verifyWebSession accepts a web_session token and rejects an access token`() {
    val web = verifier.verifyWebSession(tokenWithTyp("web_session"))
    assertEquals("https://id.test/e/abc", web?.user)
    assertNull(verifier.verifyWebSession(tokenWithTyp("access")))
    assertNull(verifier.verifyWebSession(accessToken()))
  }
}
