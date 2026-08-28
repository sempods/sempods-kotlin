package org.sempods.api.pod.system.resources

import com.google.inject.Inject
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

  private fun patch(url: String, token: String, body: String) =
    http.prepare("PATCH", url)
      .addHeader("Content-Type", "application/merge-patch+json")
      .addHeader("Authorization", "Bearer $token")
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

  // ── M2 acceptance: full lifecycle on an external IRI ────────────────────────────────

  @Test
  fun `create merge-patch delete cycle on an external foaf IRI`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val alice = "https://example.org/people/alice"
    val url = withContext(resourceUrl(pod.name, alice), contextUri)

    // CREATE
    val created = put(url, token, """{"@id":"$alice","$schemaName":"Alice"}""")
    assertEquals(201, created.statusCode)
    val location = assertNotNull(created.headers.get("Location"), "201 must carry Location")
    assertTrue(
      location.endsWith("/${UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(alice))}"),
      "Location must point at the b64 route, was: $location",
    )
    // The 201 must echo the post-write precondition ETag, byte-identical to a follow-up GET, so a
    // writer can chain a conditional write without an intervening read.
    val createTag = assertNotNull(created.headers.get("ETag"), "PUT 201 must echo the post-write ETag")
    assertEquals(createTag, get(url, token).headers.get("ETag"), "echoed create ETag must be the GET precondition tag")

    // MERGE-PATCH (adds a property, keeps name)
    val patched = patch(url, token, """{"@id":"$alice","$schemaJobTitle":"Engineer"}""")
    assertEquals(204, patched.statusCode)
    val patchTag = assertNotNull(patched.headers.get("ETag"), "PATCH 204 must echo the post-patch ETag")

    val afterPatch = get(url, token)
    assertEquals(200, afterPatch.statusCode)
    assertTrue(afterPatch.responseBody.contains("Alice"), "name must survive the merge-patch")
    assertTrue(afterPatch.responseBody.contains("Engineer"), "patched property must be present")
    assertEquals(patchTag, afterPatch.headers.get("ETag"), "echoed patch ETag must be the GET precondition tag")

    // DELETE
    assertEquals(204, delete(url, token).statusCode)
    assertEquals(404, get(url, token).statusCode, "resource must be gone after delete")
  }

  // ── M1 acceptance: GET an external IRI as canonical JSON-LD with an ETag ─────────────

  @Test
  fun `GET on external IRI returns canonical JSON-LD with an ETag`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val bob = "did:web:bob.example"
    val url = withContext(resourceUrl(pod.name, bob), contextUri)

    assertEquals(201, put(url, token, """{"@id":"$bob","$schemaName":"Bob"}""").statusCode)

    val response = get(url, token)
    assertEquals(200, response.statusCode)
    assertNotNull(response.headers.get("ETag"), "a readable resource must carry an ETag")
    assertTrue(response.responseBody.contains("Bob"))
  }

  // ── Open question 1 resolved: ETag parity across both routes for a pod-owned IRI ─────

  @Test
  fun `ETag and body are identical via canonical path and b64 route for a pod-owned IRI`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")
    val podOwnedPath = "things/thing1"
    val podOwnedIri = "${SempodsModule.config.apiBaseUrl}${pod.name}/$podOwnedPath"

    // Write via the canonical LOD path.
    val canonicalUrl = withContext("${SempodsModule.config.apiBaseUrl}${pod.name}/$podOwnedPath", contextUri)
    assertEquals(201, put(canonicalUrl, token, """{"@id":"$podOwnedIri","$schemaName":"Thing"}""").statusCode)

    val viaCanonical = get(canonicalUrl, token)
    val viaB64 = get(withContext(resourceUrl(pod.name, podOwnedIri), contextUri), token)

    assertEquals(200, viaCanonical.statusCode)
    assertEquals(200, viaB64.statusCode)
    assertEquals(
      viaCanonical.headers.get("ETag"),
      viaB64.headers.get("ETag"),
      "ETag must be byte-identical across canonical path and b64 route (cross-route conditional writes)",
    )
    assertEquals(
      viaCanonical.responseBody,
      viaB64.responseBody,
      "canonical JSON-LD body must be identical across both routes",
    )
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
  fun `a control-plane IRI can be described like any other, and stays a claim`() {
    // Statements about a control-plane IRI are statements — they live in the caller's context and
    // cannot change control-plane state, which is held in MongoDB and not in the graph. Same
    // arrangement as writing about another pod's resources: the IRI's own answer comes from
    // whoever owns it, here the control plane itself.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "privat")

    // The subject is an IRI inside the pod's reserved area — precisely what the removed guard used
    // to reject with 404. The write context is a separate, ordinary one: the claim lives there,
    // the IRI it talks about is control plane.
    val systemIri = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/apps/example/tasks"
    val writeContext = contextUri.toString()

    val write = put(
      resourceUrl(pod.name, systemIri) + "?context=$writeContext",
      token,
      """{"@id":"$systemIri","https://schema.org/name":[{"@value":"my note about this context"}]}""",
    )
    assertTrue(write.statusCode in listOf(200, 201), "body=${write.responseBody}")

    // Readable back through the operations route, with the claim in the caller's own context.
    // That the context's *own* representation stays unaffected is covered where it belongs, in
    // `PodContextsEndpointHttpTest` — the registry is the source of truth for what a context is.
    val read = get(resourceUrl(pod.name, systemIri), token)
    assertEquals(200, read.statusCode, "body=${read.responseBody}")
    assertTrue(read.responseBody.contains("my note about this context"), read.responseBody)
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
  fun `write without context query returns 400`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "privat")
    val frank = "https://example.org/people/frank"

    val response = put(resourceUrl(pod.name, frank), token, """{"@id":"$frank","$schemaName":"Frank"}""")
    assertEquals(400, response.statusCode)
  }
}
