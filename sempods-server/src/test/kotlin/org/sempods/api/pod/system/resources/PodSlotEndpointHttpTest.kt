package org.sempods.api.pod.system.resources

import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the slot/edge routes of [PodSystemResourcesEndpoint] — LOD-CRUD System-layer slot CRUD per
 * sempods-spec `spec/core/lod-crud.md` §5.
 *
 * Acceptance criterion of LOD-CRUD Iteration 1: adding a `schema:children` value to an
 * existing person works via HTTP `POST` without losing existing children, and removing one
 * child works via HTTP `DELETE` on the edge URL.
 */
class PodSlotEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val httpClient by lazy { http.followingRedirects }
  private val objectMapper = JsonMappers.default()

  private val schemaChildren = "https://schema.org/children"
  private val schemaName = "https://schema.org/name"

  private fun createContextWithToken(
    pod: PodDbo,
    contextPath: String,
    webId: String = "https://id.test/user",
  ): Pair<URI, String> {
    val podId = checkNotNull(pod.id)
    val contextUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/$contextPath")
    podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    // Context permissions resolve per (client, webId). Pass a distinct [webId] when a single
    // test mints two tokens with different scope sets that must stay isolated.
    val token = mintScopedToken(
      podName = pod.name,
      scopes = listOf("${contextUri}#read", "${contextUri}#write"),
      webId = webId,
    )
    return contextUri to token
  }

  private fun slotUrl(pod: String, subjectIri: String, predicateIri: String): String {
    val s = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(subjectIri))
    val p = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(predicateIri))
    return "${SempodsModule.config.apiBaseUrl}${pod}/_system/resources/${s}/${p}"
  }

  private fun edgeUrl(pod: String, subjectIri: String, predicateIri: String, targetIri: String): String {
    val t = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(targetIri))
    return "${slotUrl(pod, subjectIri, predicateIri)}/${t}"
  }

  private fun withContext(url: String, contextUri: URI): String =
    "$url?context=${URLEncoder.encode(contextUri.toString(), StandardCharsets.UTF_8)}"

  private fun withContextsAndProvenance(url: String, contexts: List<URI>): String {
    val contextQuery = contexts.joinToString("&") {
      "context=${URLEncoder.encode(it.toString(), StandardCharsets.UTF_8)}"
    }
    return "$url?$contextQuery&include_contexts=true"
  }

  private fun postIri(url: String, token: String, iriValue: String) = httpClient.preparePost(url)
    .addHeader("Content-Type", "application/ld+json")
    .addHeader("Authorization", "Bearer $token")
    .setBody("""{"@id":"$iriValue"}""")
    .execute()

  // ── Acceptance: schema:children round-trip ──────────────────────────────────

  @Test
  fun `POST adds an IRI value to a slot returns 201 with Location`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    val response = postIri(
      withContext(slotUrl(pod.name, bob, schemaChildren), contextUri),
      token,
      carol,
    )

    assertEquals(201, response.statusCode)
    val location = response.headers.get("Location")
    assertNotNull(location, "201 Created must include Location header for IRI value")
    assertTrue(
      location.endsWith("/${UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(carol))}"),
      "Location must point at the edge URL, was: $location",
    )
  }

  @Test
  fun `POST of already-present IRI returns 200 without Location (RDF set semantics)`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val slot = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val first = postIri(slot, token, carol)
    assertEquals(201, first.statusCode)

    val second = postIri(slot, token, carol)
    assertEquals(200, second.statusCode, "duplicate POST must collapse to no-op 200")
    assertEquals(null, second.headers.get("Location"))
  }

  @Test
  fun `GET returns the slot as a JSON-LD array of value objects`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val dave = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/dave"
    val slot = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    assertEquals(201, postIri(slot, token, carol).statusCode)
    assertEquals(201, postIri(slot, token, dave).statusCode)

    val getResponse = httpClient.prepareGet(slot)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()

    assertEquals(200, getResponse.statusCode)
    val body = objectMapper.readValue(getResponse.responseBody, List::class.java)
    val ids = body.map { (it as Map<*, *>)["@id"] }.toSet()
    assertEquals(setOf(carol, dave), ids)
  }

  @Test
  fun `GET with include_contexts returns slot values grouped by named graph`() {
    val pod = sempodsTestFactory.newPod()
    val (ctxA, tokenA) = createContextWithToken(pod, "ctx-a")
    val (ctxB, tokenB) = createContextWithToken(pod, "ctx-b")
    val tokenAB = mintScopedToken(
      podName = pod.name,
      scopes = listOf("${ctxA}#read", "${ctxA}#write", "${ctxB}#read", "${ctxB}#write"),
    )
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val dave = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/dave"
    val slot = slotUrl(pod.name, bob, schemaChildren)

    assertEquals(201, postIri(withContext(slot, ctxA), tokenA, carol).statusCode)
    assertEquals(201, postIri(withContext(slot, ctxB), tokenB, dave).statusCode)

    val getResponse = httpClient.prepareGet(withContextsAndProvenance(slot, listOf(ctxA, ctxB)))
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $tokenAB")
      .execute()

    assertEquals(200, getResponse.statusCode)
    val body = objectMapper.readValue(getResponse.responseBody, List::class.java)
    val graphIds = body.map { (it as Map<*, *>)["@id"] }.toSet()
    assertEquals(setOf(ctxA.toString(), ctxB.toString()), graphIds)
    val graphById = body.associateBy { (it as Map<*, *>)["@id"] }
    val ctxAGraph = ((graphById[ctxA.toString()] as Map<*, *>)["@graph"] as List<*>).first() as Map<*, *>
    val ctxBGraph = ((graphById[ctxB.toString()] as Map<*, *>)["@graph"] as List<*>).first() as Map<*, *>
    val ctxAChildren = ctxAGraph[schemaChildren] as List<*>
    val ctxBChildren = ctxBGraph[schemaChildren] as List<*>
    assertEquals(carol, (ctxAChildren.first() as Map<*, *>)["@id"])
    assertEquals(dave, (ctxBChildren.first() as Map<*, *>)["@id"])
  }

  @Test
  fun `DELETE single edge removes only that triple and leaves siblings intact`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val dave = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/dave"
    val slot = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    assertEquals(201, postIri(slot, token, carol).statusCode)
    assertEquals(201, postIri(slot, token, dave).statusCode)

    val deleteResponse = httpClient.prepareDelete(
      withContext(edgeUrl(pod.name, bob, schemaChildren, dave), contextUri)
    )
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, deleteResponse.statusCode)
    assertEquals(
      "removed",
      objectMapper.readValue(deleteResponse.responseBody, Map::class.java)["outcome"],
      "an edge that existed reports removed",
    )

    val getResponse = httpClient.prepareGet(slot)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    val body = objectMapper.readValue(getResponse.responseBody, List::class.java)
    val ids = body.map { (it as Map<*, *>)["@id"] }.toSet()
    assertEquals(setOf(carol), ids, "only the removed edge is gone, siblings remain")
  }

  @Test
  fun `DELETE single edge that does not exist returns 200 already_absent (idempotent)`() {
    // Per `SPS-CRUD-042`, single-edge removal is
    // idempotent — a missing edge succeeds just like removing a present one. This lets
    // clients retry a successful delete and use "ensure not-present" patterns without a
    // prior read. The 200 body's `outcome` reports which case occurred.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val unknown = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/unknown"

    val response = httpClient.prepareDelete(
      withContext(edgeUrl(pod.name, bob, schemaChildren, unknown), contextUri)
    )
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, response.statusCode)
    assertEquals(
      "already_absent",
      objectMapper.readValue(response.responseBody, Map::class.java)["outcome"],
      "an edge that never existed reports already_absent",
    )
  }

  @Test
  fun `DELETE single edge is idempotent across repeated calls`() {
    // First call removes the edge, second hits an already-absent edge — both succeed (200),
    // the `outcome` in the body tells them apart.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val slot = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)
    assertEquals(201, postIri(slot, token, carol).statusCode)

    val edge = withContext(edgeUrl(pod.name, bob, schemaChildren, carol), contextUri)
    val first = httpClient.prepareDelete(edge).addHeader("Authorization", "Bearer $token")
      .execute()
    val second = httpClient.prepareDelete(edge).addHeader("Authorization", "Bearer $token")
      .execute()

    assertEquals(200, first.statusCode, "first delete removes the edge")
    assertEquals(200, second.statusCode, "second delete on already-absent edge still succeeds")
    assertEquals(
      "removed",
      objectMapper.readValue(first.responseBody, Map::class.java)["outcome"],
      "first delete reports removed",
    )
    assertEquals(
      "already_absent",
      objectMapper.readValue(second.responseBody, Map::class.java)["outcome"],
      "second delete reports already_absent",
    )
  }

  @Test
  fun `DELETE whole slot is idempotent across repeated calls`() {
    // Same idempotency contract for whole-slot DELETE (Roadmap finding parity).
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val slot = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)
    assertEquals(201, postIri(slot, token, carol).statusCode)

    val first = httpClient.prepareDelete(slot).addHeader("Authorization", "Bearer $token")
      .execute()
    val second = httpClient.prepareDelete(slot).addHeader("Authorization", "Bearer $token")
      .execute()

    // Idempotent in effect, and the body is what says so: the status is 200 both times, and only
    // `outcome` separates "there was something" from "there was not".
    assertEquals(200, first.statusCode, "first delete empties the slot")
    assertEquals(200, second.statusCode, "second delete on already-empty slot still succeeds")
    assertEquals("cleared", objectMapper.readValue(first.responseBody, Map::class.java)["outcome"])
    assertEquals("already_empty", objectMapper.readValue(second.responseBody, Map::class.java)["outcome"])
  }

  @Test
  fun `DELETE on slot clears every value of the predicate in the context`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val dave = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/dave"
    val slot = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    assertEquals(201, postIri(slot, token, carol).statusCode)
    assertEquals(201, postIri(slot, token, dave).statusCode)

    val deleteResponse = httpClient.prepareDelete(slot)
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, deleteResponse.statusCode)
    assertEquals("cleared", objectMapper.readValue(deleteResponse.responseBody, Map::class.java)["outcome"])

    val getResponse = httpClient.prepareGet(slot)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(404, getResponse.statusCode, "empty slot returns 404 on GET")
  }

  // ── Literals ────────────────────────────────────────────────────────────────

  @Test
  fun `PUT replaces a literal slot with a new array`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val slot = withContext(slotUrl(pod.name, bob, schemaName), contextUri)

    val putBody = """
      [
        {"@value": "Bob Smith", "@language": "de"},
        {"@value": "Bob H. Smith", "@language": "de"}
      ]
    """.trimIndent()
    val putResponse = httpClient.preparePut(slot)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody(putBody)
      .execute()
    assertEquals(204, putResponse.statusCode)

    val getResponse = httpClient.prepareGet(slot)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val body = objectMapper.readValue(getResponse.responseBody, List::class.java)
    val values = body.map { (it as Map<*, *>)["@value"] }.toSet()
    assertEquals(setOf("Bob Smith", "Bob H. Smith"), values)
  }

  @Test
  fun `POST literal returns 201 without Location (literals have no edge URL)`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val slot = withContext(slotUrl(pod.name, bob, schemaName), contextUri)

    val response = httpClient.preparePost(slot)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""{"@value": "Bob Smith", "@language": "de"}""")
      .execute()
    assertEquals(201, response.statusCode)
    assertEquals(null, response.headers.get("Location"))
  }

  // ── External URIs (System layer is the only path) ──────────────────────────

  @Test
  fun `POST works on external DID subject`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bobDid = "did:web:bob.example"
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val foafKnows = "http://xmlns.com/foaf/0.1/knows"
    val slot = withContext(slotUrl(pod.name, bobDid, foafKnows), contextUri)

    val response = postIri(slot, token, bob)
    assertEquals(201, response.statusCode)

    val getResponse = httpClient.prepareGet(slot)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, getResponse.statusCode)
    val body = objectMapper.readValue(getResponse.responseBody, List::class.java)
    assertEquals(bob, (body[0] as Map<*, *>)["@id"])
  }

  @Test
  fun `GET with include_contexts works on external DID subject`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bobDid = "did:web:bob.example"
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val foafKnows = "http://xmlns.com/foaf/0.1/knows"
    val slot = withContext(slotUrl(pod.name, bobDid, foafKnows), contextUri)

    assertEquals(201, postIri(slot, token, bob).statusCode)

    val getResponse = httpClient.prepareGet("$slot&include_contexts=true")
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()

    assertEquals(200, getResponse.statusCode)
    val body = objectMapper.readValue(getResponse.responseBody, List::class.java)
    val graph = (body.first() as Map<*, *>)
    assertEquals(contextUri.toString(), graph["@id"])
    val node = (graph["@graph"] as List<*>).first() as Map<*, *>
    assertEquals(bobDid, node["@id"])
    val values = node[foafKnows] as List<*>
    assertEquals(bob, (values.first() as Map<*, *>)["@id"])
  }

  // ── Cross-context isolation ────────────────────────────────────────────────

  @Test
  fun `clear slot in one context does not touch the same slot in another`() {
    val pod = sempodsTestFactory.newPod()
    val (ctxA, tokenA) = createContextWithToken(pod, "ctx-a")
    val (ctxB, tokenB) = createContextWithToken(pod, "ctx-b")
    val tokenAB = mintScopedToken(
      podName = pod.name,
      scopes = listOf("${ctxA}#read", "${ctxA}#write", "${ctxB}#read", "${ctxB}#write"),
    )
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    assertEquals(
      201,
      postIri(withContext(slotUrl(pod.name, bob, schemaChildren), ctxA), tokenA, carol).statusCode,
    )
    assertEquals(
      201,
      postIri(withContext(slotUrl(pod.name, bob, schemaChildren), ctxB), tokenB, carol).statusCode,
    )

    val clearA = httpClient.prepareDelete(withContext(slotUrl(pod.name, bob, schemaChildren), ctxA))
      .addHeader("Authorization", "Bearer $tokenA")
      .execute()
    assertEquals(200, clearA.statusCode)

    val getB = httpClient.prepareGet(withContext(slotUrl(pod.name, bob, schemaChildren), ctxB))
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $tokenAB")
      .execute()
    assertEquals(200, getB.statusCode, "context B must still contain the slot value")
    val body = objectMapper.readValue(getB.responseBody, List::class.java)
    assertEquals(carol, (body[0] as Map<*, *>)["@id"])
  }

  // ── Error paths ─────────────────────────────────────────────────────────────

  @Test
  fun `POST without write scope returns 403`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, _) = createContextWithToken(pod, "contacts")
    val readOnlyToken = mintScopedToken(pod.name, listOf("${contextUri}#read"))
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    val response = postIri(
      withContext(slotUrl(pod.name, bob, schemaChildren), contextUri),
      readOnlyToken,
      carol,
    )
    assertEquals(403, response.statusCode)
  }

  @Test
  fun `POST without ?context= returns 400`() {
    val pod = sempodsTestFactory.newPod()
    val (_, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"

    val response = postIri(slotUrl(pod.name, bob, schemaChildren), token, carol)
    assertEquals(400, response.statusCode)
  }

  @Test
  fun `GET with invalid include_contexts returns 400 before auth`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, _) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val url = "${withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)}&include_contexts=foo"

    val response = httpClient.prepareGet(url)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer not-a-real-jwt")
      .execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `GET with a base64url segment using standard-alphabet plus returns 400`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val invalidSegment = "aGVsbG8+d29ybGQ" // contains '+', standard alphabet
    val url = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/resources/$invalidSegment/$invalidSegment"

    val response = httpClient.prepareGet(withContext(url, contextUri))
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(400, response.statusCode)
  }

  // ── Iter 2: Conditional Writes (ETag / If-Match / If-None-Match) ────────────

  private fun seedChildrenSlot(
    pod: PodDbo,
    contextUri: URI,
    token: String,
    subjectIri: String,
    targetIri: String,
  ) {
    val resp = postIri(
      withContext(slotUrl(pod.name, subjectIri, schemaChildren), contextUri),
      token,
      targetIri,
    )
    assertEquals(201, resp.statusCode)
  }

  @Test
  fun `GET on populated slot emits ETag header (single-context)`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)

    val resp = httpClient.prepareGet(withContext(slotUrl(pod.name, bob, schemaChildren), contextUri))
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, resp.statusCode)
    val etag = resp.headers.get("ETag")
    assertNotNull(etag, "Single-context slot GET must carry ETag header")
    assertTrue(etag.isNotBlank())
  }

  @Test
  fun `GET twice returns identical ETag when nothing changes`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val first = httpClient.prepareGet(url).addHeader("Authorization", "Bearer $token").execute()
    val second = httpClient.prepareGet(url).addHeader("Authorization", "Bearer $token").execute()
    assertEquals(first.headers.get("ETag"), second.headers.get("ETag"))
  }

  @Test
  fun `GET multi-context union omits ETag header`() {
    val pod = sempodsTestFactory.newPod()
    // Distinct identities so each single-context token keeps its own grant set; the union
    // token below is a separate mint that lists both contexts.
    val (ctxA, tokenA) = createContextWithToken(pod, "contacts", webId = "https://id.test/user-a")
    val (ctxB, _) = createContextWithToken(pod, "team", webId = "https://id.test/user-b")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, ctxA, tokenA, bob, carol)

    val multiContextUrl = withContextsAndProvenance(
      slotUrl(pod.name, bob, schemaChildren),
      listOf(ctxA, ctxB),
    )
    val tokenAB = mintScopedToken(
      podName = pod.name,
      scopes = listOf("${ctxA}#read", "${ctxA}#write", "${ctxB}#read", "${ctxB}#write"),
    )
    val resp = httpClient.prepareGet(multiContextUrl)
      .addHeader("Accept", "application/ld+json")
      .addHeader("Authorization", "Bearer $tokenAB")
      .execute()
    // Status is implementation-detail of the slot; ETag must be absent regardless.
    assertEquals(null, resp.headers.get("ETag"))
  }

  @Test
  fun `PUT with current If-Match succeeds`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val getResp = httpClient.prepareGet(url).addHeader("Authorization", "Bearer $token").execute()
    val currentETag = assertNotNull(getResp.headers.get("ETag"))

    val putResp = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", currentETag)
      .setBody("""[{"@id":"$carol"}]""")
      .execute()
    assertEquals(204, putResp.statusCode)
  }

  @Test
  fun `PUT with stale If-Match returns 412`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val resp = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", "\"definitely-not-the-current-tag\"")
      .setBody("""[{"@id":"$carol"}]""")
      .execute()
    assertEquals(412, resp.statusCode)
  }

  @Test
  fun `PUT with --gzip-suffixed If-Match should succeed`() {
    // Jetty's GzipHandler appends "--gzip" to the ETag it emits on a
    // compressible GET response (RFC 9110 §8.8.3). Clients faithfully
    // echo that exact tag back in If-Match on the subsequent PUT.
    // Since the handler does not strip the suffix from inbound If-Match
    // on a write (the PUT response has no body to compress), the
    // application must tolerate the suffix itself or every gzip-aware
    // client gets a 412 loop. See `BaseEndpoint.evaluatePreconditions`.
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val getResp = httpClient.prepareGet(url).addHeader("Authorization", "Bearer $token").execute()
    val rawEtag = assertNotNull(getResp.headers.get("ETag"))
    // Force the gzip-suffixed variant regardless of whether the
    // container actually compressed this particular response — we
    // want to exercise the precondition path with the suffix present.
    val gzipEtag = if (rawEtag.endsWith("--gzip\"")) rawEtag
    else rawEtag.replace(Regex("\"$"), "--gzip\"")

    val putResp = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", gzipEtag)
      .setBody("""[{"@id":"$carol"}]""")
      .execute()

    assertEquals(
      204,
      putResp.statusCode,
      "PUT with gzip-suffixed If-Match should pass the precondition (was: $gzipEtag)",
    )
  }

  @Test
  fun `PUT with If-None-Match star on empty slot succeeds`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val resp = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-None-Match", "*")
      .setBody("""[{"@id":"$carol"}]""")
      .execute()
    assertEquals(204, resp.statusCode)
  }

  @Test
  fun `PUT with If-None-Match star on populated slot returns 412`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val resp = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-None-Match", "*")
      .setBody("""[{"@id":"$carol"}]""")
      .execute()
    assertEquals(412, resp.statusCode)
  }

  @Test
  fun `PUT with INM star on empty slot for external DID subject succeeds`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val external = "did:web:bob.example"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val url = withContext(slotUrl(pod.name, external, schemaChildren), contextUri)

    val resp = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-None-Match", "*")
      .setBody("""[{"@id":"$carol"}]""")
      .execute()
    assertEquals(204, resp.statusCode)
  }

  @Test
  fun `PUT with INM star on empty slot of subject with other slots succeeds`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    // Seed a DIFFERENT slot of the same subject first (schema:name).
    httpClient.preparePut(
      withContext(slotUrl(pod.name, bob, schemaName), contextUri)
    )
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .setBody("""[{"@value":"Bob"}]""")
      .execute()

    // schema:children is still empty for Bob — INM:* should succeed (slot-leer semantics).
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)
    val resp = httpClient.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-None-Match", "*")
      .setBody("""[{"@id":"$carol"}]""")
      .execute()
    assertEquals(204, resp.statusCode)
  }

  @Test
  fun `POST with current If-Match succeeds`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val erin = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/erin"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val getResp = httpClient.prepareGet(url).addHeader("Authorization", "Bearer $token").execute()
    val currentETag = assertNotNull(getResp.headers.get("ETag"))

    val resp = httpClient.preparePost(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", currentETag)
      .setBody("""{"@id":"$erin"}""")
      .execute()
    assertEquals(201, resp.statusCode)
  }

  @Test
  fun `POST with stale If-Match returns 412`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val erin = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/erin"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val resp = httpClient.preparePost(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", "\"stale\"")
      .setBody("""{"@id":"$erin"}""")
      .execute()
    assertEquals(412, resp.statusCode)
  }

  @Test
  fun `POST without If-Match remains unconditional`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    val erin = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/erin"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val resp = postIri(url, token, erin)
    assertEquals(201, resp.statusCode)
  }

  @Test
  fun `DELETE whole slot with stale If-Match returns 412`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val resp = httpClient.prepareDelete(url)
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", "\"stale\"")
      .execute()
    assertEquals(412, resp.statusCode)
  }

  @Test
  fun `DELETE whole slot echoes post-clear ETag header`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val url = withContext(slotUrl(pod.name, bob, schemaChildren), contextUri)

    val resp = httpClient.prepareDelete(url)
      .addHeader("Authorization", "Bearer $token")
      .execute()
    assertEquals(200, resp.statusCode)
    val etag = resp.headers.get("ETag")
    assertNotNull(etag, "whole-slot DELETE must echo the post-clear ETag")
    assertTrue(etag.isNotBlank())
  }

  @Test
  fun `DELETE single edge ignores If-Match (unconditional by design)`() {
    val pod = sempodsTestFactory.newPod()
    val (contextUri, token) = createContextWithToken(pod, "contacts")
    val bob = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/bob"
    val carol = "${SempodsModule.config.apiBaseUrl}${pod.name}/contacts/carol"
    seedChildrenSlot(pod, contextUri, token, bob, carol)
    val edgeUrl = withContext(edgeUrl(pod.name, bob, schemaChildren, carol), contextUri)

    val resp = httpClient.prepareDelete(edgeUrl)
      .addHeader("Authorization", "Bearer $token")
      .addHeader("If-Match", "\"obviously-stale\"")
      .execute()
    // Edge removal is idempotent — If-Match must be ignored, edge gone,
    // 200 with the outcome body (RFC 9110 §9.3.5).
    assertEquals(200, resp.statusCode)
    assertEquals(
      "removed",
      objectMapper.readValue(resp.responseBody, Map::class.java)["outcome"],
    )
  }
}
