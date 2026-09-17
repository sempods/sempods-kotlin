package org.sempods.api.pod.resources

import com.google.inject.Inject
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.utils.UriEncodingUtil.encodeUriToUrlSafeBase64
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.rdf.toIri
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which tag validates which write, against the real server and on both addressing routes.
 *
 * The rule under test is [RepresentationTags]: a tag describes the representation it came with, and a
 * write to context X is validated by the tag of a read selected to X.
 */
class ConditionalRequestsHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val name = Values.iri("https://schema.org/name")

  /** The canonical LOD route and the System route of one pod-owned resource. */
  private fun routes(pod: PodDbo, resource: URI): List<String> = listOf(
    resource.toString(),
    "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/resources/${encodeUriToUrlSafeBase64(resource)}",
  )

  private fun context(pod: PodDbo, path: String): URI =
    sempodsUriBuilder.buildContext(pod.name, path).also {
      podContextsDao.create(podId = checkNotNull(pod.id), contextUri = it.toString(), label = null, description = null, createdBy = "test")
    }

  /** A token for [webId], whose grants become exactly [scopes] — a later call replaces them. */
  private fun token(pod: PodDbo, vararg scopes: String, webId: String = "https://id.test/user"): String =
    mintScopedToken(pod.name, scopes.toList(), webId)

  /** Plants [resource] with a `schema:name` per context. */
  private fun seed(pod: PodDbo, resource: URI, names: Map<URI, String>) {
    val model = LinkedHashModel()
    names.forEach { (context, value) -> model.add(resource.toIri(), name, Values.literal(value), context.toIri()) }
    podFacade.putResourceModel(podName = pod.name, resourceUri = resource, model = model)
  }

  private fun selected(url: String, vararg contexts: URI): String =
    url + contexts.joinToString("&", prefix = "?") { "context=${URLEncoder.encode(it.toString(), StandardCharsets.UTF_8)}" }

  private fun get(url: String, token: String?, accept: String = "application/ld+json", ifNoneMatch: String? = null): TestHttpResponse =
    http.prepareGet(url)
      .addHeader("Accept", accept)
      .apply { token?.let { addHeader("Authorization", "Bearer $it") } }
      .apply { ifNoneMatch?.let { addHeader("If-None-Match", it) } }
      .execute()

  private fun put(url: String, token: String, resource: URI, value: String, vararg conditions: Pair<String, String>): TestHttpResponse =
    http.preparePut(url)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $token")
      .apply { conditions.forEach { (header, v) -> addHeader(header, v) } }
      .setBody("""{"@id":"$resource","$name":"$value"}""")
      .execute()

  private fun nameIn(url: String, token: String): String = get(url, token).responseBody

  @Test
  fun `a tag read from a context validates a write there, in every form, on both routes`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val owner = token(pod, "$x#read", "$x#write")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(x to "Ada"))

    for (route in routes(pod, resource)) {
      val inX = selected(route, x)
      for ((accept, query) in listOf("application/ld+json" to "", "application/n-quads" to "", "application/ld+json" to "&include_contexts=true")) {
        val tag = assertNotNull(get(inX + query, owner, accept).headers.get("ETag"), "$route $accept$query")
        // The same statements again: the state, and so every tag, stays as it was.
        assertEquals(200, put(inX, owner, resource, "Ada", "If-Match" to tag).statusCode, "$route $accept$query: $tag")
      }
    }
  }

  @Test
  fun `an intervening change in the context fails the write and keeps the newer state`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val owner = token(pod, "$x#read", "$x#write")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(x to "Ada"))

    for (route in routes(pod, resource)) {
      val inX = selected(route, x)
      val tag = assertNotNull(get(inX, owner).headers.get("ETag"))
      assertEquals(200, put(inX, owner, resource, "Grace").statusCode)

      assertEquals(412, put(inX, owner, resource, "Lovelace", "If-Match" to tag).statusCode)
      assertTrue("Grace" in nameIn(inX, owner), "the newer state must survive the refused write")
      seed(pod, resource, mapOf(x to "Ada"))
    }
  }

  @Test
  fun `a change in another context leaves the tag valid, and a tag of every context validates nothing`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val y = context(pod, "y")
    val owner = token(pod, "$x#read", "$x#write", "$y#read", "$y#write")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(x to "Ada", y to "Ada in y"))

    for (route in routes(pod, resource)) {
      val inX = selected(route, x)
      val xTag = assertNotNull(get(inX, owner).headers.get("ETag"))
      val unionTag = assertNotNull(get(route, owner).headers.get("ETag"))
      assertNotEquals(xTag, unionTag, "a selected and an unselected read are two representations")

      assertEquals(200, put(selected(route, y), owner, resource, "Grace in y").statusCode)
      assertEquals(200, put(inX, owner, resource, "Ada", "If-Match" to xTag).statusCode, "a change in y must not fail a write to x")

      assertEquals(412, put(inX, owner, resource, "Ada", "If-Match" to unionTag).statusCode, "a union tag validates no write")
      seed(pod, resource, mapOf(x to "Ada", y to "Ada in y"))
    }
  }

  @Test
  fun `If-None-Match star creates in a context even when another context holds the resource`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val y = context(pod, "y")
    val owner = token(pod, "$x#read", "$x#write", "$y#read", "$y#write")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(y to "Ada in y"))

    val inX = selected(resource.toString(), x)
    assertEquals(201, put(inX, owner, resource, "Ada", "If-None-Match" to "*").statusCode)
    assertEquals(412, put(inX, owner, resource, "Grace", "If-None-Match" to "*").statusCode)
    assertTrue("Ada" in nameIn(inX, owner))
  }

  @Test
  fun `a caller who may not write is refused the same way with or without a condition`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val reader = token(pod, "$x#read")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(x to "Ada"))
    val tag = assertNotNull(get(selected(resource.toString(), x), reader).headers.get("ETag"))

    for (route in routes(pod, resource)) {
      val inX = selected(route, x)
      for (conditions in listOf(emptyArray(), arrayOf("If-Match" to tag), arrayOf("If-Match" to "\"stale\""), arrayOf("If-None-Match" to "*"), arrayOf("If-Match" to "no-quotes"))) {
        val refused = put(inX, reader, resource, "Mallory", *conditions)
        assertEquals(403, refused.statusCode, "$route ${conditions.toList()}")
        assertNull(refused.headers.get("ETag"))
      }
    }
  }

  @Test
  fun `a PATCH or DELETE of nothing in the context stays 404 under a condition`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val y = context(pod, "y")
    val owner = token(pod, "$x#read", "$x#write", "$y#read", "$y#write")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(y to "Ada in y"))

    for (route in routes(pod, resource)) {
      val inX = selected(route, x)
      val delete = http.prepareDelete(inX).addHeader("Authorization", "Bearer $owner").addHeader("If-Match", "*").execute()
      assertEquals(404, delete.statusCode, route)
      val patch = http.prepare("PATCH", inX)
        .addHeader("Content-Type", "application/merge-patch+json")
        .addHeader("Authorization", "Bearer $owner")
        .addHeader("If-Match", "*")
        .setBody("""{"$name":"Grace"}""")
        .execute()
      assertEquals(404, patch.statusCode, route)
    }
  }

  @Test
  fun `a condition that is not an entity tag refuses the write, a weak one never matches, a list matches any`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val owner = token(pod, "$x#read", "$x#write")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(x to "Ada"))
    val inX = selected(resource.toString(), x)
    val tag = assertNotNull(get(inX, owner).headers.get("ETag"))

    // Dropped, the condition would have let this write through unconditionally.
    assertEquals(400, put(inX, owner, resource, "Grace", "If-Match" to tag.trim('"')).statusCode)
    assertEquals(400, put(inX, owner, resource, "Grace", "If-None-Match" to "\"a\", *").statusCode)
    assertTrue("Ada" in nameIn(inX, owner))

    assertEquals(412, put(inX, owner, resource, "Grace", "If-Match" to "W/$tag").statusCode)
    assertEquals(412, put(inX, owner, resource, "Grace", "If-None-Match" to "W/$tag").statusCode)
    assertEquals(200, put(inX, owner, resource, "Ada", "If-Match" to "\"other\", $tag").statusCode)
  }

  @Test
  fun `a tag follows what the caller reads, so a hidden change keeps a 304 and a grant change ends one`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val y = context(pod, "y")
    val z = context(pod, "z")
    val appWebId = "https://id.test/app"
    val owner = token(pod, "$x#read", "$x#write", "$y#read", "$y#write", webId = "https://id.test/owner")
    val resource = sempodsTestFactory.eventUri(pod.name)

    for (route in routes(pod, resource)) {
      seed(pod, resource, mapOf(x to "Ada", y to "Ada in y"))
      val app = token(pod, "$x#read", webId = appWebId)
      val appTag = assertNotNull(get(route, app).headers.get("ETag"))
      val ownerTag = assertNotNull(get(route, owner).headers.get("ETag"))
      assertNotEquals(appTag, ownerTag, "different bodies cannot share a tag")

      // A write in y, which the app cannot read.
      assertEquals(200, put(selected(route, y), owner, resource, "Grace in y").statusCode)
      assertEquals(304, get(route, app, ifNoneMatch = appTag).statusCode, "a hidden change must not end the app's 304")
      assertEquals(200, get(route, owner, ifNoneMatch = ownerTag).statusCode)

      // The app is granted y as well, with no data changed: the body it gets has.
      token(pod, "$x#read", "$y#read", webId = appWebId)
      assertEquals(200, get(route, app, ifNoneMatch = appTag).statusCode)
      // And loses x and y: nothing visible, so no 304 either.
      token(pod, "$z#read", webId = appWebId)
      assertEquals(404, get(route, app, ifNoneMatch = appTag).statusCode)
    }
  }

  @Test
  fun `a read is revalidated privately, on its 200 and its 304, anonymous or not`() {
    val pod = sempodsTestFactory.newPod()
    val publicContext = sempodsTestFactory.publicContextUri(pod.name)
    val x = context(pod, "x")
    val owner = token(pod, "$x#read")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(x to "Ada", publicContext to "Ada in public"))

    for (route in routes(pod, resource)) {
      for (caller in listOf(null, owner)) {
        val ok = get(route, caller)
        val notModified = get(route, caller, ifNoneMatch = assertNotNull(ok.headers.get("ETag")))
        assertEquals(304, notModified.statusCode)
        for (response in listOf(ok, notModified)) {
          assertEquals("private, no-cache", response.headers.get("Cache-Control"), "$route caller=$caller")
          val vary = response.headers.get("Vary").orEmpty().split(",").map { it.trim().lowercase() }
          assertTrue("accept" in vary && "authorization" in vary, "$route caller=$caller: $vary")
        }
      }
    }
  }

  @Test
  fun `a slot tag describes the slot, so a change elsewhere on the subject does not fail a slot write`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val y = context(pod, "y")
    val owner = token(pod, "$x#read", "$x#write", "$y#read", "$y#write")
    val resource = sempodsTestFactory.eventUri(pod.name)
    seed(pod, resource, mapOf(x to "Ada", y to "Ada in y"))
    val knows = URI("https://schema.org/knows")
    val slot = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/resources/" +
      "${encodeUriToUrlSafeBase64(resource)}/${encodeUriToUrlSafeBase64(URI(name.stringValue()))}"
    val inX = selected(slot, x)

    val tag = assertNotNull(get(inX, owner).headers.get("ETag"))
    assertEquals(304, get(inX, owner, ifNoneMatch = tag).statusCode, "a slot read answers If-None-Match")

    // Another predicate in x, and the same predicate in y.
    val other = selected(
      "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/resources/${encodeUriToUrlSafeBase64(resource)}/${encodeUriToUrlSafeBase64(knows)}",
      x,
    )
    val added = http.preparePost(other)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $owner")
      .setBody("""{"@id":"https://example.org/people/grace"}""")
      .execute()
    assertEquals(201, added.statusCode)
    val replacedInY = http.preparePut(selected(slot, y))
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $owner")
      .setBody("""[{"@value":"Grace in y"}]""")
      .execute()
    assertEquals(204, replacedInY.statusCode)

    val write = http.preparePut(inX)
      .addHeader("Content-Type", "application/ld+json")
      .addHeader("Authorization", "Bearer $owner")
      .addHeader("If-Match", tag)
      .setBody("""[{"@value":"Grace"}]""")
      .execute()
    assertEquals(204, write.statusCode, "only the slot in x decides the condition")
    assertNotEquals(tag, write.headers.get("ETag"), "the echoed tag is the slot's new one")
  }
}
