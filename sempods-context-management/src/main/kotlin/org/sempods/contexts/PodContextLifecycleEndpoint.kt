package org.sempods.contexts

import com.google.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.api.PodBaseEndpoint
import org.sempods.api.pod.system.contexts.PodContextLifecycleRoutes
import org.sempods.pods.contexts.ContextPathRules

/**
 * Context creation and deletion, the spec's optional `context-management` module
 * (sempods-spec `spec/modules/context-management.md`):
 *
 * - `PUT    {pod}/_system/contexts/{path...}` — create a context, or find it existing.
 * - `DELETE {pod}/_system/contexts/{path...}` — delete it, with everything that hangs on it.
 *
 * The reads at the same path are the core's (`PodContextsEndpoint`); Jersey serves both classes on
 * one path. Who may create or delete a context, and what deleting one removes, are the server's
 * ([PodContextLifecycleRoutes]): they are its grant model and its cascade. What is here is the wire
 * surface and the naming rules on creation, which only this route applies (`SPS-CTX-009`,
 * `SPS-CTX-012`, `SPS-CTX-014`): reading and deleting keep working for a context of any shape.
 */
@Path("{pod}/_system/contexts")
@Consumes(MediaType.APPLICATION_JSON)
class PodContextLifecycleEndpoint @Inject constructor(
  private val routes: PodContextLifecycleRoutes,
) : PodBaseEndpoint() {

  @PUT
  @Path("{contextPath: .+}")
  @Produces("application/ld+json", "application/json", "application/n-quads")
  fun put(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
    @Context httpHeaders: HttpHeaders,
    body: PutPodContextRequest?,
    @Context requestContext: ContainerRequestContext,
  ): Response {
    val accept = httpHeaders.getHeaderString(HttpHeaders.ACCEPT)
    // Negotiated before anything is written: Jersey has already answered an unsatisfiable `Accept`
    // with 406 during matching, so no context comes into existence for one (`SPS-CTX-037`).
    routes.refuseUnacceptable(accept)?.let { return it }
    val contextUri = routes.contextUri(pod, contextPath)
    // Authority before the naming rules, which hold for creation only: a caller without it gets 403
    // whether the context exists or not (`SPS-CORE-018`).
    val credentials = authenticate(pod, requestContext)
    requireAuthenticatedOrThrow(credentials)
    val createdBy = routes.authorize(pod, contextUri, credentials)
    ContextPathRules.rejectionReason(ContextPathRules.normalize(contextPath))?.let {
      throw WebApplicationException(Response.status(400).entity(it).type(MediaType.TEXT_PLAIN).build())
    }
    val fields = body ?: PutPodContextRequest()
    return routes.create(
      pod = pod,
      contextUri = contextUri,
      label = fields.label,
      description = fields.description,
      public = fields.public,
      createdBy = createdBy,
      accept = accept,
    )
  }

  @DELETE
  @Path("{contextPath: .+}")
  fun delete(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
    @Context requestContext: ContainerRequestContext,
  ): Response {
    val contextUri = routes.contextUri(pod, contextPath)
    // Before the existence check: a caller without authority gets 403 whether the context exists or
    // not (`SPS-CORE-018`).
    val credentials = authenticate(pod, requestContext)
    requireAuthenticatedOrThrow(credentials)
    routes.authorize(pod, contextUri, credentials)
    return routes.remove(pod, contextUri)
  }
}

/** The body of a context `PUT`; the body is optional. */
data class PutPodContextRequest(
  val label: String? = null,
  val description: String? = null,
  /**
   * Marks the context anonymously readable on creation. Defaults to `false` (private).
   *
   * The default is the contract, not a convenience: `SPS-CTX-027` requires an absent flag to create
   * a private context, a request with no body at all included.
   */
  val public: Boolean = false,
)
