package org.sempods.api.pod.system.contexts

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.ext.Provider

/**
 * `Cache-Control: no-store` and `Vary: Accept, Authorization` on every answer of the context
 * registry — including the ones no registry code builds: the `401` of a refused bearer, the `404` of
 * an unknown pod, and the `406` Jersey answers while it matches, before any method runs.
 *
 * `SPS-CTX-036` covers errors as well as answers, and the anonymous errors are the ones that matter:
 * a `404` is heuristically cacheable (RFC 9111 §4.2.2), so a stored one would go on hiding a context
 * — or a whole pod — that exists by the time the next caller asks.
 *
 * This is where those two headers are set for the route. `PodContextsEndpoint` adds only what one
 * representation owes on its own, the deprecation of the transitional envelope.
 */
@Provider
class ContextRegistryCacheFilter : ContainerResponseFilter {

  override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
    if (!isRegistryPath(requestContext.uriInfo.path)) return
    responseContext.headers.putSingle(HttpHeaders.CACHE_CONTROL, "no-store")
    responseContext.headers.putSingle(HttpHeaders.VARY, "Accept, Authorization")
  }

  /** `{pod}/_system/contexts`, and every context below it. */
  private fun isRegistryPath(path: String): Boolean {
    val segments = path.trim('/').split('/')
    return segments.size >= 3 && segments[1] == "_system" && segments[2] == "contexts"
  }
}
