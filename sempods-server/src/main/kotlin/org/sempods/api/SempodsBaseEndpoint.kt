package org.sempods.api

import com.google.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.SempodsConfig
import org.sempods.SempodsModule
import org.sempods.SempodsUriBuilder
import org.sempods.api.pod.resources.WriteConditions
import org.sempods.commons.net.BearerAuth
import org.sempods.mcp.core.BearerChallenge
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.authentication.spi.PodApiRequest
import org.sempods.pods.authentication.spi.PodRequestAuthenticator
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.grants.carriesPrivilegedFeature
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.mongo.persist.toRef
import org.sempods.pods.authentication.impls.oauth.BearerPodRequestAuthenticator
import org.sempods.pods.oauth.flows.PodOwnerAuthorityRefusal
import org.sempods.pods.oauth.spi.PodTokenAuthentication
import org.sempods.pods.oauth.spi.PodTokenRejection
import org.sempods.spec.PodRef

open class SempodsBaseEndpoint(
  protected val podFacade: PodFacade,
  protected val podDao: PodDao,
) : PodApiEndpoint() {

  /**
   * The server's own deployment facts, read from the companion rather than injected: a `@Path`
   * class is constructed by Jersey through Guice, but the base URL is also needed by call sites
   * that run before the injector — see [SempodsModule.config].
   */
  protected val config: SempodsConfig = SempodsModule.config

  /** Applies this deployment's address, so a [PodRef] carries the pod's URI and not only its name. */
  @Inject
  private lateinit var sempodsUriBuilder: SempodsUriBuilder

  /** This server's bearer, for the routes that exist for it alone — see [requirePodAppTokenOrThrow]. */
  @Inject
  private lateinit var bearer: BearerPodRequestAuthenticator

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
   * cache has to key it on the credential headers as well as `Accept` ([varyOnCaller]). A private cache may keep it, but has
   * to revalidate before reusing it (RFC 9111 §5.2.2.4).
   */
  protected fun Response.ResponseBuilder.revalidatedPrivately(): Response.ResponseBuilder =
    header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
      .header(HttpHeaders.VARY, varyOnCaller())

  /**
   * Resolve caller credentials for a pod request through [PodRequestAuthenticator], anonymous
   * callers included — sempods is Linked Open Data, so every endpoint must accept anonymous reads
   * on public contexts. [PodApiEndpoint.authenticate] says which outcome becomes which status.
   *
   * Endpoints that require authentication (writes, MCP `authorize` tool, etc.) must call
   * [requireAuthenticatedOrThrow] on the returned credentials.
   */
  protected fun authenticate(pod: String): SempodsCredentials = authenticate(pod, currentApiRequest())

  /**
   * The bearer this request carries, or `null` where it carries none.
   *
   * [requirePodAppTokenOrThrow] asks for "any app" and refuses a privileged feature scope. This
   * asks for whoever turned up: the routes that exist for such a bearer have to be able to see it.
   * Registration answers unauthenticated callers too. `null` rather than [org.sempods.pods.grants.PodAuthorizer.anonymous]
   * because that resolves the pod's public contexts, and a registration consults none.
   */
  internal fun resolveBearerOrNull(podDbo: PodDbo): SempodsCredentials? {
    val podRef = podDbo.ref
    val request = currentApiRequest()
    return when (val outcome = bearer.verify(podDbo, podRef, request)) {
      PodTokenAuthentication.NoToken -> null
      is PodTokenAuthentication.Verified -> bearer.authorizeAndAudit(podDbo, podRef, outcome.token, request)
      is PodTokenAuthentication.Rejected -> throwInvalidBearer(podName = podRef.name)
    }
  }

  /**
   * [resolveBearerOrNull] for a route that requires authentication. A missing bearer is refused
   * like a rejected one: `401 invalid_token` with the pod's challenge (`SPS-CORE-015`).
   */
  internal fun requireBearerOrThrow(podDbo: PodDbo): SempodsCredentials =
    resolveBearerOrNull(podDbo) ?: throwInvalidBearer(podName = podDbo.name)

  /**
   * Throws [InvalidBearerException] (→ 401 with WWW-Authenticate) if [credentials] is
   * anonymous. Use this on endpoints that must reject unauthenticated callers (writes,
   * MCP `authorize` tool). Callers *authenticated but lacking a needed scope* should be
   * answered with 403 by the scope-check (not this helper).
   */
  protected fun requireAuthenticatedOrThrow(credentials: SempodsCredentials) {
    if (!credentials.authenticated) {
      throwInvalidBearer(podName = credentials.pod.name)
    }
  }

  private fun throwInvalidBearer(podName: String): Nothing =
    throw invalidBearer(podName, buildBearerChallenge(podName = podName))

  /** This request as [PodRequestAuthenticator] takes it, read from the request this thread serves. */
  private fun currentApiRequest(): PodApiRequest =
    currentRequestContext().let {
      podApiRequest(it.method, it.uriInfo, it.headers, it.getProperty(PathSemicolonFilter.RECEIVED_RAW_PATH) as String?)
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
   * spend a provider call on the strength of the bearer alone, and a management authority —
   * granted to administer service clients or contexts — would spend them for its hour. A route that wants
   * such a bearer authenticates it deliberately; this one takes whoever turns up.
   */
  protected fun requirePodAppTokenOrThrow(pod: String): SempodsCredentials {
    val podDbo = fetchPodOrThrow(pod)
    val podRef = podDbo.toRef(sempodsUriBuilder)
    val request = currentApiRequest()
    return when (val outcome = bearer.verify(podDbo, podRef, request)) {
      is PodTokenAuthentication.Verified ->
        refuseIfPrivileged(bearer.authorizeAndAudit(podDbo, podRef, outcome.token, request))

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

  /** The pod's `WWW-Authenticate: Bearer` challenge — see [BearerPodRequestAuthenticator.challenge]. */
  @JvmOverloads
  protected fun buildBearerChallenge(podName: String, error: String = BearerChallenge.INVALID_TOKEN): String =
    bearer.challenge(podName, error)

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
      PodOwnerAuthorityRefusal.CONSENT_OUTDATED ->
        Triple(403, BearerChallenge.INSUFFICIENT_SCOPE, "this authorization was approved before it covered this; ask for '$scope' again")
    }
    return Response.status(status)
      .header(HttpHeaders.WWW_AUTHENTICATE, buildBearerChallenge(podName, code))
      .entity(mapOf("error" to code, "error_description" to description))
      .type(MediaType.APPLICATION_JSON)
      .build()
  }
}
