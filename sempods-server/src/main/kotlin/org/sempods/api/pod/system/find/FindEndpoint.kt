package org.sempods.api.pod.system.find

import com.fasterxml.jackson.databind.DeserializationFeature
import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.SempodsUriBuilder
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.api.pod.resources.PodResourceReadService
import org.sempods.api.system.sparql.GraphResultFormat
import org.sempods.api.system.sparql.GraphResultNegotiation
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.rdf.RdfWriterUtil
import org.sempods.retrieval.FindRequest
import org.sempods.retrieval.FindRequestParser
import org.sempods.retrieval.FindSandbox
import org.sempods.retrieval.FindService
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.eclipse.rdf4j.rio.Rio

/**
 * `GET`/`POST /{pod}/_system/find` — the semantic entry to the graph retrieval pattern
 * (`docs/concepts/graph-retrieval.md`). Returns a context-sandboxed RDF subgraph (found resources +
 * best-effort `type`/`label`/`name` expansion) as JSON-LD (default) or N-Quads, mirroring the
 * `_system/sparql` graph negotiation.
 *
 * The endpoint stays thin: parse + validate input, authenticate, delegate to [FindService],
 * negotiate the serialization. `text` is required (whitespace-only → 400). The optional, repeatable
 * `type` constrains the returned hit's `rdf:type` (OR-combined). The optional, repeatable `context`
 * is a read downscope within the caller's readable contexts — same `requested ∩ readable` /
 * silent-exclusion semantics as the LOD read routes (sempods-spec `spec/core/lod-crud.md` §4 §"Reads"). The optional
 * `include_contexts` switches the result to the named-graph (provenance) form: each hit/expansion
 * statement grouped by the context it came from (JSON-LD), or carried as the 4th N-Quads term.
 *
 * The `POST` form ([findPost]) takes the same parameters in a JSON body for requests that no longer
 * fit a URL (many `contexts`); GET and POST share [executeFind]. The general predicate `filter`
 * stays a deferred additive extension (see `docs/concepts/graph-retrieval.md`).
 */
