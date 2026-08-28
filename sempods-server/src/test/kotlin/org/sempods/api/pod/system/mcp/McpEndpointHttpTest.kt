package org.sempods.api.pod.system.mcp

import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.rdf.toIri
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var refreshTokenStore: PodRefreshTokenStore

  private val httpClient by lazy { http.followingRedirects }
  private val objectMapper = JsonMappers.default()

  private fun mcpUrl(podName: String) =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/mcp"

  private fun tokenUrl(podName: String) =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/auth/token"

  private fun postForm(url: String, body: String) =
    http.preparePost(url)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody(body)
      .execute()

  /**
   * Creates a context via DAO (bypassing HTTP auth) and returns the context URI plus
   * a pod-scoped Bearer token with read+write scopes for that context.
   */
  private fun createContextWithToken(
    pod: PodDbo,
    contextPath: String,
    webId: String = "https://id.test/user",
  ): Pair<URI, String> {
    val podId = checkNotNull(pod.id)
    // Through the builder: a test context has to sit where the server mints one, or the suite
    // keeps a namespace alive that no producer creates any more.
    val contextUri = sempodsUriBuilder.buildContext(pod.name, contextPath)
    podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    // Context permissions are resolved per (client, webId) from the grant store now. Pass a
    // distinct [webId] when a single test needs two tokens with isolated grant sets.
    val token = mintScopedToken(
      podName = pod.name,
      scopes = listOf("${contextUri}#read", "${contextUri}#write"),
      webId = webId,
    )
    return contextUri to token
  }

  @Test
  fun `initialize should return server info and capabilities`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 1,
      "method" to "initialize",
      "params" to mapOf(
        "protocolVersion" to "2024-11-05",
        "capabilities" to mapOf<String, Any>(),
        "clientInfo" to mapOf(
          "name" to "test-client",
          "version" to "1.0.0"
        )
      )
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val responseBody = response.responseBody
    assertTrue(responseBody.contains("sempods-mcp-server"), "TestHttpResponse should contain server name")
    assertTrue(responseBody.contains("protocolVersion"), "TestHttpResponse should contain protocol version")
    assertTrue(responseBody.contains("capabilities"), "TestHttpResponse should contain capabilities")
  }

  @Test
  fun `sparql_graph tool should execute queries on pod`() {
    // Create test pod
    val pod = sempodsTestFactory.newPod()

    // Create test event
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)

    val eventName = "Test MCP Event - ${TestUtil.randomId()}"
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = eventName,
      description = "Test event for MCP SPARQL query",
    )

    // Query the event via MCP SPARQL tool (note: no 'pod' parameter needed)
    val sparqlQuery =
      """
        PREFIX schema: <https://schema.org/>
        CONSTRUCT { <$eventUri> ?p ?o } WHERE { <$eventUri> ?p ?o } LIMIT 10
      """.trimIndent()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 3,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_graph",
        "arguments" to mapOf(
          "query" to sparqlQuery
        )
      )
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val responseBody = response.responseBody
    assertTrue(responseBody.contains(eventUri.toString()), "TestHttpResponse should contain event uri")
    assertTrue(responseBody.contains(eventName), "TestHttpResponse should contain event name")
  }

  @Test
  fun `sparql_graph tool should reject write operations`() {
    val pod = sempodsTestFactory.newPod()

    // Attempt to execute a write query
    val writeQuery = "INSERT DATA { <http://example.org/test> <http://example.org/prop> \"value\" }"
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 4,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_graph",
        "arguments" to mapOf(
          "query" to writeQuery
        )
      )
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val responseBody = response.responseBody
    assertTrue(
      responseBody.contains("Error") || responseBody.contains("not allowed"),
      "TestHttpResponse should indicate write operations are forbidden"
    )
    assertTrue(responseBody.contains("INSERT"), "TestHttpResponse should mention the forbidden keyword")
  }

  @Test
  fun `sparql_select tool should reject SERVICE keyword`() {
    val pod = sempodsTestFactory.newPod()

    // Attempt to execute a query with SERVICE
    val serviceQuery = "SELECT ?s WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }"
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 5,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_select",
        "arguments" to mapOf(
          "query" to serviceQuery
        )
      )
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val responseBody = response.responseBody
    assertTrue(
      responseBody.contains("Error") || responseBody.contains("not allowed"),
      "TestHttpResponse should indicate SERVICE is forbidden"
    )
  }

  @Test
  fun `sparql_select should reject CONSTRUCT queries with mode-mismatch error`() {
    val pod = sempodsTestFactory.newPod()

    val query = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } LIMIT 1"
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 7,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_select",
        "arguments" to mapOf("query" to query)
      )
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("sparql_select expects a"), "Should report mode mismatch, was: $body")
    assertTrue(body.contains("got GRAPH"), "Should mention CONSTRUCT mapped to GRAPH, was: $body")
    assertTrue(body.contains("sparql_graph"), "Should suggest sparql_graph, was: $body")
  }

  @Test
  fun `sparql_select should accept ASK queries and return SPARQL-Results-JSON boolean`() {
    val pod = sempodsTestFactory.newPod()

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 8,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_select",
        "arguments" to mapOf("query" to "ASK { ?s ?p ?o }")
      )
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertFalse(body.contains("expects a"), "Should not be a mode-mismatch error, was: $body")
    assertTrue(body.contains("\\\"boolean\\\""), "Should return SPARQL-Results-JSON boolean shape, was: $body")
  }

  @Test
  fun `sparql_select should accept write keywords inside string literals`() {
    val pod = sempodsTestFactory.newPod()

    // Substring-based filter would have rejected this query because the literal
    // contains "CREATE", "INSERT", and "SERVICE". A parser-based filter sees them
    // as StringLiteral tokens and lets the query through.
    val query = """SELECT ?s WHERE { ?s ?p "needs CREATE INSERT SERVICE handling" }"""
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 6,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_select",
        "arguments" to mapOf("query" to query)
      )
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertFalse(body.contains("Write operations are not allowed"), "Should not be rejected as write op, was: $body")
    assertFalse(body.contains("SERVICE keyword is not allowed"), "Should not be rejected as SERVICE, was: $body")
    // Valid empty SPARQL-Results-JSON: head/vars present, bindings empty
    assertTrue(body.contains("\\\"head\\\""), "Should be SPARQL-Results-JSON, was: $body")
    assertTrue(body.contains("\\\"bindings\\\""), "Should contain bindings (empty), was: $body")
  }

  @Test
  fun `sparql_select should return SPARQL-Results-JSON with distinct types`() {
    val pod = sempodsTestFactory.newPod()
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "select-test-${TestUtil.randomId()}",
    )

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 200,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_select",
        "arguments" to mapOf(
          "query" to "SELECT DISTINCT ?type WHERE { ?s a ?type } LIMIT 100",
        ),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    // SPARQL-Results-JSON shape: has "head" with "vars" and "results" with "bindings".
    assertTrue(body.contains("\\\"head\\\""), "Result text should be SPARQL-Results-JSON (head field), was: $body")
    assertTrue(body.contains("\\\"vars\\\""), "Result text should contain vars, was: $body")
    assertTrue(body.contains("\\\"bindings\\\""), "Result text should contain bindings, was: $body")
    assertTrue(body.contains("schema.org/Event"), "Result should reference schema:Event type from planted event")
  }

  @Test
  fun `sparql tools reject malformed context_iri fail-closed`() {
    val pod = sempodsTestFactory.newPod()
    val token = mintScopedToken(pod.name, emptyList())

    val selectWithStringContext = toolCall(pod.name, token, "sparql_select", mapOf(
      "query" to "SELECT ?s WHERE { ?s ?p ?o } LIMIT 1",
      "context_iri" to "https://example.org/c",
    ))
    assertTrue(
      selectWithStringContext.contains("\"isError\":true"),
      "sparql_select context_iri as a string must be a tool error: $selectWithStringContext",
    )

    val graphWithNonStringContext = toolCall(pod.name, token, "sparql_graph", mapOf(
      "query" to "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }",
      "context_iri" to listOf("https://example.org/c", 5),
    ))
    assertTrue(
      graphWithNonStringContext.contains("\"isError\":true"),
      "sparql_graph context_iri array with non-string must be a tool error: $graphWithNonStringContext",
    )
  }

  @Test
  fun `sparql tools context_iri downscopes within readable contexts`() {
    val pod = sempodsTestFactory.newPod()
    val contextA = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-a")
    val contextB = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-b")
    listOf(contextA, contextB).forEach {
      podContextsDao.create(
        podId = checkNotNull(pod.id),
        contextUri = it.toString(),
        label = null,
        description = null,
        createdBy = "test",
      )
    }

    fun newEvent(context: URI, name: String): URI {
      val uri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
      sempodsTestFactory.seedEvent(
        pod = pod.name,
        eventUri = uri,
        context = context,
        name = name,
      )
      return uri
    }

    val eventA = newEvent(contextA, "SPARQL A ${TestUtil.randomId()}")
    val eventB = newEvent(contextB, "SPARQL B ${TestUtil.randomId()}")
    val token = mintScopedToken(pod.name, listOf("${contextA}#read", "${contextB}#read"))
    val query = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"

    val all = toolCall(pod.name, token, "sparql_graph", mapOf("query" to query))
    assertTrue(all.contains(eventA.toString()), "without downscope, A should be present: $all")
    assertTrue(all.contains(eventB.toString()), "without downscope, B should be present: $all")

    val onlyA = toolCall(pod.name, token, "sparql_graph", mapOf(
      "query" to query,
      "context_iri" to listOf(contextA.toString()),
    ))
    assertTrue(onlyA.contains(eventA.toString()), "downscope to A must keep A: $onlyA")
    assertFalse(onlyA.contains(eventB.toString()), "downscope to A must drop B: $onlyA")

    val none = toolCall(pod.name, token, "sparql_select", mapOf(
      "query" to "SELECT ?s WHERE { ?s ?p ?o } LIMIT 10",
      "context_iri" to listOf("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-nope"),
    ))
    assertFalse(none.contains("\"isError\":true"), "unreadable/unknown downscope is not an error: $none")
    assertFalse(none.contains(eventA.toString()) || none.contains(eventB.toString()), "empty downscope must hide both: $none")

    // A blank entry is refused, not read as "match nothing". An unreadable context above is a
    // different case and still answers empty: the pod decided it, and the filter was well-formed.
    // A blank string is a client bug, and the failure mode it risks is the filter being dropped —
    // which fails open onto every readable context.
    val blankOnly = toolCall(pod.name, token, "sparql_graph", mapOf(
      "query" to query,
      "context_iri" to listOf("", " "),
    ))
    assertTrue(blankOnly.contains("\"isError\":true"), "a blank context_iri entry must be refused: $blankOnly")
    assertFalse(
      blankOnly.contains(eventA.toString()) || blankOnly.contains(eventB.toString()),
      "a refused downscope must not answer with data: $blankOnly",
    )
  }

  @Test
  fun `sparql tools with no readable contexts return empty results`() {
    val pod = sempodsTestFactory.newPod()
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "empty-scope-${TestUtil.randomId()}",
    )

    val tokenWithoutReadableContexts = mintScopedToken(pod.name, emptyList())
    val graph = toolCall(pod.name, tokenWithoutReadableContexts, "sparql_graph", mapOf(
      "query" to "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }",
    ))
    assertFalse(graph.contains(eventUri.toString()), "empty effective scope must not leak public event: $graph")

    val ask = toolCall(pod.name, tokenWithoutReadableContexts, "sparql_select", mapOf(
      "query" to "ASK { ?s ?p ?o }",
    ))
    assertTrue(ask.contains("\\\"boolean\\\":false"), "ASK must be false for empty scope: $ask")

    val dataIndependentAsk = toolCall(pod.name, tokenWithoutReadableContexts, "sparql_select", mapOf(
      "query" to "ASK WHERE { BIND(1 AS ?x) }",
    ))
    assertTrue(
      dataIndependentAsk.contains("\\\"boolean\\\":true"),
      "Data-independent ASK must still be evaluated against an empty store: $dataIndependentAsk",
    )
  }

  @Test
  fun `initialize response should include auth and write instructions`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 100,
      "method" to "initialize",
      "params" to mapOf(
        "protocolVersion" to "2024-11-05",
        "capabilities" to mapOf<String, Any>(),
        "clientInfo" to mapOf("name" to "test-client", "version" to "1.0.0"),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("instructions"), "TestHttpResponse should contain instructions field")
    assertTrue(body.contains("oauth-protected-resource"), "Instructions should reference .well-known metadata")
    assertTrue(body.contains("create_resource"), "Instructions should describe write tools")
  }

  @Test
  fun `tools list should expose read and write tools`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 101,
      "method" to "tools/list",
      "params" to mapOf<String, Any>(),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("\"sparql_select\""), "sparql_select tool should be listed")
    assertTrue(body.contains("\"sparql_graph\""), "sparql_graph tool should be listed")
    assertTrue(body.contains("\"create_resource\""), "create_resource tool should be listed")
    assertTrue(body.contains("\"update_resource\""), "update_resource tool should be listed")
    assertTrue(body.contains("\"delete_resource\""), "delete_resource tool should be listed")
    assertTrue(body.contains("\"add_property_value\""), "add_property_value tool should be listed")
    assertTrue(body.contains("\"set_property_values\""), "set_property_values tool should be listed")
    assertTrue(body.contains("\"remove_property_value\""), "remove_property_value tool should be listed")
    assertTrue(body.contains("\"clear_property_values\""), "clear_property_values tool should be listed")
    assertTrue(body.contains("\"find\""), "find tool should be listed")
  }

  @Test
  fun `find tool returns matching resources as JSON-LD`() {
    val pod = sempodsTestFactory.newPod()
    val needle = TestUtil.randomId()
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)

    val matchUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = matchUri,
      context = publicContext,
      name = "Findable $needle",
    )

    val otherUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = otherUri,
      context = publicContext,
      name = "Unrelated event",
    )

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 7,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "find",
        "arguments" to mapOf("text" to needle),
      ),
    )
    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains(matchUri.toString()), "find result should contain the matching resource: $body")
    assertFalse(body.contains(otherUri.toString()), "find result should not contain the non-matching resource")
  }

  @Test
  fun `find tool rejects a malformed type argument fail-closed`() {
    val pod = sempodsTestFactory.newPod()

    fun findWithType(typeArg: Any): String {
      val request = mapOf(
        "jsonrpc" to "2.0",
        "id" to 8,
        "method" to "tools/call",
        "params" to mapOf(
          "name" to "find",
          "arguments" to mapOf("text" to "anything", "type" to typeArg),
        ),
      )
      val response = httpClient.preparePost(mcpUrl(pod.name))
        .addHeader("Content-Type", "application/json")
        .setBody(objectMapper.writeValueAsString(request))
        .execute()
      assertEquals(200, response.statusCode)
      return response.responseBody
    }

    // `type` as a bare string instead of an array → must NOT silently become an unfiltered search.
    assertTrue(
      findWithType("https://schema.org/Event").contains("\"isError\":true"),
      "type as a string must be a tool error",
    )
    // `type` array with a non-string element → must NOT partially drop.
    assertTrue(
      findWithType(listOf("https://schema.org/Event", 5)).contains("\"isError\":true"),
      "type array with a non-string must be a tool error",
    )
  }

  @Test
  fun `tool calls reject unknown arguments fail-closed`() {
    val pod = sempodsTestFactory.newPod()

    fun call(name: String, arguments: Map<String, Any>): String {
      val request = mapOf(
        "jsonrpc" to "2.0", "id" to 11, "method" to "tools/call",
        "params" to mapOf("name" to name, "arguments" to arguments),
      )
      return httpClient.preparePost(mcpUrl(pod.name))
        .addHeader("Content-Type", "application/json")
        .setBody(objectMapper.writeValueAsString(request))
        .execute()
        .also { assertEquals(200, it.statusCode) }
        .responseBody
    }

    // The deferred general `filter` on find: unknown field → tool error, never a silently broadened
    // (unfiltered) search. Mirrors the strict REST `POST /_system/find` body parsing.
    assertTrue(
      call("find", mapOf("text" to "anything", "filter" to mapOf("author" to "x"))).contains("\"isError\":true"),
      "find with an unknown 'filter' must be a tool error",
    )
    // A typo'd known field (include_contexts) is also unknown → rejected, not silently ignored.
    assertTrue(
      call("find", mapOf("text" to "anything", "includecontexts" to true)).contains("\"isError\":true"),
      "find with a typo'd 'includecontexts' must be a tool error",
    )
    // A declared field (include_contexts) must still be accepted by the strict check.
    assertFalse(
      call("find", mapOf("text" to "anything", "include_contexts" to true)).contains("\"isError\":true"),
      "find with the declared 'include_contexts' must NOT error",
    )
    // Breadth: a write tool's hallucinated extra field is likewise rejected (the schema claims it).
    assertTrue(
      call("sparql_select", mapOf("query" to "SELECT * WHERE { ?s ?p ?o } LIMIT 1", "bogus" to 1))
        .contains("\"isError\":true"),
      "sparql_select with an unknown field must be a tool error",
    )
  }

  @Test
  fun `find tool rejects wrong-typed include_contexts and limit`() {
    val pod = sempodsTestFactory.newPod()

    fun call(arguments: Map<String, Any>): String {
      val request = mapOf(
        "jsonrpc" to "2.0", "id" to 12, "method" to "tools/call",
        "params" to mapOf("name" to "find", "arguments" to arguments),
      )
      return httpClient.preparePost(mcpUrl(pod.name))
        .addHeader("Content-Type", "application/json")
        .setBody(objectMapper.writeValueAsString(request))
        .execute()
        .also { assertEquals(200, it.statusCode) }
        .responseBody
    }

    // `include_contexts` as a string instead of boolean → tool error, NOT a silent flat response.
    assertTrue(
      call(mapOf("text" to "x", "include_contexts" to "true")).contains("\"isError\":true"),
      "include_contexts as a string must be a tool error",
    )
    // `limit` as a string instead of a number → tool error, NOT a silent fallback to default 10.
    assertTrue(
      call(mapOf("text" to "x", "limit" to "1")).contains("\"isError\":true"),
      "limit as a string must be a tool error",
    )
    // Correct types still succeed.
    assertFalse(
      call(mapOf("text" to "x", "include_contexts" to true, "limit" to 5)).contains("\"isError\":true"),
      "correctly typed include_contexts/limit must NOT error",
    )
  }

  @Test
  fun `find tool rejects a malformed context_iri argument fail-closed`() {
    val pod = sempodsTestFactory.newPod()

    fun findWithContext(contextArg: Any): String = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(mapOf(
        "jsonrpc" to "2.0", "id" to 9, "method" to "tools/call",
        "params" to mapOf("name" to "find", "arguments" to mapOf("text" to "anything", "context_iri" to contextArg)),
      )))
      .execute()
      .also { assertEquals(200, it.statusCode) }
      .responseBody

    // A bare string instead of an array → must NOT silently broaden to a pod-wide search.
    assertTrue(
      findWithContext("https://example.org/c").contains("\"isError\":true"),
      "context_iri as a string must be a tool error",
    )
    // An array with a non-string element → must NOT partially drop.
    assertTrue(
      findWithContext(listOf("https://example.org/c", 5)).contains("\"isError\":true"),
      "context_iri array with a non-string must be a tool error",
    )
  }

  @Test
  fun `find tool context_iri downscopes within readable contexts`() {
    val pod = sempodsTestFactory.newPod()
    val needle = TestUtil.randomId()

    val contextA = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-a")
    val contextB = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-b")
    listOf(contextA, contextB).forEach {
      podContextsDao.create(
        podId = checkNotNull(pod.id), contextUri = it.toString(),
        label = null, description = null, createdBy = "test",
      )
    }
    fun newEvent(context: URI, name: String): URI {
      val uri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
      sempodsTestFactory.seedEvent(
        pod = pod.name,
        eventUri = uri,
        context = context,
        name = name,
      )
      return uri
    }
    val eventA = newEvent(contextA, "Findable $needle A")
    val eventB = newEvent(contextB, "Findable $needle B")

    val token = mintScopedToken(pod.name, listOf("${contextA}#read", "${contextB}#read"))

    // No downscope → both readable contexts.
    val all = toolCall(pod.name, token, "find", mapOf("text" to needle))
    assertTrue(all.contains(eventA.toString()) && all.contains(eventB.toString()), "both hits without downscope: $all")

    // Downscope to A → only A's hit.
    val onlyA = toolCall(pod.name, token, "find", mapOf("text" to needle, "context_iri" to listOf(contextA.toString())))
    assertTrue(onlyA.contains(eventA.toString()), "downscope to A must keep A: $onlyA")
    assertFalse(onlyA.contains(eventB.toString()), "downscope to A must drop B: $onlyA")

    // Unknown/unreadable context → silently excluded → empty result, not an error.
    val none = toolCall(pod.name, token, "find", mapOf(
      "text" to needle,
      "context_iri" to listOf("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-nope"),
    ))
    assertFalse(none.contains("\"isError\":true"), "all-unreadable downscope is not an error: $none")
    assertFalse(none.contains(eventA.toString()) || none.contains(eventB.toString()), "no hits for all-unreadable downscope: $none")

    // Refused, for the reason the shared catalog gives: a dropped filter fails open, so a blank
    // entry is a client bug worth saying out loud rather than quietly reading as "match nothing".
    val blankOnly = toolCall(pod.name, token, "find", mapOf(
      "text" to needle,
      "context_iri" to listOf("", " "),
    ))
    assertTrue(blankOnly.contains("\"isError\":true"), "a blank context_iri entry must be refused: $blankOnly")
    assertFalse(
      blankOnly.contains(eventA.toString()) || blankOnly.contains(eventB.toString()),
      "a refused downscope must not answer with data: $blankOnly",
    )
  }

  @Test
  fun `find tool include_contexts returns named-graph provenance`() {
    val pod = sempodsTestFactory.newPod()
    val needle = TestUtil.randomId()

    val contextA = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/inc-a")
    val contextB = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/inc-b")
    listOf(contextA, contextB).forEach {
      podContextsDao.create(
        podId = checkNotNull(pod.id), contextUri = it.toString(),
        label = null, description = null, createdBy = "test",
      )
    }
    fun newEvent(context: URI, name: String): URI {
      val uri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
      sempodsTestFactory.seedEvent(
        pod = pod.name,
        eventUri = uri,
        context = context,
        name = name,
      )
      return uri
    }
    val eventA = newEvent(contextA, "Findable $needle A")
    val eventB = newEvent(contextB, "Findable $needle B")
    val token = mintScopedToken(pod.name, listOf("${contextA}#read", "${contextB}#read"))

    // include_contexts=true → the JSON-LD payload names each hit's source graph (@id = context).
    val withCtx = toolCall(pod.name, token, "find", mapOf("text" to needle, "include_contexts" to true))
    assertTrue(withCtx.contains(eventA.toString()) && withCtx.contains(eventB.toString()), "both hits: $withCtx")
    assertTrue(withCtx.contains(contextA.toString()), "named graph A must be present: $withCtx")
    assertTrue(withCtx.contains(contextB.toString()), "named graph B must be present: $withCtx")

    // Default (no include_contexts) → flat: hits present, no context graph labels.
    val flat = toolCall(pod.name, token, "find", mapOf("text" to needle))
    assertTrue(flat.contains(eventA.toString()) && flat.contains(eventB.toString()), "both hits flat: $flat")
    assertFalse(flat.contains(contextA.toString()), "flat form must not surface context A: $flat")
    assertFalse(flat.contains(contextB.toString()), "flat form must not surface context B: $flat")
  }

  // ─── Property-value tools (LOD-CRUD System-layer slot CRUD over MCP) ──────

  private fun toolCall(podName: String, token: String, toolName: String, arguments: Map<String, Any>): String {
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 500,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to toolName,
        "arguments" to arguments,
      ),
    )
    val response = httpClient.preparePost(mcpUrl(podName))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()
    assertEquals(200, response.statusCode)
    return response.responseBody
  }

  @Test
  fun `add_property_value adds an IRI value to a slot via MCP`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    val responseBody = toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    assertFalse(responseBody.contains("\"isError\":true"), "must succeed: $responseBody")
    assertTrue(responseBody.contains("created"), "must report created outcome: $responseBody")
  }

  @Test
  fun `add_property_value twice on same value is a no-op (already_present)`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    val args = mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    )
    toolCall(pod.name, token, "add_property_value", args)
    val second = toolCall(pod.name, token, "add_property_value", args)
    assertTrue(second.contains("already_present"), "duplicate must collapse: $second")
  }

  @Test
  fun `set_property_values replaces the slot wholesale`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val dave = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/dave"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    val setResponse = toolCall(pod.name, token, "set_property_values", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "values" to listOf(mapOf("@id" to dave)),
    ))
    assertFalse(setResponse.contains("\"isError\":true"), "must succeed: $setResponse")

    // Asserted on the slot rather than on a count in the result. The result used to echo
    // `values.size` back, which said nothing the caller did not already know; what "wholesale"
    // means is that the value that was there is gone.
    val after = objectMapper.writeValueAsString(
      toolPayload(toolCall(pod.name, token, "get_property_values", mapOf(
        "subject_iri" to bob,
        "predicate_iri" to "https://schema.org/children",
        "context_iri" to listOf(contextUri.toString()),
      )))["values"],
    )
    assertTrue(after.contains(dave), "the new value must be there: $after")
    assertFalse(after.contains(carol), "the replaced value must be gone: $after")
  }

  @Test
  fun `remove_property_value drops one IRI edge via MCP`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    val removeResponse = toolCall(pod.name, token, "remove_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "target_iri" to carol,
    ))
    assertFalse(removeResponse.contains("\"isError\":true"), "must succeed: $removeResponse")
    assertTrue(removeResponse.contains("\\\"outcome\\\":\\\"removed\\\""), "must report removed: $removeResponse")
  }

  @Test
  fun `remove_property_value on already-absent edge is idempotent (already_absent)`() {
    // The single-edge route is `SPS-CRUD-042`; that removal is idempotent and answers
    // `already_absent` is `SPS-CRUD-044`. MCP mirrors that word on the second call —
    // no isError, so agents can lean on this for "ensure this triple does not exist".
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    val args = mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "target_iri" to carol,
    )
    val first = toolCall(pod.name, token, "remove_property_value", args)
    val second = toolCall(pod.name, token, "remove_property_value", args)

    assertFalse(first.contains("\"isError\":true"), "first remove must succeed: $first")
    assertTrue(first.contains("\\\"outcome\\\":\\\"removed\\\""), "first must report removed: $first")
    assertFalse(second.contains("\"isError\":true"), "second remove must succeed (idempotent): $second")
    assertTrue(second.contains("\\\"outcome\\\":\\\"already_absent\\\""), "second must report already_absent: $second")
  }

  @Test
  fun `clear_property_values empties the slot via MCP`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    val clearResponse = toolCall(pod.name, token, "clear_property_values", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
    ))
    assertFalse(clearResponse.contains("\"isError\":true"), "must succeed: $clearResponse")
    assertTrue(clearResponse.contains("\\\"outcome\\\":\\\"cleared\\\""), "must report cleared: $clearResponse")
  }

  @Test
  fun `clear_property_values on already-empty slot is idempotent (already_empty)`() {
    // Whole-slot clear must be idempotent too — calling it twice succeeds, with the
    // second call reporting outcome=already_empty.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    val args = mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
    )
    val first = toolCall(pod.name, token, "clear_property_values", args)
    val second = toolCall(pod.name, token, "clear_property_values", args)

    assertFalse(first.contains("\"isError\":true"), "first clear must succeed: $first")
    assertTrue(first.contains("\\\"outcome\\\":\\\"cleared\\\""), "first must report cleared: $first")
    assertFalse(second.contains("\"isError\":true"), "second clear must succeed (idempotent): $second")
    assertTrue(second.contains("\\\"outcome\\\":\\\"already_empty\\\""), "second must report already_empty: $second")
  }

  @Test
  fun `add_property_value without write scope returns tool error`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, _) = createContextWithToken(pod, "contacts")
    val readOnlyToken = mintScopedToken(pod.name, listOf("${contextUri}#read"))
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    val responseBody = toolCall(pod.name, readOnlyToken, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    assertTrue(responseBody.contains("\"isError\":true"), "must surface as MCP tool error: $responseBody")
    assertTrue(responseBody.contains("403"), "must mention 403 status: $responseBody")
  }

  @Test
  fun `property-value tools accept external DID subjects`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bobDid = "did:web:bob.example"
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"

    val responseBody = toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bobDid,
      "predicate_iri" to "http://xmlns.com/foaf/0.1/knows",
      "value" to mapOf("@id" to bob),
    ))
    assertFalse(responseBody.contains("\"isError\":true"), "must accept external IRI subject: $responseBody")
  }

  // ─── Whole-resource tools accept external IRIs (LOD-CRUD dynamic resources, M3) ───

  @Test
  fun `create update delete whole-resource cycle on an external IRI via MCP`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"

    val created = toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("@id" to alice, "https://schema.org/name" to "Alice"),
    ))
    assertFalse(created.contains("\"isError\":true"), "create on external IRI must succeed: $created")

    // Verify persistence via SPARQL (the only read path over MCP).
    val afterCreate = sparqlDescribe(pod.name, token, alice)
    assertTrue(afterCreate.contains("Alice"), "external resource must be stored: $afterCreate")

    val updated = toolCall(pod.name, token, "update_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld_patch" to mapOf("https://schema.org/jobTitle" to listOf(mapOf("@value" to "Engineer"))),
    ))
    assertFalse(updated.contains("\"isError\":true"), "merge-patch on external IRI must succeed: $updated")

    val afterPatch = sparqlDescribe(pod.name, token, alice)
    assertTrue(afterPatch.contains("Alice"), "name must survive the merge-patch: $afterPatch")
    assertTrue(afterPatch.contains("Engineer"), "patched property must be present: $afterPatch")

    val deleted = toolCall(pod.name, token, "delete_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
    ))
    assertFalse(deleted.contains("\"isError\":true"), "delete on external IRI must succeed: $deleted")

    val afterDelete = sparqlDescribe(pod.name, token, alice)
    assertFalse(afterDelete.contains("Alice"), "external resource must be gone after delete: $afterDelete")
  }

  @Test
  fun `create_resource still works on a pod-internal resource`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val thing = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/thing1"

    val created = toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to thing,
      "jsonld" to mapOf("@id" to thing, "https://schema.org/name" to "Thing"),
    ))
    assertFalse(created.contains("\"isError\":true"), "pod-internal create must still succeed: $created")
  }

  @Test
  fun `create_resource may describe a control-plane IRI like any other`() {
    // An agent may say things about a context — those are claims in the caller's own context and
    // cannot alter control-plane state, which lives in MongoDB rather than in the graph. The
    // authoritative answer for the IRI still comes from the control plane itself.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    // Subject inside the pod's reserved area — what `parseResourceUriOrThrow` used to refuse.
    // The write context is an ordinary one, so only the subject exercises the change.
    val systemIri = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/apps/example/tasks"

    val response = toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to systemIri,
      "jsonld" to mapOf("@id" to systemIri, "https://schema.org/name" to "an agent's note"),
    ))
    assertFalse(response.contains("\"isError\":true"), "describing a control-plane IRI must succeed: $response")
  }

  @Test
  fun `update_resource on external IRI rejects a stale if_match`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val carol = "https://example.org/people/carol"

    toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to carol,
      "jsonld" to mapOf("@id" to carol, "https://schema.org/name" to "Carol"),
    ))
    // "0-jsonld" is the not-yet-exists tag; the resource now exists, so it must mismatch.
    val stale = toolCall(pod.name, token, "update_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to carol,
      "jsonld_patch" to mapOf("https://schema.org/jobTitle" to listOf(mapOf("@value" to "Pilot"))),
      "if_match" to "0-jsonld",
    ))
    assertTrue(stale.contains("\"isError\":true"), "stale if_match must be rejected: $stale")
  }

  private fun sparqlDescribe(podName: String, token: String, resourceIri: String): String =
    toolCall(podName, token, "sparql_graph", mapOf(
      "query" to "CONSTRUCT { <$resourceIri> ?p ?o } WHERE { <$resourceIri> ?p ?o }",
    ))

  // ─── Read tools + conditional-write sources (get_resource / get_property_values / etag) ───

  /** Unwrap the JSON-RPC envelope → tool result → embedded JSON payload object. */
  // ─── M4: the tools run over this pod's own HTTP surface ──────────────────

  @Test
  fun `a tool call carries the caller's bearer over the wire, not the caller's credentials object`() {
    // The delegation forwards the raw bearer and lets the pod authenticate it again. If it forwarded
    // nothing, this write would come back as an anonymous 401 rather than a result; if it forwarded
    // the wrong one, the per-context scope check would refuse it. Either way the assertion below
    // fails — which is what makes this a test of the hop and not of the write.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"

    val created = toolPayload(toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("https://schema.org/name" to "Alice"),
    )))

    // `status` is the pod's HTTP status, which only exists because there was an HTTP response.
    assertEquals(201, created["status"], "a create over the wire answers 201: $created")

    // And the write really landed, read back through the same hop.
    val read = toolPayload(toolCall(pod.name, token, "get_resource", mapOf("resource_iri" to alice)))
    assertTrue(objectMapper.writeValueAsString(read["jsonld"]).contains("Alice"), "the write must be visible: $read")
  }

  @Test
  fun `create_resource without an id in the body still writes the addressed resource`() {
    // A JSON-LD body with no `@id` expands to a blank node, and the write path refuses blank nodes
    // with a 400 about RDF. It is the most common shape a model produces, so the tool sets `@id`
    // from `resource_iri` — which only matters now that the body travels over the wire verbatim.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "https://example.org/people/bob"

    val created = toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to bob,
      "jsonld" to mapOf("https://schema.org/name" to "Bob"),
    ))
    assertFalse(created.contains("\"isError\":true"), "an @id-less body must still be written: $created")

    val read = toolPayload(toolCall(pod.name, token, "get_resource", mapOf("resource_iri" to bob)))
    assertEquals(bob, read["resource_iri"])
  }

  @Test
  fun `a pod refusal becomes a tool error carrying the pod's own words, not a JSON-RPC internal error`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")

    val response = toolCall(pod.name, token, "update_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to "https://example.org/people/nobody",
      "jsonld_patch" to mapOf("https://schema.org/name" to "Nobody"),
    ))

    assertTrue(response.contains("\"isError\":true"), "a 404 from the pod is a tool error: $response")
    assertFalse(response.contains("-32603"), "and never the generic internal-error code: $response")
    // The pod's message survives the hop, unwrapped from its `{"errors":[…]}` envelope — including
    // the sentence that says which tool to use instead.
    assertTrue(response.contains("create_resource"), "the pod's hint must reach the model: $response")
    assertFalse(response.contains("_system/resources/"), "the self-call URL must not reach the model: $response")
  }

  @Test
  fun `initialize builds its instructions from the context listing over the wire`() {
    // `initialize` now makes a self-call for the context block, so the handshake has a dependency it
    // did not have before. This covers the empty end of it: a pod with no contexts at all answers,
    // and the text still points at `list_contexts`.
    //
    // The other end — the self-call *failing* — is caught and rendered as the same "could not be
    // listed, call `list_contexts`" block, deliberately, so that a pod the server cannot reach costs
    // the session its instructions and not the session itself. That branch is not exercised here:
    // making it fire needs an unreachable `apiBaseUrl`, and this suite's server is reachable by
    // construction.
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 1,
      "method" to "initialize",
      "params" to mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>()),
    )
    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    assertTrue(response.responseBody.contains("instructions"), "initialize must still carry instructions")
    assertTrue(
      response.responseBody.contains("list_contexts"),
      "the text must point at the authoritative tool: ${response.responseBody}",
    )
  }

  private fun toolPayload(responseBody: String): Map<*, *> {
    val envelope = objectMapper.readValue(responseBody, Map::class.java)
    val result = envelope["result"] as Map<*, *>
    val text = ((result["content"] as List<*>).first() as Map<*, *>)["text"] as String
    return objectMapper.readValue(text, Map::class.java)
  }

  @Test
  fun `get_resource returns canonical JSON-LD and an etag that drives update_resource if_match`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"

    toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("@id" to alice, "https://schema.org/name" to "Alice"),
    ))

    val payload = toolPayload(toolCall(pod.name, token, "get_resource", mapOf("resource_iri" to alice)))
    assertEquals(alice, payload["resource_iri"])
    val etag = assertNotNull(payload["etag"] as? String, "get_resource must return an etag")
    assertTrue(objectMapper.writeValueAsString(payload["jsonld"]).contains("Alice"), "jsonld must carry the data")

    // The returned etag must satisfy update_resource's if_match.
    val ok = toolCall(pod.name, token, "update_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld_patch" to mapOf("https://schema.org/jobTitle" to listOf(mapOf("@value" to "Engineer"))),
      "if_match" to etag,
    ))
    assertFalse(ok.contains("\"isError\":true"), "matching if_match must succeed: $ok")

    // A stale tag must now be rejected (resource changed).
    val stale = toolCall(pod.name, token, "update_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld_patch" to mapOf("https://schema.org/jobTitle" to listOf(mapOf("@value" to "Pilot"))),
      "if_match" to etag,
    ))
    assertTrue(stale.contains("\"isError\":true"), "stale if_match must be rejected: $stale")
  }

  @Test
  fun `get_resource with include_contexts returns the named-graph form`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"
    toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("@id" to alice, "https://schema.org/name" to "Alice"),
    ))

    val payload = toolPayload(toolCall(pod.name, token, "get_resource", mapOf(
      "resource_iri" to alice,
      "include_contexts" to true,
    )))
    assertTrue(
      objectMapper.writeValueAsString(payload["jsonld"]).contains(contextUri.toString()),
      "named-graph form must name the source context: $payload",
    )
  }

  @Test
  fun `get_resource on a missing resource returns a tool error`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "contacts")
    val response = toolCall(pod.name, token, "get_resource", mapOf(
      "resource_iri" to "https://example.org/people/nobody",
    ))
    assertTrue(response.contains("\"isError\":true"), "missing resource must surface as tool error: $response")
  }

  @Test
  fun `get_resource on a control-plane IRI returns the claims, not the control plane`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val systemIri = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/apps/example/tasks"
    toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to systemIri,
      "jsonld" to mapOf("@id" to systemIri, "https://schema.org/name" to "an agent's note"),
    ))

    val response = toolCall(pod.name, token, "get_resource", mapOf(
      "resource_iri" to systemIri,
    ))
    assertFalse(response.contains("\"isError\":true"), "reading claims about a control-plane IRI must work: $response")
    assertTrue(response.contains("an agent's note"), response)
  }

  @Test
  fun `get_resource rejects malformed context_iri fail-closed`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "contacts")
    val response = toolCall(pod.name, token, "get_resource", mapOf(
      "resource_iri" to "https://example.org/people/alice",
      "context_iri" to listOf("https://example.org/context", 5),
    ))
    assertTrue(response.contains("\"isError\":true"), "context_iri array with non-string must be rejected: $response")
    assertTrue(response.contains("context_iri"), "error should name context_iri: $response")
  }

  @Test
  fun `get_resource context_iri array downscopes and blank-only array matches nothing`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"

    toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("@id" to alice, "https://schema.org/name" to "Alice"),
    ))

    val visible = toolCall(pod.name, token, "get_resource", mapOf(
      "resource_iri" to alice,
      "context_iri" to listOf(contextUri.toString()),
    ))
    assertFalse(visible.contains("\"isError\":true"), "array context_iri should be accepted: $visible")
    assertTrue(visible.contains("Alice"), "downscoped read should return the resource: $visible")

    val blankOnly = toolCall(pod.name, token, "get_resource", mapOf(
      "resource_iri" to alice,
      "context_iri" to listOf("", " "),
    ))
    assertTrue(blankOnly.contains("\"isError\":true"), "blank-only downscope must not broaden to visible resource: $blankOnly")
  }

  @Test
  fun `write tools return an etag in their result`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"

    val created = toolPayload(toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("@id" to alice, "https://schema.org/name" to "Alice"),
    )))
    assertNotNull(created["etag"] as? String, "create_resource result must carry an etag")
  }

  @Test
  fun `create_resource with if_none_match star is create-or-fail`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"
    val args = mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("@id" to alice, "https://schema.org/name" to "Alice"),
      "if_none_match" to "*",
    )
    val first = toolCall(pod.name, token, "create_resource", args)
    assertFalse(first.contains("\"isError\":true"), "first create-or-fail must succeed: $first")
    val second = toolCall(pod.name, token, "create_resource", args)
    assertTrue(second.contains("\"isError\":true"), "second create-or-fail must fail (exists): $second")
  }

  @Test
  fun `get_property_values returns slot values and a single-context etag for if_match`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val children = "https://schema.org/children"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to children,
      "value" to mapOf("@id" to carol),
    ))

    val payload = toolPayload(toolCall(pod.name, token, "get_property_values", mapOf(
      "subject_iri" to bob,
      "predicate_iri" to children,
      "context_iri" to listOf(contextUri.toString()),
    )))
    assertTrue(objectMapper.writeValueAsString(payload["values"]).contains(carol), "values must list the child: $payload")
    val slotEtag = assertNotNull(payload["etag"] as? String, "single-context read must return a slot etag")

    val ok = toolCall(pod.name, token, "set_property_values", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to children,
      "values" to listOf(mapOf("@id" to carol)),
      "if_match" to slotEtag,
    ))
    assertFalse(ok.contains("\"isError\":true"), "slot etag must satisfy if_match: $ok")
  }

  @Test
  fun `get_property_values rejects malformed context_iri fail-closed`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "contacts")
    val response = toolCall(pod.name, token, "get_property_values", mapOf(
      "subject_iri" to "https://example.org/people/alice",
      "predicate_iri" to "https://schema.org/name",
      "context_iri" to listOf("https://example.org/context", 5),
    ))
    assertTrue(response.contains("\"isError\":true"), "context_iri array with non-string must be rejected: $response")
    assertTrue(response.contains("context_iri"), "error should name context_iri: $response")
  }

  @Test
  fun `get_property_values context_iri array downscopes and blank-only array matches nothing`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"
    val name = "https://schema.org/name"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to alice,
      "predicate_iri" to name,
      "value" to mapOf("@value" to "Alice"),
    ))

    val visible = toolPayload(toolCall(pod.name, token, "get_property_values", mapOf(
      "subject_iri" to alice,
      "predicate_iri" to name,
      "context_iri" to listOf(contextUri.toString()),
    )))
    assertTrue(objectMapper.writeValueAsString(visible["values"]).contains("Alice"), "array context_iri should return values: $visible")
    assertNotNull(visible["etag"] as? String, "single-context array read should return etag")

    // A blank entry is refused rather than treated as "match nothing". The shared catalog's rule,
    // and the safe one: the danger a downscope filter carries is being *dropped*, which fails open
    // and widens the read to every readable context. Refusing says so; silently matching nothing
    // would let a client keep sending a filter that never did what it thought it did.
    val blankOnly = toolCall(pod.name, token, "get_property_values", mapOf(
      "subject_iri" to alice,
      "predicate_iri" to name,
      "context_iri" to listOf("", " "),
    ))
    assertTrue(blankOnly.contains("\"isError\":true"), "a blank context_iri entry must be refused: $blankOnly")
    assertTrue(blankOnly.contains("context_iri"), "the refusal should name the argument: $blankOnly")
    assertFalse(blankOnly.contains("Alice"), "a refused downscope must not answer with the slot: $blankOnly")
  }

  @Test
  fun `get_property_values does not emit an etag for an empty slot`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"

    val payload = toolPayload(toolCall(pod.name, token, "get_property_values", mapOf(
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "context_iri" to listOf(contextUri.toString()),
    )))
    assertTrue((payload["values"] as List<*>).isEmpty(), "empty slot must report no values: $payload")
    assertFalse(payload.containsKey("etag"), "no etag may be emitted for an empty slot: $payload")
  }

  @Test
  fun `get_property_values does not leak a slot etag for an unreadable context`() {
    val pod = sempodsTestFactory.newPod()
    val (privatUri, privatToken) = createContextWithToken(pod, "contacts")
    val (_, otherToken) = createContextWithToken(pod, "other")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    // Write into 'privat'.
    toolCall(pod.name, privatToken, "add_property_value", mapOf(
      "context_iri" to privatUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))

    // A caller scoped only to 'other' asks for the 'privat' slot: it must see nothing AND
    // get no ETag (the tag derives from the global resource validator — leaking it would
    // expose hidden subject existence/change state).
    val payload = toolPayload(toolCall(pod.name, otherToken, "get_property_values", mapOf(
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "context_iri" to listOf(privatUri.toString()),
    )))
    assertTrue((payload["values"] as List<*>).isEmpty(), "unreadable context must yield no values: $payload")
    assertFalse(payload.containsKey("etag"), "no etag may leak for an unreadable context: $payload")
  }

  @Test
  fun `delete_resource honors if_match`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"
    toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("@id" to alice, "https://schema.org/name" to "Alice"),
    ))
    val etag = assertNotNull(
      toolPayload(toolCall(pod.name, token, "get_resource", mapOf("resource_iri" to alice)))["etag"] as? String,
    )

    // Stale tag → rejected.
    val stale = toolCall(pod.name, token, "delete_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "if_match" to "0-jsonld",
    ))
    assertTrue(stale.contains("\"isError\":true"), "stale if_match must block the delete: $stale")

    // Correct tag → deletes.
    val ok = toolCall(pod.name, token, "delete_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "if_match" to etag,
    ))
    assertFalse(ok.contains("\"isError\":true"), "matching if_match must allow the delete: $ok")
    assertTrue(
      toolCall(pod.name, token, "get_resource", mapOf("resource_iri" to alice)).contains("\"isError\":true"),
      "resource must be gone after conditional delete",
    )
  }

  @Test
  fun `get_property_values with a malformed context_iri returns a tool error not a crash`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"

    val response = toolCall(pod.name, token, "get_property_values", mapOf(
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "context_iri" to listOf("not a valid iri"),
    ))
    assertTrue(response.contains("\"isError\":true"), "malformed context_iri must be a tool error: $response")
    assertFalse(response.contains("-32603"), "must not escape as a JSON-RPC internal error: $response")
  }

  @Test
  fun `get_resource etag is the write-precondition tag regardless of include_contexts`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val alice = "https://example.org/people/alice"
    toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to alice,
      "jsonld" to mapOf("@id" to alice, "https://schema.org/name" to "Alice"),
    ))

    val canonical = toolPayload(toolCall(pod.name, token, "get_resource", mapOf("resource_iri" to alice)))["etag"]
    val withContexts = toolPayload(toolCall(pod.name, token, "get_resource", mapOf(
      "resource_iri" to alice,
      "include_contexts" to true,
    )))["etag"]
    assertEquals(canonical, withContexts, "etag must be the same write-precondition tag for both representations")
  }

  // ─── R4-spike-followup: discovery stubs + _meta redaction ─────────────────

  @Test
  fun `resources_list returns an empty list instead of method-not-found`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 200,
      "method" to "resources/list",
      "params" to mapOf<String, Any>(),
    )
    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("\"resources\":[]"), "Must return empty resources list: $body")
    assertFalse(body.contains("\"error\""), "Must not be a JSON-RPC error: $body")
  }

  @Test
  fun `prompts_list returns an empty list instead of method-not-found`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 201,
      "method" to "prompts/list",
      "params" to mapOf<String, Any>(),
    )
    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("\"prompts\":[]"), "Must return empty prompts list: $body")
    assertFalse(body.contains("\"error\""), "Must not be a JSON-RPC error: $body")
  }

  // ─── R4 Phase A — synthetic authorize-tool ────────────────────────────────

  @Test
  fun `tools list exposes authorize tool to anonymous callers`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 110,
      "method" to "tools/list",
      "params" to mapOf<String, Any>(),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("\"authorize\""), "authorize tool must be in tools/list: $body")
    assertTrue(
      body.contains("You are NOT authorized"),
      "anonymous tools/list must surface the not-authorized hint in the description: $body",
    )
  }

  @Test
  fun `tools list exposes authorize tool to authorized callers as idempotent`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "main-${TestUtil.randomId()}")

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 111,
      "method" to "tools/list",
      "params" to mapOf<String, Any>(),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("\"authorize\""), "authorize tool must remain in tools/list when authorized: $body")
    assertTrue(
      body.contains("You currently hold an authorized session"),
      "authorized tools/list must reflect the active session in the description: $body",
    )
  }

  @Test
  fun `tools call authorize as anonymous returns 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 112,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf<String, Any>(),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 must carry a WWW-Authenticate header")
    assertTrue(authHeader.startsWith("Bearer "), "challenge must be a Bearer challenge, was: $authHeader")
    assertTrue(
      authHeader.contains("/.well-known/oauth-protected-resource"),
      "challenge must point at the PRM URL: $authHeader",
    )
  }

  @Test
  fun `tools call authorize as public-read bearer triggers upgrade with 401`() {
    // R4 Phase A regression: a public-read bearer carries a clientId but
    // no context-scoped grants. Calling `authorize` must take the upgrade
    // path (401 + WWW-Authenticate, audited as `auth_trigger`), not the
    // no-op success path.
    val pod = sempodsTestFactory.newPod()
    val publicReadToken = mintScopedToken(
      podName = pod.name,
      scopes = listOf("public-read"),
      webId = "https://id.test/visitor",
    )

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 114,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf<String, Any>(),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $publicReadToken")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 must carry WWW-Authenticate")
    assertTrue(authHeader.startsWith("Bearer "))
    // Body must use the upgrade-specific message (distinguishes auth_trigger from invalid_bearer).
    val body = response.responseBody
    assertTrue(
      body.contains("OAuth upgrade required"),
      "public-read upgrade response must use upgrade-specific error message: $body",
    )
  }

  @Test
  fun `tools call authorize with bearer whose grants were revoked triggers upgrade with 401`() {
    // Slim access tokens prove client+subject, but context permissions are resolved from
    // PodGrantsDao per request. If the durable grants disappear after token issue, the bearer is
    // still cryptographically valid but no longer authorizes any context. `authorize` must start
    // OAuth again instead of returning the idempotent authorized=true response.
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "main-${TestUtil.randomId()}")
    val webId = "https://id.test/user"
    val clientId = "did:web:test.example"
    podGrantsDao.replaceGrants(
      podId = checkNotNull(pod.id),
      appId = clientId,
      webId = webId,
      grants = emptySet(),
      grantedBy = webId,
    )

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 1141,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf<String, Any>(),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 must carry WWW-Authenticate")
    assertTrue(authHeader.startsWith("Bearer "))
    val body = response.responseBody
    assertTrue(
      body.contains("OAuth upgrade required"),
      "stale grant-less bearer must trigger OAuth upgrade, not authorized=true: $body",
    )
  }

  @Test
  fun `tools list with public-read bearer surfaces NOT authorized hint for authorize tool`() {
    // R4 Phase A: tools/list description must reflect that public-read is
    // upgrade-eligible, not "currently hold an authorized session".
    val pod = sempodsTestFactory.newPod()
    val publicReadToken = mintScopedToken(
      podName = pod.name,
      scopes = listOf("public-read"),
      webId = "https://id.test/visitor",
    )

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 115,
      "method" to "tools/list",
      "params" to mapOf<String, Any>(),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $publicReadToken")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(
      body.contains("You are NOT authorized"),
      "public-read tools/list must surface upgrade-required hint: $body",
    )
    assertFalse(
      body.contains("You currently hold an authorized session"),
      "public-read must not be treated as fully authorized: $body",
    )
  }

  @Test
  fun `tools call authorize with invalid bearer returns 401 with invalid_bearer (not upgrade) message`() {
    // R4 Phase A regression: a manipulated/stale bearer must remain
    // classified as invalid_bearer — the upgrade path is reserved for
    // anonymous + public-read-only callers. Body wording differentiates
    // the two paths so audit logs and SDK error handlers can separate them.
    val pod = sempodsTestFactory.newPod()

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 116,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf<String, Any>(),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer not-a-valid-token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(401, response.statusCode)
    val body = response.responseBody
    assertTrue(
      body.contains("Unauthorized: invalid or expired bearer token"),
      "invalid bearer must use invalid-bearer message, not upgrade message: $body",
    )
    assertFalse(
      body.contains("OAuth upgrade required"),
      "invalid bearer must NOT be classified as auth_trigger: $body",
    )
  }

  @Test
  fun `tools call authorize when already authorized returns success with session info`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "main-${TestUtil.randomId()}")

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 113,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf<String, Any>(),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("\\\"authorized\\\":true"), "Body must report authorized=true: $body")
    assertTrue(
      body.contains("Session is already authorized"),
      "Body must contain the no-op message: $body",
    )
    assertFalse(body.contains("\"isError\":true"), "Must not report isError")
  }

  @Test
  fun `tools call authorize when already authorized does not revoke refresh tokens`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "main-${TestUtil.randomId()}")
    val webId = "https://id.test/user"
    val clientId = "did:web:test.example"
    val scopes = setOf("${contextUri}#read", "${contextUri}#write")
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = clientId,
      webId = webId,
      grants = scopes,
      grantedBy = webId,
    )
    val refreshToken = refreshTokenStore.issueNewFamily(
      podId = checkNotNull(pod.id),
      podName = pod.name,
      clientId = clientId,
      webId = webId,
      scopes = scopes,
    ).plaintext

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 114,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf<String, Any>(),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)

    val refreshResponse = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}" +
        "&client_id=${URLEncoder.encode(clientId, "UTF-8")}",
    )
    assertEquals(200, refreshResponse.statusCode)
    assertTrue(
      refreshResponse.responseBody.contains("\"access_token\""),
      "Default authorize must leave refresh tokens usable: ${refreshResponse.responseBody}",
    )
  }

  @Test
  fun `tools call authorize with reauthorize=true forces 401 even when already authorized`() {
    // Incremental authorization: a caller with valid context-scoped grants
    // can ask for additional contexts by passing `reauthorize=true`. The
    // server replies 401 with WWW-Authenticate so the MCP client restarts
    // its OAuth flow; the consent UI re-renders with existing grants
    // pre-checked and the user can extend them. This holds for the *first*
    // such call — the replay-after-OAuth path is covered by a separate test.
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "main-${TestUtil.randomId()}")

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 117,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to true),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 must carry WWW-Authenticate even on forced reauthorize")
    assertTrue(authHeader.startsWith("Bearer "))
    val body = response.responseBody
    assertTrue(
      body.contains("OAuth upgrade required"),
      "reauthorize=true response must use upgrade-specific error message: $body",
    )
  }

  @Test
  fun `tools call authorize with reauthorize=true revokes refresh tokens so clients cannot silently refresh`() {
    // A forced reauthorize must make the client run the interactive authorization
    // flow. If an existing refresh token remains valid, some MCP clients satisfy
    // the 401 by silently rotating that token; the user never sees the consent UI,
    // and the replay path returns "already authorized".
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "main-${TestUtil.randomId()}")
    val webId = "https://id.test/user"
    val clientId = "did:web:test.example"
    val scopes = setOf("${contextUri}#read", "${contextUri}#write")
    podGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      appId = clientId,
      webId = webId,
      grants = scopes,
      grantedBy = webId,
    )
    val refreshToken = refreshTokenStore.issueNewFamily(
      podId = checkNotNull(pod.id),
      podName = pod.name,
      clientId = clientId,
      webId = webId,
      scopes = scopes,
    ).plaintext

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 123,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to true),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(401, response.statusCode)

    val refreshResponse = postForm(
      tokenUrl(pod.name),
      "grant_type=refresh_token" +
        "&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}" +
        "&client_id=${URLEncoder.encode(clientId, "UTF-8")}",
    )
    assertEquals(400, refreshResponse.statusCode)
    assertTrue(
      refreshResponse.responseBody.contains("\"invalid_grant\""),
      "Revoked refresh token must force the client into an authorization flow: ${refreshResponse.responseBody}",
    )
  }

  @Test
  fun `tools call authorize with reauthorize=true returns success on replay after challenge`() {
    // Replay path: after the server returns 401 to a `reauthorize=true` call,
    // the MCP client completes its OAuth flow and replays the original
    // tools/call body with the *new* bearer. The server must recognise the
    // freshly issued token (different jti for the same client_id+sub, iat
    // at-or-after the recorded challenge time) as the replay and answer with
    // the idempotent success body — otherwise the client enters a 401 loop
    // and surfaces "Authentication failed".
    val pod = sempodsTestFactory.newPod()
    val (contextUri, originalToken) = createContextWithToken(pod, "main-${TestUtil.randomId()}")

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 119,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to true),
      ),
    )
    val body = objectMapper.writeValueAsString(request)

    // First call: must trigger the 401 challenge and record the original jti.
    val firstResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $originalToken")
      .setBody(body)
      .execute()
    assertEquals(401, firstResponse.statusCode)

    // Mint the replay token *after* the challenge was recorded so its iat is
    // at-or-after the recording moment (the server now requires this to defend
    // against parallel-session tokens that pre-date the challenge).
    val replayToken = mintScopedToken(
      podName = pod.name,
      scopes = listOf("$contextUri#read", "$contextUri#write"),
    )

    val replayResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $replayToken")
      .setBody(body)
      .execute()

    assertEquals(200, replayResponse.statusCode)
    val replayBody = replayResponse.responseBody
    assertTrue(
      replayBody.contains("\\\"authorized\\\":true"),
      "Body must report authorized=true on replay after challenge: $replayBody",
    )
  }

  @Test
  fun `tools call authorize with reauthorize=true from anonymous returns success on authenticated replay`() {
    // Anonymous `reauthorize=true` starts the default OAuth flow without a bearer.
    // The replay then arrives with the newly issued bearer but the same tool body.
    val pod = sempodsTestFactory.newPod()

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 124,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to true),
      ),
    )
    val body = objectMapper.writeValueAsString(request)

    val firstResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(body)
      .execute()
    assertEquals(401, firstResponse.statusCode)

    val (_, replayToken) = createContextWithToken(pod, "main-${TestUtil.randomId()}")

    val replayResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $replayToken")
      .setBody(body)
      .execute()

    assertEquals(200, replayResponse.statusCode)
    val replayBody = replayResponse.responseBody
    assertTrue(
      replayBody.contains("\\\"authorized\\\":true"),
      "Body must report authorized=true on anonymous → authenticated replay: $replayBody",
    )
  }

  @Test
  fun `tools call authorize with reauthorize=true from public-read bearer returns success on context-scoped replay`() {
    // Public-read upgrade path: a public-read bearer carries a clientId+sub+jti
    // but no context grants, so it goes through the upgrade branch. The challenge
    // must be recorded before the 401 is thrown, so that the replay with the
    // upgraded context-scoped token (same client_id+sub, new jti) succeeds —
    // otherwise the public-read bearer is stuck in a 401 loop.
    val pod = sempodsTestFactory.newPod()
    val publicReadToken = mintScopedToken(podName = pod.name, scopes = listOf("public-read"))

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 121,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to true),
      ),
    )
    val body = objectMapper.writeValueAsString(request)

    val firstResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $publicReadToken")
      .setBody(body)
      .execute()
    assertEquals(401, firstResponse.statusCode)

    // Upgraded context-scoped token must be issued *after* the challenge was
    // recorded so its iat is at-or-after the recording moment.
    val (_, contextScopedToken) = createContextWithToken(pod, "main-${TestUtil.randomId()}")

    val replayResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $contextScopedToken")
      .setBody(body)
      .execute()

    assertEquals(200, replayResponse.statusCode)
    val replayBody = replayResponse.responseBody
    assertTrue(
      replayBody.contains("\\\"authorized\\\":true"),
      "Body must report authorized=true on public-read → context-scoped replay: $replayBody",
    )
  }

  @Test
  fun `tools call authorize with reauthorize=true does not consume challenge from another sub`() {
    // Multi-user safety: two users share a client_id (e.g. the same DCR-registered
    // MCP client). User A's pending challenge must not be consumed by User B's
    // replay — User B must still see the 401/consent flow.
    val pod = sempodsTestFactory.newPod()
    val contextUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/main-${TestUtil.randomId()}")
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    val scopes = listOf("${contextUri}#read", "${contextUri}#write")
    val tokenUserA = mintScopedToken(podName = pod.name, scopes = scopes, webId = "https://id.test/userA")

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 122,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to true),
      ),
    )
    val body = objectMapper.writeValueAsString(request)

    val userAResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $tokenUserA")
      .setBody(body)
      .execute()
    assertEquals(401, userAResponse.statusCode)

    // Mint User B's bearer *after* User A's challenge so iat is at-or-after
    // the recording moment — that isolates this test to the sub-key check
    // (otherwise it would also pass via the iat<recordedAt rejection path).
    val tokenUserBReplay = mintScopedToken(podName = pod.name, scopes = scopes, webId = "https://id.test/userB")

    val userBResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $tokenUserBReplay")
      .setBody(body)
      .execute()
    assertEquals(
      401,
      userBResponse.statusCode,
      "User B must still see 401 — User A's challenge belongs to a different sub",
    )
  }

  @Test
  fun `tools call authorize with reauthorize=true on same token returns 401 again`() {
    // User cancelled the OAuth flow — the bearer's jti has not changed since
    // the challenge was recorded. The server must keep emitting 401 (not
    // accidentally consume the challenge) so the client can retry the OAuth
    // flow.
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "main-${TestUtil.randomId()}")

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 120,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to true),
      ),
    )
    val body = objectMapper.writeValueAsString(request)

    val first = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(body)
      .execute()
    assertEquals(401, first.statusCode)

    val second = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(body)
      .execute()
    assertEquals(401, second.statusCode)
  }

  @Test
  fun `tools call authorize with reauthorize=false behaves as default idempotent path`() {
    // Explicit `reauthorize=false` must be a no-op — same shape as omitting
    // the field. Guards against truthy-coercion bugs in the dispatch logic.
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "main-${TestUtil.randomId()}")

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 118,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to false),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("\\\"authorized\\\":true"), "Body must report authorized=true: $body")
  }

  // ─── R2.1 public-read bearer ─────────────────────────────────────────────

  @Test
  fun `public-read bearer on list_contexts returns only public contexts not private ones`() {
    // R2.1: a `scope=public-read` bearer is functionally equivalent to anonymous —
    // sees only what `getPublicContexts()` exposes, never user-private contexts that
    // happen to be registered on the pod. This is the resource-layer enforcement.
    val pod = sempodsTestFactory.newPod()
    val privateContextUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/private/notes")
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = privateContextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    val publicContextUri = sempodsTestFactory.publicContextUri(pod.name)
    val publicReadToken = mintScopedToken(
      podName = pod.name,
      scopes = listOf("public-read"),
      webId = "https://id.test/visitor",
    )

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 200,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "list_contexts",
        "arguments" to mapOf<String, Any>(),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $publicReadToken")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains(publicContextUri.toString()), "public context must be listed: $body")
    assertFalse(
      body.contains(privateContextUri.toString()),
      "private context must NOT leak through public-read bearer: $body",
    )
    // No writable_contexts for an unprivileged token. The list_contexts payload is
    // JSON-in-JSON (text field contains a serialized JSON string), so the inner
    // quotes appear escaped in the outer body.
    assertTrue(
      body.contains("\\\"writable_contexts\\\":[]"),
      "public-read bearer must have empty writable_contexts: $body",
    )
  }

  @Test
  fun `public-read bearer on create_resource is rejected`() {
    // R2.1: public-read carries no `<uri>#write` scope, so the per-context scope check
    // refuses the write. The bearer itself stays valid (no 401) — the failure is at
    // the scope layer (403-equivalent reported as MCP isError).
    val pod = sempodsTestFactory.newPod()
    val publicContextUri = sempodsTestFactory.publicContextUri(pod.name)
    val publicReadToken = mintScopedToken(
      podName = pod.name,
      scopes = listOf("public-read"),
      webId = "https://id.test/visitor",
    )
    val resourceIri = "${publicContextUri}/should-not-create-${TestUtil.randomId()}"

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 201,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "create_resource",
        "arguments" to mapOf(
          "context_iri" to publicContextUri.toString(),
          "resource_iri" to resourceIri,
          "jsonld" to mapOf(
            "@context" to mapOf("schema" to "https://schema.org/"),
            "@id" to resourceIri,
            "@type" to "schema:Action",
            "schema:name" to "should not be persisted",
          ),
        ),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $publicReadToken")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    // Either a 401 (the MCP path may treat any unauthenticated-equivalent caller this way)
    // or a 200 with isError true. Either is acceptable as long as the write does not succeed.
    if (response.statusCode == 200) {
      assertTrue(
        response.responseBody.contains("\"isError\":true"),
        "public-read create_resource must report isError: ${response.responseBody}",
      )
    } else {
      assertEquals(401, response.statusCode, "Expected 200+isError or 401, got ${response.statusCode}")
    }
  }

  @Test
  fun `invalid bearer token should return 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 102,
      "method" to "tools/list",
      "params" to mapOf<String, Any>(),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer not-a-valid-token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 response must include WWW-Authenticate header")
    assertTrue(authHeader.startsWith("Bearer "), "WWW-Authenticate should be a Bearer challenge, was: $authHeader")
    assertTrue(authHeader.contains("resource_metadata="), "Challenge must include resource_metadata pointer")
    assertTrue(
      authHeader.contains("/.well-known/oauth-protected-resource"),
      "Challenge must point at RFC 9728 metadata URL, was: $authHeader"
    )
  }

  @Test
  fun `sparql over granted context should return data, sibling context stays invisible`() {
    val pod = sempodsTestFactory.newPod()
    // Distinct identities so each token maps to its own grant set (context permissions are
    // resolved per (client, webId)); tokenA must see A only, never sibling B.
    val (contextA, tokenA) = createContextWithToken(pod, "tasks-${TestUtil.randomId()}", webId = "https://id.test/user-a-${TestUtil.randomId()}")
    val (contextB, _) = createContextWithToken(pod, "events-${TestUtil.randomId()}", webId = "https://id.test/user-b-${TestUtil.randomId()}")

    // Seeded over HTTP like any client write. The subjects sit in the pod's resource namespace and
    // not under the context IRI: `_system/contexts/{path}` is the context registry's own route, so
    // a subject below it is not dereferenceable over the LOD layer and the resource PUT answers
    // 406. Which graph holds a statement is what this test is about; where the subject is minted
    // is not.
    val resourceAUri = sempodsTestFactory.seedEvent(
      pod = pod.name,
      context = contextA,
      name = "Resource in A - ${TestUtil.randomId()}",
    )
    val resourceBUri = sempodsTestFactory.seedEvent(
      pod = pod.name,
      context = contextB,
      name = "Resource in B - ${TestUtil.randomId()}",
    )

    val sparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } LIMIT 100"
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 103,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_graph",
        "arguments" to mapOf("query" to sparqlQuery),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $tokenA")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains(resourceAUri.toString()), "TestHttpResponse must contain resource from granted context A")
    assertFalse(body.contains(resourceBUri.toString()), "TestHttpResponse must NOT leak resource from ungranted context B")
  }

  @Test
  fun `create_resource with write scope should persist and be queryable`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "tasks-${TestUtil.randomId()}")

    val resourceIri = "${contextUri}/task-${TestUtil.randomId()}"
    val resourceName = "MCP created task ${TestUtil.randomId()}"

    val createRequest = mapOf(
      "jsonrpc" to "2.0",
      "id" to 104,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "create_resource",
        "arguments" to mapOf(
          "context_iri" to contextUri.toString(),
          "resource_iri" to resourceIri,
          "jsonld" to mapOf(
            "@context" to mapOf("schema" to "https://schema.org/"),
            "@id" to resourceIri,
            "@type" to "schema:Action",
            "schema:name" to resourceName,
          ),
        ),
      ),
    )

    val createResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(createRequest))
      .execute()

    assertEquals(200, createResponse.statusCode)
    val createBody = createResponse.responseBody
    assertTrue(createBody.contains(resourceIri), "create_resource response should echo the resource_iri, was: $createBody")
    assertFalse(createBody.contains("\"isError\":true"), "create_resource must not report isError, was: $createBody")

    // Now read it back via sparql_graph.
    val readRequest = mapOf(
      "jsonrpc" to "2.0",
      "id" to 105,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "sparql_graph",
        "arguments" to mapOf("query" to "CONSTRUCT { <$resourceIri> ?p ?o } WHERE { <$resourceIri> ?p ?o }"),
      ),
    )
    val readResponse = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(readRequest))
      .execute()

    assertEquals(200, readResponse.statusCode)
    assertTrue(readResponse.responseBody.contains(resourceName), "Read-back must include created resource name")
  }

  @Test
  fun `create_resource without write scope should report isError`() {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val contextUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/tasks-${TestUtil.randomId()}")
    podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    // Read-only scope — intentionally no #write.
    val readOnlyToken = mintScopedToken(
      podName = pod.name,
      scopes = listOf("${contextUri}#read"),
    )

    val resourceIri = "${contextUri}/task-${TestUtil.randomId()}"
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 106,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "create_resource",
        "arguments" to mapOf(
          "context_iri" to contextUri.toString(),
          "resource_iri" to resourceIri,
          "jsonld" to mapOf(
            "@id" to resourceIri,
            "https://schema.org/name" to "should-be-rejected",
          ),
        ),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $readOnlyToken")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode) // JSON-RPC: tool errors surface as isError inside result
    val body = response.responseBody
    assertTrue(body.contains("\"isError\":true"), "Write without scope must report isError, was: $body")
    assertTrue(body.contains("403"), "Error text should include 403 status, was: $body")
  }

  @Test
  fun `delete_resource without bearer should return 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, _) = createContextWithToken(pod, "tasks-${TestUtil.randomId()}")
    val resourceIri = "${contextUri}/task-${TestUtil.randomId()}"

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 107,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "delete_resource",
        "arguments" to mapOf(
          "context_iri" to contextUri.toString(),
          "resource_iri" to resourceIri,
        ),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    // Writes require a bearer: missing → pre-dispatch requireAuthenticatedOrThrow → 401
    // with WWW-Authenticate so the MCP client drives the OAuth flow.
    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 response must include WWW-Authenticate header")
    assertTrue(
      authHeader.contains("/.well-known/oauth-protected-resource"),
      "Challenge must point at RFC 9728 metadata URL, was: $authHeader",
    )
  }

  @Test
  fun `401 challenge resource_metadata must point at the pod-level PRM`() {
    // A client that follows only WWW-Authenticate (instead of probing PRM under the MCP
    // URL) has to land on the pod's protected-resource metadata: the pod is the resource,
    // and its `/register` is the one registration endpoint.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, _) = createContextWithToken(pod, "tasks-${TestUtil.randomId()}")
    val resourceIri = "${contextUri}/task-${TestUtil.randomId()}"

    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 110,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "delete_resource",
        "arguments" to mapOf(
          "context_iri" to contextUri.toString(),
          "resource_iri" to resourceIri,
        ),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 response must include WWW-Authenticate header")
    // The whole string, not a substring: `BearerChallenge` in `sempods-mcp-core` makes this one
    // implementation shared with the hosted service, and a swapped parameter would still satisfy a
    // `contains` check.
    val podBaseUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}"
    assertEquals(
      "Bearer realm=\"${pod.name}\", error=\"invalid_token\", " +
        "resource=\"$podBaseUrl\", " +
        "resource_metadata=\"$podBaseUrl/.well-known/oauth-protected-resource\"",
      authHeader,
    )
  }

  @Test
  fun `invalid method should return error`() {
    val pod = sempodsTestFactory.newPod()
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 7,
      "method" to "invalid/method",
      "params" to mapOf<String, Any>()
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode) // JSON-RPC returns 200 even for errors
    val responseBody = response.responseBody
    assertTrue(responseBody.contains("error"), "TestHttpResponse should contain error")
    assertTrue(responseBody.contains("Method not found"), "TestHttpResponse should indicate method not found")
  }

  // ─── OAuth discovery probed at the MCP URL ───────────────────────────────
  // claude.ai proactively probes .well-known URLs under the MCP endpoint before it sees a 401,
  // so these have to exist in addition to the pod-level variants.

  @Test
  fun `mcp url should expose oauth-protected-resource metadata`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet("${mcpUrl(pod.name)}/.well-known/oauth-protected-resource")
      .execute()

    assertEquals(200, response.statusCode)
    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    val podBaseUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}"
    assertEquals(podBaseUrl, body["resource"], "resource must stay at the pod URL")
    assertEquals(
      listOf("$podBaseUrl/_system/auth"),
      body["authorization_servers"],
      "the pod has one issuer; the MCP URL is a spelling of the same resource",
    )
  }

  @Test
  fun `mcp url should not serve oauth-authorization-server metadata`() {
    // The MCP URL is not an issuer identifier. Serving AS-metadata under it would have to
    // name an `issuer` that differs from the URL it was fetched from (RFC 8414 §3.3); the
    // PRM points clients at `_system/auth`, which is the real issuer.
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.prepareGet("${mcpUrl(pod.name)}/.well-known/oauth-authorization-server")
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `the retired mcps path should not serve MCP`() {
    // `/_system/mcps/<mcpPath>` was the endpoint until the MCP path was retired. The cut is
    // hard on purpose — no alias — so a client configured against the old URL fails loudly and
    // is re-added, rather than silently running against a path we no longer model.
    //
    // 405, not 404: with no MCP route claiming it, the path falls into the LOD resource
    // namespace like any other unknown pod path, and `PodResourceEndpoint` has no POST. The
    // status is a routing side effect; what this pins is that no JSON-RPC answer comes back.
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/mcps/default")
      .addHeader("Content-Type", "application/json")
      .setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
      .execute()

    assertEquals(405, response.statusCode)
    assertFalse(response.responseBody.contains("jsonrpc"), "the old path must not answer JSON-RPC")
  }

  @Test
  fun `mcp url should 404 on unknown pod instead of masking it as an internal error`() {
    val response = httpClient.preparePost(mcpUrl("no-such-pod-${TestUtil.randomId()}"))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
      .execute()

    assertEquals(404, response.statusCode, "an unknown pod name must not read as a broken server")
    val body = response.responseBody
    assertTrue(body.contains("Unknown pod"), "error must name the unknown pod, was: $body")
    assertFalse(body.contains("-32603"), "must not be the generic internal-error code, was: $body")
  }

  @Test
  fun `a notification on a known pod is accepted with 202 and no body`() {
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
      .execute()

    assertEquals(202, response.statusCode, "a notification expects no response")
    assertTrue(response.responseBody.isEmpty(), "202 must carry no body, was: ${response.responseBody}")
  }

  @Test
  fun `a notification on an unknown pod should 404 rather than claim acceptance`() {
    // 202 asserts the input was accepted. It never can be for a pod that does not exist,
    // and the notification fast path used to return it without ever resolving the pod.
    val response = httpClient.preparePost(mcpUrl("no-such-pod-${TestUtil.randomId()}"))
      .addHeader("Content-Type", "application/json")
      .setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
      .execute()

    assertEquals(404, response.statusCode, "an unknown pod must not be told its notification was accepted")
    assertTrue(response.responseBody.contains("Unknown pod"), "was: ${response.responseBody}")
    // The transport spec asks for a JSON-RPC error carrying no `id` here, which is what
    // `JsonRpcErrorResponse`'s NON_NULL inclusion produces. Pinned because the alternative
    // reading — `"id": null` per plain JSON-RPC 2.0 — is a plausible "fix" that would
    // break the shape the MCP transport names for a rejected notification POST.
    @Suppress("UNCHECKED_CAST")
    val body = objectMapper.readValue(response.responseBody, Map::class.java) as Map<String, Any?>
    assertFalse(body.containsKey("id"), "a rejected notification carries no id, was: ${response.responseBody}")
    assertEquals(-32002, (body["error"] as Map<*, *>)["code"])
  }

  @Test
  fun `a suffix below the mcp url should 404`() {
    // One surface per pod: the endpoint is exactly `_system/mcp`. The path used to take a
    // free segment that forked DCR fingerprints; nothing routes below it any more.
    val pod = sempodsTestFactory.newPod()

    val response = httpClient.preparePost("${mcpUrl(pod.name)}/chatgpt-work")
      .addHeader("Content-Type", "application/json")
      .setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
      .execute()

    assertEquals(404, response.statusCode)
  }

  // ── Iter 2: MCP `if_match` (Conditional Writes) ─────────────────────────────

  private fun slotGetETag(pod: PodDbo, contextUri: URI, token: String, subjectIri: String, predicateIri: String): String {
    val s = org.sempods.commons.utils.UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(subjectIri))
    val p = org.sempods.commons.utils.UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(predicateIri))
    val ctx = URLEncoder.encode(contextUri.toString(), Charsets.UTF_8)
    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/resources/$s/$p?context=$ctx"
    val resp = httpClient.prepareGet(url).addHeader("Authorization", "Bearer $token").execute()
    return assertNotNull(resp.headers.get("ETag"))
  }

  @Test
  fun `add_property_value with current if_match succeeds`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val erin = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/erin"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    val currentETag = slotGetETag(pod, contextUri, token, bob, "https://schema.org/children")

    val resp = toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to erin),
      "if_match" to currentETag,
    ))
    assertFalse(resp.contains("\"isError\":true"), "must succeed with current tag: $resp")
  }

  @Test
  fun `add_property_value with --gzip-suffixed if_match succeeds`() {
    // Jetty's GzipHandler appends "--gzip" to the ETag emitted on a
    // compressible HTTP GET (RFC 9110 §8.8.3). MCP clients that copy
    // that tag verbatim into `if_match` must round-trip successfully
    // — the KDoc on checkSlotIfMatchOrThrow promises HTTP/MCP tag
    // parity. extractIfMatchOrNull strips the suffix so the
    // comparison sees the canonical hash.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val erin = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/erin"

    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    val rawETag = slotGetETag(pod, contextUri, token, bob, "https://schema.org/children")
    // Force the gzip-suffixed variant regardless of whether the
    // container actually compressed this particular response — we
    // want to exercise extractIfMatchOrNull with the suffix present.
    val gzipETag = if (rawETag.endsWith("--gzip\"")) rawETag
    else rawETag.replace(Regex("\"$"), "--gzip\"")

    val resp = toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to erin),
      "if_match" to gzipETag,
    ))
    assertFalse(
      resp.contains("\"isError\":true"),
      "MCP if_match must accept Jetty's --gzip suffix (was: $gzipETag): $resp",
    )
  }

  @Test
  fun `add_property_value with stale if_match returns 412 tool error`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    val resp = toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
      "if_match" to "definitely-stale",
    ))
    assertTrue(resp.contains("\"isError\":true"), "stale tag must surface as error: $resp")
    assertTrue(resp.contains("412"), "must mention 412: $resp")
  }

  @Test
  fun `set_property_values with stale if_match returns 412 tool error`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))

    val resp = toolCall(pod.name, token, "set_property_values", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "values" to listOf(mapOf("@id" to carol)),
      "if_match" to "stale",
    ))
    assertTrue(resp.contains("\"isError\":true"), "stale tag must surface as error: $resp")
    assertTrue(resp.contains("412"), "must mention 412: $resp")
  }

  @Test
  fun `clear_property_values with current if_match succeeds`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    toolCall(pod.name, token, "add_property_value", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "value" to mapOf("@id" to carol),
    ))
    val currentETag = slotGetETag(pod, contextUri, token, bob, "https://schema.org/children")

    val resp = toolCall(pod.name, token, "clear_property_values", mapOf(
      "context_iri" to contextUri.toString(),
      "subject_iri" to bob,
      "predicate_iri" to "https://schema.org/children",
      "if_match" to currentETag,
    ))
    assertFalse(resp.contains("\"isError\":true"), "must succeed with current tag: $resp")
  }

  @Test
  fun `update_resource on non-existent resource returns 404 tool error with create_resource hint`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val ghost = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/does-not-exist"

    val resp = toolCall(pod.name, token, "update_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to ghost,
      "jsonld_patch" to mapOf("https://schema.org/name" to "ghost"),
    ))
    assertTrue(resp.contains("\"isError\":true"), "missing resource must surface as error: $resp")
    assertTrue(resp.contains("404"), "must mention 404: $resp")
    assertTrue(resp.contains("does not exist"), "must mention the absence: $resp")
    assertTrue(resp.contains("create_resource"), "must hint at create_resource: $resp")
    assertFalse(resp.contains("ApiException"), "must not leak framework exception class: $resp")
  }

  @Test
  fun `delete_resource on non-existent resource returns 404 tool error`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val ghost = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/does-not-exist"

    val resp = toolCall(pod.name, token, "delete_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to ghost,
    ))
    assertTrue(resp.contains("\"isError\":true"), "missing resource must surface as error: $resp")
    assertTrue(resp.contains("404"), "must mention 404: $resp")
    assertFalse(resp.contains("ApiException"), "must not leak framework exception class: $resp")
  }

  @Test
  fun `update_resource with stale if_match returns 412 tool error`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"

    toolCall(pod.name, token, "create_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to bob,
      "jsonld" to mapOf(
        "@id" to bob,
        "https://schema.org/name" to "Bob",
      ),
    ))

    val resp = toolCall(pod.name, token, "update_resource", mapOf(
      "context_iri" to contextUri.toString(),
      "resource_iri" to bob,
      "jsonld_patch" to mapOf("https://schema.org/name" to "new"),
      "if_match" to "stale",
    ))
    assertTrue(resp.contains("\"isError\":true"), "stale tag must surface as error: $resp")
    assertTrue(resp.contains("412"), "must mention 412: $resp")
  }

  @Test
  fun `authorize rejects an unknown argument like every other tool`() {
    // `authorize` is the one tool the shared catalog does not carry — it means something different
    // on each MCP surface. Its schema therefore comes from a constant beside the endpoint, and if
    // that lookup is ever dropped this is the tool that silently stops enforcing
    // `additionalProperties: false` while still advertising it.
    val pod = sempodsTestFactory.newPod()
    // Authorized, because the argument check sits behind the auth gate: an anonymous `authorize`
    // is answered with the 401 that starts the OAuth flow and never reaches the schema.
    val (_, token) = createContextWithToken(pod, "tasks-${TestUtil.randomId()}")
    val request = mapOf(
      "jsonrpc" to "2.0",
      "id" to 1,
      "method" to "tools/call",
      "params" to mapOf(
        "name" to "authorize",
        "arguments" to mapOf("reauthorize" to false, "bogus" to "x"),
      ),
    )

    val response = httpClient.preparePost(mcpUrl(pod.name))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(objectMapper.writeValueAsString(request))
      .execute()

    assertEquals(200, response.statusCode)
    val body = response.responseBody
    assertTrue(body.contains("\"isError\":true"), "an unknown argument must be a tool error, was: $body")
    assertTrue(body.contains("bogus"), "the error must name the offending argument, was: $body")
  }
}
