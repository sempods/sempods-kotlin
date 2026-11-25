package org.sempods.api

import com.google.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.sempods.commons.jaxrs.BaseEndpoint
import org.sempods.pods.grants.PodApiRequest
import org.sempods.pods.grants.PodRequestAuthentication
import org.sempods.pods.grants.PodRequestAuthenticator
import org.sempods.pods.grants.SempodsCredentials

/**
 * The JAX-RS base of a route below `{pod}/…` that a deployment can bind on its own: it depends on
 * [PodRequestAuthenticator] and nothing else — no pod registry, no store, no configuration, and no
 * thread-local request. A route hands in what it took by `@Context`.
 *
 * What stays here is what neither the seam nor the route decides: how an outcome becomes an HTTP
 * status. [PodRequestAuthentication.PodNotFound] is a `404`, and
 * [PodRequestAuthentication.Unauthenticated] an [InvalidBearerException] — a `401` with the seam's
 * challenge.
 *
 * [SempodsBaseEndpoint] extends this for the routes that also need this server's pod registry.
 */
open class PodApiEndpoint : BaseEndpoint() {

  @Inject
  private lateinit var podRequestAuthenticator: PodRequestAuthenticator

  /**
   * The caller of [request] on [pod], anonymous or not. Throws for a pod the caller may not know
   * (`404`) and for a credential that does not hold (`401`).
   */
  protected fun authenticate(pod: String, request: PodApiRequest): SempodsCredentials =
    when (val outcome = podRequestAuthenticator.authenticate(pod, request)) {
      is PodRequestAuthentication.Authenticated -> outcome.credentials
      PodRequestAuthentication.PodNotFound -> throw WebApplicationException(Response.status(404).build())
      is PodRequestAuthentication.Unauthenticated -> throw invalidBearer(pod, outcome.challenge)
    }

  /** The request as [PodRequestAuthenticator] takes it. */
  protected fun podApiRequest(method: String, uriInfo: UriInfo, headers: MultivaluedMap<String, String>): PodApiRequest =
    PodApiRequest(
      method = method,
      path = uriInfo.getPath(false),
      query = uriInfo.requestUri.rawQuery,
      headers = headers.mapValues { it.value.toList() },
    )

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

  internal fun invalidBearer(podName: String, challenge: String?): InvalidBearerException =
    InvalidBearerException(
      podName = podName,
      response = Response.status(401)
        .apply { if (challenge != null) header(HttpHeaders.WWW_AUTHENTICATE, challenge) }
        .entity("missing or invalid app bearer token")
        .type("text/plain")
        .build()
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
