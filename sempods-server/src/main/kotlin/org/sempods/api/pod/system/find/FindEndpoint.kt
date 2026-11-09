package org.sempods.api.pod.system.find

import com.google.inject.Inject
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.api.pod.resources.PodResourceReadService
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

/**
 * `GET`/`POST /{pod}/_system/find` — the semantic entry to the graph retrieval pattern
 * (`docs/concepts/graph-retrieval.md`). Returns a context-sandboxed RDF subgraph (found resources +
 * best-effort `type`/`label`/`name` expansion) as JSON-LD (default) or N-Quads, mirroring the
 * `_system/sparql` graph negotiation.
 *
 * The transport — parameters, negotiation, the downscope, serialization and caching headers — is
 * [FindHttpHandler]'s. This endpoint adds this server's authentication as the handler's
 * [FindAuthorizer]:
 *
 * - the pod and the caller come from [authenticate]; the caller's readable contexts are the ceiling;
 * - a `context` downscope is `requested ∩ readable` against the pod's context registry, unknown and
 *   unreadable contexts silently dropped — the same as the LOD read routes (sempods-spec
 *   `spec/core/lod-crud.md` §4 §"Reads");
 * - a result read with a bearer is `Cache-Control: private, no-store`, an anonymous one `public`.
 *
 * The `POST` form ([findPost]) takes the same parameters in a JSON body ([FindPostRequest]) for
 * requests that no longer fit a URL. The general predicate `filter` stays a deferred additive
 * extension (see `docs/concepts/graph-retrieval.md`).
 */
@Path("{pod}/_system/find")
@Produces("application/ld+json", "application/n-quads")
class FindEndpoint @Inject constructor(
  private val findHttpHandler: FindHttpHandler,
  private val podResourceReadService: PodResourceReadService,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(podFacade = podFacade, podDao = podDao) {

  @GET
  fun find(
    @PathParam("pod") pod: String,
    @QueryParam("text") text: String?,
    @QueryParam("type") typeParams: List<String>?,
    @QueryParam("context") contextParams: List<String>?,
    @QueryParam("include_contexts") includeContextsParam: String?,
    @QueryParam("limit") limitParam: String?,
    @Context httpHeaders: HttpHeaders,
  ): Response =
    findHttpHandler.handleGet(
      text = text,
      typeParams = typeParams,
      contextParams = contextParams,
      includeContextsParam = includeContextsParam,
      limitParam = limitParam,
      acceptableMediaTypes = httpHeaders.acceptableMediaTypes,
      authorizer = authorizer(pod),
    )

  @POST
  @Consumes("application/json")
  fun findPost(
    @PathParam("pod") pod: String,
    body: String?,
    @Context httpHeaders: HttpHeaders,
  ): Response =
    findHttpHandler.handlePost(
      body = body,
      acceptableMediaTypes = httpHeaders.acceptableMediaTypes,
      authorizer = authorizer(pod),
    )

  private fun authorizer(pod: String) = FindAuthorizer {
    val credentials = authenticate(pod)
    FindAuthorization(
      // A pod's global identifier is its own URI; the retrieval layer and its adapters key on it
      // rather than on the server-local pod name.
      podUri = credentials.pod.uri,
      credentials = credentials,
      visibleContexts = credentials.restrictedContexts,
      // A non-empty request always resolves to a set; an absent one never reaches the resolver.
      contextResolver = { requested ->
        podResourceReadService.resolveVisibleContexts(pod, credentials, requested) ?: emptySet()
      },
      cacheControl = if (credentials.oauthClientId != null) "private, no-store" else "public",
    )
  }
}
