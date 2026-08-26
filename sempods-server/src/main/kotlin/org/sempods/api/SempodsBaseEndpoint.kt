package org.sempods.api

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.SempodsConfig
import org.sempods.SempodsModule
import org.sempods.SempodsUriBuilder
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.jaxrs.BaseEndpoint
import org.sempods.commons.net.BearerAuth
import org.sempods.mcp.core.BearerChallenge
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.PodAuthorizer
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.toRef
import org.sempods.pods.oauth.PodAccessToken
import org.sempods.pods.oauth.PodTokenAuthentication
import org.sempods.pods.oauth.PodTokenAuthenticator
import org.sempods.pods.oauth.PodTokenRejection
import org.sempods.pods.oauth.serviceclients.persist.PodServiceAuditLogDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceAuditLogDbo
import org.sempods.spec.PodRef

open class SempodsBaseEndpoint(
  protected val podFacade: PodFacade,
  protected val podDao: PodDao,
) : BaseEndpoint() {

  /**
   * The server's own deployment facts, read from the companion rather than injected: a `@Path`
   * class is constructed by Jersey through Guice, but the base URL is also needed by call sites
   * that run before the injector — see [SempodsModule.config].
   */
  protected val config: SempodsConfig = SempodsModule.config

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  /** Applies this deployment's address, so a [PodRef] carries the pod's URI and not only its name. */
  @Inject
  private lateinit var sempodsUriBuilder: SempodsUriBuilder

  @Inject
  private lateinit var podTokenAuthenticator: PodTokenAuthenticator

  @Inject
  private lateinit var podAuthorizer: PodAuthorizer

  @Inject
  private lateinit var podServiceAuditLogDao: PodServiceAuditLogDao

  /**
   * The request's `Authorization: Bearer <token>`, or `null` if there is none.
   *
   * One place rather than three, and [BearerAuth] rather than a hand-rolled prefix check: the
   * scheme name is case-insensitive (RFC 7235 §2.1), so a gateway that normalises it to `bearer`
   * must still be understood. This used to go through an authentication filter whose static helper
   * delegated here anyway.
   */
  protected fun bearerToken(): String? =
    BearerAuth.parse(currentRequestContext().getHeaderString(HttpHeaders.AUTHORIZATION))

  /**
   * Resolve caller credentials for a pod request. Supports both anonymous and authenticated
   * callers — sempods is Linked Open Data, so every endpoint must accept anonymous reads on
   * public contexts.
   *
   * Two collaborators, and the split between them is the point: [PodTokenAuthenticator] decides
   * whether the bearer is good (protocol — concrete, one implementation per definition), and
   * [PodAuthorizer] decides what a good bearer may reach (policy — the seam a deployment may
   * replace, `docs/concepts/modularity.md`). What stays here is the third thing, which is neither:
   * how a refusal becomes an HTTP status.
   *
   * - **No bearer** → anonymous caller, resolved by [PodAuthorizer.anonymous].
   * - **Valid bearer** → [PodAuthorizer.authorize].
   * - **Invalid / expired / wrong-pod bearer** → throws [InvalidBearerException] (→ 401 with
   *   RFC 6750 WWW-Authenticate challenge). A failed auth attempt is not silently downgraded
   *   to anonymous, and the wrong-pod case is a 401 here — [requirePodAppTokenOrThrow] answers
   *   that same rejection with 403.
   *
   * Endpoints that require authentication (writes, MCP `authorize` tool, etc.) must call
   * [requireAuthenticatedOrThrow] on the returned credentials.
   */
  protected fun authenticate(pod: String): SempodsCredentials {
    val podRef = fetchPodOrThrow(pod).toRef(sempodsUriBuilder)
    return when (val outcome = podTokenAuthenticator.authenticate(bearerToken(), podRef)) {
      PodTokenAuthentication.NoToken -> podAuthorizer.anonymous(podRef)
      is PodTokenAuthentication.Verified -> authorizeAndAudit(podRef, outcome.token)
      is PodTokenAuthentication.Rejected -> throwInvalidBearer(podName = podRef.name)
    }
  }

  /**
   * Throws [InvalidBearerException] (→ 401 with WWW-Authenticate) if [credentials] is
   * anonymous. Use this on endpoints that must reject unauthenticated callers (writes,
   * MCP `authorize` tool). Callers *authenticated but lacking a needed scope* should be
   * answered with 403 by the scope-check (not this helper).
   */
  protected fun requireAuthenticatedOrThrow(credentials: SempodsCredentials) {
    if (credentials.oauthClientId == null) {
      throwInvalidBearer(podName = credentials.pod.name)
    }
  }

  protected fun parseIncludeContextsOrThrow(raw: String?): Boolean {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return when (value.lowercase()) {
      "true" -> true
      "false" -> false
      else -> throw WebApplicationException(
        Response.status(400)
          .entity("include_contexts must be 'true' or 'false'")
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }
  }

  private fun throwInvalidBearer(podName: String): Nothing {
    throw InvalidBearerException(
      podName = podName,
      response = Response.status(401)
        .header("WWW-Authenticate", buildBearerChallenge(podName = podName))
        .entity("missing or invalid app bearer token")
        .type("text/plain")
        .build()
    )
  }

  protected fun fetchPodOrThrow(pod: String): PodDbo {
    return podDao.fetchByName(pod) ?: throw WebApplicationException(Response.status(404).build())
  }

  /**
   * Like [authenticate], but refuses an anonymous caller outright: the endpoints behind this need
   * an app identity, not a sandbox.
   *
   * The one place the failure classification differs from [authenticate]: a token that verifies
   * but was issued for another pod is a 403 here (the caller *has* a credential, it is simply not
   * for this resource) and a 401 there (where the answer must also advertise how to obtain one).
   */
  protected fun requirePodAppTokenOrThrow(pod: String): SempodsCredentials {
    val podRef = fetchPodOrThrow(pod).toRef(sempodsUriBuilder)
    return when (val outcome = podTokenAuthenticator.authenticate(bearerToken(), podRef)) {
      is PodTokenAuthentication.Verified -> authorizeAndAudit(podRef, outcome.token)

      is PodTokenAuthentication.Rejected ->
        if (outcome.reason == PodTokenRejection.podMismatch) {
          throw WebApplicationException(
            Response.status(403)
              .entity("app token is not valid for pod '$pod'")
              .type("text/plain")
              .build()
          )
        } else {
          throwMissingOrInvalidAppToken(pod)
        }

      PodTokenAuthentication.NoToken -> throwMissingOrInvalidAppToken(pod)
    }
  }

  private fun throwMissingOrInvalidAppToken(pod: String): Nothing {
    throw WebApplicationException(
      Response.status(401)
        .header("WWW-Authenticate", buildBearerChallenge(podName = pod))
        .entity("missing or invalid app bearer token")
        .type("text/plain")
        .build()
    )
  }

  /**
   * The two things that happen to a verified token before it becomes credentials: the
   * service-client audit row, which needs the in-flight request and therefore cannot move into
   * the authorizer, and the authorization itself.
   */
  private fun authorizeAndAudit(pod: PodRef, token: PodAccessToken): SempodsCredentials {
    if (token.isServiceClient) {
      recordServiceClientAudit(pod = pod, clientId = token.clientId)
    }
    return podAuthorizer.authorize(pod, token)
  }

  /**
   * Build an RFC 6750 `WWW-Authenticate: Bearer` challenge that points MCP-style clients to
   * the pod's RFC 9728 protected-resource metadata. Used on 401 responses so clients can
   * discover the authorization server and required scopes without out-of-band config.
   *
   * The pod is the protected resource for every caller, MCP or REST, so there is one
   * `resource_metadata` URL rather than a per-surface one.
   */
  protected fun buildBearerChallenge(podName: String): String {
    val podBaseUrl = "${config.apiBaseUrl}${podName}"
    return BearerChallenge.forResource(
      realm = podName,
      resource = podBaseUrl,
      resourceMetadataUrl = "$podBaseUrl/.well-known/oauth-protected-resource",
    )
  }

  /**
   * The pod owner, if [credentials] belong to them.
   *
   * Ownership is not a grant and not a scope — it is `pod.owner in webIds` and nothing else, so
   * there is nothing for an owner to be granted or to consent to. What this resolves is only
   * *recognition*: which person the request is from. That used to come from an identity JWT issued
   * by the id-server and presented here as a bearer, which meant a token minted to prove who
   * someone is doubled as a credential for their pod. It now comes from the pod's own access
   * token, whose `sub` is the WebID the person signed in as.
   *
   * Matched against the deterministic twins of the subject ([WebIdUriDeriver.derivableAliases] —
   * the `{id}/e/<hash>` and `urn:sempods:e:<hash>` spellings of one address). Today that is defence
   * in depth rather than a live case: `PodDao.insert`/`setOwner` run `requireEmailBasedWebId`, so a
   * stored owner is always the `https` spelling, which is also what the id-server puts in `sub`.
   * It is here so that loosening that constraint cannot silently narrow who counts as the owner.
   *
   * Profile-linked aliases are deliberately *not* resolved, matching the rule the grant path
   * already states: a request carries one identity URI, and equivalences are applied when a grant
   * is written, not when it is read.
   */
  protected fun resolvePodOwnerPrincipal(credentials: SempodsCredentials): PodOwnerPrincipal? {
    val subject = credentials.tokenSub?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (credentials.pod.owner !in webIdUriDeriver.derivableAliases(subject)) return null
    logger.info { "[oauth] Resolved pod owner: pod='${credentials.pod.name}', webId='$subject'" }
    return PodOwnerPrincipal(webIdUri = subject)
  }

  /**
   * Writes one [PodServiceAuditLogDbo] row for the in-flight request. Called whenever the
   * authenticator recognises a service-client (`client_credentials`) token, so every request a
   * 2-leg caller makes leaves an audit entry.
   *
   * `statusCode` is intentionally not populated here — this runs before the response is built. A
   * follow-up `ContainerResponseFilter` (TODO) will mutate the matching row once Jersey wires the
   * response back through. Best-effort: any DAO failure is logged and swallowed; audit must never
   * break the actual request.
   */
  private fun recordServiceClientAudit(pod: PodRef, clientId: String) {
    try {
      val podId = podFacade.getPodId(pod.name) ?: return
      val ctx = currentRequestContext()
      val method = ctx.method ?: "UNKNOWN"
      val uriInfo = ctx.uriInfo
      val path = uriInfo?.requestUri?.let { uri ->
        val raw = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.takeIf { it.isNotBlank() }
        if (query != null) "$raw?$query" else raw
      } ?: "/"

      podServiceAuditLogDao.record(
        PodServiceAuditLogDbo(
          podId = podId,
          clientId = clientId,
          operation = method,
          path = path,
        )
      )
    } catch (t: Throwable) {
      logger.warn(t) {
        "[oauth/access] Failed to write service-client audit entry for pod='${pod.name}', " +
            "clientId='$clientId': ${t.message}"
      }
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}

open class InvalidBearerException(
  val podName: String,
  response: Response,
) : WebApplicationException("invalid bearer token for pod '$podName'", response)

/**
 * R4 Phase A: thrown when an authenticated-but-write-incapable caller (today:
 * `scope=public-read` only) explicitly requests an OAuth upgrade via the
 * synthetic `authenticate` MCP tool. Distinguished from the parent
 * [InvalidBearerException] so the audit stream can log this as
 * `outcome=auth_trigger` (planned upgrade) rather than `outcome=error
 * error=invalid_bearer` (manipulated/stale token).
 */
class OAuthUpgradeRequiredException(
  podName: String,
  response: Response,
) : InvalidBearerException(podName, response)

data class PodOwnerPrincipal(val webIdUri: String) {
  fun toSubject(): String = webIdUri
}
