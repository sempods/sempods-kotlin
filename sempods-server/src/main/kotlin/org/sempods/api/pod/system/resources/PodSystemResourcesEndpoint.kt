package org.sempods.api.pod.system.resources

import com.google.inject.Inject
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.api.pod.resources.PodContextWriteAuthorizer
import org.sempods.api.pod.resources.PodResourceReadService
import org.sempods.api.pod.resources.PodResourceWriteService
import org.sempods.api.pod.resources.RepresentationTags
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.toIri
import jakarta.ws.rs.*
import jakarta.ws.rs.core.*
import org.eclipse.rdf4j.model.*
import org.eclipse.rdf4j.model.vocabulary.XSD
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/**
 * HTTP entry point for the LOD-CRUD **System layer** under `{pod}/_system/resources`. It owns
 * the whole base64url-addressed surface, in two flavors that share this one JAX-RS root resource
 * (a second class at the same `@Path` would be shadowed by JAX-RS root-resource selection):
 *
 * - **slot / edge** — triple-granular CRUD on RDF property slots and single edges, and
 * - **whole resource** — CRUD on an entire resource at an arbitrary IRI (the dynamic
 *   resource-by-IRI route; see the section further down in this class).
 *
 * See sempods-spec `spec/core/lod-crud.md` §5.
 *
 * URL scheme (all path segments base64url-encoded, RFC 4648 §5, no padding):
 *
 * ```
 * /{pod}/_system/resources/{b64u(resourceIri)}                              # whole resource
 * /{pod}/_system/resources/{b64u(subject)}/{b64u(predicate)}                # slot
 * /{pod}/_system/resources/{b64u(subject)}/{b64u(predicate)}/{b64u(target)} # single edge (IRI only)
 * ```
 *
 * The subject / resource IRI may be any IRI — local (`https://sempods.org/{pod}/...`) or external
 * (`did:web:bob.example`, `urn:isbn:...`, etc.). The System layer is the only path through which
 * external URIs can be addressed.
 *
 * Conditional requests on slots (`SPS-CRUD-050` to `SPS-CRUD-054`), with tags from [RepresentationTags]:
 * - A `GET` in exactly one context carries a strong `ETag` over the slot's statements there, and
 *   answers a matching `If-None-Match` with `304`. A read spanning several contexts carries none.
 * - `PUT` honors `If-Match` and `If-None-Match: *` (the slot is empty in the target context);
 *   `POST` and whole-slot `DELETE` honor `If-Match` when given. [PodSlotWriteService] evaluates
 *   them once the write is authorized.
 * - Single-edge `DELETE` ignores both.
 */
