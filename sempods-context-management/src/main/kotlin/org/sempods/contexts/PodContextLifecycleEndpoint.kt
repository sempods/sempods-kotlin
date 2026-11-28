package org.sempods.contexts

import com.google.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.api.PodBaseEndpoint
import org.sempods.api.pod.system.contexts.PodContextLifecycleRoutes
import org.sempods.pods.grants.SempodsCredentials

/**
 * Context creation and deletion, the spec's optional `context-management` module
 * (sempods-spec `spec/modules/context-management.md`):
 *
 * - `PUT    {pod}/_system/contexts/{path...}` — create a context, or find it existing.
 * - `DELETE {pod}/_system/contexts/{path...}` — delete it, with everything that hangs on it.
 *
 * The reads at the same path are the core's (`PodContextsEndpoint`); Jersey serves both classes on
 * one path. Who may create or delete a context, the naming rules on creation and what deleting one
 * removes are the server's, run as one checked operation each ([PodContextLifecycleRoutes]): they
 * are its grant model and its cascade. What is here is the wire surface.
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
    val fields = body ?: PutPodContextRequest()
    return routes.create(
      pod = pod,
      contextPath = contextPath,
      label = fields.label,
      description = fields.description,
      public = fields.public,
      accept = httpHeaders.getHeaderString(HttpHeaders.ACCEPT),
    ) { caller(pod, requestContext) }
  }

  @DELETE
  @Path("{contextPath: .+}")
  fun delete(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
    @Context requestContext: ContainerRequestContext,
  ): Response = routes.remove(pod, contextPath) { caller(pod, requestContext) }

  /** The authenticated caller: `401` for an anonymous one. Asked by the server once the path holds. */
  private fun caller(pod: String, requestContext: ContainerRequestContext): SempodsCredentials =
    authenticate(pod, requestContext).also { requireAuthenticatedOrThrow(it) }
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
