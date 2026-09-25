package org.sempods.api.pod

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpRequest
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.commons.utils.UriEncodingUtil.encodeUriToUrlSafeBase64
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDbo
import org.eclipse.rdf4j.model.Statement
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SPS-CORE-018` from the outside: a write without authority on its target context gets the same
 * `403` whether that context exists or not — on the LOD, System, media and context-management
 * routes, and as the same tool error on the pod's MCP write tools.
 *
 * Every case sends one request twice with the same caller: first while the target context is absent,
 * then once it is registered and holds data. Status, body and headers must not differ, and neither
 * state may change anything. The last test is the other half: a caller whose grant covers an absent
 * context still gets the operation's own missing-target answer.
 */
class WriteDenialHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var podMediaDao: PodMediaDao

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val objectMapper = JsonMappers.default()

  @Test
  fun `a write without authority on its context answers 403 whether the context exists or not`() {
    val pod = sempodsTestFactory.newPod()
    assertDeniedAlike(pod, webId = "https://id.test/${randomId()}")
  }

  @Test
  fun `an app acting for the owner is denied alike outside its grants`() {
    val owner = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = owner)
    assertDeniedAlike(pod, webId = webIdUriDeriver.deriveFromEmail(owner.email))
  }

  @Test
  fun `context management without manage authority answers 403 whether the context exists or not`() {
    val pod = sempodsTestFactory.newPod()
    // Manage somewhere else, so the caller is a manager — just not of the target.
    val elsewhere = register(pod, "apps/other-${randomId()}")
    val token = mintScopedToken(pod.name, listOf("$elsewhere#manage"), webId = "https://id.test/${randomId()}")

    // `apps/notes` is a type root: the naming rules would refuse to create it, and that `400` must
    // not stand in for the denial while it is absent.
    for (path in listOf("tests/elsewhere-${randomId()}", "apps/notes")) {
      val target = sempodsUriBuilder.buildContext(pod.name, path)
      val url = "${podBase(pod)}/_system/contexts/$path"
      val cases = listOf<Pair<String, () -> TestHttpRequest>>(
        "PUT $path" to { http.preparePut(url).addHeader("Content-Type", "application/json").setBody("{}") },
        "DELETE $path" to { http.prepareDelete(url) },
      )

      val absent = cases.map { (name, request) -> name to request().bearer(token).execute() }
      assertFalse(isRegistered(pod, target), "a denied PUT must not create $target")

      podContextsDao.create(podId = checkNotNull(pod.id), contextUri = target.toString(), label = null, description = null, createdBy = "test")
      val present = cases.map { (_, request) -> request().bearer(token).execute() }
      assertTrue(isRegistered(pod, target), "a denied DELETE must not remove $target")

      absent.zip(present).forEach { (named, answer) -> assertSameDenial(named.first, named.second, answer) }
    }
  }

  @Test
  fun `an MCP write tool without authority fails alike whether the context exists or not`() {
    val pod = sempodsTestFactory.newPod()
    val own = register(pod, "tests/own-${randomId()}")
    val token = mintScopedToken(pod.name, listOf("$own#read", "$own#write"), webId = "https://id.test/${randomId()}")
    val target = sempodsUriBuilder.buildContext(pod.name, "tests/elsewhere-${randomId()}")
    val resource = sempodsTestFactory.eventUri(pod.name).toString()
    val calls = listOf(
      "create_resource" to mapOf("resource_iri" to resource, "jsonld" to mapOf("@id" to resource, NAME to "x")),
      "update_resource" to mapOf("resource_iri" to resource, "jsonld_patch" to mapOf(NAME to "x")),
      "delete_resource" to mapOf("resource_iri" to resource),
      "add_property_value" to mapOf("subject_iri" to resource, "predicate_iri" to NAME, "value" to mapOf("@value" to "x")),
      "set_property_values" to mapOf("subject_iri" to resource, "predicate_iri" to NAME, "values" to listOf(mapOf("@value" to "x"))),
      "remove_property_value" to mapOf("subject_iri" to resource, "predicate_iri" to LOCATION, "target_iri" to "$resource-place"),
      "clear_property_values" to mapOf("subject_iri" to resource, "predicate_iri" to NAME),
    )
    fun call(tool: String, arguments: Map<String, Any>): TestHttpResponse =
      http.preparePost("${podBase(pod)}/_system/mcp")
        .addHeader("Content-Type", "application/json")
        .setBody(
          objectMapper.writeValueAsString(
            mapOf(
              "jsonrpc" to "2.0",
              "id" to 1,
              "method" to "tools/call",
              "params" to mapOf("name" to tool, "arguments" to arguments + ("context_iri" to target.toString())),
            ),
          ),
        )
        .bearer(token)
        .execute()

    val absent = calls.map { (tool, arguments) -> call(tool, arguments) }
    val kept = seed(pod, target, URI(resource))
    val present = calls.map { (tool, arguments) -> call(tool, arguments) }

    calls.indices.forEach { i ->
      val tool = calls[i].first
      listOf(absent[i], present[i]).forEach { answer ->
        assertEquals(200, answer.statusCode, "$tool: a tool error travels over 200")
        val result = objectMapper.readTree(answer.responseBody).path("result")
        assertTrue(result.path("isError").asBoolean(), "$tool must fail as a tool error: ${answer.responseBody}")
        assertTrue(result.toString().contains("403"), "$tool must carry the pod's denial: ${answer.responseBody}")
      }
      assertEquals(absent[i].responseBody, present[i].responseBody, "$tool: the result must not tell the two states apart")
      assertEquals(comparableHeaders(absent[i]), comparableHeaders(present[i]), "$tool: nor may the headers")
    }
    assertEquals(kept, statementsIn(pod, URI(resource), target), "denied tool calls must leave the data as it was")
  }

  @Test
  fun `a caller whose grant covers an absent context gets the missing-target answer`() {
    val pod = sempodsTestFactory.newPod()
    val root = register(pod, "apps/notes-${randomId()}")
    val manager = mintScopedToken(pod.name, listOf("$root#manage"), webId = "https://id.test/${randomId()}")
    val target = URI("$root/new-${randomId()}")
    val direct = sempodsUriBuilder.buildContext(pod.name, "tests/granted-${randomId()}")
    val directToken = mintScopedToken(pod.name, listOf("$direct#read", "$direct#write"), webId = "https://id.test/${randomId()}")
    val mediaId = mediaIdOf(upload(pod, root, manager))
    val cases = writes(pod, mediaId)

    // Neither context is registered: `root#manage` covers the one by the slash rule, and a grant
    // names the other directly.
    for ((context, token) in listOf(target to manager, direct to directToken)) {
      cases.forEach { (name, request) ->
        val answer = request(context).bearer(token).execute()
        val expected = if (name == "media unassign") 204 else 404
        assertEquals(expected, answer.statusCode, "$name into unregistered $context: ${answer.responseBody}")
      }
      assertFalse(isRegistered(pod, context))
    }
    assertEquals(setOf(root.toString()), assignments(pod, mediaId), "no assignment may name an unregistered context")

    val url = "${podBase(pod)}/_system/contexts/${target.toString().substringAfter("/_system/contexts/")}"
    assertEquals(404, http.prepareDelete(url).bearer(manager).execute().statusCode, "deleting a missing context")
    val created = http.preparePut(url).addHeader("Content-Type", "application/json").setBody("{}").bearer(manager).execute()
    assertEquals(201, created.statusCode, created.responseBody)
    val written = cases.first { it.first == "LOD PUT" }.second(target).bearer(manager).execute()
    assertEquals(201, written.statusCode, written.responseBody)
  }

  /**
   * Every data write route, once with the target context absent and once with it registered and
   * holding data, for a caller with read and write on a context of its own and nothing on the
   * target.
   */
  private fun assertDeniedAlike(pod: PodDbo, webId: String) {
    val own = register(pod, "tests/own-${randomId()}")
    val token = mintScopedToken(pod.name, listOf("$own#read", "$own#write"), webId = webId)
    val mediaId = mediaIdOf(upload(pod, own, token))
    val target = sempodsUriBuilder.buildContext(pod.name, "tests/elsewhere-${randomId()}")
    val cases = writes(pod, mediaId)

    val absent = cases.map { (name, request) -> name to request(target).bearer(token).execute() }
    assertFalse(isRegistered(pod, target))
    assertEquals(emptySet<Statement>(), statementsIn(pod, deniedResource(pod), target), "a denied write must not store anything")
    assertEquals(setOf(own.toString()), assignments(pod, mediaId), "a denied assignment must not be recorded")

    val kept = seed(pod, target, deniedResource(pod))
    // Assigned by someone who may, so the denied unassign has something to leave alone.
    val assigner = mintScopedToken(pod.name, listOf("$own#read", "$target#read", "$target#write"), webId = "https://id.test/${randomId()}")
    assertEquals(204, http.preparePut(mediaUrl(pod, mediaId, target)).bearer(assigner).execute().statusCode)
    val present = cases.map { (_, request) -> request(target).bearer(token).execute() }

    absent.zip(present).forEach { (named, answer) -> assertSameDenial(named.first, named.second, answer) }
    assertEquals(kept, statementsIn(pod, deniedResource(pod), target), "denied writes must leave the data as it was")
    assertEquals(setOf(own.toString(), target.toString()), assignments(pod, mediaId), "nor the assignments")
  }

  /** The data write routes, each a request against the context it is given. */
  private fun writes(pod: PodDbo, mediaId: String): List<Pair<String, (URI) -> TestHttpRequest>> {
    val resource = deniedResource(pod)
    val lod = resource.toString()
    val node = "${podBase(pod)}/_system/resources/${encodeUriToUrlSafeBase64(resource)}"
    val slot = "$node/${encodeUriToUrlSafeBase64(URI(NAME))}"
    val edge = "$node/${encodeUriToUrlSafeBase64(URI(LOCATION))}/${encodeUriToUrlSafeBase64(URI("$lod-place"))}"
    val body = """{"@id":"$lod","$NAME":"replaced"}"""
    val patch = """{"$NAME":"patched"}"""
    return listOf(
      "LOD PUT" to { c -> http.preparePut(inContext(lod, c)).jsonLd(body) },
      "LOD PUT if-none-match" to { c -> http.preparePut(inContext(lod, c)).jsonLd(body).addHeader("If-None-Match", "*") },
      "LOD PATCH if-match" to { c -> http.prepare("PATCH", inContext(lod, c)).mergePatch(patch).addHeader("If-Match", STALE) },
      "LOD DELETE" to { c -> http.prepareDelete(inContext(lod, c)) },
      "System PUT" to { c -> http.preparePut(inContext(node, c)).jsonLd(body) },
      "System PATCH" to { c -> http.prepare("PATCH", inContext(node, c)).mergePatch(patch) },
      "System DELETE if-match" to { c -> http.prepareDelete(inContext(node, c)).addHeader("If-Match", STALE) },
      "slot PUT if-none-match" to { c -> http.preparePut(inContext(slot, c)).jsonLd("""[{"@value":"x"}]""").addHeader("If-None-Match", "*") },
      "slot POST" to { c -> http.preparePost(inContext(slot, c)).jsonLd("""{"@value":"x"}""") },
      "slot DELETE if-match" to { c -> http.prepareDelete(inContext(slot, c)).addHeader("If-Match", STALE) },
      "edge DELETE" to { c -> http.prepareDelete(inContext(edge, c)) },
      "media upload" to { c -> http.preparePost(inContext("${podBase(pod)}/_system/media", c)).addHeader("Content-Type", "image/png").setBody("bytes-${randomId()}") },
      "media assign" to { c -> http.preparePut(mediaUrl(pod, mediaId, c)) },
      "media unassign" to { c -> http.prepareDelete(mediaUrl(pod, mediaId, c)) },
    )
  }

  private fun assertSameDenial(name: String, absent: TestHttpResponse, present: TestHttpResponse) {
    assertEquals(403, absent.statusCode, "$name, context absent: ${absent.responseBody}")
    assertEquals(403, present.statusCode, "$name, context registered: ${present.responseBody}")
    assertEquals(absent.responseBody, present.responseBody, "$name: the body must not tell the two states apart")
    assertEquals(comparableHeaders(absent), comparableHeaders(present), "$name: nor may the headers")
  }

  /** Every header but the two that differ between any two answers: `Date` and the trace id. */
  private fun comparableHeaders(response: TestHttpResponse): Map<String, List<String>> =
    response.headers.toMultimap() - setOf("date", "traceparent")

  /** Registers the context as the control plane would, and returns it. */
  private fun register(pod: PodDbo, path: String): URI =
    sempodsUriBuilder.buildContext(pod.name, path).also {
      podContextsDao.create(podId = checkNotNull(pod.id), contextUri = it.toString(), label = null, description = null, createdBy = "test")
    }

  private fun isRegistered(pod: PodDbo, context: URI): Boolean =
    podContextsDao.exists(podId = checkNotNull(pod.id), contextUri = context.toString())

  /** Registers [context] and gives [resource] a name and an edge there; returns what was stored. */
  private fun seed(pod: PodDbo, context: URI, resource: URI): Set<Statement> {
    if (!isRegistered(pod, context)) {
      podContextsDao.create(podId = checkNotNull(pod.id), contextUri = context.toString(), label = null, description = null, createdBy = "test")
    }
    sempodsTestFactory.seedEvent(pod.name, eventUri = resource, context = context, name = "kept", location = URI("$resource-place"))
    return statementsIn(pod, resource, context).also { assertTrue(it.isNotEmpty(), "seeding must store something") }
  }

  private fun statementsIn(pod: PodDbo, resource: URI, context: URI): Set<Statement> =
    podFacade.getResource(pod.name, resource).orEmpty().filterTo(HashSet()) { it.context?.stringValue() == context.toString() }

  private fun assignments(pod: PodDbo, mediaId: String): Set<String> =
    podMediaDao.iterate(checkNotNull(pod.id)) { rows -> rows.first { it.mediaId == mediaId }.contexts }

  private fun upload(pod: PodDbo, context: URI, token: String): TestHttpResponse =
    http.preparePost(inContext("${podBase(pod)}/_system/media", context))
      .addHeader("Content-Type", "image/png")
      .setBody("bytes-${randomId()}")
      .bearer(token)
      .execute()
      .also { assertEquals(201, it.statusCode, it.responseBody) }

  private fun mediaIdOf(response: TestHttpResponse): String =
    checkNotNull(objectMapper.readTree(response.responseBody).path("id").asText().takeIf { it.isNotEmpty() })

  private fun mediaUrl(pod: PodDbo, mediaId: String, context: URI) = inContext("${podBase(pod)}/_system/media/$mediaId", context)

  private fun deniedResource(pod: PodDbo): URI = sempodsTestFactory.eventUri(pod.name, "denied")

  private fun podBase(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}"

  private fun inContext(url: String, context: URI) =
    "$url?context=${URLEncoder.encode(context.toString(), StandardCharsets.UTF_8)}"

  private fun TestHttpRequest.bearer(token: String) = addHeader("Authorization", "Bearer $token")

  private fun TestHttpRequest.jsonLd(body: String) = addHeader("Content-Type", "application/ld+json").setBody(body)

  private fun TestHttpRequest.mergePatch(body: String) = addHeader("Content-Type", "application/merge-patch+json").setBody(body)

  private companion object {
    const val NAME = "https://schema.org/name"
    const val LOCATION = "https://schema.org/location"
    const val STALE = "\"stale\""
  }
}
