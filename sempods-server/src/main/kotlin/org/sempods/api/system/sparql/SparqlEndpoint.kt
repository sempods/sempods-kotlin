package org.sempods.api.system.sparql

import com.google.inject.Inject
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.api.pod.resources.PodResourceReadService
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.query.ForbiddenSparqlException
import org.sempods.query.SparqlQueryService
import org.sempods.query.SparqlSandbox
import jakarta.ws.rs.*
import jakarta.ws.rs.core.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.net.URI
import org.sempods.commons.logging.LogSafeText

@Path("{pod}/_system/sparql")
class SparqlEndpoint @Inject constructor(
  private val sparqlQueryService: SparqlQueryService,
  private val podResourceReadService: PodResourceReadService,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  @Consumes("application/sparql-query", MediaType.TEXT_PLAIN)
  @Produces(
    "application/sparql-results+json",
    "application/ld+json",
    "application/n-quads",
  )
  @POST
  @Path("query")
  fun query(
    @PathParam("pod") pod: String,
    body: InputStream,
    @Context httpHeaders: HttpHeaders,
    @QueryParam("default-graph-uri") defaultGraphUris: List<String>?,
    @QueryParam("named-graph-uri") namedGraphUris: List<String>?,
  ): Response {

    val sparqlQuery = body.bufferedReader(Charsets.UTF_8).readText()

    logger.debug { "SPARQL (first 200): ${LogSafeText.of(sparqlQuery.take(200))}" }

    if (sparqlQuery.isEmpty()) {
      return Response.status(Response.Status.BAD_REQUEST).entity("Missing SPARQL query").build()
    }

    val credentials = authenticate(pod)

    val validated = try {
      sparqlQueryService.validateReadOnly(sparqlQuery)
    } catch (e: ForbiddenSparqlException) {
      return Response.status(Response.Status.BAD_REQUEST).entity(e.message).type(MediaType.TEXT_PLAIN).build()
    }

    // Optional SPARQL-1.1-protocol dataset params (default-graph-uri / named-graph-uri) downscope
    // the query to a subset of the caller's readable contexts. Resolution goes through the SAME
    // registry-normalizing path as the pod-immanent MCP sparql tools
    // (PodResourceReadService.resolveVisibleContexts → {requested} ∩ readable, unknown/unreadable
    // contexts silently dropped), so the two surfaces resolve context_iri identically and the params
    // can only narrow. Presence is judged on the RAW values: a present-but-blank param
    // (?default-graph-uri=) is still a downscope request and must fail closed to empty, never fall
    // back to the whole readable set.
    val rawDefault = defaultGraphUris.orEmpty()
    val rawNamed = namedGraphUris.orEmpty()
    val effectiveContexts: Set<URI>? = if (rawDefault.isEmpty() && rawNamed.isEmpty()) {
      credentials.restrictedContexts
    } else {
      val nonBlank = (rawDefault + rawNamed).filter { it.isNotBlank() }
      if (nonBlank.isEmpty()) emptySet()
      else podResourceReadService.resolveVisibleContexts(pod, credentials, nonBlank)
    }
    val matchesNothing = SparqlSandbox.matchesNothing(effectiveContexts)
    val dataset = if (matchesNothing) null else SparqlSandbox.buildDataset(effectiveContexts)

    return when (validated.type) {
      SparqlQueryService.ReadQueryType.TUPLE -> {
        if (!accepts(httpHeaders.acceptableMediaTypes, SPARQL_RESULTS_JSON_MEDIA_TYPE)) {
          return Response.status(Response.Status.NOT_ACCEPTABLE)
            .entity("SELECT queries can only produce application/sparql-results+json")
            .type(MediaType.TEXT_PLAIN)
            .build()
        }
        if (matchesNothing) {
          return Response.ok(sparqlQueryService.executeTupleAsJsonOnEmptyStore(sparqlQuery))
            .type("application/sparql-results+json")
            .build()
        }
        val entity = StreamingOutput { out ->
          sparqlQueryService.executeTuple(pod = pod, query = sparqlQuery, dataset = dataset, out = out)
        }
        Response.ok(entity).type("application/sparql-results+json").build()
      }

      SparqlQueryService.ReadQueryType.BOOLEAN -> {
        if (!accepts(httpHeaders.acceptableMediaTypes, SPARQL_RESULTS_JSON_MEDIA_TYPE)) {
          return Response.status(Response.Status.NOT_ACCEPTABLE)
            .entity("ASK queries can only produce application/sparql-results+json")
            .type(MediaType.TEXT_PLAIN)
            .build()
        }
        if (matchesNothing) {
          return Response.ok(sparqlQueryService.executeBooleanAsJsonOnEmptyStore(sparqlQuery))
            .type("application/sparql-results+json")
            .build()
        }
        val json = sparqlQueryService.executeBooleanAsJson(pod = pod, query = sparqlQuery, dataset = dataset)
        val entity = StreamingOutput { out ->
          out.write(json.toByteArray(Charsets.UTF_8))
        }
        Response.ok(entity).type("application/sparql-results+json").build()
      }

      SparqlQueryService.ReadQueryType.GRAPH -> {
        val graphResult = GraphResultNegotiation.select(httpHeaders.acceptableMediaTypes)
          ?: return Response.status(Response.Status.NOT_ACCEPTABLE)
            .entity("Graph queries can only produce application/ld+json or application/n-quads")
            .type(MediaType.TEXT_PLAIN)
            .build()
        if (matchesNothing) {
          return Response.ok(sparqlQueryService.executeGraphAsStringOnEmptyStore(sparqlQuery, graphResult.rdfFormat))
            .type(graphResult.contentType)
            .build()
        }
        val entity = StreamingOutput { out ->
          sparqlQueryService.executeGraph(
            pod = pod,
            query = sparqlQuery,
            dataset = dataset,
            out = out,
            rdfFormat = graphResult.rdfFormat,
          )
        }
        Response.ok(entity).type(graphResult.contentType).build()
      }
    }
  }

  private fun accepts(acceptableMediaTypes: List<MediaType>, produced: MediaType): Boolean {
    if (acceptableMediaTypes.isEmpty()) {
      return true
    }
    return acceptableMediaTypes.any { accepted -> accepted.isCompatible(produced) }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
    private val SPARQL_RESULTS_JSON_MEDIA_TYPE = MediaType.valueOf("application/sparql-results+json")
  }
}
