package org.sempods.pods.oauth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.JwtRejection
import org.sempods.auth.core.JwtVerification
import org.sempods.auth.core.JwtVerifier
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.SigningKeys
import org.sempods.auth.core.stringClaimOrNull
import org.sempods.spec.PodRef
import java.time.Instant

/**
 * Verifies an incoming pod bearer token: protocol, not policy.
 *
 * This is one half of what `SempodsBaseEndpoint.resolveOAuthAccess` used to do in one piece. It
 * answers *who is calling and is the token good* — signature, expiry, the issuer being this pod,
 * and the claims a caller is identified by. It answers nothing about **what** the caller may
 * reach; that is [org.sempods.pods.grants.PodAuthorizer], which is a seam, while this is
 * deliberately a concrete class: every deployment of this server verifies the same self-issued
 * JWT against the same keys, so there is nothing here for a deployment to select.
 *
 * Failures are returned, not thrown, and they are *reasons* rather than statuses: the same
 * [PodTokenRejection.podMismatch] is a 401 on the read path and a 403 on the app-token path, and
 * that mapping belongs to the endpoint. See `SempodsBaseEndpoint`.
 */
class PodTokenAuthenticator @Inject constructor(
  signingKeys: SigningKeys,
) {

  /**
   * Built once, over the keys [SigningKeys] parsed at boot. Every token this pod accepts is one it
   * minted, so the local mode is the whole of it — there is no foreign issuer to fetch from.
   */
  private val jwtVerifier = JwtVerifier.localKeys(signingKeys.publicKeys)

  /**
   * Verifies [bearerToken] against [pod]. A `null` or blank token is [PodTokenAuthentication.NoToken]
   * — the anonymous caller, which sempods must accept because a pod is Linked Open Data — and not a
   * failure.
   */
  fun authenticate(bearerToken: String?, pod: PodRef): PodTokenAuthentication {
    val raw = bearerToken?.takeIf { it.isNotBlank() } ?: return PodTokenAuthentication.NoToken

    // Signature and expiry together, and **nothing reads a claim before both hold**. This path used
    // to parse the claims first and verify after — the same trap `PodTokenIssuer.readSession`
    // documents on the cookie path, where an attacker-supplied `{"token_use": []}` reached Nimbus's
    // typed getters and became a 500. A bearer is no less attacker-supplied than a cookie.
    val claims = when (val verification = jwtVerifier.verify(raw)) {
      is JwtVerification.Verified -> verification.claims
      is JwtVerification.Rejected -> {
        when (verification.why) {
          // A one-hour access token aging out is the routine case and by far the common one; at
          // WARN it would bury the other. The exact `exp` no longer reaches the line — reading it
          // would mean trusting claims off a token that did not verify.
          JwtRejection.badClaims ->
            logger.info { "[oauth/access] Token expired or carries no expiry: pod='${pod.name}'" }
          JwtRejection.badSignature ->
            logger.warn { "[oauth/access] Token signature verification failed for pod='${pod.name}'" }
        }
        return PodTokenAuthentication.Rejected(PodTokenRejection.invalidToken)
      }
      // Unparseable, or signed with something we hold no key for. Silent, as before.
      JwtVerification.Inconclusive -> return PodTokenAuthentication.Rejected(PodTokenRejection.invalidToken)
    }

    // The trailing-slash form of the pod's own URI: `PodRef.uri` is canonical without one, and
    // `SempodsUriBuilder.buildPodUri` records that the OAuth issuer is the variant with it.
    val expectedIssuer = "${pod.uri}/"
    val tokenIssuer = claims.issuer?.trim()
    if (tokenIssuer != expectedIssuer) {
      logger.info {
        "[oauth/access] Issuer mismatch: pod='${pod.name}', expected='$expectedIssuer', got='$tokenIssuer'"
      }
      return PodTokenAuthentication.Rejected(PodTokenRejection.podMismatch)
    }

    // `did:web:` client identity, used verbatim.
    val clientId = claims.stringClaimOrNull("client_id")?.takeIf { it.isNotBlank() }
      ?: return PodTokenAuthentication.Rejected(PodTokenRejection.missingClientId)

    val sub = claims.stringClaimOrNull("sub")?.takeIf { it.isNotBlank() }
    val clientType = claims.stringClaimOrNull("client_type")?.takeIf { it.isNotBlank() }
    val isServiceClient = clientType == SERVICE_CLIENT_TYPE

    if (!isServiceClient && sub == null) {
      logger.info {
        "[oauth/access] Rejecting bearer without sub for non-service token: pod='${pod.name}', clientId='$clientId'"
      }
      return PodTokenAuthentication.Rejected(PodTokenRejection.invalidToken)
    }

    val scopeValues = OAuthSyntax.scopeClaimValues(claims)
    // DEBUG, not INFO: every authenticated request reaches this line, so at INFO it is a
    // per-request access log by another name — the same thing `JaxRsDebugFilter` was moved off
    // INFO for after it wrote 118,905 lines in 25 hours (`docs/logging.md`). The rejection paths
    // above stay at INFO/WARN, because those are events rather than traffic.
    //
    // `client_type` renders as `(authorization_code)` when the claim is absent, which is what its
    // absence means — `PodTokenIssuer.issue` omits it and only `issueServiceToken` sets it. The
    // older `(unset)` read like a missing value rather than the flow it actually names.
    logger.debug {
      "[oauth/access] Token verified: pod='${pod.name}', clientId='$clientId', sub='$sub', " +
          "clientType='${clientType ?: "(authorization_code)"}', scopes=${scopeValues.sorted()}"
    }

    return PodTokenAuthentication.Verified(
      PodAccessToken(
        clientId = clientId,
        sub = sub,
        clientType = clientType,
        scopeValues = scopeValues,
        jti = claims.jwtid?.takeIf { it.isNotBlank() },
        issuedAt = claims.issueTime?.toInstant(),
      )
    )
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/** The outcome of [PodTokenAuthenticator.authenticate]. */
sealed interface PodTokenAuthentication {

  /** No bearer was presented. An anonymous caller, which is a legitimate state, not a failure. */
  data object NoToken : PodTokenAuthentication

  /** The bearer verified; [token] carries what it claims. */
  data class Verified(val token: PodAccessToken) : PodTokenAuthentication

  /** A bearer was presented and is not usable. [reason] is why, not what to answer. */
  data class Rejected(val reason: PodTokenRejection) : PodTokenAuthentication
}

/**
 * Why a presented bearer was refused. Kept apart from the HTTP status deliberately:
 * [podMismatch] is the one an endpoint may want to distinguish (a token that is valid, but for
 * another pod), and the two read paths answer it differently.
 */
enum class PodTokenRejection {
  /** Unparseable, wrongly signed, expired, or a user token without a `sub`. */
  invalidToken,

  /** Verified, but carries no `client_id` — no app to resolve grants for. */
  missingClientId,

  /** Verified, but issued by (and for) a different pod. */
  podMismatch,
}

/**
 * Value of the JWT `client_type` claim on tokens issued through `client_credentials`. Absent on
 * authorization-code tokens.
 *
 * Here rather than on `PodTokenIssuer`, which is where it used to live: the claim is read on the
 * way in by code that sits below the `api` layer, and only written on the way out.
 */
const val SERVICE_CLIENT_TYPE: String = "service"

/**
 * What a verified pod bearer says about its caller. Server-side policy — which contexts the caller
 * may reach — is deliberately *not* in here; it is resolved per request from the grant store, see
 * [org.sempods.pods.grants.PodAuthorizer].
 *
 * [sub] is the WebID the person signed in as, and is null exactly on a service-client token, where
 * there is no person. The [init] block holds that invariant so an implementation of the authorizer
 * seam can rely on it instead of re-deriving it.
 */
data class PodAccessToken(
  val clientId: String,
  val sub: String?,
  val clientType: String?,
  /** Raw `scope` ∪ `scp`, unvalidated — sanitizing them is the authorizer's job. */
  val scopeValues: Set<String>,
  val jti: String?,
  val issuedAt: Instant?,
) {

  val isServiceClient: Boolean get() = clientType == SERVICE_CLIENT_TYPE

  init {
    require(isServiceClient || sub != null) { "a non-service pod token must carry a sub" }
  }
}
