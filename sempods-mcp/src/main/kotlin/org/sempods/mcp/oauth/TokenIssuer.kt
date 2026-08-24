package org.sempods.mcp.oauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.sempods.auth.core.SigningKeys
import org.sempods.mcp.persist.ProfilePath
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Issues the service's own OAuth access tokens (RS256 JWTs) — the tokens the service hands to
 * AI clients (the AI-client → service OAuth layer). The key comes from [SigningKeys] over
 * [org.sempods.mcp.persist.oauth.McpSigningKeyStore], so tokens survive restarts; JWKS exposes
 * every persisted public key so older tokens still verify after a rotation.
 *
 * Claims:
 *  - iss:       the profile's authorization-server issuer — the service root [mcpBaseUrl] for the
 *               default profile, `[mcpBaseUrl]/<profile>` for a named one. This matches the
 *               `issuer` the profile's RFC 8414 metadata advertises (both derive from
 *               [org.sempods.mcp.persist.ProfilePath.baseUrlFor]); a client that binds token
 *               validation to the discovered issuer (RFC 9068) accepts it.
 *  - sub:       the user's stable WebID (`user`)
 *  - client_id: the AI client's DCR client_id
 *  - profile:   the profile bundle the session is bound to (default = "")
 *  - scope:     space-separated granted feature scopes
 *  - exp:       now + [USER_TOKEN_TTL_SECONDS]
 */
class TokenIssuer(
  private val mcpBaseUrl: String,
  signingKeys: SigningKeys,
) {

  private val signingKey: RSAKey = signingKeys.signingKey()

  /** JWKS JSON containing only public keys. */
  val jwksJson: String = signingKeys.jwksJson

  fun issueAccessToken(
    user: String,
    profile: String,
    clientId: String,
    scopes: Set<String>,
    ttlSeconds: Long = USER_TOKEN_TTL_SECONDS,
  ): String {
    val now = Instant.now()
    val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build()
    val claims = JWTClaimsSet.Builder()
      .issuer(ProfilePath.baseUrlFor(mcpBaseUrl, profile))
      .subject(user)
      .claim("client_id", clientId)
      .claim("profile", profile)
      .claim("scope", scopes.joinToString(" "))
      .claim("typ", TYP_ACCESS)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
      .jwtID(UUID.randomUUID().toString())
      .build()
    val jwt = SignedJWT(header, claims)
    jwt.sign(RSASSASigner(signingKey))
    return jwt.serialize()
  }

  /**
   * Mints a browser **web-session** token (carried in the `/_system/ui` session cookie, M2),
   * signed with the same key but tagged `typ=web_session` so it can never be presented as an
   * MCP access token (and vice versa). Holds only the user identity — the active profile is a
   * request-scoped selection in the UI, not bound to the session (one session spans all profiles).
   * Always minted under the service-root issuer.
   */
  fun issueWebSession(user: String, ttlSeconds: Long = WEB_SESSION_TTL_SECONDS): String =
    issueWebSessionWithJti(user, ttlSeconds).token

  /**
   * As [issueWebSession], but also returns the token's `jti` — the value that doubles as the web
   * session's CSRF token. Lets a caller that needs the CSRF token (e.g. rendering a form right
   * after establishing the session) obtain it directly, without parsing/verifying the token it
   * just minted.
   */
  fun issueWebSessionWithJti(user: String, ttlSeconds: Long = WEB_SESSION_TTL_SECONDS): IssuedWebSession {
    val now = Instant.now()
    val jti = UUID.randomUUID().toString()
    val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build()
    val claims = JWTClaimsSet.Builder()
      .issuer(mcpBaseUrl)
      .subject(user)
      .claim("typ", TYP_WEB_SESSION)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
      .jwtID(jti)
      .build()
    val token = SignedJWT(header, claims).apply { sign(RSASSASigner(signingKey)) }.serialize()
    return IssuedWebSession(token, jti)
  }

  /** A freshly minted web-session token together with its `jti` (the CSRF token). */
  data class IssuedWebSession(val token: String, val jti: String)

  companion object {
    /** Default TTL for service access tokens (1 h). */
    const val USER_TOKEN_TTL_SECONDS: Long = 3600

    /** Default TTL for browser web-session cookies (12 h). */
    const val WEB_SESSION_TTL_SECONDS: Long = 12 * 3600

    /** `typ` claim values that keep MCP access tokens and web-session cookies non-interchangeable. */
    const val TYP_ACCESS = "access"
    const val TYP_WEB_SESSION = "web_session"
  }
}