@Path("{pod}/_system/resources")
class PodSystemResourcesEndpoint @Inject constructor(
  private val podSlotWriteService: PodSlotWriteService,
  private val podContextWriteAuthorizer: PodContextWriteAuthorizer,
  private val podResourceWriteService: PodResourceWriteService,
  private val podResourceReadService: PodResourceReadService,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  companion object {
    private val logger = KotlinLogging.logger {}
  }

  @GET
  @Path("{subjectB64}/{predicateB64}")
  @Produces("application/ld+json", "application/json")
  fun getSlot(
    @PathParam("pod") pod: String,
    @PathParam("subjectB64") subjectB64: String,
    @PathParam("predicateB64") predicateB64: String,
    @QueryParam("context") contextParams: List<String>?,
    @QueryParam("include_contexts") includeContextsParam: String?,
  ): Response {
    val includeContexts = parseIncludeContextsOrThrow(includeContextsParam)
    val credentials = authenticate(pod)
    val subjectUri = decodeUriSegmentOrThrow(subjectB64, "subject")
    val predicateUri = decodeUriSegmentOrThrow(predicateB64, "predicate")

    val requestedContexts = resolveReadContexts(pod, contextParams)
    val slotModel = podSlotWriteService.getSlot(
      pod = pod,
      subjectUri = subjectUri,
      predicateUri = predicateUri,
      requestedContexts = requestedContexts,
      credentials = credentials,
    )
    if (slotModel.isEmpty()) {
      // Empty result — silent on unreadable / unknown contexts (no topology leak per spec).
      return Response.status(404).build()
    }
    // A tag only for a read in exactly one context (`SPS-CRUD-050`, `SPS-CRUD-051`).
    val entityTag = requestedContexts?.singleOrNull()
      ?.let { RepresentationTags.slot(slotModel, subjectUri, predicateUri, it, includeContexts) }
    entityTag?.let { tag ->
      evaluatePreconditions(tag)?.let { return Response.fromResponse(it).revalidatedPrivately().build() }
    }
    val body = if (includeContexts) {
      RdfWriterUtil.toJsonLdNamedGraphs(model = slotModel, resource = subjectUri.toIri())
    } else {
      renderSlotAsJsonLdArray(slotModel, subjectUri, predicateUri)
    }
    return Response.ok(body).tag(entityTag).revalidatedPrivately().build()
  }

  @PUT
  @Path("{subjectB64}/{predicateB64}")
  @Consumes("application/ld+json", "application/json")
  fun putSlot(
    @PathParam("pod") pod: String,
    @PathParam("subjectB64") subjectB64: String,
    @PathParam("predicateB64") predicateB64: String,
    @QueryParam("context") contextParams: List<String>?,
    body: String,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val subjectUri = decodeUriSegmentOrThrow(subjectB64, "subject")
    val predicateUri = decodeUriSegmentOrThrow(predicateB64, "predicate")
    val contextUri = resolveSingleWriteContext(pod, contextParams)

    podSlotWriteService.replaceSlot(
      pod = pod,
      subjectUri = subjectUri,
      predicateUri = predicateUri,
      contextUri = contextUri,
      body = body,
      credentials = credentials,
      conditions = writeConditions(),
    )
    logSlotAudit("set", pod, subjectUri, predicateUri, contextUri, credentials)
    return Response.status(204)
      .tag(slotTag(pod, subjectUri, predicateUri, contextUri))
      .build()
  }

  @POST
  @Path("{subjectB64}/{predicateB64}")
  @Consumes("application/ld+json", "application/json")
  fun postSlotValue(
    @PathParam("pod") pod: String,
    @PathParam("subjectB64") subjectB64: String,
    @PathParam("predicateB64") predicateB64: String,
    @QueryParam("context") contextParams: List<String>?,
    body: String,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val subjectUri = decodeUriSegmentOrThrow(subjectB64, "subject")
    val predicateUri = decodeUriSegmentOrThrow(predicateB64, "predicate")
    val contextUri = resolveSingleWriteContext(pod, contextParams)

    val result = podSlotWriteService.addSlotValue(
      pod = pod,
      subjectUri = subjectUri,
      predicateUri = predicateUri,
      contextUri = contextUri,
      body = body,
      credentials = credentials,
      conditions = writeConditions(),
    )
    // TODO: consider in-memory burst grouping for repeated `add` calls on the same
    //  `(pod, subject, predicate, context)` so the audit stream stays readable when an
    //  agent loops a batch insert.
    val auditResult = when (result.outcome) {
      PodFacade.SlotAddOutcome.CREATED -> "created"
      PodFacade.SlotAddOutcome.ALREADY_PRESENT -> "already_present"
    }
    logSlotAudit("add", pod, subjectUri, predicateUri, contextUri, credentials, result = auditResult)
    val postWriteTag = slotTag(pod, subjectUri, predicateUri, contextUri)
    // The status already separates the two outcomes (201 created / 200 already present), and the
    // body says it again in the vocabulary the caller asked in. Same reasoning as `removeSlotEdge`
    // below, and the reason it is worth the repetition: a caller that reads this route through a
    // tool layer sees a result object, not a status line, so an outcome only a status carries is one
    // that layer has to re-derive — and `sempods-mcp-core`'s executor deliberately does not know
    // route-by-route what a status means.
    return when (result.outcome) {
      PodFacade.SlotAddOutcome.CREATED -> {
        val addedValue = result.addedValue
        val created = Response.status(201).tag(postWriteTag).entity(outcomeBody("created")).type(MediaType.APPLICATION_JSON)
        if (addedValue is IRI) {
          val location = "/${pod}/_system/resources/${subjectB64}/${predicateB64}/" +
              UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(addedValue.stringValue()))
          created.header("Location", location).build()
        } else {
          created.build()
        }
      }

      PodFacade.SlotAddOutcome.ALREADY_PRESENT -> Response.status(200)
        .tag(postWriteTag)
        .entity(outcomeBody("already_present"))
        .type(MediaType.APPLICATION_JSON)
        .build()
    }
  }

  @DELETE
  @Path("{subjectB64}/{predicateB64}")
  fun clearSlot(
    @PathParam("pod") pod: String,
    @PathParam("subjectB64") subjectB64: String,
    @PathParam("predicateB64") predicateB64: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val subjectUri = decodeUriSegmentOrThrow(subjectB64, "subject")
    val predicateUri = decodeUriSegmentOrThrow(predicateB64, "predicate")
    val contextUri = resolveSingleWriteContext(pod, contextParams)

    val cleared = podSlotWriteService.clearSlot(
      pod = pod,
      subjectUri = subjectUri,
      predicateUri = predicateUri,
      contextUri = contextUri,
      credentials = credentials,
      conditions = writeConditions(),
    )
    logSlotAudit(
      outcome = "clear",
      pod = pod,
      subjectUri = subjectUri,
      predicateUri = predicateUri,
      contextUri = contextUri,
      credentials = credentials,
      result = if (cleared) "cleared" else "already_empty",
    )
    // Echo the post-clear (now-empty) slot tag, as `SPS-CRUD-052` asks. It hashes the slot's
    // identity as well as its statements, so it is well-defined for an empty slot too.
    //
    // `200` with `{"outcome": …}` rather than a bare `204`, for the reason `removeSlotEdge` gives
    // below and which applies here word for word: clearing is idempotent, so the status alone cannot
    // say whether anything was there. It used to be visible only in this server's audit log, which
    // means every caller outside this process — the SDK, the chat app, both MCP surfaces — was told
    // "done" and could not tell "done" from "there was nothing to do". RFC 9110 §9.3.5 blesses the
    // representation; the tag rides along on it unchanged.
    return Response.status(200)
      .tag(slotTag(pod, subjectUri, predicateUri, contextUri))
      .entity(outcomeBody(if (cleared) "cleared" else "already_empty"))
      .type(MediaType.APPLICATION_JSON)
      .build()
  }

  /**
   * Single-edge DELETE: unconditional. `If-Match` / `If-None-Match` headers are ignored —
   * removing one IRI value is idempotent (a missing edge yields the same outcome as removing
   * a present one), so optimistic-concurrency control adds no value here.
   */
  @DELETE
  @Path("{subjectB64}/{predicateB64}/{targetB64}")
  fun removeSlotEdge(
    @PathParam("pod") pod: String,
    @PathParam("subjectB64") subjectB64: String,
    @PathParam("predicateB64") predicateB64: String,
    @PathParam("targetB64") targetB64: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val subjectUri = decodeUriSegmentOrThrow(subjectB64, "subject")
    val predicateUri = decodeUriSegmentOrThrow(predicateB64, "predicate")
    val targetUri = decodeUriSegmentOrThrow(targetB64, "target")
    val contextUri = resolveSingleWriteContext(pod, contextParams)

    val removed = podSlotWriteService.removeSlotEdge(
      pod = pod,
      subjectUri = subjectUri,
      predicateUri = predicateUri,
      contextUri = contextUri,
      targetIri = org.eclipse.rdf4j.model.util.Values.iri(targetUri.toString()),
      credentials = credentials,
    )
    logSlotAudit(
      outcome = "remove_edge",
      pod = pod,
      subjectUri = subjectUri,
      predicateUri = predicateUri,
      contextUri = contextUri,
      credentials = credentials,
      result = if (removed) "removed" else "already_absent",
      extra = "target='$targetUri'",
    )
    // Single-edge DELETE is idempotent, so the status code alone cannot
    // distinguish a real removal from a no-op. RFC 9110 §9.3.5 blesses
    // returning `200 OK` with a representation describing the outcome
    // instead of a bare `204`; we surface `{"outcome": ...}` so HTTP
    // clients (the SDK / chat `remove_property_value`) report the same
    // `removed` / `already_absent` distinction as the MCP tool. Body
    // (not a custom header) keeps it browser-readable without CORS
    // expose-header gymnastics.
    return Response.status(200)
      .entity(outcomeBody(if (removed) "removed" else "already_absent"))
      .type(MediaType.APPLICATION_JSON)
      .build()
  }

  /**
   * The one-field representation the three idempotent slot mutations answer with.
   *
   * One helper so they cannot drift into three spellings of the same idea — the distinction each
   * reports (`created`/`already_present`, `cleared`/`already_empty`, `removed`/`already_absent`) is
   * the same distinction, and it is what a caller cannot recover from an idempotent status code.
   * Documented in sempods-spec `spec/core/lod-crud.md` §5.
   */
  private fun outcomeBody(outcome: String): String = "{\"outcome\":\"$outcome\"}"

  // ── Whole-resource CRUD by b64-encoded IRI (LOD-CRUD, dynamic resources) ─────────────
  //
  // Single-segment sibling of the slot routes above. Where the slot routes address one
  // property of a subject, these address an ENTIRE resource at an arbitrary IRI — including
  // IRIs outside the pod namespace, for which the canonical LOD path
  // (`PodResourceEndpoint`, `{pod}/{resourcePath}`) has no route at all.
  //
  // Writes delegate to the shared LOD write path ([PodResourceWriteService]); reads flow through the
  // shared [PodResourceReadService] and [RepresentationTags]. Context rules, conditional writes,
  // the canonical JSON-LD representation, and the ETag are therefore byte-identical to the
  // canonical path, so a pod-owned IRI has one identity across both
  // routes — which is `SPS-CRUD-002`, and `SPS-CRUD-001` for why the identity is the LOD IRI and
  // this is only an operations address. The verb set this route owes is `SPS-CRUD-040`.

  @GET
  @Path("{resourceB64}")
  @Produces("application/json", "application/ld+json")
  fun getResource(
    @PathParam("pod") pod: String,
    @PathParam("resourceB64") resourceB64: String,
    @QueryParam("context") contextParams: List<String>?,
    @QueryParam("include_contexts") includeContextsParam: String?,
  ): Response {
    val includeContexts = parseIncludeContextsOrThrow(includeContextsParam)
    val credentials = authenticate(pod)
    val resourceUri = decodeUriSegmentOrThrow(resourceB64, "resource")

    // Visibility 404 BEFORE any precondition handling — a conditional request must not leak the
    // existence of a resource the caller cannot read.
    val scope = podResourceReadService.resolveReadScope(pod, credentials, contextParams)
    val model = podResourceReadService.loadVisibleResourceModelOrThrow(pod, resourceUri, scope.visible)

    val form = if (includeContexts) RepresentationTags.Form.JSON_LD_WITH_CONTEXTS else RepresentationTags.Form.JSON_LD
    val entityTag = RepresentationTags.resource(model, scope.selection, form)
    evaluatePreconditions(entityTag)?.let { return Response.fromResponse(it).revalidatedPrivately().build() }

    val body = if (includeContexts) {
      RdfWriterUtil.toJsonLdNamedGraphs(model = model, resource = resourceUri.toIri())
    } else {
      RdfWriterUtil.toCanonicalJsonLdEntry(model = model, resource = resourceUri.toIri())
    }
    return Response.ok(body).tag(entityTag).revalidatedPrivately().build()
  }

  @GET
  @Path("{resourceB64}")
  @Produces("application/n-quads")
  fun getResourceNQuads(
    @PathParam("pod") pod: String,
    @PathParam("resourceB64") resourceB64: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response {
    val credentials = authenticate(pod)
    val resourceUri = decodeUriSegmentOrThrow(resourceB64, "resource")

    val scope = podResourceReadService.resolveReadScope(pod, credentials, contextParams)
    val model = podResourceReadService.loadVisibleResourceModelOrThrow(pod, resourceUri, scope.visible)

    val entityTag = RepresentationTags.resource(model, scope.selection, RepresentationTags.Form.N_QUADS)
    evaluatePreconditions(entityTag)?.let { return Response.fromResponse(it).revalidatedPrivately().build() }

    val streamingOutput = StreamingOutput { out ->
      RdfWriterUtil.streamNQuads(model = model, outputStream = out)
    }
    return Response.ok(streamingOutput).tag(entityTag).revalidatedPrivately().build()
  }

  // HEAD is auto-routed to the matching @GET by JAX-RS (body discarded). We declare @HEAD
  // explicitly so OPTIONS' generated Allow header includes HEAD — mirrors PodResourceEndpoint.

  @HEAD
  @Path("{resourceB64}")
  @Produces("application/json", "application/ld+json")
  fun headResource(
    @PathParam("pod") pod: String,
    @PathParam("resourceB64") resourceB64: String,
    @QueryParam("context") contextParams: List<String>?,
    @QueryParam("include_contexts") includeContextsParam: String?,
  ): Response = getResource(pod, resourceB64, contextParams, includeContextsParam)

  @HEAD
  @Path("{resourceB64}")
  @Produces("application/n-quads")
  fun headResourceNQuads(
    @PathParam("pod") pod: String,
    @PathParam("resourceB64") resourceB64: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response = getResourceNQuads(pod, resourceB64, contextParams)

  @OPTIONS
  @Path("{resourceB64}")
  fun optionsResource(
    @PathParam("pod") pod: String,
    @PathParam("resourceB64") resourceB64: String,
  ): Response {
    val credentials = authenticate(pod)
    val resourceUri = decodeUriSegmentOrThrow(resourceB64, "resource")
    // Reads are always available (LOD is read-public); writes only with a write/manage scope.
    // Mirrors PodResourceEndpoint.buildAllowHeader.
    val methods = mutableListOf("GET", "HEAD", "OPTIONS")
    val hasWriteScope = credentials.oauthScopes.any { it.endsWith("#write") || it.endsWith("#manage") }
    if (hasWriteScope) {
      methods.addAll(listOf("PUT", "PATCH", "DELETE"))
    }
    return Response.ok().header(HttpHeaders.ALLOW, methods.joinToString(", ")).build()
  }

  @PUT
  @Path("{resourceB64}")
  @Consumes("application/json", "application/ld+json", "application/n-quads")
  fun putResource(
    @PathParam("pod") pod: String,
    @PathParam("resourceB64") resourceB64: String,
    @QueryParam("context") contextParams: List<String>?,
    @HeaderParam("Content-Type") contentTypeHeader: String?,
    body: String,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val resourceUri = decodeUriSegmentOrThrow(resourceB64, "resource")
    val contextUri = resolveSingleWriteContext(pod, contextParams)

    val outcome = podResourceWriteService.putResource(
      pod = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
      contentTypeHeader = contentTypeHeader,
      body = body,
      credentials = credentials,
      conditions = writeConditions(),
    )
    val auditOutcome = when (outcome) {
      PodResourceWriteService.PutResourceOutcome.CREATED -> "created"
      PodResourceWriteService.PutResourceOutcome.UPDATED -> "replaced"
    }
    logResourceAudit(auditOutcome, pod, resourceUri, contextUri, credentials)
    // No ETag, on either route: the stored representation is not the body that was sent
    // (`SPS-CRUD-030`, RFC 9110 §9.3.4).
    return when (outcome) {
      PodResourceWriteService.PutResourceOutcome.CREATED -> {
        // The canonical path does not exist for external IRIs, so Location points at the b64 route.
        val location = "/${pod}/_system/resources/${resourceB64}"
        Response.status(201).header("Location", location).build()
      }

      PodResourceWriteService.PutResourceOutcome.UPDATED -> Response.status(200).build()
    }
  }

  @PATCH
  @Path("{resourceB64}")
  @Consumes("application/merge-patch+json")
  fun patchResource(
    @PathParam("pod") pod: String,
    @PathParam("resourceB64") resourceB64: String,
    @QueryParam("context") contextParams: List<String>?,
    body: String,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val resourceUri = decodeUriSegmentOrThrow(resourceB64, "resource")
    val contextUri = resolveSingleWriteContext(pod, contextParams)

    podResourceWriteService.mergePatchResource(
      pod = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
      body = body,
      credentials = credentials,
      conditions = writeConditions(),
    )
    logResourceAudit("patched", pod, resourceUri, contextUri, credentials)
    return Response.status(204).build()
  }

  @DELETE
  @Path("{resourceB64}")
  fun deleteResource(
    @PathParam("pod") pod: String,
    @PathParam("resourceB64") resourceB64: String,
    @QueryParam("context") contextParams: List<String>?,
  ): Response {
    val credentials = authenticate(pod)
    requireAuthenticatedOrThrow(credentials)
    val resourceUri = decodeUriSegmentOrThrow(resourceB64, "resource")
    val contextUri = resolveSingleWriteContext(pod, contextParams)

    podResourceWriteService.deleteResource(
      pod = pod,
      resourceUri = resourceUri,
      contextUri = contextUri,
      credentials = credentials,
      conditions = writeConditions(),
    )
    logResourceAudit("deleted", pod, resourceUri, contextUri, credentials)
    return Response.status(204).build()
  }

  // TODO: DX-Helper `GET /{pod}/_system/resources/_decode/{b64}` returning the decoded
  //  IRI as plain text would save callers from base64url-decoding by hand when debugging
  //  System-layer routes. Defer until a client actually asks for it — most ops happen via
  //  the MCP tools, which never expose the encoded form to the model anyway.

  // -- helpers --

  private fun decodeUriSegmentOrThrow(segment: String, role: String): URI {
    return try {
      UriEncodingUtil.decodeUrlSafeBase64ToUriStrict(segment)
    } catch (e: IllegalArgumentException) {
      throw WebApplicationException(
        Response.status(400)
          .entity("invalid base64url $role segment: ${e.message}")
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }
  }

  /**
   * Resolve the read-side `?context=` filter into a downscope set of context URIs, intersected
   * with the caller's readable contexts. Empty list → no `?context=` parameter → null
   * (caller-side filtering takes over).
   *
   * Repeated `?context=A&context=B` is allowed — the intersection downscope of `SPS-CRUD-014`.
   * Unreadable and unknown contexts are excluded silently (`SPS-CRUD-015`), and an empty result
   * set surfaces as `404` upstream, which `SPS-CRUD-017` requires to be indistinguishable from a
   * resource that simply has nothing there.
   */
  private fun resolveReadContexts(
    pod: String,
    rawContexts: List<String>?,
  ): Collection<URI>? = podContextWriteAuthorizer.resolveReadDownscopeOrEmpty(pod, rawContexts)

  private fun resolveSingleWriteContext(
    pod: String,
    rawContexts: List<String>?,
  ): URI = podContextWriteAuthorizer.resolveSingleWriteContextOrThrow(pod, rawContexts)

  /**
   * Resource-level audit line. Uses the SAME `[lod/audit]` marker as the canonical LOD path
   * (see [org.sempods.api.pod.resources.PodResourceEndpoint]) so resource-level writes stay
   * greppable across both addressing routes — distinct from this endpoint's `[slot/audit]`.
   */
  private fun logResourceAudit(
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

  /**
   * Audit line for System-layer slot writes. Marker `[slot/audit]` is intentionally distinct
   * from `[lod/audit]` and `[mcp/audit]` so ops can split resource-level from slot-level
   * writes with a single grep. `result=` (optional) carries the secondary outcome useful
   * for burst detection — e.g. `outcome=add result=already_present`.
   */
  private fun logSlotAudit(
    outcome: String,
    pod: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    credentials: SempodsCredentials,
    result: String? = null,
    extra: String? = null,
  ) {
    val resultPart = result?.let { " result=$it" } ?: ""
    val extraPart = extra?.let { " $it" } ?: ""
    logger.info {
      "[slot/audit] outcome=$outcome$resultPart pod='$pod' subject='$subjectUri' " +
          "predicate='$predicateUri' context='$contextUri' " +
          "client_id='${credentials.oauthClientId ?: "(anon)"}'$extraPart"
    }
  }

  /** The slot's tag in [contextUri] as it stands now — what a slot write echoes (`SPS-CRUD-052`). */
  private fun slotTag(pod: String, subjectUri: URI, predicateUri: URI, contextUri: URI): EntityTag =
    RepresentationTags.slot(
      statements = podSlotWriteService.slotInContext(pod, subjectUri, predicateUri, contextUri),
      subject = subjectUri,
      predicate = predicateUri,
      context = contextUri,
      withContexts = false,
    )

  /**
   * Render a slot model as a JSON-LD array of value objects.
   *
   * - IRI objects → `{"@id": "..."}`
   * - Literals with language tag → `{"@value": "...", "@language": "..."}`
   * - Literals with explicit datatype (other than xsd:string) → `{"@value": "...", "@type": "..."}`
   * - Plain string literals → `{"@value": "..."}`
   *
   * Source-context tagging on each value is explicitly out of scope for Iteration 1 (Iter 3).
   */
  private fun renderSlotAsJsonLdArray(model: Model, subjectUri: URI, predicateUri: URI): List<Map<String, Any>> {
    return model.asSequence()
      .filter { it.subject.stringValue() == subjectUri.toString() && it.predicate.stringValue() == predicateUri.toString() }
      .map { stmt -> valueToJsonLdNode(stmt.`object`) }
      .toList()
  }

  private fun valueToJsonLdNode(value: Value): Map<String, Any> {
    return when (value) {
      is Literal -> {
        val map = linkedMapOf<String, Any>("@value" to value.label)
        val lang = value.language.orElse(null)
        if (lang != null) {
          map["@language"] = lang
        } else if (value.datatype != null && value.datatype.stringValue() != XSD.STRING.stringValue()) {
          map["@type"] = value.datatype.stringValue()
        }
        map
      }

      is Resource -> mapOf("@id" to value.stringValue())
      else -> mapOf("@value" to value.stringValue())
    }
  }
}
