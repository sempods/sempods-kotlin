package org.sempods.api.pod.resources

import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.utils.UriEncodingUtil.encodeUriToUrlSafeBase64
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.toIri
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.getAll
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodResourceEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val httpClient by lazy { http.followingRedirects }

  private fun withContext(url: String, context: String): String =
    "$url?context=${URLEncoder.encode(context, StandardCharsets.UTF_8)}"

  private fun preparePatch(url: String) =
    http.prepare("PATCH", url).setFollowRedirect(true)

  /**
   * Creates a context via DAO (bypassing HTTP auth) and returns the context URI plus
   * a fake OAuth Bearer token with read+write scopes for that context.
   */
  private fun createContextWithToken(pod: PodDbo, contextPath: String): Pair<URI, String> {
    val podId = checkNotNull(pod.id)
    // Through the builder, so these tests exercise the namespace the server actually mints rather
    // than a string assembled here — [contextPath] is relative to `_system/contexts/`.
    val contextUri = sempodsUriBuilder.buildContext(pod.name, contextPath)
    podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    val readScope = "${contextUri}#read"
    val writeScope = "${contextUri}#write"
    val token = mintScopedToken(pod.name, listOf(readScope, writeScope))
    return contextUri to token
  }

  private fun resolveJsonLdPropertyKeyByLiteral(jsonLd: String, literal: String): String {
    val root = JsonMappers.default().readValue(jsonLd, Map::class.java) as Map<*, *>
    return root.entries
      .firstOrNull { (key, value) ->
        key is String && key !in setOf("@context", "@id", "@type") && jsonValueContainsLiteral(value, literal)
      }
      ?.key
      ?.toString()
      ?: throw IllegalStateException("Unable to resolve JSON-LD key for literal '$literal'")
  }

  private fun jsonValueContainsLiteral(value: Any?, literal: String): Boolean {
    return when (value) {
      is String -> value == literal
      is List<*> -> value.any { jsonValueContainsLiteral(it, literal) }
      is Map<*, *> -> value.values.any { jsonValueContainsLiteral(it, literal) }
      else -> false
    }
  }

  @Test
  fun `GET resource as JSON-LD should work via HTTP`() {
    val pod = sempodsTestFactory.newPod()

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)

    val eventName = "Test Event - ${TestUtil.randomId()}"
    val eventDescription = "This is a test event created via HTTP test"
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = eventName,
      description = eventDescription,
    )

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .execute()

    assertEquals(200, response.statusCode)
    assertTrue(
      response.contentType.orEmpty().startsWith("application/ld+json") || response.contentType.orEmpty().startsWith("application/json"),
      "Content type should be application/ld+json or application/json, was: ${response.contentType}"
    )
    val responseBody = response.responseBody
    assertTrue(responseBody.contains(eventName))
    assertTrue(responseBody.contains(eventDescription))
  }

  @Test
  fun `GET resource with include_contexts returns JSON-LD named graphs`() {
    val pod = sempodsTestFactory.newPod()
    val (ctxA, _) = createContextWithToken(pod, "ctx-a")
    val (ctxB, _) = createContextWithToken(pod, "ctx-b")
    val tokenAB = mintScopedToken(
      podName = pod.name,
      scopes = listOf("${ctxA}#read", "${ctxB}#read"),
    )
    val resourceUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob")
    val resource = resourceUri.toIri()
    val vf = SimpleValueFactory.getInstance()
    val model = LinkedHashModel()
    model.add(resource, vf.createIRI("https://schema.org/name"), vf.createLiteral("Bob"), ctxA.toIri())
    model.add(resource, vf.createIRI("https://schema.org/description"), vf.createLiteral("Private note"), ctxB.toIri())
    podFacade.putResourceModel(
      podName = pod.name,
      resourceUri = resourceUri,
      model = model,
    )

    val response = httpClient.prepareGet("${resourceUri}?include_contexts=true")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $tokenAB")
      .execute()

    assertEquals(200, response.statusCode)
    val body = JsonMappers.default().readValue(response.responseBody, List::class.java)
    val graphIds = body.map { (it as Map<*, *>)["@id"] }.toSet()
    assertEquals(setOf(ctxA.toString(), ctxB.toString()), graphIds)
    val graphById = body.associateBy { (it as Map<*, *>)["@id"] }
    val ctxAGraph = ((graphById[ctxA.toString()] as Map<*, *>)["@graph"] as List<*>).first() as Map<*, *>
    val ctxBGraph = ((graphById[ctxB.toString()] as Map<*, *>)["@graph"] as List<*>).first() as Map<*, *>
    // Canonical JSON-LD: every literal is a value object, not a bare string.
    assertEquals(listOf(mapOf("@value" to "Bob")), ctxAGraph["https://schema.org/name"])
    assertEquals(listOf(mapOf("@value" to "Private note")), ctxBGraph["https://schema.org/description"])
  }

  @Test
  fun `GET resource with invalid include_contexts returns 400 before auth`() {
    val pod = sempodsTestFactory.newPod()
    val resourceUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob")

    val response = httpClient.prepareGet("${resourceUri}?include_contexts=foo")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer not-a-real-jwt")
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `GET resource as N-Quads should work via HTTP`() {
    val pod = sempodsTestFactory.newPod()

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)

    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "Test Event NQuads - ${TestUtil.randomId()}",
    )

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/n-quads")
      .execute()

    assertEquals(200, response.statusCode)
    assertEquals("application/n-quads", response.contentType.orEmpty().split(";")[0])

    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { inStream ->
      RdfWriterUtil.readNQuads(inStream)
    }
    assertNotNull(model)
    assertTrue(model.isNotEmpty())
    val nameStatements = model.filter(eventUri.toIri(), null, null)
    assertTrue(nameStatements.isNotEmpty())
  }

  @Test
  fun `GET resource with invalid bearer should return 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "event ${TestUtil.randomId()}",
    )

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer not-a-real-jwt")
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
  fun `GET non-existent resource should return 404 via HTTP`() {
    val pod = sempodsTestFactory.newPod()
    val nonExistentId = TestUtil.randomId()

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$nonExistentId"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `conditional GET must not leak existence of a resource in an unreadable context`() {
    val pod = sempodsTestFactory.newPod()
    // Resource lives ONLY in a private context the anonymous caller cannot read.
    val (ctxPrivate, _) = createContextWithToken(pod, "ctx-private")
    val resourceUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/secret-${TestUtil.randomId()}")
    val resource = resourceUri.toIri()
    val vf = SimpleValueFactory.getInstance()
    val model = LinkedHashModel().apply {
      add(resource, vf.createIRI("https://schema.org/name"), vf.createLiteral("Top secret"), ctxPrivate.toIri())
    }
    podFacade.putResourceModel(podName = pod.name, resourceUri = resourceUri, model = model)

    // Baseline: a plain anonymous GET is 404 (no visible statements).
    val plain = httpClient.prepareGet(resourceUri.toString())
      .addHeader("Accept", "application/ld+json")
      .execute()
    assertEquals(404, plain.statusCode)

    // If-None-Match: * must not short-circuit to 304 — that would leak existence and an ETag.
    val ifNoneMatch = httpClient.prepareGet(resourceUri.toString())
      .addHeader("Accept", "application/ld+json")
      .addHeader("If-None-Match", "*")
      .execute()
    assertEquals(404, ifNoneMatch.statusCode, "If-None-Match:* on an unreadable resource must be 404, not 304")
    assertNull(ifNoneMatch.headers.get("ETag"), "no ETag may leak for an unreadable resource")

    // If-Match: <any> must not short-circuit to 412 either.
    val ifMatch = httpClient.prepareGet(resourceUri.toString())
      .addHeader("Accept", "application/ld+json")
      .addHeader("If-Match", "\"0-jsonld\"")
      .execute()
    assertEquals(404, ifMatch.statusCode, "If-Match on an unreadable resource must be 404, not 412")
  }

  @Test
  fun `conditional GET with matching If-None-Match still returns 304 for a readable resource`() {
    val pod = sempodsTestFactory.newPod()
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "cond ${TestUtil.randomId()}",
    )

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    val first = httpClient.prepareGet(url).addHeader("Accept", "application/ld+json")
      .execute()
    assertEquals(200, first.statusCode)
    val etag = assertNotNull(first.headers.get("ETag"), "a readable resource must carry an ETag")

    val second = httpClient.prepareGet(url).addHeader("Accept", "application/ld+json")
      .addHeader("If-None-Match", etag)
      .execute()
    assertEquals(304, second.statusCode, "matching If-None-Match must still short-circuit to 304")
  }

  @Test
  fun `GET unknown resource path should return 404 via HTTP`() {
    val pod = sempodsTestFactory.newPod()
    val eventId = TestUtil.randomId()

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/invalid-view-name/$eventId"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `GET system area via resource endpoint should return 404`() {
    val pod = sempodsTestFactory.newPod()

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/private"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .execute()

    assertEquals(404, response.statusCode)
  }

  // `:pod/.well-known/...` is reserved for OAuth/MCP discovery (served by
  // [org.sempods.api.pod.system.auth.PodOAuthMetadataEndpoint]). The pod-resource
  // CRUD endpoint must reject every method on every `.well-known/...` path that JAX-RS
  // routes to it, exactly like `:pod/_system/...`. These tests pin that protection so a
  // future refactor cannot accidentally let CRUD writes land on a discovery path.
  @Test
  fun `GET well-known area via resource endpoint should return 404`() {
    val pod = sempodsTestFactory.newPod()

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/.well-known/random-probe"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `PUT well-known area via resource endpoint should not write`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/.well-known/random-probe"
    val response = httpClient.preparePut(withContext(url, writeContextUri.toString()))
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"@id":"$url","https://schema.org/name":"hijack"}""")
      .execute()

    // Must not be 2xx — protection comes from `rejectSystemArea` for unmapped
    // `.well-known/*` paths and from JAX-RS method-routing for paths owned by the
    // metadata endpoint (which exposes only GET).
    assertTrue(
      response.statusCode >= 400,
      "PUT on .well-known/* must not write; got ${response.statusCode}",
    )
  }

  @Test
  fun `PATCH well-known area via resource endpoint should not write`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/.well-known/random-probe"
    val response = preparePatch(withContext(url, writeContextUri.toString()))
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"https://schema.org/name":"hijack"}""")
      .execute()

    assertTrue(
      response.statusCode >= 400,
      "PATCH on .well-known/* must not write; got ${response.statusCode}",
    )
  }

  @Test
  fun `DELETE well-known area via resource endpoint should not write`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/.well-known/random-probe"
    val response = httpClient.prepareDelete(withContext(url, writeContextUri.toString()))
      .addHeader("Authorization", "Bearer $token")
      .execute()

    assertTrue(
      response.statusCode >= 400,
      "DELETE on .well-known/* must not write; got ${response.statusCode}",
    )
  }

  @Test
  fun `PUT on the discovery PRM path is refused by the metadata endpoint`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/.well-known/oauth-protected-resource"
    val response = httpClient.preparePut(withContext(url, writeContextUri.toString()))
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"@id":"$url","https://schema.org/name":"hijack"}""")
      .execute()

    // The metadata endpoint exposes only GET on this literal path; JAX-RS returns 405 (or
    // 404 if `rejectSystemArea` catches it via a fallback route). Either way must not 2xx.
    assertTrue(
      response.statusCode >= 400,
      "PUT on the discovery PRM path must not write; got ${response.statusCode}",
    )
  }

  @Test
  fun `PUT should replace all outgoing edges of a resource`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val replacedDescription = "old-description-${TestUtil.randomId()}"
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "old-name-${TestUtil.randomId()}",
      description = replacedDescription,
    )

    val replacementName = "new-name-${TestUtil.randomId()}"
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "$replacementName" <${writeContextUri}> .
    """.trimIndent()
    val putResponse = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()
    assertTrue(
      putResponse.statusCode in setOf(200, 201),
      "expected 200/201 (Iter-0 PUT semantics), got ${putResponse.statusCode}",
    )

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    assertTrue(getResponse.responseBody.contains(replacementName))
    assertFalse(getResponse.responseBody.contains(replacedDescription))
  }

  @Test
  fun `PUT JSON-LD should write resource statements into target context from query parameter`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/default"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val replacementName = "jsonld-name-${TestUtil.randomId()}"
    val putBody = """
      {
        "@context": {"schema":"https://schema.org/"},
        "@id": "$eventUri",
        "@type": "schema:Event",
        "schema:name": "$replacementName"
      }
    """.trimIndent()

    val putResponse = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(putBody)
      .execute()
    assertTrue(
      putResponse.statusCode in setOf(200, 201),
      "expected 200/201 (Iter-0 PUT semantics), got ${putResponse.statusCode}",
    )

    val nquadsResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, nquadsResponse.statusCode)
    val model = ByteArrayInputStream(nquadsResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val resourceStatements = model.getStatements(eventUri.toIri(), null, null)
    assertTrue(resourceStatements.any())
    assertTrue(
      resourceStatements.all { stmt -> stmt.context?.stringValue() == writeContextUri.toString() },
    )
  }

  @Test
  fun `PUT JSON-LD with a nested anonymous object (blank node) returns 400`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/default"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    // The nested object has no `@id` → JSON-LD expands it to a blank-node object. Blank nodes are
    // forbidden and must be rejected with 400 (not surface as a 500 from the ResourceBoundary).
    val putBody = """
      {
        "@context": {"schema":"https://schema.org/"},
        "@id": "$eventUri",
        "@type": "schema:Event",
        "schema:location": {"schema:name": "Stadtpark"}
      }
    """.trimIndent()

    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(putBody)
      .execute()
    assertEquals(
      400,
      response.statusCode,
      "blank node must be rejected with 400, got ${response.statusCode}: ${response.responseBody}",
    )
  }

  @Test
  fun `DELETE should remove outgoing edges but keep incoming edges from other resources`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val targetId = TestUtil.randomId()
    val targetUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = targetId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = targetUri,
      context = writeContextUri,
      name = "target-${TestUtil.randomId()}",
    )

    val referencingId = TestUtil.randomId()
    val referencingUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = referencingId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = referencingUri,
      context = writeContextUri,
      name = "ref-${TestUtil.randomId()}",
      location = targetUri,
    )

    val deleteResponse = httpClient.prepareDelete(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$targetId", writeContextUri.toString())
    )
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(204, deleteResponse.statusCode)

    val deletedGet = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$targetId")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(404, deletedGet.statusCode)

    val incomingGet = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$referencingId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, incomingGet.statusCode)
    val model = ByteArrayInputStream(incomingGet.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    assertTrue(
      model.getStatements(referencingUri.toIri(), null, targetUri.toIri()).any(),
    )
  }

  @Test
  fun `PATCH merge-patch should update single property and keep others`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val oldName = "old-name-${TestUtil.randomId()}"
    val oldDescription = "old-description-${TestUtil.randomId()}"
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = oldName,
      description = oldDescription,
    )

    val beforePatchResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, beforePatchResponse.statusCode)
    val nameKey = resolveJsonLdPropertyKeyByLiteral(beforePatchResponse.responseBody, oldName)

    val newName = "patched-name-${TestUtil.randomId()}"
    val patchBody = JsonMappers.default().writeValueAsString(mapOf(nameKey to newName))
    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(patchBody)
      .execute()
    assertEquals(204, patchResponse.statusCode)

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val model = ByteArrayInputStream(getResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val statements = model.getStatements(eventUri.toIri(), null, null, writeContextUri.toIri())
    assertTrue(statements.any { it.predicate.stringValue() == "https://schema.org/name" && it.`object`.stringValue() == newName })
    assertTrue(statements.any { it.predicate.stringValue() == "https://schema.org/description" && it.`object`.stringValue() == oldDescription })
  }

  @Test
  fun `PATCH merge-patch with root id should update single property and keep others`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val oldName = "old-name-${TestUtil.randomId()}"
    val oldDescription = "old-description-${TestUtil.randomId()}"
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = oldName,
      description = oldDescription,
    )

    val beforePatchResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, beforePatchResponse.statusCode)
    val nameKey = resolveJsonLdPropertyKeyByLiteral(beforePatchResponse.responseBody, oldName)

    val newName = "patched-name-${TestUtil.randomId()}"
    val patchBody = JsonMappers.default().writeValueAsString(
      mapOf("@id" to eventUri.toString(), nameKey to newName)
    )
    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(patchBody)
      .execute()
    assertEquals(204, patchResponse.statusCode)

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val model = ByteArrayInputStream(getResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val statements = model.getStatements(eventUri.toIri(), null, null, writeContextUri.toIri())
    assertTrue(statements.any { it.predicate.stringValue() == "https://schema.org/name" && it.`object`.stringValue() == newName })
    assertTrue(statements.any { it.predicate.stringValue() == "https://schema.org/description" && it.`object`.stringValue() == oldDescription })
  }

  @Test
  fun `PATCH merge-patch should remove property when value is null`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val name = "name-${TestUtil.randomId()}"
    val description = "description-${TestUtil.randomId()}"
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = name,
      description = description,
    )

    val beforePatchResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, beforePatchResponse.statusCode)
    val descriptionKey = resolveJsonLdPropertyKeyByLiteral(beforePatchResponse.responseBody, description)

    val patchBody = JsonMappers.default().writeValueAsString(mapOf(descriptionKey to null))
    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(patchBody)
      .execute()
    assertEquals(204, patchResponse.statusCode)

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val model = ByteArrayInputStream(getResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val statements = model.getStatements(eventUri.toIri(), null, null, writeContextUri.toIri())
    assertFalse(statements.any { it.predicate.stringValue() == "https://schema.org/description" })
    assertTrue(statements.any { it.predicate.stringValue() == "https://schema.org/name" && it.`object`.stringValue() == name })
  }

  @Test
  fun `PATCH merge-patch should update one property and remove another in single request`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val originalName = "name-${TestUtil.randomId()}"
    val originalDescription = "description-${TestUtil.randomId()}"
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = originalName,
      description = originalDescription,
    )

    val beforePatchResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, beforePatchResponse.statusCode)
    val nameKey = resolveJsonLdPropertyKeyByLiteral(beforePatchResponse.responseBody, originalName)
    val descriptionKey = resolveJsonLdPropertyKeyByLiteral(beforePatchResponse.responseBody, originalDescription)

    val newName = "patched-name-${TestUtil.randomId()}"
    val patchBody = JsonMappers.default().writeValueAsString(mapOf(nameKey to newName, descriptionKey to null))
    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(patchBody)
      .execute()
    assertEquals(204, patchResponse.statusCode)

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val model = ByteArrayInputStream(getResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val statements = model.getStatements(eventUri.toIri(), null, null, writeContextUri.toIri())
    assertTrue(statements.any { it.predicate.stringValue() == "https://schema.org/name" && it.`object`.stringValue() == newName })
    assertFalse(statements.any { it.predicate.stringValue() == "https://schema.org/description" })
  }

  @Test
  fun `PATCH merge-patch should replace JSON-LD literal value object with IRI value object`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val oldStatus = "schema:PotentialActionStatus"
    val statusPredicate = "https://schema.org/actionStatus"
    val newStatus = "https://schema.org/PotentialActionStatus"
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <${statusPredicate}> "$oldStatus" <${writeContextUri}> .
    """.trimIndent()
    val putResponse = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()
    assertTrue(
      putResponse.statusCode in setOf(200, 201),
      "expected 200/201 (Iter-0 PUT semantics), got ${putResponse.statusCode}",
    )

    val beforePatchResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, beforePatchResponse.statusCode)
    val statusKey = resolveJsonLdPropertyKeyByLiteral(beforePatchResponse.responseBody, oldStatus)

    val patchBody = JsonMappers.default().writeValueAsString(mapOf(statusKey to mapOf("@id" to newStatus)))
    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(patchBody)
      .execute()
    assertEquals(204, patchResponse.statusCode)

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val model = ByteArrayInputStream(getResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val statusIri = SimpleValueFactory.getInstance().createIRI(statusPredicate)
    val statusStatements = model.getStatements(eventUri.toIri(), statusIri, null, writeContextUri.toIri())
    assertTrue(statusStatements.any { it.`object` is IRI && it.`object`.stringValue() == newStatus })
    assertFalse(statusStatements.any { it.`object` is Literal && it.`object`.stringValue() == oldStatus })
  }

  @Test
  fun `PATCH merge-patch should replace JSON-LD IRI value object with literal value object`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val statusPredicate = "https://schema.org/actionStatus"
    val oldStatus = "https://schema.org/PotentialActionStatus"
    val newStatus = "schema:PotentialActionStatus"
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <${statusPredicate}> <${oldStatus}> <${writeContextUri}> .
    """.trimIndent()
    val putResponse = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()
    assertTrue(
      putResponse.statusCode in setOf(200, 201),
      "expected 200/201 (Iter-0 PUT semantics), got ${putResponse.statusCode}",
    )

    val patchBody = """{"$statusPredicate":{"@value":"$newStatus"}}"""
    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(patchBody)
      .execute()
    assertEquals(204, patchResponse.statusCode)

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val model = ByteArrayInputStream(getResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val statusIri = SimpleValueFactory.getInstance().createIRI(statusPredicate)
    val statusStatements = model.getStatements(eventUri.toIri(), statusIri, null, writeContextUri.toIri())
    assertTrue(statusStatements.any { it.`object` is Literal && it.`object`.stringValue() == newStatus })
    assertFalse(statusStatements.any { it.`object` is IRI && it.`object`.stringValue() == oldStatus })
  }

  @Test
  fun `PATCH merge-patch with @context or compact key is rejected with 400 under canonical-form contract`() {
    // Iter-0 behavior break: the LOD-layer PATCH endpoint accepts only the canonical
    // JSON-LD shape — absolute IRI predicate keys, no top-level "@context". Patches that
    // still carry "@context" + compact terms (the pre-Iter-0 contract) are 400, not silently
    // expanded.
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "name-${TestUtil.randomId()}",
    )

    val patchBody = """{"@context":{"schema":"https://schema.org/"},"schema:actionStatus":"x"}"""
    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(patchBody)
      .execute()
    assertEquals(400, patchResponse.statusCode)
    assertTrue(
      patchResponse.responseBody.contains("@context") || patchResponse.responseBody.contains("absolute IRI"),
      "expected error to mention @context or absolute-IRI requirement, was: ${patchResponse.responseBody}",
    )
  }

  @Test
  fun `PATCH merge-patch with absolute IRI null delete removes the property`() {
    // Iter-0 canonical-form replacement for the old `@context + compact null-delete` test:
    // null delete still works, but predicate must be an absolute IRI.
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val name = "name-${TestUtil.randomId()}"
    val description = "description-${TestUtil.randomId()}"
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = name,
      description = description,
    )

    val patchBody = """{"https://schema.org/description":null}"""
    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(patchBody)
      .execute()
    assertEquals(204, patchResponse.statusCode)

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val model = ByteArrayInputStream(getResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val statements = model.getStatements(eventUri.toIri(), null, null, writeContextUri.toIri())
    assertTrue(statements.any { it.predicate.stringValue() == "https://schema.org/name" && it.`object`.stringValue() == name })
    assertFalse(statements.any { it.predicate.stringValue() == "https://schema.org/description" })
  }

  @Test
  fun `PATCH merge-patch null delete should work for @type keyword`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val name = "name-${TestUtil.randomId()}"
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "$name" <${writeContextUri}> .
    """.trimIndent()
    val putResponse = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()
    assertTrue(
      putResponse.statusCode in setOf(200, 201),
      "expected 200/201 (Iter-0 PUT semantics), got ${putResponse.statusCode}",
    )

    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"@type":null}""")
      .execute()
    assertEquals(204, patchResponse.statusCode)

    val getResponse = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val model = ByteArrayInputStream(getResponse.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val rdfType = SimpleValueFactory.getInstance().createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    val typeStatements = model.getStatements(eventUri.toIri(), rdfType, null, writeContextUri.toIri())
    assertFalse(typeStatements.iterator().hasNext(), "rdf:type should be deleted, got: ${typeStatements.toList()}")
    val nameStatements = model.getStatements(
      eventUri.toIri(),
      SimpleValueFactory.getInstance().createIRI("https://schema.org/name"),
      null,
      writeContextUri.toIri(),
    )
    assertTrue(nameStatements.any { it.`object`.stringValue() == name }, "schema:name must remain")
  }

  @Test
  fun `PATCH merge-patch null delete should reject non-type JSON-LD keywords with 400`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "name-${TestUtil.randomId()}",
    )

    for (keyword in listOf("@id", "@graph", "@reverse", "@context")) {
      val patchResponse = preparePatch(
        withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
      )
        .addHeader("Content-Type", "application/merge-patch+json")
        .addHeader("Authorization", "Bearer $token")
        .setBody("""{"$keyword":null}""")
        .execute()
      assertEquals(400, patchResponse.statusCode, "expected 400 for $keyword:null, got ${patchResponse.statusCode}")
      assertTrue(
        patchResponse.responseBody.contains(keyword) && patchResponse.responseBody.contains("@type"),
        "Error should mention rejected keyword $keyword and the only-allowed @type, was: ${patchResponse.responseBody}"
      )
    }
  }

  @Test
  fun `PATCH should return 404 for unknown resource`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()

    val patchResponse = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"@context":{"schema":"https://schema.org/"},"schema:name":"patched"}""")
      .execute()

    assertEquals(404, patchResponse.statusCode)
  }

  @Test
  fun `PUT should return 403 with a read-only token on the target context`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val writeContextUri = sempodsUriBuilder.buildContext(pod.name, writeContext)
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = writeContextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    val readOnlyToken = mintScopedToken(pod.name, listOf("${writeContextUri}#read"))

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "name-${TestUtil.randomId()}" <${writeContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $readOnlyToken")
      .setBody(nQuads)
      .execute()

    assertEquals(403, response.statusCode)
  }

  @Test
  fun `PUT with bogus pod-root manage scope must not authorize writes anywhere in the pod`() {
    // Hardening: `<pod-base>#manage` is rejected by [PodScopeValidator] (pod base is
    // not itself a context URI), but a string-matching authorizer would mistake the
    // pod root as a manage root and grant write access to every `<pod-base>/*` context.
    // The resolver-side sanitizer drops the bogus scope before it reaches
    // [PodContextWriteAuthorizer], which independently validates via the scope
    // parser. This test exercises the full path: malformed JWT → 403, no writes.
    val pod = sempodsTestFactory.newPod()
    val targetContext = "apps/test-app/tasks"
    val targetContextUri = sempodsUriBuilder.buildContext(pod.name, targetContext)
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = targetContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    val podRoot = "${SempodsModule.config.apiBaseUrl}${pod.name}"
    val bogusToken = mintScopedToken(pod.name, listOf("${podRoot}#manage"))

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${targetContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", targetContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $bogusToken")
      .setBody(nQuads)
      .execute()

    assertEquals(
      403,
      response.statusCode,
      "pod-root manage scope must NOT authorize writes (got ${response.statusCode})"
    )
  }

  @Test
  fun `PUT with manage scope on context root should reject writes to a sibling-prefix context`() {
    // Regression: raw startsWith would let `tasks#manage` reach `tasks-private` because the
    // string prefix matches. Manage must only authorize the root itself or slash-delimited
    // descendants.
    val pod = sempodsTestFactory.newPod()
    val rootContext = "apps/test-app/tasks"
    val rootContextUri = sempodsUriBuilder.buildContext(pod.name, rootContext)
    val siblingContext = "apps/test-app/tasks-private"
    val siblingContextUri = sempodsUriBuilder.buildContext(pod.name, siblingContext)
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = rootContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = siblingContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    val manageRootToken = mintScopedToken(pod.name, listOf("${rootContextUri}#manage"))

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${siblingContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", siblingContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $manageRootToken")
      .setBody(nQuads)
      .execute()

    assertEquals(
      403,
      response.statusCode,
      "manage on '$rootContextUri' must NOT authorize writes to sibling '$siblingContextUri'"
    )
  }

  @Test
  fun `PUT with manage scope on context root should authorize writes to a slash-delimited descendant`() {
    val pod = sempodsTestFactory.newPod()
    val rootContext = "apps/test-app/tasks"
    val rootContextUri = sempodsUriBuilder.buildContext(pod.name, rootContext)
    val childContext = "apps/test-app/tasks/child"
    val childContextUri = sempodsUriBuilder.buildContext(pod.name, childContext)
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = rootContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = childContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    val manageRootToken = mintScopedToken(pod.name, listOf("${rootContextUri}#manage"))

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${childContextUri}> .
      <${eventUri}> <https://schema.org/name> "manage-descendant-${TestUtil.randomId()}" <${childContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", childContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $manageRootToken")
      .setBody(nQuads)
      .execute()

    assertTrue(
      response.statusCode in 200..299,
      "manage on '$rootContextUri' must authorize writes to descendant '$childContextUri' (got ${response.statusCode})"
    )
  }

  @Test
  fun `GET with manage scope on context root should return statements from a slash-delimited descendant`() {
    // Read-side counterpart to "PUT with manage scope … descendant". A service token
    // carrying only `<R>#manage` must reach `<R>/...` for reads too — otherwise a client
    // can write `<R>/events/abc` and immediately get 404 on GET. See
    // `SPS-GRANT-007` (sempods-spec).
    val pod = sempodsTestFactory.newPod()
    val rootContext = "apps/test-app/tasks"
    val rootContextUri = sempodsUriBuilder.buildContext(pod.name, rootContext)
    val childContext = "apps/test-app/tasks/child"
    val childContextUri = sempodsUriBuilder.buildContext(pod.name, childContext)
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = rootContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = childContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    val manageRootToken = mintScopedToken(pod.name, listOf("${rootContextUri}#manage"))

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val nameLiteral = "manage-descendant-read-${TestUtil.randomId()}"
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${childContextUri}> .
      <${eventUri}> <https://schema.org/name> "$nameLiteral" <${childContextUri}> .
    """.trimIndent()
    val putResponse = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", childContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $manageRootToken")
      .setBody(nQuads)
      .execute()
    assertTrue(
      putResponse.statusCode in 200..299,
      "precondition: write must succeed, got ${putResponse.statusCode}"
    )

    val getResponse = httpClient.prepareGet(
      "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    )
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $manageRootToken")
      .execute()

    assertEquals(
      200,
      getResponse.statusCode,
      "manage on '$rootContextUri' must authorize reads on descendant '$childContextUri' (got ${getResponse.statusCode})"
    )
    assertTrue(
      getResponse.responseBody.contains(nameLiteral),
      "GET body should include the literal stored in the descendant context"
    )
  }

  @Test
  fun `GET with manage scope on context root must not return statements from a sibling-prefix context`() {
    // Regression: raw `startsWith` would let `<R>#manage` reach `<R>-private`. The read
    // expansion must use the same slash-delimited rule the write authorizer uses
    // (see `PodContextWriteAuthorizer.kt`), so neither path crosses sibling-prefix
    // boundaries.
    val pod = sempodsTestFactory.newPod()
    val rootContext = "apps/test-app/tasks"
    val rootContextUri = sempodsUriBuilder.buildContext(pod.name, rootContext)
    val siblingContext = "apps/test-app/tasks-private"
    val siblingContextUri = sempodsUriBuilder.buildContext(pod.name, siblingContext)
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = rootContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = siblingContextUri.toString(),
      label = null, description = null, createdBy = "test",
    )

    // Seed the sibling context with data using a token that DOES have write access there.
    val seedToken = mintScopedToken(pod.name, listOf("${siblingContextUri}#write"))
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val secretLiteral = "sibling-${TestUtil.randomId()}"
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${siblingContextUri}> .
      <${eventUri}> <https://schema.org/name> "$secretLiteral" <${siblingContextUri}> .
    """.trimIndent()
    val seedResponse = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", siblingContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $seedToken")
      .setBody(nQuads)
      .execute()
    assertTrue(seedResponse.statusCode in 200..299, "precondition: seed must succeed")

    // Now read with a token that has manage on the root only — sibling must stay invisible.
    val manageRootToken = mintScopedToken(pod.name, listOf("${rootContextUri}#manage"))
    val getResponse = httpClient.prepareGet(
      "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    )
      .addHeader("Accept", "application/n-quads")
      .addHeader("Authorization", "Bearer $manageRootToken")
      .execute()

    assertEquals(
      404,
      getResponse.statusCode,
      "manage on '$rootContextUri' must NOT authorize reads on sibling '$siblingContextUri'"
    )
  }

  @Test
  fun `PUT without bearer should return 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, _) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .setBody(nQuads)
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 response must include WWW-Authenticate header")
    assertTrue(authHeader.contains("/.well-known/oauth-protected-resource"))
  }

  @Test
  fun `DELETE should return 404 for unknown resource`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()

    val response = httpClient.prepareDelete(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Authorization", "Bearer $token")
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `PUT should return 400 when context query parameter is missing`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "apps/test-app/tasks")
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val context = sempodsTestFactory.publicContextUri(pod.name)

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${context}> .
      <${eventUri}> <https://schema.org/name> "name-${TestUtil.randomId()}" <${context}> .
    """.trimIndent()
    val response = httpClient.preparePut("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `DELETE should return 400 when context query parameter is missing`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "apps/test-app/tasks")
    val eventId = TestUtil.randomId()

    val response = httpClient.prepareDelete("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Authorization", "Bearer $token")
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `PUT should return 404 for unknown context`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "apps/test-app/known")
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val unknownContextUri = sempodsUriBuilder.buildContext(pod.name, "apps/unknown/tasks")

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${unknownContextUri}> .
      <${eventUri}> <https://schema.org/name> "name-${TestUtil.randomId()}" <${unknownContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", unknownContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `PUT should return 400 when n-quads contain explicit context different from target context`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val foreignContextUri = sempodsUriBuilder.buildContext(pod.name, "apps/other-app/tasks")

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "name-${TestUtil.randomId()}" <${foreignContextUri}> .
    """.trimIndent()

    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(response.responseBody.contains("other than"))
  }

  // ── Iteration 0 — RFC 9110 / RFC 7232 conformance ──────────────────────────

  @Test
  fun `PUT to a new resource returns 201 Created with Location header`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "fresh-name" <${writeContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()

    assertEquals(201, response.statusCode)
    val location = response.headers.get("Location")
    assertNotNull(location, "201 Created must include Location header")
    assertEquals(eventUri.toString(), location)
  }

  @Test
  fun `PUT to an existing resource returns 200 OK without Location`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "initial-name",
    )

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "replaced-name" <${writeContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()

    assertEquals(200, response.statusCode)
    assertEquals(null, response.headers.get("Location"), "200 OK on update must not include Location")
  }

  @Test
  fun `PUT with If-None-Match star on existing resource returns 412`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "occupant",
    )

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "second" <${writeContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-None-Match", "*")
      .setBody(nQuads)
      .execute()

    assertEquals(412, response.statusCode)
  }

  @Test
  fun `PUT with If-None-Match star on new resource succeeds with 201`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "first" <${writeContextUri}> .
    """.trimIndent()
    val response = httpClient.preparePut(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-None-Match", "*")
      .setBody(nQuads)
      .execute()

    assertEquals(201, response.statusCode)
  }

  @Test
  fun `DELETE with --gzip-suffixed If-Match should succeed`() {
    // Jetty's GzipHandler appends "--gzip" to the ETag it emits on a
    // compressed GET response (RFC 9110 §8.8.3). Clients faithfully
    // echo that exact tag back in If-Match on the subsequent DELETE.
    // Since the handler does not strip the suffix from inbound If-Match
    // on a write (the DELETE response has no body to compress), the
    // application must tolerate the suffix itself or every gzip-aware
    // client gets a 412 loop. See `BaseEndpoint.evaluatePreconditions`.
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "to be deleted",
    )

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    val getResponse = httpClient.prepareGet(withContext(url, writeContextUri.toString()))
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val rawEtag = assertNotNull(getResponse.headers.get("ETag"))
    // Force the gzip-suffixed variant regardless of whether the
    // container actually compressed this particular response — we
    // want to exercise the precondition path with the suffix present.
    val gzipEtag = if (rawEtag.endsWith("--gzip\"")) rawEtag
    else rawEtag.replace(Regex("\"$"), "--gzip\"")

    val deleteResponse = httpClient.prepareDelete(withContext(url, writeContextUri.toString()))
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", gzipEtag)
      .execute()

    assertEquals(
      204,
      deleteResponse.statusCode,
      "DELETE with gzip-suffixed If-Match should pass the precondition (was: $gzipEtag)",
    )
  }

  @Test
  fun `PATCH with stale If-Match returns 412`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "live",
    )

    val response = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", "\"definitely-not-the-current-tag\"")
      .setBody("""{"https://schema.org/name":"new"}""")
      .execute()

    assertEquals(412, response.statusCode)
  }

  @Test
  fun `HEAD returns same headers as GET without body`() {
    val pod = sempodsTestFactory.newPod()
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "Head Test",
    )

    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    val getResponse = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .execute()
    val headResponse = http.prepareHead(url)
      .addHeader("Accept", "application/ld+json")
      .execute()

    assertEquals(200, headResponse.statusCode)
    assertEquals(getResponse.contentType, headResponse.contentType)
    assertEquals(getResponse.headers.get("ETag"), headResponse.headers.get("ETag"))
    // Vary header set: container may add gzip-related Vary values too — we only require
    // that "Accept" is part of one of the Vary values across all entries.
    val varyValues = headResponse.headers.getAll("Vary").orEmpty()
    assertTrue(
      varyValues.any { it.contains("Accept") },
      "HEAD response should advertise Vary: Accept (got: $varyValues)",
    )
    assertTrue(
      headResponse.responseBody.isNullOrEmpty(),
      "HEAD must not return a response body, was: ${headResponse.responseBody}",
    )
  }

  @Test
  fun `OPTIONS returns Allow header including writes when caller has write scope`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()

    val response = http.prepareOptions("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Authorization", "Bearer $token")
      .execute()

    assertEquals(200, response.statusCode)
    val allow = response.headers.get("Allow") ?: ""
    listOf("GET", "HEAD", "OPTIONS", "PUT", "PATCH", "DELETE").forEach { method ->
      assertTrue(allow.contains(method), "Allow header should include $method, was: $allow")
    }
  }

  @Test
  fun `OPTIONS returns Allow header without writes for anonymous caller`() {
    val pod = sempodsTestFactory.newPod()
    val eventId = TestUtil.randomId()

    val response = http.prepareOptions("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .execute()

    assertEquals(200, response.statusCode)
    val allow = response.headers.get("Allow") ?: ""
    assertTrue(allow.contains("GET"), "Allow should include GET, was: $allow")
    assertTrue(allow.contains("HEAD"), "Allow should include HEAD, was: $allow")
    assertTrue(allow.contains("OPTIONS"), "Allow should include OPTIONS, was: $allow")
    listOf("PUT", "PATCH", "DELETE").forEach { method ->
      assertFalse(allow.contains(method), "Allow must not include $method for anonymous, was: $allow")
    }
  }

  @Test
  fun `GET emits Vary Accept on content-negotiated responses`() {
    val pod = sempodsTestFactory.newPod()
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "Vary Test",
    )

    val response = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/ld+json")
      .execute()

    assertEquals(200, response.statusCode)
    val varyValues = response.headers.getAll("Vary").orEmpty()
    // We require "Accept" as a Vary directive (content negotiation marker), separate from
    // any "Accept-Encoding" the container might add for compression.
    val mentionsAccept = varyValues.any { directive ->
      directive.split(",").map(String::trim).any { it.equals("Accept", ignoreCase = true) }
    }
    assertTrue(mentionsAccept, "Vary header should include Accept (was: $varyValues)")
  }

  @Test
  fun `GET with unsatisfiable Accept returns 406 Not Acceptable, and the refusal is traceable`() {
    val pod = sempodsTestFactory.newPod()
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = publicContext,
      name = "406 Test",
    )

    val traceId = UUID.randomUUID().toString().replace("-", "")

    val response = httpClient.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId")
      .addHeader("Accept", "application/xml")
      .addHeader("traceparent", "00-$traceId-00f067aa0ba902b7-01")
      .execute()

    assertEquals(406, response.statusCode)

    // A 406 is decided *during* matching, so no resource method runs and a post-matching filter
    // never fires. That the trace comes back is the evidence that `TraceContextFilter` is
    // `@PreMatching` — which is what lets `ApiExceptionMapper` name the caller of a refusal that
    // reached no endpoint. It is also the evidence that the response filter still runs for a
    // matching failure, i.e. that the thread-local binding is released rather than leaked.
    // `docs/request-tracing.md` §"Where the binding lives" carries the rule.
    val echoedTrace = response.getHeader("traceparent")
    assertNotNull(echoedTrace, "a 406 must carry the trace it was refused under")
    assertTrue(echoedTrace.contains(traceId), "the caller's trace id must survive, was: $echoedTrace")
  }

  @Test
  fun `PUT with repeated context query parameter returns 400`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val (otherContextUri, _) = createContextWithToken(pod, "apps/test-app/other")
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "x" <${writeContextUri}> .
    """.trimIndent()
    val baseUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    val url = "$baseUrl?context=${URLEncoder.encode(writeContextUri.toString(), StandardCharsets.UTF_8)}" +
        "&context=${URLEncoder.encode(otherContextUri.toString(), StandardCharsets.UTF_8)}"

    val response = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(
      response.responseBody.contains("exactly one") || response.responseBody.contains("repeated"),
      "expected error to mention repeated/exactly-one rule, was: ${response.responseBody}",
    )
  }

  @Test
  fun `GET with repeated context query parameter downscopes to the intersection`() {
    val pod = sempodsTestFactory.newPod()
    val (ctxA, _) = createContextWithToken(pod, "ctx-a")
    val (ctxB, _) = createContextWithToken(pod, "ctx-b")
    val (ctxC, _) = createContextWithToken(pod, "ctx-c")
    val tokenAll = mintScopedToken(
      podName = pod.name,
      scopes = listOf("${ctxA}#read", "${ctxB}#read", "${ctxC}#read"),
    )
    val resourceUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob")
    val resource = resourceUri.toIri()
    val vf = SimpleValueFactory.getInstance()
    val model = LinkedHashModel()
    model.add(resource, vf.createIRI("https://schema.org/name"), vf.createLiteral("In A"), ctxA.toIri())
    model.add(resource, vf.createIRI("https://schema.org/description"), vf.createLiteral("In B"), ctxB.toIri())
    model.add(resource, vf.createIRI("https://schema.org/text"), vf.createLiteral("In C"), ctxC.toIri())
    podFacade.putResourceModel(podName = pod.name, resourceUri = resourceUri, model = model)

    val url = "${resourceUri}?context=${URLEncoder.encode(ctxA.toString(), StandardCharsets.UTF_8)}" +
        "&context=${URLEncoder.encode(ctxB.toString(), StandardCharsets.UTF_8)}"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $tokenAll")
      .execute()

    assertEquals(200, response.statusCode)
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java)
    // Canonical JSON-LD: keys are absolute IRIs.
    assertTrue(body.containsKey("https://schema.org/name"), "A is in scope, name must be present")
    assertTrue(body.containsKey("https://schema.org/description"), "B is in scope, description must be present")
    assertFalse(body.containsKey("https://schema.org/text"), "C must be filtered out, intersection ⊂ {A,B}")
  }

  @Test
  fun `PATCH with bare compact predicate key is rejected with 400`() {
    // Regression for finding P1: URI("schema:name").isAbsolute is true (scheme = "schema"),
    // so the earlier "isAbsolute" check let compact terms slip through. Strict canonical
    // form must reject them.
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "name-${TestUtil.randomId()}",
    )

    val response = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      // No @context block — just a compact predicate key on its own.
      .setBody("""{"schema:name":[{"@value":"new"}]}""")
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(
      response.responseBody.contains("schema:name") || response.responseBody.contains("absolute IRI"),
      "expected rejection mentioning compact key, was: ${response.responseBody}",
    )
  }

  @Test
  fun `PATCH with compact type value is rejected with 400`() {
    // Regression for the @type variant of the P1 compact-key bypass: predicate keys are
    // strict, but the @type value must hold the same canonical-absolute-IRI bar.
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "name-${TestUtil.randomId()}",
    )

    val response = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"@type":"schema:Event"}""")
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(
      response.responseBody.contains("@type") &&
          (response.responseBody.contains("absolute IRI") || response.responseBody.contains("schema:Event")),
      "expected @type error to mention absolute-IRI requirement or the offending value, was: ${response.responseBody}",
    )
  }

  @Test
  fun `PATCH with compact type value inside array is rejected with 400`() {
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "name-${TestUtil.randomId()}",
    )

    val response = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"@type":["https://schema.org/Event","schema:Audience"]}""")
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `PATCH with urn and did predicate keys is accepted`() {
    // Ensure the stricter check still admits opaque-form absolute IRIs the LOD spec
    // explicitly supports (urn:, did:, mailto:, tag:, ...).
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = eventUri,
      context = writeContextUri,
      name = "name-${TestUtil.randomId()}",
    )

    val response = preparePatch(
      withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId", writeContextUri.toString())
    )
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(
        """{"urn:example:foo":[{"@value":"a"}],"did:web:example.com":[{"@id":"https://example.com/x"}]}"""
      )
      .execute()

    assertEquals(204, response.statusCode)
  }

  @Test
  fun `PUT with one valid and one blank context query parameter returns 400`() {
    // Regression for finding P2: `?context=valid&context=` was previously treated as a
    // single context because blanks were filtered before counting. The "exactly one"
    // rule applies to parameter occurrences, not to non-blank values.
    val pod = sempodsTestFactory.newPod()
    val writeContext = "apps/test-app/tasks"
    val (writeContextUri, token) = createContextWithToken(pod, writeContext)
    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)

    val nQuads = """
      <${eventUri}> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <${writeContextUri}> .
      <${eventUri}> <https://schema.org/name> "x" <${writeContextUri}> .
    """.trimIndent()
    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId" +
        "?context=${URLEncoder.encode(writeContextUri.toString(), StandardCharsets.UTF_8)}&context="

    val response = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `GET with only-unknown contexts returns 404 without leaking topology`() {
    val pod = sempodsTestFactory.newPod()
    val (ctxA, tokenA) = createContextWithToken(pod, "ctx-a")
    val resourceUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob")
    val resource = resourceUri.toIri()
    val vf = SimpleValueFactory.getInstance()
    val model = LinkedHashModel()
    model.add(resource, vf.createIRI("https://schema.org/name"), vf.createLiteral("Bob"), ctxA.toIri())
    podFacade.putResourceModel(podName = pod.name, resourceUri = resourceUri, model = model)

    val unknownContext = "${SempodsModule.config.apiBaseUrl}${pod.name}/not-a-real-context"
    val url = "${resourceUri}?context=${URLEncoder.encode(unknownContext, StandardCharsets.UTF_8)}"
    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $tokenA")
      .execute()

    assertEquals(404, response.statusCode)
  }

  // ── Iter 3: Discovery — Link headers advertising System-layer entry points ────────

  @Test
  fun `GET resource emits no per-predicate slot-discovery Link headers`() {
    val pod = sempodsTestFactory.newPod()
    val (ctx, token) = createContextWithToken(pod, "contacts")
    val resourceUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob")
    val resource = resourceUri.toIri()
    val vf = SimpleValueFactory.getInstance()
    val model = LinkedHashModel()
    model.add(resource, vf.createIRI("https://schema.org/name"), vf.createLiteral("Bob"), ctx.toIri())
    model.add(resource, vf.createIRI("https://schema.org/description"), vf.createLiteral("note"), ctx.toIri())
    podFacade.putResourceModel(podName = pod.name, resourceUri = resourceUri, model = model)

    // Per-predicate `rel=edit-slot` discovery was removed: it scaled response headers with the
    // resource's predicate count and blew Jetty's responseHeaderSize on rich resources. Clients
    // construct the deterministic System-layer slot URL (`/_system/resources/{b64u(subject)}/
    // {b64u(predicate)}`) directly from the subject + predicate they already hold.
    for (accept in listOf("application/ld+json", "application/n-quads")) {
      val response = httpClient.prepareGet(resourceUri.toString())
        .addHeader("Accept", accept)
        .addHeader("Authorization", "Bearer $token")
        .execute()
      assertEquals(200, response.statusCode)
      assertTrue(
        response.headers.getAll("Link").orEmpty().none { it.contains("edit-slot") },
        "GET must not emit per-predicate edit-slot Link headers (Accept=$accept), got: ${response.headers.getAll("Link")}",
      )
    }
  }
}
