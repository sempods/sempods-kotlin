package org.sempods.commons.jaxrs

import jakarta.ws.rs.container.ContainerRequestContext

/**
 * The in-flight request, bound to the thread handling it.
 *
 * What lets an endpoint method reach its own request without threading a
 * [ContainerRequestContext] parameter through every call. [ContainerRequestHolderFilter] binds
 * and clears it; nothing else should.
 *
 * Shaped like `org.sempods.commons.trace.TraceContextHolder`, and for the same reason: a thread-local
 * with two call sites is easier to reason about than a public `ThreadLocal` field that any
 * caller can `set`.
 */
object ContainerRequestHolder {

  private val holder = ThreadLocal<ContainerRequestContext>()

  fun get(): ContainerRequestContext? = holder.get()

  /** The current request, or an [IllegalStateException] if this thread is not serving one. */
  fun require(): ContainerRequestContext =
    holder.get() ?: throw IllegalStateException("Not inside a request")

  fun set(requestContext: ContainerRequestContext) = holder.set(requestContext)

  fun clear() = holder.remove()
}