@Path("{pod}/_system/find")
@Produces("application/ld+json", "application/n-quads")
class FindEndpoint @Inject constructor(
  private val findService: FindService,
  private val podResourceReadService: PodResourceReadService,
  private val sempodsUriBuilder: SempodsUriBuilder,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(podFacade = podFacade, podDao = podDao) {

  private val objectMapper = JsonMappers.default()

  // Strict POST-body parsing: the shared mapper ignores unknown fields project-wide, which would
  // let an unsupported `filter` (or a typo'd `contexts`) be silently dropped and silently broaden
  // the result — fail-open on a security-relevant parameter. This one rejects unknown fields so
  // such a request is a 400, never a half-honored broader search.
  private val strictBodyMapper = JsonMappers.newDefault()
    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

  @GET
  fun find(
    @PathParam("pod") pod: String,
    @QueryParam("text") text: String?,
    @QueryParam("type") typeParams: List<String>?,
    @QueryParam("context") contextParams: List<String>?,
    @QueryParam("include_contexts") includeContextsParam: String?,
    @QueryParam("limit") limitParam: String?,
    @Context httpHeaders: HttpHeaders,
  ): Response {
    // Shared validation (REST + MCP cannot drift): whitespace-only/absent text → 400, invalid
    // type IRI → 400, limit clamped 1..100.
    val request = try {
      FindRequestParser.parse(
        text = text,
        typeIris = typeParams ?: emptyList(),
        limit = limitParam?.trim()?.toIntOrNull(),
      )
    } catch (e: IllegalArgumentException) {
      return badRequest(e.message ?: "invalid request")
    }
    val includeContexts = parseIncludeContextsOrThrow(includeContextsParam)
    // An empty repeated `?context=` list means "absent" (→ pod-wide); a present-but-blank value
    // (`?context=`) keeps a non-empty raw list so [executeFind] treats it as a present-empty scope.
    return executeFind(pod, request, contextParams?.takeIf { it.isNotEmpty() }, includeContexts, httpHeaders)
  }

  @POST
  @Consumes("application/json")
  fun findPost(
    @PathParam("pod") pod: String,
    body: String?,
    @Context httpHeaders: HttpHeaders,
  ): Response {
    val rawBody = body?.takeIf { it.isNotBlank() } ?: return badRequest("missing JSON request body")
    val parsed = try {
      strictBodyMapper.readValue(rawBody, FindPostRequest::class.java)
    } catch (e: Exception) {
      // Unknown fields (the not-yet-supported `filter`, a typo'd `contexts`, …) are rejected, not
      // silently dropped — a half-honored request must never broaden the result.
      return badRequest("invalid request body: ${e.message?.substringBefore('\n') ?: "could not parse"}")
    }
    val request = try {
      FindRequestParser.parse(
        text = parsed.text,
        typeIris = parsed.type ?: emptyList(),
        limit = parsed.limit,
      )
    } catch (e: IllegalArgumentException) {
      return badRequest(e.message ?: "invalid request")
    }
    return executeFind(pod, request, parsed.contexts, parsed.includeContexts == true, httpHeaders)
  }

  /**
   * Shared core for GET and POST: negotiate the serialization, authenticate, resolve the optional
   * context downscope, run [FindService], and serialize. `includeContexts` selects flat vs.
   * named-graph (provenance) output.
   */
  private fun executeFind(
    pod: String,
    request: FindRequest,
    rawContexts: List<String>?,
    includeContexts: Boolean,
    httpHeaders: HttpHeaders,
  ): Response {
    val format = GraphResultNegotiation.select(httpHeaders.acceptableMediaTypes)
      ?: return Response.status(Response.Status.NOT_ACCEPTABLE)
        .entity("find can only produce application/ld+json or application/n-quads")
        .type(MediaType.TEXT_PLAIN)
        .build()

    val credentials = authenticate(pod)
    // A pod's global identifier is its own URI (sempods are decentralized Linked Open Data); the
    // retrieval layer and its adapters key on this rather than the server-local pod name. The
    // canonical (no-trailing-slash) form comes from SempodsUriBuilder, the same builder that mints
    // every resource URI — distinct from the OAuth issuer / RFC 9728 base used in the auth layer.
    val podUri = credentials.pod.uri
    // Optional context downscope. `rawContexts == null` = absent → pod-wide within the readable
    // ceiling. A present list is fail-closed: blank-only (`contexts:[]` / `[""]`) resolves to an
    // empty scope (→ empty result), NOT pod-wide; otherwise `requested ∩ readable` with
    // unknown/unreadable contexts silently dropped. Mirrors the MCP find tool's semantics and the
    // LOD read routes.
    val contextFilter = rawContexts?.let { raw ->
      val cleaned = raw.filter { it.isNotBlank() }
      if (cleaned.isEmpty()) {
        emptySet()
      } else {
        podResourceReadService.resolveVisibleContexts(pod, credentials, cleaned)
      }
    }
    val sandbox = FindSandbox(
      podUri = podUri,
      credentials = credentials,
      visibleContexts = credentials.restrictedContexts,
      contextFilter = contextFilter,
    )
    val model = findService.find(request = request, sandbox = sandbox)

    val entity: Any = if (includeContexts && format == GraphResultFormat.JSON_LD) {
      // Named-graph JSON-LD: statements grouped by their source context (provenance), the same
      // representation `get_resource?include_contexts=true` returns — but multi-subject.
      objectMapper.writeValueAsString(RdfWriterUtil.toJsonLdNamedGraphs(model))
    } else {
      // Flat by default (drop context deterministically); for include_contexts + N-Quads keep the
      // context as each statement's 4th term.
      val outModel = if (includeContexts) model else RdfWriterUtil.flatten(model)
      StreamingOutput { out ->
        val writer = Rio.createWriter(format.rdfFormat, out)
        writer.startRDF()
        for (statement in outModel) writer.handleStatement(statement)
        writer.endRDF()
      }
    }

    // Permission-scoped caching (`docs/concepts/graph-retrieval.md` "GET caching is
    // permission-scoped"): the body depends on the negotiated serialization (Accept) AND on the
    // bearer — an anonymous caller sees public contexts only, an authenticated one sees private
    // contexts too. A bearer-backed result must never be served to a different caller from a shared
    // cache, so it is marked `private, no-store`; anonymous results may be cached. `Vary` keys
    // shared caches on both.
    val cacheControl = if (credentials.oauthClientId != null) "private, no-store" else "public"
    return Response.ok(entity)
      .type(format.contentType)
      .header(HttpHeaders.VARY, "Accept, Authorization")
      .header(HttpHeaders.CACHE_CONTROL, cacheControl)
      .build()
  }

  private fun badRequest(message: String): Response =
    Response.status(Response.Status.BAD_REQUEST)
      .entity(message)
      .type(MediaType.TEXT_PLAIN)
      .build()
}
