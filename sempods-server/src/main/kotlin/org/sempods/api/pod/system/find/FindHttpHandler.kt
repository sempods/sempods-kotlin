package org.sempods.api.pod.system.find

import com.google.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.eclipse.rdf4j.rio.Rio
import org.sempods.api.IncludeContextsParameter
import org.sempods.api.system.sparql.GraphResultFormat
import org.sempods.api.system.sparql.GraphResultNegotiation
import org.sempods.commons.json.JsonMappers
import org.sempods.rdf.RdfWriterUtil
import org.sempods.retrieval.FindRequest
import org.sempods.retrieval.FindRequestParser
import org.sempods.retrieval.FindSandbox
import org.sempods.retrieval.FindService

/**
 * The HTTP transport of `find` (`docs/concepts/graph-retrieval.md`), without authentication.
 * [FindEndpoint] serves `/{pod}/_system/find` with it; a deployment with its own authentication
 * serves the same route by supplying its own [FindAuthorizer].
 *
 * Both entry points run the same order:
 *
 * 1. Parse and validate. `text` is required (whitespace-only → `400`), a `type` must be an IRI, the
 *    limit is clamped to 1..100, `include_contexts` must be `true` or `false`, and the `POST` body
 *    is read strictly — an unknown field is a `400`.
 * 2. Negotiate JSON-LD (the default) or N-Quads from the `Accept` media types, else `406`.
 * 3. Call the [FindAuthorizer]. Whatever it throws propagates.
 * 4. Resolve the optional context downscope. Absent searches every readable context. Present but
 *    blank-only (`context=`, `contexts: []`) is an empty scope and an empty result, and the
 *    [FindContextResolver] is not called. Otherwise the resolver's set, intersected with
 *    [FindAuthorization.visibleContexts], is the downscope.
 * 5. Run [FindService] and serialize: a flat graph by default; with `include_contexts`, named-graph
 *    JSON-LD or N-Quads with the context as the fourth term.
 * 6. Answer `200` with `Vary: Accept, Authorization` and [FindAuthorization.cacheControl].
 *
 * A `400` from step 1 is a returned response, except the `include_contexts` one, which is thrown as
 * a `WebApplicationException`. The `406` is returned.
 */
class FindHttpHandler @Inject constructor(
  private val findService: FindService,
) {

  private val objectMapper = JsonMappers.default()

  /**
   * `GET`, from the raw query parameters.
   *
   * @param typeParams the repeated `type` parameter.
   * @param contextParams the repeated `context` parameter; `null` or empty when absent.
   * @param acceptableMediaTypes the request's `Accept`, as JAX-RS orders it
   *   (`HttpHeaders.getAcceptableMediaTypes`).
   */
  fun handleGet(
    text: String?,
    typeParams: List<String>?,
    contextParams: List<String>?,
    includeContextsParam: String?,
    limitParam: String?,
    acceptableMediaTypes: List<MediaType>,
    authorizer: FindAuthorizer,
  ): Response {
    val request = try {
      FindRequestParser.parse(
        text = text,
        typeIris = typeParams ?: emptyList(),
        limit = limitParam?.trim()?.toIntOrNull(),
      )
    } catch (e: IllegalArgumentException) {
      return badRequest(e.message ?: "invalid request")
    }
    val includeContexts = IncludeContextsParameter.parseOrThrow(includeContextsParam)
    // An empty repeated list is an absent parameter; `?context=` keeps one blank entry, which is
    // a present downscope.
    return execute(
      request = request,
      rawContexts = contextParams?.takeIf { it.isNotEmpty() },
      includeContexts = includeContexts,
      acceptableMediaTypes = acceptableMediaTypes,
      authorizer = authorizer,
    )
  }

  /**
   * `POST`, from the raw JSON body — a [FindPostRequest].
   *
   * @param acceptableMediaTypes the request's `Accept`, as JAX-RS orders it
   *   (`HttpHeaders.getAcceptableMediaTypes`).
   */
  fun handlePost(
    body: String?,
    acceptableMediaTypes: List<MediaType>,
    authorizer: FindAuthorizer,
  ): Response {
    val rawBody = body?.takeIf { it.isNotBlank() } ?: return badRequest("missing JSON request body")
    val parsed = try {
      JsonMappers.strict().readValue(rawBody, FindPostRequest::class.java)
    } catch (e: Exception) {
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
    return execute(
      request = request,
      rawContexts = parsed.contexts,
      includeContexts = parsed.includeContexts == true,
      acceptableMediaTypes = acceptableMediaTypes,
      authorizer = authorizer,
    )
  }

  private fun execute(
    request: FindRequest,
    rawContexts: List<String>?,
    includeContexts: Boolean,
    acceptableMediaTypes: List<MediaType>,
    authorizer: FindAuthorizer,
  ): Response {
    val format = GraphResultNegotiation.select(acceptableMediaTypes)
      ?: return Response.status(Response.Status.NOT_ACCEPTABLE)
        .entity("find can only produce application/ld+json or application/n-quads")
        .type(MediaType.TEXT_PLAIN)
        .build()

    val authorization = authorizer.authorize()
    val contextFilter = rawContexts?.let { raw ->
      val cleaned = raw.filter { it.isNotBlank() }
      if (cleaned.isEmpty()) {
        emptySet()
      } else {
        val resolved = authorization.contextResolver.resolve(cleaned)
        authorization.visibleContexts?.let { resolved intersect it } ?: resolved
      }
    }
    val sandbox = FindSandbox(
      podUri = authorization.podUri,
      credentials = authorization.credentials,
      visibleContexts = authorization.visibleContexts,
      contextFilter = contextFilter,
    )
    val model = findService.find(request = request, sandbox = sandbox)

    val entity: Any = if (includeContexts && format == GraphResultFormat.JSON_LD) {
      objectMapper.writeValueAsString(RdfWriterUtil.toJsonLdNamedGraphs(model))
    } else {
      val outModel = if (includeContexts) model else RdfWriterUtil.flatten(model)
      StreamingOutput { out ->
        val writer = Rio.createWriter(format.rdfFormat, out)
        writer.startRDF()
        for (statement in outModel) writer.handleStatement(statement)
        writer.endRDF()
      }
    }

    // The body depends on the serialization and on the credential, so a shared cache keys on both.
    return Response.ok(entity)
      .type(format.contentType)
      .header(HttpHeaders.VARY, "Accept, Authorization")
      .header(HttpHeaders.CACHE_CONTROL, authorization.cacheControl)
      .build()
  }

  private fun badRequest(message: String): Response =
    Response.status(Response.Status.BAD_REQUEST)
      .entity(message)
      .type(MediaType.TEXT_PLAIN)
      .build()
}
