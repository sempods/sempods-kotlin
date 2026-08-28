package org.sempods.api.pod.resources

import com.google.inject.Inject
import org.sempods.SempodsModule
import org.sempods.SempodsUriBuilder
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.toIri
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HEAD
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.OPTIONS
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

@Path("{pod: [^/]+}")
class PodResourceEndpoint @Inject constructor(
  private val sempodsUriBuilder: SempodsUriBuilder,
  private val podResourceWriteService: PodResourceWriteService,
  private val podResourceReadService: PodResourceReadService,
  private val podContextWriteAuthorizer: PodContextWriteAuthorizer,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  companion object {
    private val logger = KotlinLogging.logger {}
  }

  @PUT
  @Path("{resourcePath: .+}")
  @Consumes("application/json", "application/ld+json", "application/n-quads")
  fun put(
    @PathParam("pod") pod: String,
    @PathParam("resourcePath") resourcePath: String,
    @QueryParam("context") contextParams: List<String>?,
    @HeaderParam("Content-Type") contentTypeHeader: String?,
    body: String,
  ): Response {
    rejectSystemArea(resourcePath)
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val resourceUri = buildResourceUri(pod, resourcePath)
    val contextUri = podContextWriteAuthorizer.resolveSingleWriteContextOrThrow(pod, contextParams)
    evaluateWritePreconditionsOrNull(pod, resourceUri)?.let { return it }

    val outcome = podResourceWriteService.putResource(
      pod = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
      contentTypeHeader = contentTypeHeader,
      body = body,
      credentials = credentials,
    )
    val auditOutcome = when (outcome) {
      PodResourceWriteService.PutResourceOutcome.CREATED -> "created"
      PodResourceWriteService.PutResourceOutcome.UPDATED -> "replaced"
    }
    logLodAudit(auditOutcome, pod, resourceUri, contextUri, credentials)
    return when (outcome) {
      PodResourceWriteService.PutResourceOutcome.CREATED -> {
        val location = "${SempodsModule.config.apiBaseUrl}${pod}/${resourcePath}"
        Response.status(201).header("Location", location).build()
      }
      PodResourceWriteService.PutResourceOutcome.UPDATED -> Response.status(200).build()
    }
  }

  @PATCH
  @Path("{resourcePath: .+}")
  @Consumes("application/merge-patch+json")
  fun patch(
    @PathParam("pod") pod: String,
    @PathParam("resourcePath") resourcePath: String,
    @QueryParam("context") contextParams: List<String>?,
    body: String,
  ): Response {
    rejectSystemArea(resourcePath)
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val resourceUri = buildResourceUri(pod, resourcePath)
    val contextUri = podContextWriteAuthorizer.resolveSingleWriteContextOrThrow(pod, contextParams)
    evaluateWritePreconditionsOrNull(pod, resourceUri)?.let { return it }

    podResourceWriteService.mergePatchResource(
      pod = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
      body = body,
      credentials = credentials,
    )
    logLodAudit("patched", pod, resourceUri, contextUri, credentials)
    return Response.status(204).build()
  }

  @DELETE
  @Path("{resourcePath: .+}")
  fun delete(
    @PathParam("pod") pod: String,
    @PathParam("resourcePath") resourcePath: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response {
    rejectSystemArea(resourcePath)
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val resourceUri = buildResourceUri(pod, resourcePath)
    val contextUri = podContextWriteAuthorizer.resolveSingleWriteContextOrThrow(pod, contextParams)
    evaluateWritePreconditionsOrNull(pod, resourceUri)?.let { return it }

    podResourceWriteService.deleteResource(
      pod = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
      credentials = credentials,
    )
    logLodAudit("deleted", pod, resourceUri, contextUri, credentials)
    return Response.status(204).build()
  }

  @GET
  @Path("{resourcePath: .+}")
  @Produces("application/json", "application/ld+json")
  fun getEntry(
    @PathParam("pod") pod: String,
    @PathParam("resourcePath") resourcePath: String,
    @QueryParam("context") contextParams: List<String>?,
    @QueryParam("include_contexts") includeContextsParam: String?,
  ): Response {
    rejectSystemArea(resourcePath)
    val includeContexts = parseIncludeContextsOrThrow(includeContextsParam)
    val credentials = authenticate(pod)
    val resourceUri = buildResourceUri(pod, resourcePath)

    // Resolve visibility and load the visible representation BEFORE any ETag / precondition handling.
    // A caller who cannot see the resource (it lives only in unreadable contexts, or does not exist)
    // must get 404 here — otherwise a conditional request would short-circuit to 304 (If-None-Match)
    // or 412 (If-Match) and leak the resource's existence and its content-hash fingerprint, bypassing
    // the read sandbox. The validator/tag is global across contexts, so it must never be exposed to a
    // caller without a confirmed visible representation.
    val visibleContexts = podResourceReadService.resolveVisibleContexts(pod, credentials, contextParams)
    val model = podResourceReadService.loadVisibleResourceModelOrThrow(pod, resourceUri, visibleContexts)

    val entityTag = entityTag(pod, resourceUri, "application/ld+json", includeContexts)
    evaluatePreconditions(entityTag)?.let { return varyOnAccept(it.toResponseBuilder()).build() }

    val body = if (includeContexts) {
      RdfWriterUtil.toJsonLdNamedGraphs(model = model, resource = resourceUri.toIri())
    } else {
      // Canonical JSON-LD per `SPS-CRUD-023` (sempods-spec): absolute
      // IRI predicate keys, no @context, value objects as arrays. This is the only shape the
      // LOD-layer PATCH endpoint accepts as patch document — same shape on the way out.
      RdfWriterUtil.toCanonicalJsonLdEntry(
        model = model,
        resource = resourceUri.toIri(),
      )
    }
    val builder = Response.ok(body).tag(entityTag)
    return varyOnAccept(builder).build()
  }

  @GET
  @Path("{resourcePath: .+}")
  @Produces("application/n-quads")
  fun nQuads(
    @PathParam("pod") pod: String,
    @PathParam("resourcePath") resourcePath: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response {
    rejectSystemArea(resourcePath)
    val credentials = authenticate(pod)
    val resourceUri = buildResourceUri(pod, resourcePath)

    // Visibility 404 before any ETag / precondition handling — see getEntry for the rationale
    // (a conditional request must not leak existence of a resource in an unreadable context).
    val visibleContexts = podResourceReadService.resolveVisibleContexts(pod, credentials, contextParams)
    val model = podResourceReadService.loadVisibleResourceModelOrThrow(pod, resourceUri, visibleContexts)

    val entityTag = entityTag(pod, resourceUri, "application/n-quads")
    evaluatePreconditions(entityTag)?.let { return varyOnAccept(it.toResponseBuilder()).build() }

    val streamingOutput = StreamingOutput { out ->
      RdfWriterUtil.streamNQuads(model = model, outputStream = out)
    }
    val builder = Response.ok(streamingOutput).tag(entityTag)
    return varyOnAccept(builder).build()
  }

  // HEAD is automatically routed to the matching @GET handler by JAX-RS, which discards the
  // entity body and emits the headers only (Jersey behaviour). We still declare @HEAD methods
  // so OPTIONS' auto-generated Allow header includes HEAD without explicit overrides.

  @HEAD
  @Path("{resourcePath: .+}")
  @Produces("application/json", "application/ld+json")
  fun head(
    @PathParam("pod") pod: String,
    @PathParam("resourcePath") resourcePath: String,
    @QueryParam("context") contextParams: List<String>?,
    @QueryParam("include_contexts") includeContextsParam: String?,
  ): Response = getEntry(pod, resourcePath, contextParams, includeContextsParam)

  @HEAD
  @Path("{resourcePath: .+}")
  @Produces("application/n-quads")
  fun headNQuads(
    @PathParam("pod") pod: String,
    @PathParam("resourcePath") resourcePath: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response = nQuads(pod, resourcePath, contextParams)

  @OPTIONS
  @Path("{resourcePath: .+}")
  fun options(
    @PathParam("pod") pod: String,
    @PathParam("resourcePath") resourcePath: String,
  ): Response {
    rejectSystemArea(resourcePath)
    val credentials = authenticate(pod)
    val allow = buildAllowHeader(pod, resourcePath, credentials)
    return Response.ok().header(HttpHeaders.ALLOW, allow).build()
  }

  /**
   * Compute the `Allow` header for [resourcePath] based on what [credentials] may actually do:
   *
   * - Reads (`GET`, `HEAD`, `OPTIONS`) are always available — the LOD layer is read-public
   *   by design; resources without readable statements return `404` at request time.
   * - Writes (`PUT`, `PATCH`, `DELETE`) are advertised only when the caller is authenticated
   *   and holds at least one `<context>#write` or `<root>#manage` scope. We do not require a
   *   specific context here because `?context=` is per-call and the caller may legitimately
   *   target different contexts on subsequent requests.
   */
  private fun buildAllowHeader(pod: String, resourcePath: String, credentials: SempodsCredentials): String {
    val methods = mutableListOf("GET", "HEAD", "OPTIONS")
    val hasWriteScope = credentials.oauthScopes.any { it.endsWith("#write") || it.endsWith("#manage") }
    if (hasWriteScope) {
      methods.addAll(listOf("PUT", "PATCH", "DELETE"))
    }
    return methods.joinToString(", ")
  }

  private fun buildResourceUri(pod: String, resourcePath: String): URI {
    return sempodsUriBuilder.buildResourceUri(
      podName = pod,
      resourcePath = resourcePath,
    )
  }

  private fun rejectSystemArea(resourcePath: String) {
    if (resourcePath == "_system" || resourcePath.startsWith("_system/") ||
      resourcePath == ".well-known" || resourcePath.startsWith(".well-known/")
    ) {
      throw WebApplicationException(Response.status(404).build())
    }
  }

  private fun entityTag(pod: String, resourceUri: URI, contentType: String, includeContexts: Boolean = false): EntityTag {
    val baseValue = podResourceReadService.resourceTagBaseValue(pod, resourceUri, includeContexts)
    return createContentTypeAwareEntityTag(baseValue, contentType)
  }

  /**
   * Pre-write conditional check honoring `If-Match` and `If-None-Match` per RFC 7232.
   *
   * - Existing resource (`getResourceValidator != null`): compare client preconditions against the
   *   strong validator (content hash) computed from the store.
   * - Non-existent resource: use JAX-RS no-arg `evaluatePreconditions` so `If-None-Match: *`
   *   passes (create-or-fail semantics for PUT) and `If-Match: <tag>` fails (precondition
   *   on a non-existent representation can never be met).
   *
   * Returns the response to send back on a precondition failure, or `null` if the call may
   * proceed. The validator is global (across contexts) because LOD identity is global; the
   * subsequent write still targets exactly one context.
   */
  private fun evaluateWritePreconditionsOrNull(pod: String, resourceUri: URI): Response? {
    val validator = podResourceReadService.resourceWriteValidator(pod, resourceUri)
    return if (validator != null) {
      val tag = createContentTypeAwareEntityTag(
        validator,
        "application/ld+json",
      )
      evaluatePreconditions(tag)
    } else {
      try {
        currentRequestContext().request.evaluatePreconditions()?.build()
      } catch (_: Exception) {
        null
      }
    }
  }

  private fun varyOnAccept(builder: Response.ResponseBuilder): Response.ResponseBuilder =
    builder.header(HttpHeaders.VARY, HttpHeaders.ACCEPT)

  private fun Response.toResponseBuilder(): Response.ResponseBuilder = Response.fromResponse(this)

  /**
   * Audit line for LOD-layer writes. Counterpart to `[mcp/audit]` (see
   * [org.sempods.api.pod.system.mcp.McpEndpoint]) and `[slot/audit]` (System layer).
   * The `[lod/audit]` marker lets ops split resource-level writes from slot-level writes
   * with a single grep against one log stream.
   */
  private fun logLodAudit(
    outcome: String,
    pod: String,
    resourceUri: URI,
    contextUri: URI,
    credentials: SempodsCredentials,
  ) {
    logger.info {
      "[lod/audit] outcome=$outcome pod='$pod' resource='$resourceUri' context='$contextUri' " +
        "client_id='${credentials.oauthClientId ?: "(anon)"}'"
    }
  }

}
