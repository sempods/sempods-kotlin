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
import org.sempods.auth.core.instantClaimOrNull
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
   * Issues a pod-scoped user access token (sub = WebID), good for an hour.
   *
   * Service-client tokens take a different route via [issueServiceToken].
   *
   * Claims:
   * - iss: `{apiBaseUrl}{pod}/` (pod-specific issuer)
   * - sub: user's WebID
   * - client_id: the app's did:web identity
   * - scope: space-separated granted scopes
   * - exp: now + [USER_TOKEN_TTL_SECONDS]
   */
  fun issue(pod: String, webId: String, clientId: String, scopes: Set<String>): String =
    issue(pod, webId, clientId, scopes, USER_TOKEN_TTL_SECONDS)

  /**
   * The same, for a caller with a reason to hand out less than an hour.
   *
   * The token endpoint's reason is a refresh-token family whose deadline is nearer than that: an
   * access token minted from it must not outlive the family, or "seven days" means seven days and
   * an hour. The caller derives one number and spends it twice — on `exp` here, and on its own
   * `expires_in` — because two derivations of the same lifetime drift.
   *
   * An overload rather than a defaulted parameter: this module is published, and a default replaces
   * the JVM descriptor the four-argument form has always had.
   */
  fun issue(pod: String, webId: String, clientId: String, scopes: Set<String>, ttlSeconds: Long): String {
    return issueToken(
      pod = pod,
      subject = webId,
      clientId = clientId,
      scopes = scopes,
      ttlSeconds = ttlSeconds,
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
   * `ConsentTransactionStore`'s question, and a session-derived value cannot answer it: one
   * session spans every screen it outlives, and a second sign-in replaces it.
   *
   * **Not an access token, and not accepted as one.** It carries no `client_id`, which
   * `PodTokenAuthenticator` requires, and `token_use=session` says so outright —
   * two independent reasons, because the two are signed by the same key and a shape that drifted
   * into being redeemable would be silent.
   *
   */
  fun issueSession(pod: String, webId: String, alsoKnownAs: List<String> = emptyList()): String =
    issueSession(pod, webId, alsoKnownAs, Instant.now(), SESSION_TTL_SECONDS)

  /**
   * The form [renewSession] needs, kept beside the one a sign-in calls rather than folded into it.
   *
   * Two defaulted parameters on the public function would read the same in Kotlin source and
   * replace its JVM method with a five-argument one — a `NoSuchMethodError` for anything already
   * compiled against this module, which is published. The overload costs a line and keeps the
   * descriptor a sign-in has always had.
   *
   * @param authTime when the person authenticated at the id-server. A sign-in passes now; a
   *   renewal passes the original forward, because the absolute limit is measured from it and a
   *   renewal that reset it would lift it.
   * @param ttlSeconds how long the cookie is good for. [SESSION_TTL_SECONDS] except near the
   *   absolute deadline, where what is left of it is shorter.
   */
  internal fun issueSession(
    pod: String,
    webId: String,
    alsoKnownAs: List<String>,
    authTime: Instant,
    ttlSeconds: Long,
  ): String {
    val now = Instant.now()
    val claims = JWTClaimsSet.Builder()
      .issuer("${apiBaseUrl.trimEnd('/')}/$pod/")
      .subject(webId)
      .claim(CLAIM_TOKEN_USE, TOKEN_USE_SESSION)
      // When the person actually signed in, carried unchanged across every renewal — `exp` moves,
      // this does not. It is what [renewSession] measures the absolute limit against, and it is
      // OIDC Core 1.0 §2's `auth_time` because that is the claim that already means this.
      .claim(CLAIM_AUTH_TIME, authTime.epochSecond)
      // The equivalent identity URIs the id-server asserted. Ownership and grants are decided
      // against all of them, so a session that dropped them would silently demote someone whose
      // pod records an alias — and would write app grants under a narrower subject set than the
      // consent actually covers.
      .apply { if (alsoKnownAs.isNotEmpty()) claim(CLAIM_ALSO_KNOWN_AS, alsoKnownAs) }
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
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
    return SessionPrincipal(
      webId = webId,
      alsoKnownAs = claims.stringListClaimOrNull(CLAIM_ALSO_KNOWN_AS).orEmpty(),
      // A cookie minted before renewals existed carries no `auth_time`, and its `iat` is that
      // sign-in — the two were the same value until the first renewal moved one of them. So the
      // fallback is the honest reading rather than a grace period, and it drains: the renewal it
      // permits writes the claim, and the absolute limit runs from where it always did.
      authTime = claims.instantClaimOrNull(CLAIM_AUTH_TIME) ?: claims.issueTime?.toInstant() ?: Instant.EPOCH,
    )
  }

  /**
   * A fresh session cookie for a person who is using one, or `null` once the sign-in behind it is
   * too old to extend.
   *
   * This is what makes [SESSION_TTL_SECONDS] an idle window: the twelve hours run from the last
   * authorization. Which requests renew, and what that leaves untouched, is the caller's —
   * `PodAuthEndpoint.withRenewedSession`. [SESSION_ABSOLUTE_TTL_SECONDS] is the other end, and
   * carries why it exists.
   *
   * `null` is not an error: the caller leaves the cookie it has, which expires on its own and
   * sends the person through the id-server once.
   *
   * @return the cookie and the lifetime to set on it — never longer than what is left of
   *   [SESSION_ABSOLUTE_TTL_SECONDS] — or `null` once that is spent.
   */
  fun renewSession(pod: String, session: SessionPrincipal): RenewedSession? {
    val remaining = session.authTime.plusSeconds(SESSION_ABSOLUTE_TTL_SECONDS).epochSecond - Instant.now().epochSecond
    if (remaining <= 0) return null
    // The shorter of the two windows, so a renewal in the final hours ends *at* the deadline. The
    // full idle window there would put [SESSION_ABSOLUTE_TTL_SECONDS] most of a day out of date,
    // and that renewal is the one an actively used session gets last.
    val ttlSeconds = minOf(SESSION_TTL_SECONDS, remaining)
    return RenewedSession(
      token = issueSession(pod, session.webId, session.alsoKnownAs, session.authTime, ttlSeconds),
      ttlSeconds = ttlSeconds,
    )
  }

  /**
   * @param ttlSeconds how long [token] is good for. The caller sets the cookie's `Max-Age` from it
   *   rather than from [SESSION_TTL_SECONDS], so the browser stops presenting the value at the
   *   moment this server stops accepting it.
   */
  data class RenewedSession(val token: String, val ttlSeconds: Long)

  /**
   * @param authTime when the person signed in at the id-server, which a renewal carries forward
   *   rather than resetting. Older than the cookie's own `iat` on every session that has been
   *   renewed at least once.
   */
  data class SessionPrincipal(val webId: String, val alsoKnownAs: List<String>, val authTime: Instant)

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
     * How long the pod remembers a sign-in that nobody is using (12 h).
     *
     * Longer than an access token because it is not one: it authorises nothing on its own, it only
     * saves the person a round trip to the id-server. Every authorization renews it
     * ([renewSession]), so this is the gap after which a pod forgets — a shared machine still does
     * not stay signed in overnight, and somebody working past the twelfth hour is not interrupted.
     */
    const val SESSION_TTL_SECONDS: Long = 12 * 3600

    /**
     * How long a sign-in can be extended by using it (30 d), measured from `auth_time`.
     *
     * A renewed session outlives the twelve hours by design, and something has to end it: the
     * cookie is a signature rather than a row, so nothing can recall it once issued, and "renew on
     * use" without a ceiling is a credential that lives as long as whoever holds it keeps asking.
     * Thirty days is the point at which the pod stops taking the id-server's word from a month ago
     * and asks again.
     */
    const val SESSION_ABSOLUTE_TTL_SECONDS: Long = 30 * 24 * 3600

    /** Says a token is a browser session, so nothing can mistake it for an access token. */
    const val CLAIM_TOKEN_USE = "token_use"
    const val TOKEN_USE_SESSION = "session"
    const val CLAIM_ALSO_KNOWN_AS = "also_known_as"

    /** OIDC Core 1.0 §2 — when the person authenticated, not when this cookie was written. */
    const val CLAIM_AUTH_TIME = "auth_time"

    /**
     * Default TTL for service-client access tokens (10 min). `client_credentials`
     * needs no refresh — the client mints a new token on demand. The per-pod
     * token cache on the client side amortises the token-endpoint calls.
     */
    const val SERVICE_TOKEN_TTL_SECONDS: Long = 600
  }
}
