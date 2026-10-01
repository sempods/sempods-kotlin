package org.sempods.api.system.sparql

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.assertPodBearerChallenge
import org.sempods.client.SempodsContextSelection
import org.sempods.client.SempodsGraphFormat
import org.sempods.client.SempodsPodSparql
import org.sempods.client.SempodsResponse
import org.sempods.client.SempodsSparqlTermKind
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.toIri
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.logging.CapturedLog
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.eclipse.rdf4j.model.Model
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SparqlEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  /** [query]'s graph as N-Quads, parsed. */
  private fun SempodsPodSparql.quads(query: String): Model {
    val response = graphBytes(query, SempodsGraphFormat.N_QUADS)
    assertEquals(200, response.status)
    return response.nQuads()
  }

  private fun SempodsResponse<ByteArray>.nQuads(): Model =
    ByteArrayInputStream(assertNotNull(body)).use { RdfWriterUtil.readNQuads(it) }

  @Test
  fun `an anonymous query reads a public resource as N-Quads, as JSON-LD, as typed rows and as a boolean`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = sempodsTestFactory.publicContextUri(pod.name),
      name = "SPARQL N-Quads Test - ${TestUtil.randomId()}",
    )
    val sparql = podAs(pod.name).sparql()
    val sparqlQuery = """
      CONSTRUCT { <$eventUri> ?p ?o }
      WHERE { <$eventUri> ?p ?o }
    """.trimIndent()

    val response = sparql.graphBytes(sparqlQuery, SempodsGraphFormat.N_QUADS)

    assertEquals(200, response.status)
    assertEquals("application/n-quads", response.headers["Content-Type"].orEmpty().split(";")[0])
    val model = response.nQuads()
    assertTrue(model.isNotEmpty(), "Model should contain statements")
    assertTrue(model.filter(eventUri.toIri(), null, null).isNotEmpty(), "Model should contain the event resource")

    val jsonLd = sparql.graphBytes(sparqlQuery, SempodsGraphFormat.JSON_LD)
    assertTrue(jsonLd.headers["Content-Type"].orEmpty().startsWith("application/ld+json"), "${jsonLd.headers}")

    val subjects = assertNotNull(sparql.select("SELECT ?s WHERE { ?s ?p ?o }").body).column("s")
    assertTrue(subjects.any { it.kind == SempodsSparqlTermKind.IRI && it.value == eventUri.toString() }, "$subjects")
    val objects = assertNotNull(sparql.select("SELECT ?o WHERE { <$eventUri> ?p ?o }").body).column("o")
    assertTrue(objects.any { it.kind == SempodsSparqlTermKind.LITERAL }, "$objects")
    assertEquals(true, sparql.ask("ASK { <$eventUri> ?p ?o }").body)
  }

  @Test
  fun `SPARQL SELECT query with Accept N-Quads should return 406`() {
    val pod = sempodsTestFactory.newPod()

    val sparqlQuery = """
      SELECT ?s
      WHERE { ?s ?p ?o }
      LIMIT 1
    """.trimIndent()

    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .setBody(sparqlQuery)
      .execute()

    assertEquals(406, response.statusCode)
  }

  @Test
  fun `SPARQL CONSTRUCT without auth should only return resources from public contexts`() = withSetup {
    val pod = sempodsTestFactory.newPod()

    // Resource in public context — visible to any caller, authenticated or anonymous.
    val publicEventId = TestUtil.randomId()
    val publicEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = publicEventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = publicEventUri,
      context = publicContext,
      name = "public-event-${TestUtil.randomId()}",
    )

    // Resource in a non-public context — must NOT surface for an anonymous caller.
    val privateEventId = TestUtil.randomId()
    val privateEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = privateEventId)
    val privateContext = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/default")
    // Registered, because the write goes over HTTP and an unregistered context is a 404 there. It
    // also makes the assertion mean what it says: the resource must stay invisible because the
    // context is private, not because it never existed.
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = privateContext.toString(),
      label = null, description = null, createdBy = "test",
    )
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = privateEventUri,
      context = privateContext,
      name = "private-event-${TestUtil.randomId()}",
    )

    val sparql = podAs(pod.name).sparql()

    val model = sparql.quads("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")

    assertTrue(
      model.filter(publicEventUri.toIri(), null, null).isNotEmpty(),
      "Public event should be visible to anonymous caller"
    )
    assertFalse(
      model.filter(privateEventUri.toIri(), null, null).isNotEmpty(),
      "Non-public event should NOT be visible to anonymous caller"
    )
    val unreadable = SempodsContextSelection.of(privateContext.toString())
    assertEquals(
      emptyList(),
      assertNotNull(sparql.select("SELECT ?s WHERE { ?s ?p ?o }", unreadable).body).solutions,
      "selecting a context the caller cannot read selects nothing",
    )
  }

  @Test
  fun `SPARQL with authenticated caller but no readable contexts should return empty results`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val publicEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = publicEventUri,
      context = publicContext,
      name = "public-event-${TestUtil.randomId()}",
    )

    val tokenWithoutReadableContexts = mintScopedToken(pod.name, emptyList())
    val sparql = podAs(pod.name, bearer = tokenWithoutReadableContexts).sparql()

    val model = sparql.quads("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
    assertTrue(model.isEmpty(), "No-readable-context token must not fall through to the whole store")

    val ask = sparql.ask("ASK { ?s ?p ?o }")
    assertEquals(200, ask.status)
    assertEquals(false, ask.body, "ASK must be false for empty scope")

    val dataIndependentAsk = sparql.ask("ASK WHERE { BIND(1 AS ?x) }")
    assertEquals(200, dataIndependentAsk.status)
    assertEquals(true, dataIndependentAsk.body, "Data-independent ASK must still be evaluated against an empty store")
  }

  @Test
  fun `SPARQL graph query with Accept SPARQL results JSON should return 406`() {
    val pod = sempodsTestFactory.newPod()

    val sparqlQuery = """
      CONSTRUCT { ?s ?p ?o }
      WHERE { ?s ?p ?o }
    """.trimIndent()

    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/sparql-results+json")
      .setBody(sparqlQuery)
      .execute()

    assertEquals(406, response.statusCode)
  }

  @Test
  fun `SPARQL CONSTRUCT with manage root token should see slash-delimited descendant contexts`() = withSetup {
    // The SPARQL sandbox builds its FROM/FROM NAMED set from `restrictedContexts`. A
    // manage-root token has to expand to descendants there too, or the sandbox masks
    // graphs the same token can legitimately write to. Sibling-prefix contexts must
    // stay outside.
    val pod = sempodsTestFactory.newPod()
    val rootContext = "apps/test-app/tasks"
    val rootContextUri = sempodsUriBuilder.buildContext(pod.name, rootContext)
    val childContext = "apps/test-app/tasks/child"
    val childContextUri = sempodsUriBuilder.buildContext(pod.name, childContext)
    val siblingContext = "apps/test-app/tasks-private"
    val siblingContextUri = sempodsUriBuilder.buildContext(pod.name, siblingContext)
    val podId = checkNotNull(pod.id)
    listOf(rootContextUri, childContextUri, siblingContextUri).forEach { uri ->
      podContextsDao.create(
        podId = podId,
        contextUri = uri.toString(),
        label = null, description = null, createdBy = "test",
      )
    }

    val childEventId = TestUtil.randomId()
    val childEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = childEventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = childEventUri,
      context = childContextUri,
      name = "child-event-${TestUtil.randomId()}",
    )

    val siblingEventId = TestUtil.randomId()
    val siblingEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = siblingEventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = siblingEventUri,
      context = siblingContextUri,
      name = "sibling-event-${TestUtil.randomId()}",
    )

    val manageRootToken = mintScopedToken(pod.name, listOf("${rootContextUri}#manage"))

    val model = podAs(pod.name, bearer = manageRootToken).sparql().quads("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")

    assertTrue(
      model.filter(childEventUri.toIri(), null, null).isNotEmpty(),
      "Event in slash-delimited descendant context should be reachable via SPARQL"
    )
    assertFalse(
      model.filter(siblingEventUri.toIri(), null, null).isNotEmpty(),
      "Event in sibling-prefix context must NOT be reachable via SPARQL"
    )
  }

  @Test
  fun `SPARQL endpoint should reject SERVICE clauses for SSRF protection`() {
    val pod = sempodsTestFactory.newPod()

    val serviceQuery = "SELECT ?s WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }"
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/sparql-results+json")
      .setBody(serviceQuery)
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(
      response.responseBody.contains("SERVICE"),
      "TestHttpResponse should mention SERVICE rejection, was: ${response.responseBody}"
    )
  }

  @Test
  fun `a query cannot forge a second log line`() {
    // The query is the request body, logged before anything has looked at it — and the hand-rolled
    // newline replacement this replaced covered `\n` and nothing else. `docs/logging.md`
    // §"Three rules".
    val pod = sempodsTestFactory.newPod()
    // The marker is what tells this case's line from a sibling's: the suite runs its classes
    // concurrently and the appender sees every line logged while the block runs.
    val marker = "forged-${TestUtil.randomId()}"
    val forged = "SELECT ?s WHERE { ?s ?p ?o } # $marker\r\n2026-01-01 21:00:00,000 WARN  [jetty] $marker"

    val lines = CapturedLog.linesFrom(SparqlEndpoint::class.java) {
      http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
        .addHeader("Content-Type", "application/sparql-query")
        .addHeader("Accept", "application/sparql-results+json")
        .setBody(forged)
        .execute()
    }

    val line = lines.single { marker in it }
    assertFalse('\n' in line, "the line carries a raw newline: $line")
    assertFalse('\r' in line, "the line carries a raw carriage return: $line")
    assertTrue("\\u000d\\u000a" in line, line)
  }

  @Test
  fun `SPARQL endpoint should reject Update queries`() {
    val pod = sempodsTestFactory.newPod()

    val updateQuery = "INSERT DATA { <http://example.org/x> <http://example.org/p> \"v\" }"
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/sparql-results+json")
      .setBody(updateQuery)
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(
      response.responseBody.contains("Write operations are not allowed") ||
          response.responseBody.contains("malformed"),
      "TestHttpResponse should reject the update, was: ${response.responseBody}"
    )
  }

  @Test
  fun `SPARQL endpoint should accept literals containing keyword tokens`() = withSetup {
    val pod = sempodsTestFactory.newPod()

    // A naive substring filter would have rejected this query because the literal
    // contains "CREATE", "INSERT", and "SERVICE". Parser-based validation accepts it.
    val query = """SELECT ?s WHERE { ?s ?p "needs CREATE INSERT SERVICE handling" } LIMIT 1"""
    val response = podAs(pod.name).sparql().select(query)

    assertEquals(200, response.status)
  }

  @Test
  fun `SPARQL answers an anonymous query and challenges a rejected bearer`() {
    val pod = sempodsTestFactory.newPod()

    fun query(authorization: String?): TestHttpResponse {
      val request = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
        .addHeader("Content-Type", "application/sparql-query")
        .addHeader("Accept", "application/n-quads")
        .setBody("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
      authorization?.let { request.addHeader("Authorization", it) }
      return request.execute()
    }

    assertEquals(200, query(authorization = null).statusCode)
    assertPodBearerChallenge(query(authorization = "Bearer not-a-real-jwt"), pod.name)
  }

  @Test
  fun `SPARQL default-graph-uri narrows to a subset of the readable contexts`() {
    val pod = sempodsTestFactory.newPod()
    val rootContextUri = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/tasks")
    val childContextUri = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/tasks/child")
    val podId = checkNotNull(pod.id)
    listOf(rootContextUri, childContextUri).forEach { uri ->
      podContextsDao.create(podId = podId, contextUri = uri.toString(), label = null, description = null, createdBy = "test")
    }

    val rootEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = rootEventUri,
      context = rootContextUri,
      name = "root-event-${TestUtil.randomId()}",
    )

    val childEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = childEventUri,
      context = childContextUri,
      name = "child-event-${TestUtil.randomId()}",
    )

    // manage-root token reads both the root and its slash-delimited child.
    val token = mintScopedToken(pod.name, listOf("${rootContextUri}#manage"))

    // Narrow to the child context only, via the SPARQL-1.1-protocol dataset param.
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addQueryParam("default-graph-uri", childContextUri.toString())
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
      .execute()

    assertEquals(200, response.statusCode)
    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    assertTrue(
      model.filter(childEventUri.toIri(), null, null).isNotEmpty(),
      "Event in the requested default graph should be visible",
    )
    assertFalse(
      model.filter(rootEventUri.toIri(), null, null).isNotEmpty(),
      "Event in a readable-but-not-requested context must be narrowed out",
    )
  }

  @Test
  fun `SPARQL protocol dataset params cannot broaden beyond the readable set`() {
    val pod = sempodsTestFactory.newPod()
    val grantedContextUri = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/granted")
    val ungrantedContextUri = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/ungranted")
    val podId = checkNotNull(pod.id)
    listOf(grantedContextUri, ungrantedContextUri).forEach { uri ->
      podContextsDao.create(podId = podId, contextUri = uri.toString(), label = null, description = null, createdBy = "test")
    }

    val ungrantedEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = ungrantedEventUri,
      context = ungrantedContextUri,
      name = "ungranted-event-${TestUtil.randomId()}",
    )

    // Token may read only the granted context; the sibling `ungranted` is NOT covered.
    val token = mintScopedToken(pod.name, listOf("${grantedContextUri}#manage"))

    // Requesting the ungranted context via the protocol param must be silently dropped — never leaked,
    // and never a fall-through to the readable set — so the result is empty (fail-closed).
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addQueryParam("default-graph-uri", ungrantedContextUri.toString())
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
      .execute()

    assertEquals(200, response.statusCode)
    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    assertTrue(
      model.isEmpty(),
      "Requesting an ungranted context must fail closed to empty, never leak it",
    )
  }

  @Test
  fun `a present-but-blank dataset param fails closed to empty, not the whole readable set`() {
    val pod = sempodsTestFactory.newPod()
    val publicEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = publicEventUri,
      context = publicContext,
      name = "public-event-${TestUtil.randomId()}",
    )

    // A present-but-blank default-graph-uri is still a downscope *request*: dropping the blank value
    // must NOT let the query fall back to the whole readable (here: public) set. It must fail closed.
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addQueryParam("default-graph-uri", "")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .setBody("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
      .execute()

    assertEquals(200, response.statusCode)
    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    assertTrue(
      model.isEmpty(),
      "A present-but-blank dataset param must fail closed, not widen back to the whole readable set",
    )
  }

  @Test
  fun `a context exports over this route as triples, and nothing of the next`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val tasks = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/tasks")
    val notes = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/notes")
    listOf(tasks, notes).forEach {
      podContextsDao.create(podId = podId, contextUri = it.toString(), label = null, description = null, createdBy = "test")
    }
    val token = mintScopedToken(pod.name, listOf(tasks, notes).flatMap { listOf("$it#read", "$it#write") })
    val exported = sempodsTestFactory.seedEvent(pod = pod.name, context = tasks, name = "exported")
    val other = sempodsTestFactory.seedEvent(pod = pod.name, context = notes, name = "not exported")

    val out = ByteArrayOutputStream()
    val written = podAs(pod.name, bearer = token).contexts().exportTo(tasks.toString(), out)

    assertEquals(200, written.status)
    val quads = out.toString(Charsets.UTF_8)
    assertEquals(quads.toByteArray(Charsets.UTF_8).size.toLong(), written.body)
    assertTrue(quads.contains("<$exported>"), quads)
    assertFalse(quads.contains("$other"), "the query names one graph: $quads")
    // A CONSTRUCT answers triples, so the export carries no context of its own to re-import from.
    quads.lines().filter { it.isNotBlank() }.forEach { line ->
      assertTrue(Regex("<[^>]*>").findAll(line).count() <= 3, line)
    }
  }

  @Test
  fun `a selection narrows a query to its contexts, and an empty selection matches nothing`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val rootContextUri = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/tasks")
    val childContextUri = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/tasks/child")
    listOf(rootContextUri, childContextUri).forEach { uri ->
      podContextsDao.create(podId = checkNotNull(pod.id), contextUri = uri.toString(), label = null, description = null, createdBy = "test")
    }
    val rootEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(pod = pod.name, eventUri = rootEventUri, context = rootContextUri, name = "root-event-${TestUtil.randomId()}")
    val childEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(pod = pod.name, eventUri = childEventUri, context = childContextUri, name = "child-event-${TestUtil.randomId()}")
    val token = mintScopedToken(pod.name, listOf("${rootContextUri}#manage"))

    val sparql = podAs(pod.name, bearer = token).sparql()
    fun subjects(selection: SempodsContextSelection) =
      assertNotNull(sparql.select("SELECT DISTINCT ?s WHERE { ?s ?p ?o }", selection).body).column("s").map { it.value }.toSet()

    val child = SempodsContextSelection.of(childContextUri.toString())
    assertTrue(childEventUri.toString() in subjects(child))
    assertFalse(rootEventUri.toString() in subjects(child))
    assertEquals(
      listOf(childContextUri.toString()),
      assertNotNull(sparql.select("SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }", child).body).column("g").map { it.value },
    )
    assertTrue(subjects(SempodsContextSelection.readable()).containsAll(setOf(rootEventUri.toString(), childEventUri.toString())))

    val none = SempodsContextSelection.none()
    assertEquals(emptySet(), subjects(none))
    assertEquals(false, sparql.ask("ASK { ?s ?p ?o }", none).body)
    assertEquals(true, sparql.ask("ASK {}", none).body)
  }

  // ── The RDF4J adapter against this route ─────────────────────────────────────

  @Test
  fun `the RDF4J adapter reads a SELECT result as binding sets and a CONSTRUCT graph as a model without contexts`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    val name = "public-event-${TestUtil.randomId()}"
    sempodsTestFactory.seedEvent(pod = pod.name, eventUri = eventUri, context = sempodsTestFactory.publicContextUri(pod.name), name = name)
    val event = Values.iri(eventUri.toString())

    val sparql = rdfAs(pod.name).sparql()

    val results = assertNotNull(sparql.select("SELECT ?p ?o WHERE { <$eventUri> ?p ?o }").body)
    assertEquals(listOf("p", "o"), results.variables)
    assertTrue(results.bindingSets.any { it.getValue("o") == Values.literal(name) }, "$results")

    val graph = assertNotNull(sparql.graphModel("CONSTRUCT WHERE { <$eventUri> ?p ?o }").body)
    assertTrue(graph.contains(event, null, Values.literal(name)), "graph: $graph")
    assertEquals(setOf(null), graph.contexts())
  }

  @Test
  fun `the RDF4J adapter exports one context with that context on every statement, and streams a graph`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val tasks = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/tasks")
    val notes = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/notes")
    listOf(tasks, notes).forEach {
      podContextsDao.create(podId = podId, contextUri = it.toString(), label = null, description = null, createdBy = "test")
    }
    val token = mintScopedToken(pod.name, listOf(tasks, notes).flatMap { listOf("$it#read", "$it#write") })
    val exported = sempodsTestFactory.seedEvent(pod = pod.name, context = tasks, name = "exported")
    val other = sempodsTestFactory.seedEvent(pod = pod.name, context = notes, name = "not exported")

    val rdf = rdfAs(pod.name, bearer = token)

    val handled = LinkedHashModel()
    val count = rdf.contexts().export(tasks.toString(), StatementCollector(handled))
    assertEquals(handled.size.toLong(), count.body)
    assertEquals(setOf(Values.iri(tasks.toString())), handled.contexts())
    assertTrue(handled.contains(Values.iri(exported.toString()), null, null), "export: $handled")
    assertFalse(handled.contains(Values.iri(other.toString()), null, null), "export: $handled")
    assertTrue(Models.isomorphic(handled, assertNotNull(rdf.contexts().exportModel(tasks.toString()).body)))

    val streamed = LinkedHashModel()
    rdf.sparql().graphStream("CONSTRUCT WHERE { <$exported> ?p ?o }", StatementCollector(streamed))
    assertTrue(streamed.contains(Values.iri(exported.toString()), null, null), "stream: $streamed")
    assertEquals(setOf(null), streamed.contexts())
  }
}
