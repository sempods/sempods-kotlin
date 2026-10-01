package org.sempods.pods.oauth

import org.sempods.pods.oauth.spi.PodRequestVerifier
import org.sempods.pods.oauth.spi.PodResourceRequest
import org.sempods.pods.oauth.spi.PodTokenAuthentication
import org.sempods.pods.oauth.spi.PodTokenRejection
import org.sempods.pods.oauth.spi.PodAccessToken
import org.sempods.pods.oauth.spi.SERVICE_CLIENT_TYPE
import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.JwtRejection
import org.sempods.auth.core.JwtVerification
import org.sempods.auth.core.JwtVerifier
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.SigningKeys
import org.sempods.auth.core.stringClaimOrNull
import org.sempods.commons.net.BearerAuth
import org.sempods.spec.PodRef

/**
 * The production [PodRequestVerifier]: verifies a bearer this pod issued itself — signature,
 * expiry, the issuer being this pod, and the claims a caller is identified by.
 */
class PodTokenAuthenticator @Inject constructor(
  signingKeys: SigningKeys,
) : PodRequestVerifier {

  /**
   * Built once, over the keys [SigningKeys] parsed at boot. Every token this pod accepts is one it
   * minted, so the local mode is the whole of it — there is no foreign issuer to fetch from.
   */
  private val jwtVerifier = JwtVerifier.localKeys(signingKeys.publicKeys)

  /**
   * Reads the bearer from the `Authorization` header and verifies it with [authenticate]. Method and
   * target play no part: a bearer is not bound to the request it travels on.
   */
  override fun verify(request: PodResourceRequest, pod: PodRef): PodTokenAuthentication =
    authenticate(BearerAuth.parse(request.header("Authorization").takeIf { it.isNotEmpty() }?.joinToString(",")), pod)

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

    val expectedIssuer = pod.uri.toString()
    val tokenIssuer = claims.issuer?.trim()
    if (!isPodIssuer(tokenIssuer, expectedIssuer)) {
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
