package org.sempods.commons.jaxrs

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * One line per matched request: what came in, and which resource claimed it.
 *
 * **At DEBUG, and that is the point.** It is registered on every connector unconditionally, and at
 * INFO it wrote 118,905 lines in 25 hours on the pod server — enough to bury the 79 that mattered.
 * Turning it on is a `<logger>` element naming this class, the mechanism `docs/logging.md`
 * describes for the loggers that name themselves:
 *
 * ```xml
 * <logger name="org.sempods.commons.jaxrs.JaxRsDebugFilter" level="DEBUG"/>
 * ```
 *
 * It is a debugging aid rather than an access log. A real one is Jetty's `CustomRequestLog`, which
 * would also cover the requests no resource matches — those are named by
 * [org.sempods.commons.jaxrs.errors.ApiExceptionMapper] instead, since a filter running after
 * matching is never reached by them.
 */
class JaxRsDebugFilter : ContainerRequestFilter {

  override fun filter(requestContext: ContainerRequestContext) {
    logger.debug {
      val matchedResource = requestContext.uriInfo.matchedResources.firstOrNull()
      // Through `RequestPathForLog` for the same reason the exception mapper is: a matched path
      // still holds whatever the caller put in its template parameters, decoded.
      "JAX-RS Match: ${requestContext.method} ${RequestPathForLog.of(requestContext.uriInfo.path)} -> " +
          "${matchedResource?.javaClass?.simpleName} | " +
          "Matched URIs: ${requestContext.uriInfo.matchedURIs.map { RequestPathForLog.of(it) }}"
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
