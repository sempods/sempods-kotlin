package org.sempods.commons.jaxrs

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.container.PreMatching

/**
 * Binds [ContainerRequestHolder] for the duration of a request and clears it afterwards.
 *
 * Named for what it does rather than for the framework it was first written beside — it touches no
 * DI container. [JaxRsServerModule] registers it on every connector it builds, which is what makes
 * `BaseEndpoint.currentRequestContext()` work.
 *
 * **`@PreMatching` is load-bearing**, for the reason [org.sempods.commons.jaxrs.filters.TraceContextFilter]
 * states: a request refused *during* matching never reaches a post-matching filter, and
 * `ApiExceptionMapper` then has nothing to name it by. The context object stored here is the live
 * one, so an endpoint method reading it later still sees its matching information — binding it
 * earlier takes nothing away.
 */
@PreMatching
class ContainerRequestHolderFilter : ContainerRequestFilter, ContainerResponseFilter {

  override fun filter(requestContext: ContainerRequestContext) {
    ContainerRequestHolder.set(requestContext)
  }

  override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
    ContainerRequestHolder.clear()
  }
}
