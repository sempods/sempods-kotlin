package org.sempods.api.system.sparql

import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.rdf.toIri
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

/**
 * Anti-drift regression for per-context SPARQL scoping. The REST endpoint
 * (`_system/sparql/query`, SPARQL-1.1-protocol `default-graph-uri` / `named-graph-uri`) and the
 * pod-immanent MCP sparql tools (`context_iri`) are two transports over the SAME downscope. They
 * are meant to resolve a requested context identically — both route through
 * [org.sempods.api.pod.resources.PodResourceReadService.resolveVisibleContexts] +
 * [org.sempods.query.SparqlSandbox.buildDataset]. `tools.md` states "the two surfaces resolve
 * context_iri identically"; this test enforces that as code, so a future change to one path that
 * does not touch the shared resolver is caught.
 *
 * The guarantee is currently *structural* (shared code). This test is defense-in-depth: for one
 * fixed pod state it drives both surfaces with the same requested contexts and asserts they return
 * the same visibility — no downscope, a proper subset, an unreadable context, and a present-but-blank
 * request (which must fail closed on both, never widen back to the whole pod).
 */
class SparqlContextScopingParityTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val httpClient by lazy { http.followingRedirects }
  private val objectMapper = JsonMappers.default()

  private val graphQuery = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"

  @Test
  fun `REST dataset params and MCP context_iri resolve the same downscope`() {
    val pod = sempodsTestFactory.newPod()
    val base = "${SempodsModule.config.apiBaseUrl}${pod.name}"
    val contextA = URI("$base/ctx-a")
    val contextB = URI("$base/ctx-b")
    listOf(contextA, contextB).forEach { uri ->
      podContextsDao.create(podId = checkNotNull(pod.id), contextUri = uri.toString(), label = null, description = null, createdBy = "test")
    }

    val eventA = plantEvent(pod.name, contextA)
    val eventB = plantEvent(pod.name, contextB)
    // A single token that may read BOTH contexts, so any narrowing is the downscope's doing — not a
    // missing grant.
    val token = mintScopedToken(pod.name, listOf("${contextA}#read", "${contextB}#read"))
    val unreadable = "$base/ctx-nope"

    // scenario -> (requested contexts, expected A visible, expected B visible)
    val scenarios = listOf(
      Triple("no downscope", emptyList<String>(), true to true),
      Triple("downscope to A", listOf(contextA.toString()), true to false),
      Triple("unreadable context", listOf(unreadable), false to false),
      Triple("present-but-blank", listOf("", " "), false to false),
    )

    for ((label, requested, expected) in scenarios) {
      val restVis = visibility(restGraphBody(pod.name, token, requested), eventA, eventB)
      val mcpVis = visibility(mcpGraphBody(pod.name, token, requested), eventA, eventB)
      // The parity assertion — the actual drift-check.
      assertEquals(restVis, mcpVis, "$label: REST and MCP must resolve context_iri identically (REST=$restVis, MCP=$mcpVis)")
      // …and both must match the intended semantics, so the test can't pass by both being wrong.
      assertEquals(expected, restVis, "$label: unexpected visibility")
    }
  }

  private fun plantEvent(podName: String, context: URI): URI {
    val uri = sempodsTestFactory.eventUri(podName = podName, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = podName,
      eventUri = uri,
      context = context,
      name = "parity-${TestUtil.randomId()}",
    )
    return uri
  }

  private fun visibility(body: String, eventA: URI, eventB: URI): Pair<Boolean, Boolean> =
    body.contains(eventA.toString()) to body.contains(eventB.toString())

  /** REST surface: each requested context is sent as both `default-graph-uri` and `named-graph-uri`. */
  private fun restGraphBody(podName: String, token: String, contextIris: List<String>): String {
    val request = http.preparePost("${SempodsModule.config.apiBaseUrl}$podName/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(graphQuery)
    contextIris.forEach { iri ->
      request.addQueryParam("default-graph-uri", iri)
      request.addQueryParam("named-graph-uri", iri)
    }
    val response = request.execute()
    assertEquals(200, response.statusCode)
    return response.responseBody
  }

  /** MCP surface: the same requested contexts as the `context_iri` argument (omitted when empty). */
  private fun mcpGraphBody(podName: String, token: String, contextIris: List<String>): String {
    val arguments: Map<String, Any> =
      if (contextIris.isEmpty()) mapOf("query" to graphQuery)
      else mapOf("query" to graphQuery, "context_iri" to contextIris)
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 700,
      "method" to "tools/call",
      "params" to mapOf("name" to "sparql_graph", "arguments" to arguments),
    )
    val response = httpClient.preparePost("${SempodsModule.config.apiBaseUrl}$podName/_system/mcp")
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()
    assertEquals(200, response.statusCode)
    return response.responseBody
  }
}
