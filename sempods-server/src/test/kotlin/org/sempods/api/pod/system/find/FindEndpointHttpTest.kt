package org.sempods.api.pod.system.find

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.toIri
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.getAll
import org.eclipse.rdf4j.model.Model
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP integration tests for `GET /{pod}/_system/find` — the SPARQL text-match PoC.
 * Mirrors the patterns in [org.sempods.api.system.sparql.SparqlEndpointHttpTest].
 */
class FindEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val schemaEvent = "https://schema.org/Event"
  private val schemaPerson = "https://schema.org/Person"

  private fun findUrl(pod: String) = "${SempodsModule.config.apiBaseUrl}$pod/_system/find"

  private fun newPublicEvent(podName: String, name: String): URI {
    val eventUri = sempodsTestFactory.eventUri(podName = podName, eventId = TestUtil.randomId())
    val publicContext = sempodsTestFactory.publicContextUri(podName)
    sempodsTestFactory.seedEvent(
      pod = podName,
      eventUri = eventUri,
      context = publicContext,
      name = name,
    )
    return eventUri
  }

  @Test
  fun `find matches resources whose literal contains the token`() {
    val pod = sempodsTestFactory.newPod()
    val alice = newPublicEvent(pod.name, "Alice Workshop ${TestUtil.randomId()}")
    val bob = newPublicEvent(pod.name, "Bob Meeting ${TestUtil.randomId()}")

    val response = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", "alice")
      .execute()

    assertEquals(200, response.statusCode)
    assertTrue(response.responseBody.contains(alice.toString()), "matching event should be present")
    assertFalse(response.responseBody.contains(bob.toString()), "non-matching event should be absent")
  }

  @Test
  fun `find requires all tokens within a single literal`() {
    val pod = sempodsTestFactory.newPod()
    val both = newPublicEvent(pod.name, "Alice Annual Workshop ${TestUtil.randomId()}")
    val onlyOne = newPublicEvent(pod.name, "Alice Picnic ${TestUtil.randomId()}")

    val response = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", "alice workshop")
      .execute()

    assertEquals(200, response.statusCode)
    assertTrue(response.responseBody.contains(both.toString()), "literal with both tokens should match")
    assertFalse(response.responseBody.contains(onlyOne.toString()), "literal missing a token should not match")
  }

  @Test
  fun `find matches case-insensitively in both directions`() {
    val pod = sempodsTestFactory.newPod()
    val event = newPublicEvent(pod.name, "Alice Workshop ${TestUtil.randomId()}")

    // The literal is mixed-case; an upper-cased query must still match it. This is the direction
    // that used to depend on `FindRequestParser` having folded the token before it reached the
    // query builder — the builder now folds it itself, so the endpoint keeps this behaviour no
    // matter how the FindRequest was produced.
    val upper = http.prepareGet(findUrl(pod.name)).addQueryParam("text", "ALICE WORKSHOP").execute()
    assertEquals(200, upper.statusCode)
    assertTrue(upper.responseBody.contains(event.toString()), "upper-cased query should match a mixed-case literal")

    val mixed = http.prepareGet(findUrl(pod.name)).addQueryParam("text", "aLiCe").execute()
    assertEquals(200, mixed.statusCode)
    assertTrue(mixed.responseBody.contains(event.toString()), "mixed-case query should match a mixed-case literal")
  }

  @Test
  fun `find with empty text returns 400`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", "   ")
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `find without text parameter returns 400`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(findUrl(pod.name))
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `find with type facet constrains the returned hit type`() {
    val pod = sempodsTestFactory.newPod()
    val token = TestUtil.randomId()
    val event = newPublicEvent(pod.name, "Typed $token")

    // type = Event → returned
    val matching = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", token)
      .addQueryParam("type", schemaEvent)
      .execute()
    assertEquals(200, matching.statusCode)
    assertTrue(matching.responseBody.contains(event.toString()), "Event hit should match type=Event")

    // type = Person → exact match, the Event is filtered out
    val nonMatching = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", token)
      .addQueryParam("type", schemaPerson)
      .execute()
    assertEquals(200, nonMatching.statusCode)
    assertFalse(nonMatching.responseBody.contains(event.toString()), "Event must not match type=Person")
  }

  @Test
  fun `find with multiple type params is OR-combined`() {
    val pod = sempodsTestFactory.newPod()
    val token = TestUtil.randomId()
    val event = newPublicEvent(pod.name, "OrTyped $token")

    val response = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", token)
      .addQueryParam("type", schemaPerson)
      .addQueryParam("type", schemaEvent)
      .execute()

    assertEquals(200, response.statusCode)
    assertTrue(
      response.responseBody.contains(event.toString()),
      "Event should be returned when Event is one of the OR-ed types",
    )
  }

  @Test
  fun `find returns and expands multiple hits`() {
    val pod = sempodsTestFactory.newPod()
    val needle = "multihit${TestUtil.randomId()}"
    val first = newPublicEvent(pod.name, "$needle one")
    val second = newPublicEvent(pod.name, "$needle two")

    val response = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", needle)
      .addHeader("Accept", "application/n-quads")
      .execute()

    assertEquals(200, response.statusCode)
    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    listOf(first, second).forEach { uri ->
      assertTrue(model.filter(uri.toIri(), null, null).isNotEmpty(), "hit $uri must be present")
      assertTrue(
        model.filter(uri.toIri(), org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, null).isNotEmpty(),
        "expansion must add rdf:type for $uri",
      )
    }
  }

  @Test
  fun `find without auth only returns resources from public contexts`() {
    val pod = sempodsTestFactory.newPod()
    val needle = "sandboxneedle${TestUtil.randomId()}"

    val publicEvent = newPublicEvent(pod.name, "$needle public")

    val privateEventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    val privateContext = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/default")
    // Registered like the contexts in the tests below: the write path refuses an unregistered
    // context with a 404, and a context that does not exist would make the assertion below pass for
    // the wrong reason.
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = privateContext.toString(),
      label = null, description = null, createdBy = "test",
    )
    sempodsTestFactory.seedEvent(
      pod = pod.name,
      eventUri = privateEventUri,
      context = privateContext,
      name = "$needle private",
    )

    val response = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", needle)
      .execute()

    assertEquals(200, response.statusCode)
    assertTrue(response.responseBody.contains(publicEvent.toString()), "public event should be visible anonymously")
    assertFalse(
      response.responseBody.contains(privateEventUri.toString()),
      "non-public event must not be visible to an anonymous caller",
    )
  }

  /** Register a context and put a named event into it; returns the event URI. */
  private fun newEventInContext(podName: String, context: URI, name: String): URI {
    val eventUri = sempodsTestFactory.eventUri(podName = podName, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(
      pod = podName,
      eventUri = eventUri,
      context = context,
      name = name,
    )
    return eventUri
  }

  @Test
  fun `find with context downscopes within the readable contexts`() {
    val pod = sempodsTestFactory.newPod()
    val needle = "ctxneedle${TestUtil.randomId()}"

    val contextA = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-a")
    val contextB = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-b")
    listOf(contextA, contextB).forEach {
      podContextsDao.create(
        podId = checkNotNull(pod.id),
        contextUri = it.toString(),
        label = null, description = null, createdBy = "test",
      )
    }
    val eventA = newEventInContext(pod.name, contextA, "$needle in A")
    val eventB = newEventInContext(pod.name, contextB, "$needle in B")

    val token = mintScopedToken(pod.name, listOf("${contextA}#read", "${contextB}#read"))
    fun find(vararg contexts: URI) = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", needle)
      .addHeader("Authorization", "Bearer $token")
      .also { req -> contexts.forEach { req.addQueryParam("context", it.toString()) } }
      .execute()

    // No `context=` → pod-wide within the readable ceiling: both hits.
    val all = find()
    assertEquals(200, all.statusCode)
    assertTrue(all.responseBody.contains(eventA.toString()), "without downscope, A must be present")
    assertTrue(all.responseBody.contains(eventB.toString()), "without downscope, B must be present")

    // `context=A` → only A's hit; B is excluded though the caller may read it.
    val onlyA = find(contextA)
    assertEquals(200, onlyA.statusCode)
    assertTrue(onlyA.responseBody.contains(eventA.toString()), "downscope to A must keep A")
    assertFalse(onlyA.responseBody.contains(eventB.toString()), "downscope to A must drop B")

    // An unknown/unreadable context → silently excluded → empty result, not an error.
    val unknown = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/ctx-nope")
    val none = find(unknown)
    assertEquals(200, none.statusCode)
    assertFalse(none.responseBody.contains(eventA.toString()), "all-unreadable downscope yields no hits")
    assertFalse(none.responseBody.contains(eventB.toString()), "all-unreadable downscope yields no hits")

    // A present-but-blank `?context=` is a present (empty) scope, not absent → fail-closed empty
    // result, NOT a broadening to pod-wide.
    val blank = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", needle)
      .addQueryParam("context", "")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, blank.statusCode)
    assertFalse(
      blank.responseBody.contains(eventA.toString()) || blank.responseBody.contains(eventB.toString()),
      "present-but-blank ?context= must not broaden to pod-wide: ${blank.responseBody}",
    )
  }

  /** Register two contexts on a pod and return their URIs. */
  private fun newReadableContexts(pod: org.sempods.pods.mongo.persist.PodDbo, vararg paths: String): List<URI> =
    paths.map { path ->
      val uri = sempodsUriBuilder.buildContext(pod.name, path)
      podContextsDao.create(
        podId = checkNotNull(pod.id),
        contextUri = uri.toString(),
        label = null, description = null, createdBy = "test",
      )
      uri
    }

  @Test
  fun `find POST mirrors GET with body parameters`() {
    val pod = sempodsTestFactory.newPod()
    val needle = "postneedle${TestUtil.randomId()}"
    val (contextA, contextB) = newReadableContexts(pod, "post-a", "post-b")
    val eventA = newEventInContext(pod.name, contextA, "$needle in A")
    val eventB = newEventInContext(pod.name, contextB, "$needle in B")
    val token = mintScopedToken(pod.name, listOf("${contextA}#read", "${contextB}#read"))

    fun post(body: String) = http.preparePost(findUrl(pod.name))
      .addHeader("Authorization", "Bearer $token")
      .addHeader("Content-Type", "application/json")
      .setBody(body)
      .execute()

    // Body `contexts: [A]` → downscope to A (same as GET `?context=A`).
    val onlyA = post("""{"text":"$needle","contexts":["$contextA"]}""")
    assertEquals(200, onlyA.statusCode)
    assertTrue(onlyA.responseBody.contains(eventA.toString()), "POST downscope to A must keep A")
    assertFalse(onlyA.responseBody.contains(eventB.toString()), "POST downscope to A must drop B")

    // No `contexts` → pod-wide.
    val both = post("""{"text":"$needle"}""")
    assertEquals(200, both.statusCode)
    assertTrue(
      both.responseBody.contains(eventA.toString()) && both.responseBody.contains(eventB.toString()),
      "POST without contexts must be pod-wide",
    )

    // Present-but-empty `contexts` → fail-closed empty scope, NOT pod-wide.
    val emptyScope = post("""{"text":"$needle","contexts":[]}""")
    assertEquals(200, emptyScope.statusCode)
    assertFalse(
      emptyScope.responseBody.contains(eventA.toString()) || emptyScope.responseBody.contains(eventB.toString()),
      "present-but-empty contexts must not broaden to pod-wide: ${emptyScope.responseBody}",
    )

    // Blank-only `contexts` → same fail-closed empty scope.
    val blankScope = post("""{"text":"$needle","contexts":[""," "]}""")
    assertEquals(200, blankScope.statusCode)
    assertFalse(
      blankScope.responseBody.contains(eventA.toString()) || blankScope.responseBody.contains(eventB.toString()),
      "blank-only contexts must not broaden to pod-wide",
    )

    // Empty text → 400, same validation as GET.
    val empty = post("""{"text":"   "}""")
    assertEquals(400, empty.statusCode)

    // Unknown / unsupported field (e.g. the deferred `filter`) → 400, never a silently broadened
    // result. Strict body parsing closes this fail-open.
    val withFilter = post("""{"text":"$needle","filter":{"author":"x"}}""")
    assertEquals(400, withFilter.statusCode)
  }

  @Test
  fun `find include_contexts returns named-graph provenance`() {
    val pod = sempodsTestFactory.newPod()
    val needle = "provctx${TestUtil.randomId()}"
    val (contextA, contextB) = newReadableContexts(pod, "prov-a", "prov-b")
    val eventA = newEventInContext(pod.name, contextA, "$needle in A")
    val eventB = newEventInContext(pod.name, contextB, "$needle in B")
    val token = mintScopedToken(pod.name, listOf("${contextA}#read", "${contextB}#read"))

    fun find(includeContexts: Boolean, accept: String) = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", needle)
      .also { if (includeContexts) it.addQueryParam("include_contexts", "true") }
      .addHeader("Authorization", "Bearer $token")
      .addHeader("Accept", accept)
      .execute()

    // include_contexts=true + N-Quads → each statement carries its source named graph.
    val nq = find(includeContexts = true, accept = "application/n-quads")
    assertEquals(200, nq.statusCode)
    val model: Model = ByteArrayInputStream(nq.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    val contexts = model.contexts().mapNotNull { it?.stringValue() }.toSet()
    assertTrue(contexts.contains(contextA.toString()), "A must appear as a named graph: $contexts")
    assertTrue(contexts.contains(contextB.toString()), "B must appear as a named graph: $contexts")
    assertTrue(
      model.filter(eventA.toIri(), null, null).all { it.context?.stringValue() == contextA.toString() },
      "eventA's statements must sit under context A",
    )

    // include_contexts=true + JSON-LD → named-graph @id for each context.
    val jsonld = find(includeContexts = true, accept = "application/ld+json")
    assertEquals(200, jsonld.statusCode)
    assertTrue(jsonld.responseBody.contains(contextA.toString()), "JSON-LD must name graph A")
    assertTrue(jsonld.responseBody.contains(contextB.toString()), "JSON-LD must name graph B")

    // Default (no include_contexts) → flat: hits present, but no named graphs.
    val flat = find(includeContexts = false, accept = "application/n-quads")
    assertEquals(200, flat.statusCode)
    val flatModel: Model = ByteArrayInputStream(flat.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    assertTrue(flatModel.filter(eventA.toIri(), null, null).isNotEmpty(), "hit still present in flat form")
    assertTrue(flatModel.contexts().all { it == null }, "flat result must carry no named graphs")
  }

  @Test
  fun `find expands hits with type and name`() {
    val pod = sempodsTestFactory.newPod()
    val token = TestUtil.randomId()
    val event = newPublicEvent(pod.name, "Expandable $token")

    val response = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", token)
      .addHeader("Accept", "application/n-quads")
      .execute()

    assertEquals(200, response.statusCode)
    assertEquals("application/n-quads", response.contentType.orEmpty().split(";")[0])

    val model: Model = ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    assertTrue(model.filter(event.toIri(), null, null).isNotEmpty(), "hit must be present")
    assertTrue(
      model.filter(event.toIri(), org.eclipse.rdf4j.model.vocabulary.RDF.TYPE, null).isNotEmpty(),
      "expansion must add rdf:type for the hit",
    )
  }

  @Test
  fun `find sets permission-scoped cache headers`() {
    val pod = sempodsTestFactory.newPod()

    // Anonymous read → cacheable, but Vary keys shared caches on Accept + Authorization.
    val anon = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", "x")
      .execute()
    assertEquals(200, anon.statusCode)
    assertEquals("public", anon.headers.get("Cache-Control"))
    val anonVary = anon.headers.getAll("Vary").joinToString(", ")
    assertTrue(anonVary.contains("Authorization"), "Vary must include Authorization, was: [$anonVary]")
    assertTrue(anonVary.contains("Accept"), "Vary must include Accept, was: [$anonVary]")

    // Authenticated read → must not be stored / shared across callers.
    val contextUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/contacts")
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = contextUri.toString(),
      label = null, description = null, createdBy = "test",
    )
    val token = mintScopedToken(pod.name, listOf("${contextUri}#read"))
    val authed = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", "x")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, authed.statusCode)
    assertEquals("private, no-store", authed.headers.get("Cache-Control"))
    assertTrue(authed.headers.getAll("Vary").joinToString(", ").contains("Authorization"))
  }

  @Test
  fun `find with invalid bearer returns 401 with WWW-Authenticate`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.prepareGet(findUrl(pod.name))
      .addQueryParam("text", "test")
      .addHeader("Authorization", "Bearer not-a-real-jwt")
      .execute()

    assertEquals(401, response.statusCode)
    val authHeader = response.headers.get("WWW-Authenticate")
    assertNotNull(authHeader, "401 response must include WWW-Authenticate header")
    assertTrue(
      authHeader.contains("/.well-known/oauth-protected-resource"),
      "challenge must point at RFC 9728 metadata URL, was: $authHeader",
    )
  }
}
