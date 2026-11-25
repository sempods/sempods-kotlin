package org.sempods.api

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.PreMatching
import jakarta.ws.rs.ext.Provider
import java.net.URI

/**
 * Makes a `;` in the request path part of the segment it is in.
 *
 * Jersey reads `;…` as a matrix parameter and cuts it from every segment before it matches, so
 * `{pod}/notes/a;b` would reach the resource `{pod}/notes/a` and `alice;x/_system/sparql` the pod
 * `alice`. This server has no matrix parameters: a `;` is an ordinary path character (RFC 3986 §3.3),
 * and a LOD IRI or a context IRI may carry one (`SPS-CRUD-001`, `SPS-CTX-005`). Written as `%3B`, it
 * stays in its segment through matching and reaches a `@PathParam` decoded, as `;`.
 *
 * The path as received stays on the request as [RECEIVED_RAW_PATH], because a credential bound to
 * the target signed the `;` and not the `%3B`.
 */
@Provider
@PreMatching
class PathSemicolonFilter : ContainerRequestFilter {

  override fun filter(requestContext: ContainerRequestContext) {
    val uri = requestContext.uriInfo.requestUri
    if (';' !in uri.rawPath) return
    requestContext.setProperty(RECEIVED_RAW_PATH, uri.rawPath)
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    requestContext.setRequestUri(URI("${uri.scheme}://${uri.rawAuthority}${uri.rawPath.replace(";", "%3B")}$query"))
  }

  companion object {
    /** The request property holding the raw path before this filter rewrote it. */
    const val RECEIVED_RAW_PATH = "org.sempods.api.receivedRawPath"
  }
}
