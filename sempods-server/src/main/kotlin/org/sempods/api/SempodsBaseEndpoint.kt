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
import org.sempods.api.pod.resources.WriteConditions
import org.sempods.commons.jaxrs.BaseEndpoint
import org.sempods.commons.net.BearerAuth
import org.sempods.mcp.core.BearerChallenge
import org.sempods.pods.oauth.flows.PodOwnerAuthorityRefusal
import org.sempods.pods.PodFacade
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.PodAuthorizer
import org.sempods.pods.grants.carriesPrivilegedFeature
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.mongo.persist.toPodId
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.mongo.persist.toRef
import org.sempods.pods.oauth.PodAccessToken
import org.sempods.pods.oauth.PodSignOut
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

  /** Applies this deployment's address, so a [PodRef] carries the pod's URI and not only its name. */
  @Inject
  private lateinit var sempodsUriBuilder: SempodsUriBuilder

  @Inject
  private lateinit var podTokenAuthenticator: PodTokenAuthenticator

  @Inject
  private lateinit var podAuthorizer: PodAuthorizer

  @Inject
  private lateinit var podSignOut: PodSignOut

  @Inject
  private lateinit var podServiceAuditLogDao: PodServiceAuditLogDao

  /** This row as the pod it names — its URI, its owner and the segment this deployment routes by. */
  internal val PodDbo.ref: PodRef get() = toRef(sempodsUriBuilder)

  /** The same row as the pod *and* the key it is stored under — see [HostedPod]. */
  internal val PodDbo.hosted: HostedPod get() = toHostedPod(sempodsUriBuilder)

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

  /** The request's `If-Match` and `If-None-Match`, for a write service to evaluate. */
  protected fun writeConditions(): WriteConditions {
    val headers = currentRequestContext().headers
    fun field(name: String) = headers[name]?.takeIf { it.isNotEmpty() }?.joinToString(",")
    return WriteConditions(ifMatch = field(HttpHeaders.IF_MATCH), ifNoneMatch = field(HttpHeaders.IF_NONE_MATCH))
  }

  /**
   * The cache policy of a resource or slot read, for its `200` and its `304`.
   *
   * What a read returns depends on the credential, so a shared cache must not store it and a private
   * cache has to key it on `Authorization` as well as `Accept`. A private cache may keep it, but has
   * to revalidate before reusing it (RFC 9111 §5.2.2.4).
   */
  protected fun Response.ResponseBuilder.revalidatedPrivately(): Response.ResponseBuilder =
    header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
      .header(HttpHeaders.VARY, "${HttpHeaders.ACCEPT}, ${HttpHeaders.AUTHORIZATION}")

  /**
   * Resolve caller credentials for a pod request. Supports both anonymous and authenticated
   * callers — sempods is Linked Open Data, so every endpoint must accept anonymous reads on
   * public contexts.
   *
   * Two collaborators, and the split between them is the point: [PodTokenAuthenticator] decides
   * whether the bearer is good (protocol — concrete, one implementation per definition), and
   * [PodAuthorizer] decides what a good bearer may reach (policy — the seam a deployment may
   * replace, `docs/concepts/modularity.md`). What stays here is the third thing, which is neither:
   * how a refusal becomes an HTTP status. Between the two, [authenticateBearer] asks whether the
   * person behind a good bearer has signed out since it was issued.
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
  protected fun authenticate(pod: String): SempodsCredentials =
    checkNotNull(resolveCredentials(fetchPodOrThrow(pod), podAuthorizer::anonymous)) {
      "an anonymous caller resolves to a sandbox, never to nothing"
    }

  /**
   * The bearer this request carries, or `null` where it carries none.
   *
   * [requirePodAppTokenOrThrow] asks for "any app" and refuses a privileged feature scope. This
   * asks for whoever turned up: the one route that exists for such a bearer has to be able to see
   * it, and the same route answers unauthenticated callers too. `null` rather than
   * [PodAuthorizer.anonymous] because that resolves the pod's public contexts, and a registration
   * consults none.
   */
  internal fun resolveBearerOrNull(podDbo: PodDbo): SempodsCredentials? = resolveCredentials(podDbo) { null }

  /**
   * What every bearer on this server goes through, with the one arm its callers disagree about.
   *
   * A credential that is presented and does not verify is always a 401 — a request that showed an
   * ID is not a request that showed none — and a verified one is always signed-out-checked and
   * audited. Only the absent bearer means different things to different routes, so only that is
   * [onNoToken]'s.
   */
  private fun resolveCredentials(
    podDbo: PodDbo,
    onNoToken: (PodRef) -> SempodsCredentials?,
  ): SempodsCredentials? {
    val podRef = podDbo.ref
    return when (val outcome = authenticateBearer(podDbo, podRef)) {
      PodTokenAuthentication.NoToken -> onNoToken(podRef)
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

  /**
   * [PodTokenAuthenticator.authenticate], and a verified token whose person has signed out of the pod
   * since it was issued refused as an invalid one — a 401, so a client refreshes, finds its family
   * revoked and starts again.
   *
   * The authenticator reads no store, so the check sits here. [PodAuthorizer] is a seam a deployment
   * may replace, and no deployment may drop a sign-out.
   *
   * The pod comes as the row this request just read, because [PodSignOut] is asked for the id on it
   * rather than for a name to resolve — see its KDoc.
   */
  private fun authenticateBearer(podDbo: PodDbo, podRef: PodRef): PodTokenAuthentication =
    when (val outcome = podTokenAuthenticator.authenticate(bearerToken(), podRef)) {
      is PodTokenAuthentication.Verified ->
        if (podSignOut.accessTokenStands(podDbo.podId(), outcome.token)) outcome
        else PodTokenAuthentication.Rejected(PodTokenRejection.invalidToken)

      else -> outcome
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

  internal fun fetchPodOrThrow(pod: String): PodDbo {
    return podDao.fetchByName(pod) ?: throw WebApplicationException(Response.status(404).build())
  }

  /**
   * Like [authenticate], but refuses an anonymous caller outright: the endpoints behind this need
   * an app identity, not a sandbox.
   *
   * The one place the failure classification differs from [authenticate]: a token that verifies
   * but was issued for another pod is a 403 here (the caller *has* a credential, it is simply not
   * for this resource) and a 401 there (where the answer must also advertise how to obtain one).
   *
   * **What this asks is "any app", which is why a privileged feature scope does not pass it.** An
   * empty sandbox is no answer where a route never consults one: the AI routes behind this gate
   * spend a provider call on the strength of the bearer alone, and an installation authority —
   * granted to register one service client — would spend them for its hour. A route that wants
   * such a bearer authenticates it deliberately; this one takes whoever turns up.
   */
  protected fun requirePodAppTokenOrThrow(pod: String): SempodsCredentials {
    val podDbo = fetchPodOrThrow(pod)
    val podRef = podDbo.toRef(sempodsUriBuilder)
    return when (val outcome = authenticateBearer(podDbo, podRef)) {
      is PodTokenAuthentication.Verified -> refuseIfPrivileged(authorizeAndAudit(podRef, outcome.token))

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

  /**
   * The bearer, unless what it carries is an authority for one named operation.
   *
   * A `403` rather than a `401`: the credential is valid and the caller is who they say, the scope
   * simply does not cover this. Named after the scope, so the answer says which of the caller's
   * assumptions is wrong.
   */
  private fun refuseIfPrivileged(credentials: SempodsCredentials): SempodsCredentials {
    if (!credentials.carriesPrivilegedFeature) return credentials
    throw WebApplicationException(
      Response.status(403)
        .entity("'${credentials.oauthScopes.sorted().joinToString(" ")}' does not authorize this route")
        .type("text/plain")
        .build()
    )
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
  @JvmOverloads
  protected fun buildBearerChallenge(podName: String, error: String = BearerChallenge.INVALID_TOKEN): String {
    val podBaseUrl = "${config.apiBaseUrl}${podName}"
    return BearerChallenge.forResource(
      realm = podName,
      resource = podBaseUrl,
      resourceMetadataUrl = "$podBaseUrl/.well-known/oauth-protected-resource",
      error = error,
    )
  }

  /**
   * A bearer holding no owner authority for [scope], answered the same on every route that asks
   * [org.sempods.pods.oauth.flows.PodOwnerAuthority]: `401 invalid_token` where the authority was
   * withdrawn, `403 insufficient_scope` otherwise, each with the pod's RFC 6750 challenge.
   *
   * @param manages what the owner manages on this route, for the wording: "contexts".
   */
  internal fun ownerAuthorityRefused(podName: String, reason: PodOwnerAuthorityRefusal, scope: String, manages: String): Response {
    val (status, code, description) = when (reason) {
      PodOwnerAuthorityRefusal.SCOPE_REQUIRED ->
        Triple(403, BearerChallenge.INSUFFICIENT_SCOPE, "this needs an authorization carrying '$scope'")
      PodOwnerAuthorityRefusal.AUTHORITY_WITHDRAWN ->
        Triple(401, BearerChallenge.INVALID_TOKEN, "this authorization no longer stands")
      PodOwnerAuthorityRefusal.NOT_OWNER ->
        Triple(403, BearerChallenge.INSUFFICIENT_SCOPE, "this pod's owner manages its $manages")
    }
    return Response.status(status)
      .header(HttpHeaders.WWW_AUTHENTICATE, buildBearerChallenge(podName, code))
      .entity(mapOf("error" to code, "error_description" to description))
      .type(MediaType.APPLICATION_JSON)
      .build()
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
