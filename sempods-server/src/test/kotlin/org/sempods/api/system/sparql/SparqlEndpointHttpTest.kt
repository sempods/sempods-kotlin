package org.sempods.api.system.sparql

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
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

  @Test
  fun `SPARQL graph query should support N-Quads via HTTP`() {
    val pod = sempodsTestFactory.newPod()

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)

    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "SPARQL N-Quads Test - ${TestUtil.randomId()}",
    )

    val sparqlQuery = """
      CONSTRUCT { <$eventUri> ?p ?o }
      WHERE { <$eventUri> ?p ?o }
    """.trimIndent()

    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .setBody(sparqlQuery)
      .execute()

    assertEquals(200, response.statusCode)
    assertEquals("application/n-quads", response.contentType.orEmpty().split(";")[0])

    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { inStream ->
      RdfWriterUtil.readNQuads(inStream)
    }

    assertTrue(model.isNotEmpty(), "Model should contain statements")
    assertTrue(model.filter(eventUri.toIri(), null, null).isNotEmpty(), "Model should contain the event resource")
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
  fun `SPARQL CONSTRUCT without auth should only return resources from public contexts`() {
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

    val sparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"

    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .setBody(sparqlQuery)
      .execute()

    assertEquals(200, response.statusCode)

    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }

    assertTrue(
      model.filter(publicEventUri.toIri(), null, null).isNotEmpty(),
      "Public event should be visible to anonymous caller"
    )
    assertFalse(
      model.filter(privateEventUri.toIri(), null, null).isNotEmpty(),
      "Non-public event should NOT be visible to anonymous caller"
    )
  }

  @Test
  fun `SPARQL with authenticated caller but no readable contexts should return empty results`() {
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

    val graphResponse = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $tokenWithoutReadableContexts")
      .setBody("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
      .execute()

    assertEquals(200, graphResponse.statusCode)
    val model: Model = ByteArrayInputStream(graphResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    assertTrue(model.isEmpty(), "No-readable-context token must not fall through to the whole store")

    val askResponse = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/sparql-results+json")
      .addHeader("Authorization", "Bearer $tokenWithoutReadableContexts")
      .setBody("ASK { ?s ?p ?o }")
      .execute()

    assertEquals(200, askResponse.statusCode)
    assertTrue(askResponse.responseBody.contains("\"boolean\":false"), "ASK must be false for empty scope")

    val dataIndependentAskResponse =
      http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
        .addHeader("Content-Type", "application/sparql-query")
        .addHeader("Accept", "application/sparql-results+json")
        .addHeader("Authorization", "Bearer $tokenWithoutReadableContexts")
        .setBody("ASK WHERE { BIND(1 AS ?x) }")
        .execute()

    assertEquals(200, dataIndependentAskResponse.statusCode)
    assertTrue(
      dataIndependentAskResponse.responseBody.contains("\"boolean\":true"),
      "Data-independent ASK must still be evaluated against an empty store",
    )
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
  fun `SPARQL CONSTRUCT with manage root token should see slash-delimited descendant contexts`() {
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
    val sparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $manageRootToken")
      .setBody(sparqlQuery)
      .execute()

    assertEquals(200, response.statusCode)
    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
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
  fun `SPARQL endpoint should accept literals containing keyword tokens`() {
    val pod = sempodsTestFactory.newPod()

    // A naive substring filter would have rejected this query because the literal
    // contains "CREATE", "INSERT", and "SERVICE". Parser-based validation accepts it.
    val query = """SELECT ?s WHERE { ?s ?p "needs CREATE INSERT SERVICE handling" } LIMIT 1"""
    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/sparql-results+json")
      .setBody(query)
      .execute()

    assertEquals(200, response.statusCode)
  }

  @Test
  fun `SPARQL with invalid bearer should return 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/sparql/query")
      .addHeader("Content-Type", "application/sparql-query")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer not-a-real-jwt")
      .setBody("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 response must include WWW-Authenticate header")
    assertTrue(authHeader.startsWith("Bearer "), "WWW-Authenticate should be a Bearer challenge, was: $authHeader")
    assertTrue(
      authHeader.contains("/.well-known/oauth-protected-resource"),
      "Challenge must point at RFC 9728 metadata URL, was: $authHeader"
    )
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
}
