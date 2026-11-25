package org.sempods.api

import com.google.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.api.pod.resources.WriteConditions
import org.sempods.commons.jaxrs.BaseEndpoint
import org.sempods.pods.authentication.spi.PodApiRequest
import org.sempods.pods.authentication.spi.PodRequestAuthentication
import org.sempods.pods.authentication.spi.PodRequestAuthenticator
import org.sempods.pods.grants.SempodsCredentials

/**
 * The base of every route below `{pod}/…`. It depends on [PodRequestAuthenticator] and nothing
 * else — no pod registry, no store, no configuration — and reads the request only from what a
 * route hands it by `@Context`, never from a thread-local. That is what lets a deployment bind a
 * route on its own.
 *
 * What stays here is what neither the seam nor the route decides: how an outcome becomes an HTTP
 * status. [PodRequestAuthentication.PodNotFound] is a `404`, and
 * [PodRequestAuthentication.Unauthenticated] an [InvalidBearerException] — a `401` with the seam's
 * challenge. A route that also needs this server's pod registry or its bearer takes
 * [PodBearerRoutes] as a constructor parameter.
 *
 * A conditional read takes the request too: `evaluatePreconditions(requestContext, tag)`, not the
 * overload that reads `ContainerRequestHolder`.
 */
open class PodBaseEndpoint : BaseEndpoint() {

  @Inject
  private lateinit var podRequestAuthenticator: PodRequestAuthenticator

  /**
   * The caller of [request] on [pod], anonymous or not — sempods is Linked Open Data, so every
   * route accepts anonymous reads on public contexts. Throws for a pod the caller may not know
   * (`404`) and for a credential that does not hold (`401`).
   *
   * A route that requires authentication (writes, the MCP `authorize` tool) calls
   * [requireAuthenticatedOrThrow] on the result.
   */
  protected fun authenticate(pod: String, request: ContainerRequestContext): SempodsCredentials =
    when (val outcome = podRequestAuthenticator.authenticate(pod, podApiRequest(request))) {
      is PodRequestAuthentication.Authenticated -> outcome.credentials
      // Not stored: where a deployment hides a pod from some callers, the next one may see it.
      PodRequestAuthentication.PodNotFound ->
        throw WebApplicationException(Response.status(404).header(HttpHeaders.CACHE_CONTROL, "no-store").build())
      is PodRequestAuthentication.Unauthenticated -> throw invalidBearer(pod, outcome.challenge)
    }

  /**
   * Throws [InvalidBearerException] (→ 401 with the seam's challenge) if [credentials] is
   * anonymous. Callers *authenticated but lacking a needed scope* are answered with 403 by the
   * scope check, not by this.
   */
  protected fun requireAuthenticatedOrThrow(credentials: SempodsCredentials) {
    if (!credentials.authenticated) {
      throw invalidBearer(credentials.pod.name, podRequestAuthenticator.challenge(credentials.pod.name))
    }
  }

  /**
   * The `Vary` value of a response that depends on the caller: `Accept`, and every header the bound
   * authenticator reads credentials from ([PodRequestAuthenticator.credentialHeaders]).
   */
  protected fun varyOnCaller(): String =
    (listOf(HttpHeaders.ACCEPT) + podRequestAuthenticator.credentialHeaders).distinct().joinToString(", ")

  /** [varyOnCaller] without `Accept`, for a route whose representation does not negotiate on it. */
  protected fun varyOnCredentials(): String = podRequestAuthenticator.credentialHeaders.joinToString(", ")

  /**
   * The cache policy of a read that depends on the caller, for its `200` and its `304`.
   *
   * A shared cache must not store it and a private cache has to key it on the credential headers as
   * well as `Accept` ([varyOnCaller]). A private cache may keep it, but has to revalidate before
   * reusing it (RFC 9111 §5.2.2.4).
   */
  protected fun Response.ResponseBuilder.revalidatedPrivately(): Response.ResponseBuilder =
    header(HttpHeaders.CACHE_CONTROL, "private, no-cache").header(HttpHeaders.VARY, varyOnCaller())

  /** The request's `If-Match` and `If-None-Match`, for a write service to evaluate. */
  protected fun writeConditions(request: ContainerRequestContext): WriteConditions {
    fun field(name: String) = request.headers[name]?.takeIf { it.isNotEmpty() }?.joinToString(",")
    return WriteConditions(ifMatch = field(HttpHeaders.IF_MATCH), ifNoneMatch = field(HttpHeaders.IF_NONE_MATCH))
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

  internal fun invalidBearer(podName: String, challenge: String): InvalidBearerException =
    InvalidBearerException(
      podName = podName,
      response = Response.status(401)
        .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
        .entity("missing or invalid app bearer token")
        .type("text/plain")
        .build()
    )
}

/**
 * [request] as [PodRequestAuthenticator] takes it. Where [PathSemicolonFilter] rewrote the path
 * before matching, the path is the one the client sent, read from the request property it left.
 */
internal fun podApiRequest(request: ContainerRequestContext): PodApiRequest {
  val uriInfo = request.uriInfo
  val receivedRawPath = request.getProperty(PathSemicolonFilter.RECEIVED_RAW_PATH) as String?
  return PodApiRequest(
    method = request.method,
    path = receivedRawPath?.removePrefix(uriInfo.baseUri.rawPath) ?: uriInfo.getPath(false),
    query = uriInfo.requestUri.rawQuery,
    headers = request.headers.mapValues { it.value.toList() },
  )
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
