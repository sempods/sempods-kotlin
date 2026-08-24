package org.sempods.mcp.auth

import com.nimbusds.jwt.JWTClaimsSet
import org.sempods.mcp.oauth.TokenIssuer
import org.sempods.mcp.persist.ProfilePath
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.JwtRejection
import org.sempods.auth.core.JwtVerification
import org.sempods.auth.core.JwtVerifier
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.SigningKeys

/**
 * Verifies the service's *own* access tokens (those minted by [org.sempods.mcp.oauth.TokenIssuer])
 * presented by an AI client on each MCP call. Validates issuer, expiry and RS256 signature
 * against the signing keys, and extracts the session identity.
 *
 * Takes a [JwtVerifier] directly so it is trivially testable; in production it is built over the
 * process's one [SigningKeys], which is also what [org.sempods.mcp.oauth.TokenIssuer] signs with.
 * Reading the store a second time here would be a second answer to one question — and on a first
 * boot a wrong one, since whichever of the two ran first found the collection empty and would then
 * reject every token the other minted.
 */
class ServiceBearerVerifier(
  private val mcpBaseUrl: String,
  private val jwtVerifier: JwtVerifier,
) {

  data class Session(
    val user: String,
    val profile: String,
    val clientId: String,
    val scopes: Set<String>,
    /** JWT id of the presented access token — distinguishes one token from its successor. */
    val tokenJti: String?,
    /** Issue time of the presented access token. */
    val issuedAt: java.time.Instant?,
  )

  /**
   * Browser web-session identity (from the `/_system/ui` session cookie). [csrfToken] is the
   * session token's `jti` — it lives only in the httpOnly cookie, so a same-origin form can echo
   * it but a cross-site request cannot know it: a per-session CSRF token for mutating POSTs.
   */
  data class WebPrincipal(val user: String, val csrfToken: String)

  /**
   * Verifies an **MCP access token** (the AI-client → service bearer). Rejects web-session
   * tokens so a `/_system/ui` cookie can never be replayed as an MCP bearer.
   */
  fun verify(bearerToken: String?): Session? {
    val claims = verifiedClaims(bearerToken) ?: return null
    if ((claims.getClaim("typ") as? String) == TokenIssuer.TYP_WEB_SESSION) return null
    val user = claims.subject?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val clientId = (claims.getClaim("client_id") as? String)?.trim().orEmpty()
    val profile = (claims.getClaim("profile") as? String)?.trim().orEmpty()
    // The issuer must be the one the profile's authorization-server metadata advertises. Binding
    // `iss` to the `profile` claim also rejects a token whose two fields disagree.
    if (claims.issuer != ProfilePath.baseUrlFor(mcpBaseUrl, profile)) return null
    val scopes = OAuthSyntax.scopeClaimValues(claims)
    return Session(
      user = user,
      profile = profile,
      clientId = clientId,
      scopes = scopes,
      tokenJti = claims.jwtid?.trim()?.takeIf { it.isNotBlank() },
      issuedAt = claims.issueTime?.toInstant(),
    )
  }

  /** Verifies a **web-session** token; requires `typ=web_session` (rejects MCP access tokens). */
  fun verifyWebSession(token: String?): WebPrincipal? {
    val claims = verifiedClaims(token) ?: return null
    if ((claims.getClaim("typ") as? String) != TokenIssuer.TYP_WEB_SESSION) return null
    // Web-session cookies are always minted under the service root issuer (never a profile).
    if (claims.issuer != mcpBaseUrl) return null
    val user = claims.subject?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val jti = claims.jwtid?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return WebPrincipal(user, csrfToken = jti)
  }

  /**
   * Shared expiry + RS256-signature check; returns the claims or null. The issuer is validated by
   * the caller ([verify] binds it to the profile claim, [verifyWebSession] pins it to the root),
   * since a named profile is its own authorization-server issuer.
   */
  private fun verifiedClaims(token: String?): JWTClaimsSet? = when (val v = jwtVerifier.verify(token)) {
    is JwtVerification.Verified -> v.claims
    is JwtVerification.Rejected -> {
      // An access token aging out is routine and constant; only the other cause is worth a warning.
      if (v.why == JwtRejection.badSignature) {
        logger.warn { "Service token rejected: signature verification failed" }
      }
      null
    }
    // Not a JWT, or signed with a key or algorithm this process holds nothing for. Silent, as before.
    JwtVerification.Inconclusive -> null
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    /** Production factory: verify against the very keys this process signs with. */
    fun using(mcpBaseUrl: String, signingKeys: SigningKeys): ServiceBearerVerifier =
      ServiceBearerVerifier(mcpBaseUrl, JwtVerifier.localKeys(signingKeys.publicKeys))
  }
}
