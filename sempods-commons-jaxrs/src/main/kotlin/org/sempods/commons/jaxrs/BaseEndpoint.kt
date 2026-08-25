package org.sempods.commons.jaxrs

import org.sempods.commons.jaxrs.errors.ApiErrors
import jakarta.ws.rs.Produces
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.time.Instant
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * What every JAX-RS endpoint in the project needs and nothing that belongs to one application.
 *
 * The half of an endpoint base class that names no domain type: the current request, ETag and
 * precondition evaluation, the `respondWith*` shortcuts, and parameter parsing. Authentication —
 * `isAdmin()`, `checkAdminAccess()`, `authenticate()`, and the admin authority behind them — is
 * deliberately absent: who a caller is (an admin credential, a session) is an application's own
 * notion, and a shared module must not impose one.
 */
@Produces(MediaType.APPLICATION_JSON + "; charset=\"utf-8\"")
open class BaseEndpoint {

  protected fun currentRequestContext(): ContainerRequestContext = ContainerRequestHolder.require()

  /**
   * The URI of the current request as the *public* API sees it, rather than as it arrived.
   *
   * [apiBaseUrl] replaces the path prefix, so a request that reached the process through a proxy
   * still reports the address a client can call back. The query string is carried over verbatim.
   */
  protected fun currentRequestUri(apiBaseUrl: String): URI {
    val uriInfo = currentRequestContext().uriInfo
    var uri = apiBaseUrl + uriInfo.path
    if (uriInfo.queryParameters.isNotEmpty()) {
      val requestUriStr = uriInfo.requestUri.toASCIIString()
      requestUriStr.indexOf('?').let { qMarkIdx ->
        if (qMarkIdx != -1) {
          uri += requestUriStr.substring(qMarkIdx)
        }
      }
    }
    return URI(uri)
  }

  protected fun okNoContent(func: () -> Unit): Response {
    func.invoke()
    return Response.status(204).build()
  }

  protected fun respondWithAccessDenied(): Response {
    return Response.status(403).build()
  }

  protected fun respondWithNotFound(): Response {
    return Response.status(404).build()
  }

  protected fun evaluatePreconditions(entityTag: EntityTag): Response? {
    try {
      val request = currentRequestContext().request
      val response = request.evaluatePreconditions(entityTag)
      if (response == null) return null

      // Jetty's GzipHandler appends "--gzip" to the ETag it emits on a
      // compressed response (RFC 9110 §8.8.3 — different Content-Encoding
      // values are different representations). Clients echo that exact
      // tag back in If-Match on the subsequent write. Since the handler
      // strips the suffix only on its own GET path (the write response
      // has no body to compress), the application sees a raw
      // `If-Match: "X--gzip"` while it computes `"X"` — a guaranteed 412.
      // Re-evaluate against the suffixed variant so writes succeed when
      // the client played back exactly the tag we handed out.
      val ifMatch = currentRequestContext().getHeaderString("If-Match")
      if (ifMatch != null && ifMatch.contains("--gzip\"")) {
        val gzipTag = EntityTag("${entityTag.value}--gzip")
        if (request.evaluatePreconditions(gzipTag) == null) return null
      }

      return response.tag(entityTag).build()
    } catch (e: Exception) {
      // happens if the request sends an invalid If-None-Match header
      logger.warn { "Failed to evaluate preconditions: $e" }
    }
    return null
  }

  /**
   * Creates an EntityTag that incorporates both a base value (e.g., timestamp) and a content type.
   * This ensures different representations of the same resource have distinct ETags, as required
   * by HTTP specifications (RFC 7232, RFC 9110).
   *
   * While the Vary: Accept header informs caches that responses may differ based on the Accept header,
   * the ETag itself must be unique per representation to prevent cache confusion where a client with
   * a cached JSON representation might incorrectly match against an N-Quads representation.
   *
   * @param baseValue The base identifier (typically a timestamp or version number)
   * @param contentType The content type of the representation (e.g., "application/json", "application/n-quads")
   * @return An EntityTag that uniquely identifies this specific representation
   */
  protected fun createContentTypeAwareEntityTag(baseValue: String, contentType: String): EntityTag {
    // Normalize content type to a short stable identifier
    val contentTypeSuffix = when (contentType) {
      "application/json" -> "json"
      "application/ld+json" -> "jsonld"
      "application/n-quads" -> "nquads"
      else -> {
        // For other content types, use a sanitized version of the type
        contentType.replace("[^a-zA-Z0-9]".toRegex(), "").take(10).lowercase()
      }
    }
    return EntityTag("$baseValue-$contentTypeSuffix")
  }

  companion object {

    private val logger = KotlinLogging.logger {}

    fun parseBooleanParameter(payload: Map<String, Any?>, key: String): Boolean? {
      val value = payload[key] ?: return null
      if (value is Boolean) {
        return value
      }
      value.toString().toBooleanStrictOrNull()?.let { return it }
      throw ApiErrors.throwInvalidParameterError(key)
    }

    fun parseIsoDateTime(value: String?, parameterName: String): Instant? {
      return value?.let {
        try {
          Instant.parse(it)
        } catch (e: Exception) {
          throw ApiErrors.throwInvalidParameterError(parameterName)
        }
      }
    }
  }
}
