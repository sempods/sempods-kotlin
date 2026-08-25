package org.sempods.commons.jaxrs.filters

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter

/**
 * Filter to add "Accept" to the Vary header for proper content negotiation caching.
 * This ensures that caches understand that responses may vary based on the Accept header.
 */
class VaryHeaderFilter : ContainerResponseFilter {

  override fun filter(
    requestContext: ContainerRequestContext,
    responseContext: ContainerResponseContext,
  ) {
    VaryHeaderUtil.addCommaSeparatedHeaderValue(responseContext, "Vary", "Accept")
  }
}
