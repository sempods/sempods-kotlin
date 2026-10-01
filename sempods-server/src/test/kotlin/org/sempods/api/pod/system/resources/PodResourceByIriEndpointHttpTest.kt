package org.sempods.api.pod.system.resources

import com.google.inject.Inject
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.assertPodBearerChallenge
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.client.SempodsContent
import org.sempods.client.SempodsContextSelection
import org.sempods.client.SempodsGraphFormat
import org.sempods.client.SempodsReadOptions
import org.sempods.client.SempodsWriteOptions
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.util.Values
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the whole-resource CRUD-by-IRI route on [PodSystemResourcesEndpoint]
 * (`{pod}/_system/resources/{b64u(resourceIri)}`) — see
 * `SPS-CRUD-040` (sempods-spec).
 *
 * Acceptance focus:
 * - whole-resource create → merge-patch → delete on an IRI **outside** the pod namespace,
 * - conditional writes (`If-Match` / `If-None-Match: *`) identical to the LOD layer,
 * - ETag parity: a pod-owned IRI has the same tag via the canonical path and the b64 route.
 */
class PodResourceByIriEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val httpClient by lazy { http.followingRedirects }

  private val schemaName = "https://schema.org/name"
  private val schemaJobTitle = "https://schema.org/jobTitle"

  private fun createContextWithToken(pod: PodDbo, contextPath: String): Pair<URI, String> {
    val podId = checkNotNull(pod.id)
    val contextUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/$contextPath")
    podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    val token = mintScopedToken(pod.name, listOf("${contextUri}#read", "${contextUri}#write"))
    return contextUri to token
  }

  private fun resourceUrl(pod: String, resourceIri: String): String {
    val b64 = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(resourceIri))
    return "${SempodsModule.config.apiBaseUrl}${pod}/_system/resources/${b64}"
  }

  private fun withContext(url: String, contextUri: URI): String =
    "$url?context=${URLEncoder.encode(contextUri.toString(), StandardCharsets.UTF_8)}"

  private fun put(url: String, token: String?, body: String, contentType: String = "application/ld+json") =
    httpClient.preparePut(url)
      .addHeader("Content-Type", contentType)
      .apply { token?.let { addHeader("Authorization", "Bearer $it") } }
      .setBody(body)
      .execute()

  private fun patch(url: String, token: String, body: String, ifMatch: String? = null) =
    http.prepare("PATCH", url)
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
      .apply { ifMatch?.let { addHeader("If-Match", it) } }
      .setBody(body)
      .execute()

  private fun get(url: String, token: String?) =
    httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .apply { token?.let { addHeader("Authorization", "Bearer $it") } }
      .execute()

  private fun delete(url: String, token: String) =
    httpClient.prepareDelete(url)
      .addHeader("Authorization", "Bearer $token")
      .execute()

  // ── acceptance: full lifecycle on an external IRI ────────────────────────────────

  @Test
  fun `create merge-patch delete cycle on an external IRI`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val subjects = podAs(pod.name, bearer = token).subjects()
    val inPrivat = SempodsWriteOptions.inContext(contextUri.toString())

    for (iri in listOf("https://example.org/people/alice", "did:web:bob.example")) {
      assertEquals(201, subjects.put(iri, SempodsGraphFormat.JSON_LD, SempodsContent.of("""{"@id":"$iri","$schemaName":"Alice"}"""), inPrivat).status, iri)
      val read = subjects.getText(iri, options = SempodsReadOptions.of(SempodsContextSelection.of(contextUri.toString())))
      assertEquals(200, read.status, iri)

      // Under the tag of that read; adds a property and keeps the name.
      val patched = subjects.patch(iri, SempodsContent.of("""{"@id":"$iri","$schemaJobTitle":"Engineer"}"""), inPrivat.withIfMatch(read.headers["ETag"]))
      assertEquals(204, patched.status, iri)
      val afterPatch = subjects.getText(iri).body.orEmpty()
      assertTrue(afterPatch.contains("Alice"), "name must survive the merge-patch: $afterPatch")
      assertTrue(afterPatch.contains("Engineer"), "patched property must be present: $afterPatch")

      assertEquals(204, subjects.delete(iri, inPrivat).status, iri)
      assertEquals(404, subjects.getText(iri).status, "resource must be gone after delete: $iri")
    }
  }

  @Test
  fun `a write on the System route locates the b64 route and claims no tag, and a patch under an old tag is 412`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val alice = "https://example.org/people/alice"
    val url = withContext(resourceUrl(pod.name, alice), contextUri)

    val created = put(url, token, """{"@id":"$alice","$schemaName":"Alice"}""")
    assertEquals(201, created.statusCode)
    val location = assertNotNull(created.headers.get("Location"), "201 must carry Location")
    assertTrue(
      location.endsWith("/${UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(alice))}"),
      "Location must point at the b64 route, was: $location",
    )
    // No ETag on a write, as on the canonical route: what is stored is not the body that was sent
    // (`SPS-CRUD-030`). The validator comes from a read of the same context.
    assertNull(created.headers.get("ETag"), "PUT 201 must not claim an ETag")
    val createTag = assertNotNull(get(url, token).headers.get("ETag"))

    val patched = patch(url, token, """{"@id":"$alice","$schemaJobTitle":"Engineer"}""", ifMatch = createTag)
    assertEquals(204, patched.statusCode)
    assertNull(patched.headers.get("ETag"), "PATCH 204 must not claim an ETag")
    assertEquals(412, patch(url, token, """{"@id":"$alice","$schemaName":"Eve"}""", ifMatch = createTag).statusCode)
  }

  // ── acceptance: GET an external IRI as canonical JSON-LD with an ETag ─────────────

  @Test
  fun `GET on external IRI returns canonical JSON-LD with an ETag`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val bob = "did:web:bob.example"
    val subjects = podAs(pod.name, bearer = token).subjects()
    val inPrivat = SempodsWriteOptions.inContext(contextUri.toString())
    assertEquals(201, subjects.put(bob, SempodsGraphFormat.JSON_LD, SempodsContent.of("""{"@id":"$bob","$schemaName":"Bob"}"""), inPrivat).status)

    val read = subjects.getText(bob, options = SempodsReadOptions.of(SempodsContextSelection.of(contextUri.toString())))

    assertEquals(200, read.status)
    assertNotNull(read.headers["ETag"], "a readable resource must carry an ETag")
    assertTrue(read.body.orEmpty().contains("Bob"), read.body)
  }

  // ── Open question 1 resolved: ETag parity across both routes for a pod-owned IRI ─────

  @Test
  fun `ETag and body are identical via canonical path and b64 route for a pod-owned IRI`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val podOwnedIri = "${SempodsModule.config.apiBaseUrl}${pod.name}/things/thing1"
    val core = podAs(pod.name, bearer = token)
    val inPrivat = SempodsReadOptions.of(SempodsContextSelection.of(contextUri.toString()))

    // Written through the canonical LOD path.
    val created = core.resources().put(
      podOwnedIri,
      SempodsGraphFormat.JSON_LD,
      SempodsContent.of("""{"@id":"$podOwnedIri","$schemaName":"Thing"}"""),
      SempodsWriteOptions.inContext(contextUri.toString()),
    )
    assertEquals(201, created.status)

    val viaCanonical = core.resources().getText(podOwnedIri, SempodsGraphFormat.JSON_LD, inPrivat)
    val viaB64 = core.subjects().getText(podOwnedIri, SempodsGraphFormat.JSON_LD, inPrivat)

    assertEquals(200, viaCanonical.status)
    assertEquals(200, viaB64.status)
    assertEquals(
      viaCanonical.headers["ETag"],
      viaB64.headers["ETag"],
      "ETag must be byte-identical across canonical path and b64 route (cross-route conditional writes)",
    )
    assertEquals(viaCanonical.body, viaB64.body, "canonical JSON-LD body must be identical across both routes")
  }

  // ── Conditional writes ──────────────────────────────────────────────────────────────

  @Test
  fun `PUT with If-None-Match star is create-or-fail`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val carol = "https://example.org/people/carol"
    val url = withContext(resourceUrl(pod.name, carol), contextUri)

    val first = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-None-Match", "*")
      .setBody("""{"@id":"$carol","$schemaName":"Carol"}""")
      .execute()
    assertTrue(first.statusCode in setOf(200, 201), "INM:* on a fresh resource must succeed")

    val second = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-None-Match", "*")
      .setBody("""{"@id":"$carol","$schemaName":"Carol again"}""")
      .execute()
    assertEquals(412, second.statusCode, "INM:* on an existing resource must be 412")
  }

  @Test
  fun `PUT with stale If-Match returns 412`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val dave = "https://example.org/people/dave"
    val url = withContext(resourceUrl(pod.name, dave), contextUri)

    assertEquals(201, put(url, token, """{"@id":"$dave","$schemaName":"Dave"}""").statusCode)
    val etag = assertNotNull(get(url, token).headers.get("ETag"))

    // Mutate so the stored validator no longer matches the captured tag.
    assertEquals(204, patch(url, token, """{"@id":"$dave","$schemaJobTitle":"Pilot"}""").statusCode)

    val stale = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", etag)
      .setBody("""{"@id":"$dave","$schemaName":"Dave 2"}""")
      .execute()
    assertEquals(412, stale.statusCode, "stale If-Match must be rejected with 412")
  }

  // ── Guards ───────────────────────────────────────────────────────────────────────────

  @Test
  fun `a control-plane IRI outside the context namespace can be described, one inside it cannot`() {
    // Statements about a control-plane IRI are statements — they live in the caller's context and
    // cannot change control-plane state, which is held in MongoDB and not in the graph.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val podBase = "${SempodsModule.config.apiBaseUrl}${pod.name}"

    val mediaIri = "$podBase/_system/media/m1/content"
    val write = put(
      withContext(resourceUrl(pod.name, mediaIri), contextUri),
      token,
      """{"@id":"$mediaIri","https://schema.org/name":[{"@value":"my note about this media"}]}""",
    )
    assertEquals(201, write.statusCode, write.responseBody)
    val read = get(resourceUrl(pod.name, mediaIri), token)
    assertEquals(200, read.statusCode, "body=${read.responseBody}")
    assertTrue(read.responseBody.contains("my note about this media"), read.responseBody)

    // `GET` under `_system/contexts/` is the context registry, so the pod refuses a subject there.
    // `ContextNamespaceWriteHttpTest` covers the rest of that rule.
    val contextIri = "$podBase/_system/contexts/apps/example/tasks"
    val refused = put(
      withContext(resourceUrl(pod.name, contextIri), contextUri),
      token,
      """{"@id":"$contextIri","https://schema.org/name":[{"@value":"my note about this context"}]}""",
    )
    assertEquals(400, refused.statusCode, refused.responseBody)
    assertTrue(refused.responseBody.contains("'$podBase/_system/contexts/'"), refused.responseBody)
  }

  @Test
  fun `write without write scope returns 403`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, _) = createContextWithToken(pod, "privat")
    val readOnly = mintScopedToken(pod.name, listOf("${contextUri}#read"))
    val erin = "https://example.org/people/erin"

    val response = put(
      withContext(resourceUrl(pod.name, erin), contextUri),
      readOnly,
      """{"@id":"$erin","$schemaName":"Erin"}""",
    )
    assertEquals(403, response.statusCode)
  }

  @Test
  fun `write without a bearer or with a rejected one is challenged`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, _) = createContextWithToken(pod, "privat")
    val gina = "https://example.org/people/gina"

    for (token in listOf(null, "not-a-real-jwt")) {
      val response = put(withContext(resourceUrl(pod.name, gina), contextUri), token, """{"@id":"$gina","$schemaName":"Gina"}""")
      assertPodBearerChallenge(response, pod.name)
    }
  }

  @Test
  fun `write without context query returns 400`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "privat")
    val frank = "https://example.org/people/frank"

    val response = put(resourceUrl(pod.name, frank), token, """{"@id":"$frank","$schemaName":"Frank"}""")
    assertEquals(400, response.statusCode)
  }

  @Test
  fun `an IRI whose segment the standard base64 alphabet would spell differently is written and read back`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val inPrivat = SempodsWriteOptions.inContext(contextUri.toString())
    val subjects = podAs(pod.name, bearer = token).subjects()

    listOf("urn:x:ab~", "https://example.org/ü").forEach { iri ->
      val created = subjects.put(iri, SempodsGraphFormat.JSON_LD, SempodsContent.of("""{"@id":"$iri","$schemaName":"Awkward"}"""), inPrivat)
      assertEquals(201, created.status, iri)
      val read = subjects.getText(iri)
      assertEquals(200, read.status, iri)
      assertTrue(read.body.orEmpty().contains("Awkward"), iri)
    }
  }

  // ── The RDF4J adapter against this route ────────────────────────────────────────

  @Test
  fun `the RDF4J adapter writes a did subject from a model and reads it back with its context`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val bob = "did:web:bob.example"
    val model = LinkedHashModel().apply {
      add(Values.iri(bob), Values.iri(schemaName), Values.literal("Bob"), Values.iri(contextUri.toString()))
      add(Values.iri(bob), Values.iri(schemaJobTitle), Values.literal("Ingenieur", "de-CH"), Values.iri(contextUri.toString()))
    }

    val subjects = rdfAs(pod.name, bearer = token).subjects()

    assertEquals(201, subjects.put(bob, model, SempodsWriteOptions.inContext(contextUri.toString())).status)
    val read = subjects.getModel(bob, SempodsReadOptions.of(SempodsContextSelection.of(contextUri.toString())))
    assertTrue(Models.isomorphic(model, assertNotNull(read.body)), "read back: ${read.body}")
  }
}
