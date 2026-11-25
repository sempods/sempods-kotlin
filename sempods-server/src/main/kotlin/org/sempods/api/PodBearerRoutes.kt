package org.sempods.api

import com.google.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.SempodsUriBuilder
import org.sempods.commons.net.BearerAuth
import org.sempods.mcp.core.BearerChallenge
import org.sempods.pods.HostedPod
import org.sempods.pods.authentication.impls.oauth.BearerPodRequestAuthenticator
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.grants.carriesPrivilegedFeature
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.mongo.persist.toRef
import org.sempods.pods.oauth.flows.PodOwnerAuthorityRefusal
import org.sempods.pods.oauth.spi.PodTokenAuthentication
import org.sempods.pods.oauth.spi.PodTokenRejection
import org.sempods.spec.PodRef

/**
 * What the routes that exist for this server's own bearer share: the pod registry, the bearer read
 * through [BearerPodRequestAuthenticator]'s steps rather than the seam, and the answers those
 * routes give — registration, service clients, the OAuth metadata, the AI routes, context
 * management and the MCP endpoint.
 *
 * A constructor parameter rather than a base class, so a route's constructor shows that it reads
 * the registry, and a route that does not cannot be bound only with MongoDB on the classpath.
 */
class PodBearerRoutes @Inject internal constructor(
  private val podDao: PodDao,
  private val sempodsUriBuilder: SempodsUriBuilder,
  private val bearer: BearerPodRequestAuthenticator,
) {

  /** The pod's row, or `404`. */
  internal fun fetchPodOrThrow(pod: String): PodDbo =
    podDao.fetchByName(pod) ?: throw WebApplicationException(Response.status(404).build())

  /** This row as the pod it names — its URI, its owner and the segment this deployment routes by. */
  internal fun ref(podDbo: PodDbo): PodRef = podDbo.toRef(sempodsUriBuilder)

  /** The same row as the pod *and* the key it is stored under — see [HostedPod]. */
  internal fun hosted(podDbo: PodDbo): HostedPod = podDbo.toHostedPod(sempodsUriBuilder)

  /**
   * The request's `Authorization: Bearer <token>`, or `null` if there is none. [BearerAuth] rather
   * than a hand-rolled prefix check: the scheme name is case-insensitive (RFC 7235 §2.1).
   */
  internal fun bearerToken(request: ContainerRequestContext): String? =
    BearerAuth.parse(request.getHeaderString(HttpHeaders.AUTHORIZATION))

  /**
   * The bearer this request carries, or `null` where it carries none.
   *
   * [requirePodAppTokenOrThrow] asks for "any app" and refuses a privileged feature scope. This
   * asks for whoever turned up: the routes that exist for such a bearer have to be able to see it.
   * Registration answers unauthenticated callers too. `null` rather than
   * [org.sempods.pods.grants.PodAuthorizer.anonymous] because that resolves the pod's public
   * contexts, and a registration consults none.
   */
  internal fun resolveBearerOrNull(podDbo: PodDbo, request: ContainerRequestContext): SempodsCredentials? {
    val podRef = ref(podDbo)
    val apiRequest = podApiRequest(request)
    return when (val outcome = bearer.verify(podDbo, podRef, apiRequest)) {
      PodTokenAuthentication.NoToken -> null
      is PodTokenAuthentication.Verified -> bearer.authorizeAndAudit(podDbo, podRef, outcome.token, apiRequest)
      is PodTokenAuthentication.Rejected -> throwInvalidBearer(podName = podRef.name)
    }
  }

  /**
   * [resolveBearerOrNull] for a route that requires authentication. A missing bearer is refused
   * like a rejected one: `401 invalid_token` with the pod's challenge (`SPS-CORE-015`).
   */
  internal fun requireBearerOrThrow(podDbo: PodDbo, request: ContainerRequestContext): SempodsCredentials =
    resolveBearerOrNull(podDbo, request) ?: throwInvalidBearer(podName = podDbo.name)

  /**
   * The bearer of an app, refusing an anonymous caller outright: the routes behind this need an app
   * identity, not a sandbox.
   *
   * The one place the failure classification differs from [PodBaseEndpoint.authenticate]: a token
   * that verifies but was issued for another pod is a 403 here (the caller *has* a credential, it
   * is simply not for this resource) and a 401 there (where the answer must also advertise how to
   * obtain one).
   *
   * **What this asks is "any app", which is why a privileged feature scope does not pass it.** An
   * empty sandbox is no answer where a route never consults one: the AI routes behind this gate
   * spend a provider call on the strength of the bearer alone, and a management authority —
   * granted to administer service clients or contexts — would spend them for its hour. A route that
   * wants such a bearer authenticates it deliberately; this one takes whoever turns up.
   */
  internal fun requirePodAppTokenOrThrow(pod: String, request: ContainerRequestContext): SempodsCredentials {
    val podDbo = fetchPodOrThrow(pod)
    val podRef = ref(podDbo)
    val apiRequest = podApiRequest(request)
    return when (val outcome = bearer.verify(podDbo, podRef, apiRequest)) {
      is PodTokenAuthentication.Verified ->
        refuseIfPrivileged(bearer.authorizeAndAudit(podDbo, podRef, outcome.token, apiRequest))

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

  /** The pod's `WWW-Authenticate: Bearer` challenge — see [BearerPodRequestAuthenticator.challenge]. */
  internal fun challenge(podName: String, error: String = BearerChallenge.INVALID_TOKEN): String =
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
      .header(HttpHeaders.WWW_AUTHENTICATE, challenge(podName, code))
      .entity(mapOf("error" to code, "error_description" to description))
      .type(MediaType.APPLICATION_JSON)
      .build()
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

  private fun throwInvalidBearer(podName: String): Nothing =
    throw InvalidBearerException(
      podName = podName,
      response = Response.status(401)
        .header(HttpHeaders.WWW_AUTHENTICATE, challenge(podName))
        .entity("missing or invalid app bearer token")
        .type("text/plain")
        .build()
    )

  private fun throwMissingOrInvalidAppToken(pod: String): Nothing {
    throw WebApplicationException(
      Response.status(401)
        .header(HttpHeaders.WWW_AUTHENTICATE, challenge(pod))
        .entity("missing or invalid app bearer token")
        .type("text/plain")
        .build()
    )
  }
}
