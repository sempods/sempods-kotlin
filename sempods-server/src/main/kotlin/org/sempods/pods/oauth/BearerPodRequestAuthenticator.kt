package org.sempods.pods.oauth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.SempodsConfig
import org.sempods.SempodsUriBuilder
import org.sempods.mcp.core.BearerChallenge
import org.sempods.pods.grants.PodApiRequest
import org.sempods.pods.grants.PodAuthorizer
import org.sempods.pods.grants.PodRequestAuthentication
import org.sempods.pods.grants.PodRequestAuthenticator
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.mongo.persist.toRef
import org.sempods.pods.oauth.serviceclients.persist.PodServiceAuditLogDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceAuditLogDbo
import org.sempods.pods.oauth.spi.PodAccessToken
import org.sempods.pods.oauth.spi.PodRequestVerifier
import org.sempods.pods.oauth.spi.PodResourceRequest
import org.sempods.pods.oauth.spi.PodTokenAuthentication
import org.sempods.pods.oauth.spi.PodTokenRejection
import org.sempods.spec.PodRef
import java.net.URI

/**
 * This server's [PodRequestAuthenticator]: the pod from its registry, and a bearer this server
 * issued.
 *
 * Two collaborators, and the split between them is the point: [PodRequestVerifier] decides whether
 * the credential is good (protocol), and [PodAuthorizer] decides what a verified caller may reach
 * (policy). Between the two, [PodSignOut] asks whether the person behind a verified credential has
 * signed out since it was issued. The verifier reads no store, so that check sits here.
 *
 * - **No bearer** → an anonymous caller, resolved by [PodAuthorizer.anonymous].
 * - **Valid bearer** → [PodAuthorizer.authorize], after the service-client audit row.
 * - **Invalid, expired, signed-out or wrong-pod bearer** → [PodRequestAuthentication.Unauthenticated]
 *   with the pod's RFC 6750 challenge. A failed attempt is never downgraded to anonymous.
 *
 * The routes that exist for this server's bearer only — registration, service clients, the AI
 * routes — read the same steps through [verify] and [authorizeAndAudit] and answer the cases
 * differently; see `org.sempods.api.SempodsBaseEndpoint`.
 */
class BearerPodRequestAuthenticator @Inject internal constructor(
  private val config: SempodsConfig,
  private val podDao: PodDao,
  private val sempodsUriBuilder: SempodsUriBuilder,
  private val podRequestVerifier: PodRequestVerifier,
  private val podAuthorizer: PodAuthorizer,
  private val podSignOut: PodSignOut,
  private val podServiceAuditLogDao: PodServiceAuditLogDao,
) : PodRequestAuthenticator {

  override fun authenticate(pod: String, request: PodApiRequest): PodRequestAuthentication {
    val podDbo = podDao.fetchByName(pod) ?: return PodRequestAuthentication.PodNotFound
    val podRef = podDbo.toRef(sempodsUriBuilder)
    return when (val outcome = verify(podDbo, podRef, request)) {
      PodTokenAuthentication.NoToken -> PodRequestAuthentication.Authenticated(podAuthorizer.anonymous(podRef))
      is PodTokenAuthentication.Verified ->
        PodRequestAuthentication.Authenticated(authorizeAndAudit(podDbo, podRef, outcome.token, request))
      is PodTokenAuthentication.Rejected -> PodRequestAuthentication.Unauthenticated(challenge(podRef.name))
    }
  }

  /**
   * [PodRequestVerifier.verify], and a verified token whose person has signed out of the pod since
   * it was issued refused as an invalid one — a 401, so a client refreshes, finds its family
   * revoked and starts again.
   *
   * The pod comes as the row this request just read, because [PodSignOut] is asked for the id on it
   * rather than for a name to resolve — see its KDoc.
   */
  internal fun verify(podDbo: PodDbo, podRef: PodRef, request: PodApiRequest): PodTokenAuthentication =
    when (val outcome = podRequestVerifier.verify(resourceRequest(request), podRef)) {
      is PodTokenAuthentication.Verified ->
        if (podSignOut.accessTokenStands(podDbo.podId(), outcome.token)) outcome
        else PodTokenAuthentication.Rejected(PodTokenRejection.invalidToken)

      else -> outcome
    }

  /**
   * The two things that happen to a verified token before it becomes credentials: the
   * service-client audit row, which needs the in-flight request, and the authorization itself.
   */
  internal fun authorizeAndAudit(
    podDbo: PodDbo,
    podRef: PodRef,
    token: PodAccessToken,
    request: PodApiRequest,
  ): SempodsCredentials {
    if (token.isServiceClient) {
      recordServiceClientAudit(podDbo = podDbo, clientId = token.clientId, request = request)
    }
    return podAuthorizer.authorize(podRef, token)
  }

  /**
   * The pod's RFC 6750 `WWW-Authenticate: Bearer` challenge. Its `resource_metadata` is
   * `{pod}/.well-known/oauth-protected-resource` (`SPS-AUTH-064`), so a client finds the
   * authorization server without out-of-band config.
   *
   * The MCP endpoint sends it too; `docs/mcp/authentication.md` §"Bearer challenge format" says
   * how that deviates from `SPS-MCP-009`.
   */
  internal fun challenge(podName: String, error: String = BearerChallenge.INVALID_TOKEN): String {
    val podBaseUrl = "${config.apiBaseUrl}$podName"
    return BearerChallenge.forResource(
      realm = podName,
      resource = podBaseUrl,
      resourceMetadataUrl = "$podBaseUrl/.well-known/oauth-protected-resource",
      error = error,
    )
  }

  /**
   * [request] as [PodRequestVerifier] takes it, addressed by [SempodsConfig.apiBaseUrl]. Path and
   * query stay encoded as the client sent them — `%3B` and `%C3%A4` reach the verifier as `%3B`
   * and `%C3%A4`.
   */
  private fun resourceRequest(request: PodApiRequest): PodResourceRequest =
    PodResourceRequest(
      method = request.method,
      target = URI(config.apiBaseUrl + request.path + request.query?.let { "?$it" }.orEmpty()),
      headers = request.headers,
    )

  /**
   * Writes one [PodServiceAuditLogDbo] row for the in-flight request, so every request a service
   * client (`client_credentials`) makes leaves an audit entry.
   *
   * `statusCode` is intentionally not populated here — this runs before the response is built. A
   * follow-up `ContainerResponseFilter` (TODO) will mutate the matching row once Jersey wires the
   * response back through. Best-effort: any DAO failure is logged and swallowed; audit must never
   * break the actual request.
   */
  private fun recordServiceClientAudit(podDbo: PodDbo, clientId: String, request: PodApiRequest) {
    try {
      val query = request.query?.takeIf { it.isNotBlank() }
      podServiceAuditLogDao.record(
        PodServiceAuditLogDbo(
          podId = checkNotNull(podDbo.id),
          clientId = clientId,
          operation = request.method,
          path = "/${request.path}" + query?.let { "?$it" }.orEmpty(),
        )
      )
    } catch (t: Throwable) {
      logger.warn(t) {
        "[oauth/access] Failed to write service-client audit entry for pod='${podDbo.name}', " +
            "clientId='$clientId': ${t.message}"
      }
    }
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
