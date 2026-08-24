package org.sempods.api.pod.system.auth

import org.sempods.pods.oauth.SERVICE_CLIENT_TYPE
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.sempods.auth.core.JwtVerification
import org.sempods.auth.core.JwtVerifier
import org.sempods.auth.core.SigningKeys
import org.sempods.auth.core.stringClaimOrNull
import org.sempods.auth.core.stringListClaimOrNull
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Issues pod-scoped OAuth access tokens (RS256 JWTs).
 *
 * The key comes from [SigningKeys] and therefore survives a restart — before it was persisted,
 * every deploy invalidated all outstanding access tokens (observed live as
 * `[oauth/access] Token signature verification failed` mid-session). Its store is
 * [PodSigningKeyStore] over `oauth.signingKeys`; JWKS exposes every persisted key so
 * resource servers can keep verifying older tokens after a rotation.
 *
 * TODO: nothing performs a rotation. The persisted schema already carries `kid`, `algorithm` and
 *  `retiredAt`, and the JWKS endpoint publishes every persisted key, so this is a change to
 *  [SigningKeys] — mark the active key retired, mint a successor, keep the old one in JWKS for the
 *  grace period — rather than a migration. Named as a limitation in `docs/auth/README.md`.
 */
class PodTokenIssuer(
  private val apiBaseUrl: String,
  signingKeys: SigningKeys,
) {

  private val signingKey: RSAKey = signingKeys.signingKey()

  /**
   * Built once over the keys [SigningKeys] parsed at boot. [readSession] used to be handed a
   * `JWKSet` its caller re-parsed from [jwksJson] on every request.
   */
  private val jwtVerifier = JwtVerifier.localKeys(signingKeys.publicKeys)

  /** JWKS JSON containing only public keys. */
  val jwksJson: String = signingKeys.jwksJson

  /**
   * Issues a pod-scoped user access token (sub = WebID, default TTL 1 h).
   *
   * Thin wrapper around [issueToken] kept under its original signature so user
   * call sites do not need to change. Service-client tokens take a different
   * route via [issueServiceToken].
   *
   * Claims:
   * - iss: `{apiBaseUrl}{pod}/` (pod-specific issuer)
   * - sub: user's WebID
   * - client_id: the app's did:web identity
   * - scope: space-separated granted scopes
   * - exp: now + 3600 s
   */
  fun issue(pod: String, webId: String, clientId: String, scopes: Set<String>): String {
    return issueToken(
      pod = pod,
      subject = webId,
      clientId = clientId,
      scopes = scopes,
      ttlSeconds = USER_TOKEN_TTL_SECONDS,
      clientType = null,
    )
  }

  /**
   * Issues a pod-scoped service access token (OAuth `client_credentials`).
   *
   * Differs from [issue] in three ways:
   * - `sub` is the [clientId] itself, not a WebID — there is no user behind the call.
   * - TTL is short ([SERVICE_TOKEN_TTL_SECONDS]) because the client mints a new
   *   token on demand; no refresh-token mechanism backs this grant.
   * - The `client_type=service` claim labels the caller so the resource layer's
   *   audit hook can attribute the request correctly.
   */
  fun issueServiceToken(
    pod: String,
    clientId: String,
    scopes: Set<String>,
    ttlSeconds: Long = SERVICE_TOKEN_TTL_SECONDS,
  ): String {
    return issueToken(
      pod = pod,
      subject = clientId,
      clientId = clientId,
      scopes = scopes,
      ttlSeconds = ttlSeconds,
      clientType = SERVICE_CLIENT_TYPE,
    )
  }

  /**
   * A browser session on this pod's origin.
   *
   * The person proved who they are at the id-server; this is how the pod remembers it across the
   * consent screen and the next authorization, instead of running the whole round trip again. It
   * answers *who*, and only that — *which consent screen, and has it been used* is
   * `ConsentTransactionStore`'s question, and a session-derived value cannot answer it: the same
   * session spans every screen for twelve hours, and a second sign-in replaces it.
   *
   * **Not an access token, and not accepted as one.** It carries no `client_id`, which
   * `PodTokenAuthenticator` requires, and `token_use=session` says so outright —
   * two independent reasons, because the two are signed by the same key and a shape that drifted
   * into being redeemable would be silent.
   */
  fun issueSession(pod: String, webId: String, alsoKnownAs: List<String> = emptyList()): String {
    val now = Instant.now()
    val claims = JWTClaimsSet.Builder()
      .issuer("${apiBaseUrl.trimEnd('/')}/$pod/")
      .subject(webId)
      .claim(CLAIM_TOKEN_USE, TOKEN_USE_SESSION)
      // The equivalent identity URIs the id-server asserted. Ownership and grants are decided
      // against all of them, so a session that dropped them would silently demote someone whose
      // pod records an alias — and would write app grants under a narrower subject set than the
      // consent actually covers.
      .apply { if (alsoKnownAs.isNotEmpty()) claim(CLAIM_ALSO_KNOWN_AS, alsoKnownAs) }
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(SESSION_TTL_SECONDS)))
      .jwtID(UUID.randomUUID().toString())
      .build()
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
    jwt.sign(RSASSASigner(signingKey))
    return jwt.serialize()
  }

  /**
   * The person behind a session cookie, or `null` for anything that is not one of ours.
   *
   * Checks the signature and the expiry against the published keys **first**, then that the issuer
   * is *this* pod (a session on one pod must not authenticate on another — pods are isolated
   * tenants), and that it says it is a session. That order is the point: a cookie is
   * attacker-supplied, and nothing should interpret its claims until they are known to be ours.
   */
  fun readSession(pod: String, cookieValue: String?): SessionPrincipal? {
    // Signature and expiry first, and everything else after: until they hold, the claims are a
    // stranger's JSON. Reading them earlier is how an arbitrary cookie reaches parsing code on a
    // public endpoint — and Nimbus' typed getters throw on a custom claim of the wrong type, so
    // `{"token_use": []}` became a 500 rather than "not a session". `JwtVerifier` hands back the
    // claims only on the branch where both held, so the order is now the type's rather than a
    // convention this method has to keep.
    val claims = (jwtVerifier.verify(cookieValue?.trim()) as? JwtVerification.Verified)?.claims
      ?: return null

    // Still read defensively: this pod signs no such token today, but a claim of the wrong type is
    // the same answer as an absent one, and that should not depend on who signed it.
    if (claims.stringClaimOrNull(CLAIM_TOKEN_USE) != TOKEN_USE_SESSION) return null
    if (claims.issuer != "${apiBaseUrl.trimEnd('/')}/$pod/") return null
    val webId = claims.subject?.takeIf { it.isNotBlank() } ?: return null
    return SessionPrincipal(webId = webId, alsoKnownAs = claims.stringListClaimOrNull(CLAIM_ALSO_KNOWN_AS).orEmpty())
  }

  data class SessionPrincipal(val webId: String, val alsoKnownAs: List<String>)

  private fun issueToken(
    pod: String,
    subject: String,
    clientId: String,
    scopes: Set<String>,
    ttlSeconds: Long,
    clientType: String?,
  ): String {
    val now = Instant.now()
    val issuer = "${apiBaseUrl.trimEnd('/')}/$pod/"

    val header = JWSHeader.Builder(JWSAlgorithm.RS256)
      .keyID(signingKey.keyID)
      .build()
    val claimsBuilder = JWTClaimsSet.Builder()
      .issuer(issuer)
      .subject(subject)
      .claim("client_id", clientId)
      .claim("scope", scopes.joinToString(" "))
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
      .jwtID(UUID.randomUUID().toString())
    if (clientType != null) {
      claimsBuilder.claim("client_type", clientType)
    }
    val jwt = SignedJWT(header, claimsBuilder.build())
    jwt.sign(RSASSASigner(signingKey))
    return jwt.serialize()
  }

  companion object {
    /** Default TTL for user access tokens (1 h). */
    const val USER_TOKEN_TTL_SECONDS: Long = 3600

    /**
     * How long the pod remembers a sign-in (12 h).
     *
     * Longer than an access token because it is not one: it authorises nothing on its own, it only
     * saves the person a round trip to the id-server. Short enough that a shared machine does not
     * stay signed in overnight.
     */
    const val SESSION_TTL_SECONDS: Long = 12 * 3600

    /** Says a token is a browser session, so nothing can mistake it for an access token. */
    const val CLAIM_TOKEN_USE = "token_use"
    const val TOKEN_USE_SESSION = "session"
    const val CLAIM_ALSO_KNOWN_AS = "also_known_as"

    /**
     * Default TTL for service-client access tokens (10 min). `client_credentials`
     * needs no refresh — the client mints a new token on demand. The per-pod
     * token cache on the client side amortises the token-endpoint calls.
     */
    const val SERVICE_TOKEN_TTL_SECONDS: Long = 600
  }
}
