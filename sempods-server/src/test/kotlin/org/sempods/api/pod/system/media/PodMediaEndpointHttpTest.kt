package org.sempods.api.pod.system.media

import com.google.inject.Inject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.SempodsTestModule
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.media.PodMediaRef
import org.sempods.pods.media.PodMediaStore
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.media.PodMediaSource
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.okhttp.getAll
import org.sempods.pods.mongo.persist.toPodId
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The media surface over HTTP, with the authorization that matters checked from the outside.
 *
 * The point of most of these is not that the happy path works but that the *context* model reaches
 * media unchanged: anonymous callers see what a public context holds and nothing else, a bearer
 * sees what it was granted, and a manage scope cascades the same way it does for resources.
 */
/**
 * Serialised against [org.sempods.pods.media.MediaSourceFetcherTest]: this suite fetches remote
 * media through the same code path, and that one counts the buffers it leaves in the JVM's temp
 * directory. Rung 2 of `docs/testing.md` §"When a test is not safe".
 */
@ResourceLock("sempods-media-source-buffers")
class PodMediaEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var mediaStore: PodMediaStore

  @Inject
  private lateinit var podMediaDao: PodMediaDao

  private val httpClient by lazy { http.followingRedirects }

  private val mediaBase get() = "${SempodsModule.config.apiBaseUrl}"

  private fun uploadUrl(pod: String, context: URI, filename: String? = null): String {
    val encoded = URLEncoder.encode(context.toString(), StandardCharsets.UTF_8)
    val suffix = filename?.let { "&filename=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" } ?: ""
    return "$mediaBase$pod/_system/media?context=$encoded$suffix"
  }

  private fun mediaUrl(pod: String, mediaId: String, context: URI? = null): String {
    val base = "$mediaBase$pod/_system/media/$mediaId"
    return context?.let { "$base?context=${URLEncoder.encode(it.toString(), StandardCharsets.UTF_8)}" } ?: base
  }

  private fun contentUrl(pod: String, mediaId: String) = "$mediaBase$pod/_system/media/$mediaId/content"

  /** Creates a context and a token carrying the given permissions on it. */
  private fun contextWithToken(
    pod: PodDbo,
    path: String,
    permissions: List<String> = listOf("read", "write"),
    public: Boolean = false,
    webId: String = "https://id.test/${randomId()}",
  ): Pair<URI, String> {
    val contextUri = sempodsUriBuilder.buildContext(pod.name, path)
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
      isPublic = public,
    )
    return contextUri to mintScopedToken(pod.name, permissions.map { "$contextUri#$it" }, webId = webId)
  }

  private fun upload(
    pod: PodDbo,
    context: URI,
    token: String,
    content: String = "bytes-${randomId()}",
    contentType: String = "image/png",
    filename: String? = null,
  ): TestHttpResponse =
    httpClient.preparePost(uploadUrl(pod.name, context, filename))
      .addHeader("Authorization", "Bearer $token")
      .addHeader("Content-Type", contentType)
      .setBody(content)
      .execute()

  /** The contexts a metadata response reports — one per assignment it was allowed to show. */
  private fun assignedContexts(response: TestHttpResponse): Set<String> =
    assignmentsOf(response).mapTo(HashSet()) { it["context"] as String }

  private fun assignmentsOf(response: TestHttpResponse): List<Map<*, *>> {
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java)
    return (body["assignments"] as List<*>).map { it as Map<*, *> }
  }

  private fun readAssignments(url: String, token: String): List<Map<*, *>> =
    assignmentsOf(httpClient.prepareGet(url).addHeader("Authorization", "Bearer $token").execute())

  private fun mediaIdOf(response: TestHttpResponse): String {
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java)
    return checkNotNull(body["id"] as? String) { "no id in ${response.responseBody}" }
  }

  // ─── upload ────────────────────────────────────────────────────────────────

  @Test
  fun `upload stores the bytes and answers with the media and its content url`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")
    val content = "png-bytes-${randomId()}"

    val response = upload(pod, context, token, content = content, filename = "photo.png")

    assertEquals(201, response.statusCode)
    val body = JsonMappers.default().readValue(response.responseBody, Map::class.java)
    val mediaId = body["id"] as String
    assertEquals(contentUrl(pod.name, mediaId), body["content_url"])
    assertEquals(contentUrl(pod.name, mediaId), response.getHeader("Location"))
    // Where it is, and nothing about it — see `upload answers with no metadata at all` below.
    assertEquals(setOf("id", "content_url"), body.keys)

    val metadata = JsonMappers.default().readValue(
      httpClient.prepareGet(mediaUrl(pod.name, mediaId)).addHeader("Authorization", "Bearer $token")
        .execute().responseBody,
      Map::class.java,
    )
    assertEquals(content.length, (metadata["size"] as Number).toInt())
    // Everything descriptive hangs off the assignment, not the object — see MediaAssignment.
    val assignment = (metadata["assignments"] as List<*>).single() as Map<*, *>
    assertEquals(context.toString(), assignment["context"])
    assertEquals("image/png", assignment["content_type"])
    assertEquals("photo.png", assignment["filename"])
  }

  @Test
  fun `upload answers with no metadata at all, so a dedup hit cannot be told from a first store`() {
    val pod = sempodsTestFactory.newPod()
    val (first, firstToken) = contextWithToken(pod, "tests/first-${randomId()}")
    val (second, secondToken) = contextWithToken(pod, "tests/second-${randomId()}")
    val content = "shared-${randomId()}"

    val firstUpload = upload(pod, first, firstToken, content = content, contentType = "image/png", filename = "original.png")
    val secondUpload = upload(pod, second, secondToken, content = content, contentType = "image/jpeg", filename = "mine.jpg")

    // The stored row belongs to the first uploader. Returning it would have told the second one
    // "already here" through created_at, filename and content_type, whatever the status code says.
    assertEquals(firstUpload.responseBody, secondUpload.responseBody)
    assertEquals(
      setOf("id", "content_url"),
      JsonMappers.default().readValue(secondUpload.responseBody, Map::class.java).keys,
    )
  }

  @Test
  fun `uploading the same bytes twice yields one media with both contexts`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.test/${randomId()}"
    val first = sempodsUriBuilder.buildContext(pod.name, "tests/one-${randomId()}")
    val second = sempodsUriBuilder.buildContext(pod.name, "tests/two-${randomId()}")
    listOf(first, second).forEach {
      podContextsDao.create(checkNotNull(pod.id), it.toString(), null, null, "test")
    }
    val token = mintScopedToken(
      pod.name,
      listOf("$first#read", "$first#write", "$second#read", "$second#write"),
      webId = webId,
    )
    val content = "identical-${randomId()}"

    val firstUpload = upload(pod, first, token, content = content)
    val secondUpload = upload(pod, second, token, content = content)

    // Both 201, and deliberately so: a 200-vs-201 distinction would tell an uploader that this pod
    // already holds a given file, in contexts they may not be able to read.
    assertEquals(201, firstUpload.statusCode)
    assertEquals(201, secondUpload.statusCode)
    assertEquals(mediaIdOf(firstUpload), mediaIdOf(secondUpload))

    val metadata = httpClient.prepareGet(mediaUrl(pod.name, mediaIdOf(firstUpload)))
      .addHeader("Authorization", "Bearer $token").execute()
    assertEquals(setOf(first.toString(), second.toString()), assignedContexts(metadata))
  }

  @Test
  fun `upload without a token is rejected`() {
    val pod = sempodsTestFactory.newPod()
    val (context, _) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = httpClient.preparePost(uploadUrl(pod.name, context))
      .addHeader("Content-Type", "image/png").setBody("bytes").execute()

    assertEquals(401, response.statusCode)
  }

  @Test
  fun `upload into a context the caller cannot write is forbidden`() {
    val pod = sempodsTestFactory.newPod()
    val (readable, token) = contextWithToken(pod, "tests/readable-${randomId()}", permissions = listOf("read"))

    val response = upload(pod, readable, token)

    assertEquals(403, response.statusCode)
  }

  @Test
  fun `upload without a content type is rejected`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    // The registry records the type and the content route serves it, so there is no sensible
    // default: guessing would put a wrong type in front of every later reader.
    val response = httpClient.preparePost(uploadUrl(pod.name, context))
      .addHeader("Authorization", "Bearer $token").setBody("bytes").execute()

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `upload over the size limit is refused with 413`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = upload(pod, context, token, content = "x".repeat((SempodsTestModule.TEST_MAX_UPLOAD_BYTES + 1).toInt()))

    assertEquals(413, response.statusCode)
  }

  // ─── reading ───────────────────────────────────────────────────────────────

  @Test
  fun `anonymous callers read media of a public context`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/public-${randomId()}", public = true)
    val content = "public-${randomId()}"
    val mediaId = mediaIdOf(upload(pod, context, token, content = content))

    val response = httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute()

    assertEquals(200, response.statusCode)
    assertEquals(content, response.responseBody)
    assertEquals("image/png", response.getHeader("Content-Type"))
  }

  @Test
  fun `anonymous callers get 404 for media of a private context`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/private-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, context, token))

    // 404 rather than 403 on purpose: the id is a content hash, so a distinguishable "exists but
    // forbidden" would answer "does this pod hold exactly these bytes" for anyone who has them.
    assertEquals(404, httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute().statusCode)
    assertEquals(404, httpClient.prepareGet(mediaUrl(pod.name, mediaId)).execute().statusCode)
  }

  @Test
  fun `a dedup hit is indistinguishable from a first store, all the way through`() {
    val pod = sempodsTestFactory.newPod()
    val (hidden, ownerToken) = contextWithToken(pod, "tests/hidden-${randomId()}")
    val (mine, myToken) = contextWithToken(pod, "tests/mine-${randomId()}")
    val shared = "leaked-document-${randomId()}"

    // Somebody else already put these bytes in a context I cannot read.
    upload(pod, hidden, ownerToken, content = shared, contentType = "image/png", filename = "their-name.png")

    val dedupHit = upload(pod, mine, myToken, content = shared, contentType = "image/jpeg", filename = "my-name.jpg")
    val firstStore = upload(pod, mine, myToken, content = "never-seen-${randomId()}", contentType = "image/jpeg", filename = "my-name.jpg")

    assertEquals(dedupHit.statusCode, firstStore.statusCode)

    // The metadata I can now read must describe *my* assignment. If the row carried one shared
    // description, comparing it with what I sent would tell me the pod already held the file —
    // which for a holder of write on a single sandbox context is an oracle over every other app's
    // private contexts.
    val dedupMeta = readAssignments(mediaUrl(pod.name, mediaIdOf(dedupHit)), myToken)
    val freshMeta = readAssignments(mediaUrl(pod.name, mediaIdOf(firstStore)), myToken)

    listOf(dedupMeta, freshMeta).forEach { assignments ->
      assertEquals(1, assignments.size, "only my own assignment is visible")
      assertEquals(mine.toString(), assignments.single()["context"])
      assertEquals("image/jpeg", assignments.single()["content_type"])
      assertEquals("my-name.jpg", assignments.single()["filename"])
    }
    assertEquals(
      freshMeta.single().keys,
      dedupMeta.single().keys,
      "nothing in the shape may differ either",
    )
  }

  @Test
  fun `metadata reports only the contexts the caller may read`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.test/${randomId()}"
    val private = sempodsUriBuilder.buildContext(pod.name, "tests/secret-${randomId()}")
    val public = sempodsUriBuilder.buildContext(pod.name, "tests/public-${randomId()}")
    podContextsDao.create(checkNotNull(pod.id), private.toString(), null, null, "test")
    podFacade.createContext(podName = pod.name, contextUri = public, public = true, label = "", description = null)
    val token = mintScopedToken(
      pod.name,
      listOf("$private#read", "$private#write", "$public#write"),
      webId = webId,
    )
    val mediaId = mediaIdOf(upload(pod, private, token))
    assertEquals(204, http.prepare("PUT", mediaUrl(pod.name, mediaId, public))
      .addHeader("Authorization", "Bearer $token").execute().statusCode)

    val anonymous = httpClient.prepareGet(mediaUrl(pod.name, mediaId)).execute()

    assertEquals(200, anonymous.statusCode, "the public assignment makes it readable")
    // Readability is decided by intersection, so the answer has to be reported by intersection:
    // the private context is a path inside the pod's context tree and names what it is for.
    assertEquals(setOf(public.toString()), assignedContexts(anonymous))
  }

  @Test
  fun `an unknown media id answers the same 404 as a forbidden one`() {
    val pod = sempodsTestFactory.newPod()

    assertEquals(404, httpClient.prepareGet(contentUrl(pod.name, randomId())).execute().statusCode)
  }

  @Test
  fun `a bearer without a grant on the media's context gets 404`() {
    val pod = sempodsTestFactory.newPod()
    val (context, ownerToken) = contextWithToken(pod, "tests/owned-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, context, ownerToken))
    val (_, strangerToken) = contextWithToken(pod, "tests/other-${randomId()}")

    val response = httpClient.prepareGet(contentUrl(pod.name, mediaId))
      .addHeader("Authorization", "Bearer $strangerToken").execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `a manage scope on the root reaches media of a descendant context`() {
    val pod = sempodsTestFactory.newPod()
    val root = sempodsUriBuilder.buildContext(pod.name, "apps/mediaapp")
    val child = sempodsUriBuilder.buildContext(pod.name, "apps/mediaapp/inner")
    listOf(root, child).forEach {
      podContextsDao.create(checkNotNull(pod.id), it.toString(), null, null, "test")
    }
    val manageToken = mintScopedToken(pod.name, listOf("$root#manage"))
    val content = "managed-${randomId()}"

    val mediaId = mediaIdOf(upload(pod, child, manageToken, content = content))
    val response = httpClient.prepareGet(contentUrl(pod.name, mediaId))
      .addHeader("Authorization", "Bearer $manageToken").execute()

    assertEquals(200, response.statusCode, "manage on the root must cascade to a descendant, as it does for resources")
    assertEquals(content, response.responseBody)
  }

  // ─── caching and delivery headers ──────────────────────────────────────────

  @Test
  fun `the content etag is the media id and answers 304`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/public-${randomId()}", public = true)
    val mediaId = mediaIdOf(upload(pod, context, token))

    val first = httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute()
    val etag = assertNotNull(first.getHeader("ETag"))
    assertTrue(etag.contains(mediaId), "the content hash is the strong validator by construction: $etag")
    assertEquals("private, no-cache", first.getHeader("Cache-Control"))

    val conditional = httpClient.prepareGet(contentUrl(pod.name, mediaId))
      .addHeader("If-None-Match", etag).execute()

    assertEquals(304, conditional.statusCode)
  }

  @Test
  fun `the etag changes when the served type does, so a stale representation cannot survive a 304`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.test/${randomId()}"
    // Lexically ordered on purpose: `a-…` is the assignment the content route selects while it is
    // readable, `b-…` the one it falls back to. Both public, so an anonymous client can revalidate.
    val first = sempodsUriBuilder.buildContext(pod.name, "tests/a-${randomId()}")
    val second = sempodsUriBuilder.buildContext(pod.name, "tests/b-${randomId()}")
    listOf(first, second).forEach {
      podFacade.createContext(podName = pod.name, contextUri = it, public = true, label = "", description = null)
    }
    val token = mintScopedToken(
      pod.name,
      listOf("$first#read", "$first#write", "$second#read", "$second#write"),
      webId = webId,
    )
    val content = "same-bytes-${randomId()}"
    val mediaId = mediaIdOf(upload(pod, first, token, content = content, contentType = "image/png"))
    upload(pod, second, token, content = content, contentType = "application/x-thing")

    val asPng = httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute()
    assertEquals("inline", asPng.getHeader("Content-Disposition"))
    val pngTag = assertNotNull(asPng.getHeader("ETag"))

    // The first assignment goes away, so this URL now answers with the other context's claim.
    assertEquals(204, http.prepare("DELETE", mediaUrl(pod.name, mediaId, first))
      .addHeader("Authorization", "Bearer $token").execute().statusCode)

    val revalidated = httpClient.prepareGet(contentUrl(pod.name, mediaId))
      .addHeader("If-None-Match", pngTag).execute()

    // A media-id-only validator would answer 304 here and leave the client rendering an
    // `application/x-thing` as an inline image, using headers the server no longer serves.
    assertEquals(200, revalidated.statusCode, "the representation changed, so the validator must have too")
    assertEquals("attachment", revalidated.getHeader("Content-Disposition"))
    assertTrue(revalidated.getHeader("ETag") != pngTag)
  }

  @Test
  fun `a conditional request from a caller who may not read gets 404, not 304`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/private-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, context, token))

    // Visibility is decided before preconditions; otherwise a 304 would confirm both the object's
    // existence and its content hash to someone who cannot see it.
    val response = httpClient.prepareGet(contentUrl(pod.name, mediaId))
      .addHeader("If-None-Match", "\"$mediaId\"").execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `safe raster types are served inline and everything else downloads`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/public-${randomId()}", public = true)

    val png = mediaIdOf(upload(pod, context, token, content = "png-${randomId()}", contentType = "image/png"))
    val svg = mediaIdOf(upload(pod, context, token, content = "<svg/>-${randomId()}", contentType = "image/svg+xml"))
    val unknown = mediaIdOf(upload(pod, context, token, content = "blob-${randomId()}", contentType = "application/x-thing"))

    val pngResponse = httpClient.prepareGet(contentUrl(pod.name, png)).execute()
    assertEquals("inline", pngResponse.getHeader("Content-Disposition"))
    assertEquals("nosniff", pngResponse.getHeader("X-Content-Type-Options"))

    // SVG is an image type and a scriptable document — inline in the pod's own origin would give it
    // same-origin access to whatever else the pod serves.
    assertEquals("attachment", httpClient.prepareGet(contentUrl(pod.name, svg)).execute().getHeader("Content-Disposition"))
    assertEquals("attachment", httpClient.prepareGet(contentUrl(pod.name, unknown)).execute().getHeader("Content-Disposition"))
  }

  @Test
  fun `the content route serves the stored type to a client that asks for it`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/public-${randomId()}", public = true)
    val content = "png-${randomId()}"
    val mediaId = mediaIdOf(upload(pod, context, token, content = content, contentType = "image/png"))

    // `<img>` sends `Accept: image/*`, and a fetch for a known type sends the type. The endpoint
    // inherits a class-level `@Produces(application/json)` from `commons-jaxrs`' BaseEndpoint, so
    // without its own declaration Jersey refuses these before the method ever runs — and a
    // wildcard Accept, which is what the other tests send, would never notice.
    listOf("image/png", "image/*", "image/png;q=0.9,*/*;q=0.8").forEach { accept ->
      val response = httpClient.prepareGet(contentUrl(pod.name, mediaId))
        .addHeader("Accept", accept).execute()

      assertEquals(200, response.statusCode, "Accept: $accept must be served, not negotiated away")
      assertEquals(content, response.responseBody)
    }
  }

  @Test
  fun `no answer on either route may be cached without asking again`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/public-${randomId()}", public = true)
    val mediaId = mediaIdOf(upload(pod, context, token))

    val metadata = httpClient.prepareGet(mediaUrl(pod.name, mediaId)).execute()
    val content = httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute()
    val revalidated = httpClient.prepareGet(contentUrl(pod.name, mediaId))
      .addHeader("If-None-Match", assertNotNull(content.getHeader("ETag"))).execute()

    // Both URLs answer differently per caller AND over time: a context turning private, or a grant
    // being revoked, changes the answer without changing the URL. Without an explicit directive a
    // cache may apply heuristic freshness (RFC 9111 §4.2.2) and keep serving the old one — which
    // would quietly contradict `SPS-GRANT-003`: revocation takes effect on the next request.
    assertEquals("private, no-store", metadata.getHeader("Cache-Control"), "metadata must not be stored at all")
    assertEquals("private, no-cache", content.getHeader("Cache-Control"), "content may be stored but must be revalidated")
    assertEquals(304, revalidated.statusCode)
    assertEquals(
      "private, no-cache",
      revalidated.getHeader("Cache-Control"),
      "the 304 updates the cache's held headers, so it has to repeat the policy",
    )
    listOf(metadata, content).forEach { response ->
      // Repeated `Vary` headers are equivalent to one comma-joined list, and there are several
      // here: this endpoint's `Authorization`, the shared filter's `Accept`, and the connector's
      // `Accept-Encoding`. Reading only the first would assert whichever landed on top.
      val vary = response.headers.getAll("Vary").flatMap { it.split(",") }.map { it.trim() }
      assertTrue("Authorization" in vary, "the answer depends on who is asking, got: $vary")
    }
  }

  @Test
  fun `range requests are not advertised`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/public-${randomId()}", public = true)
    val mediaId = mediaIdOf(upload(pod, context, token))

    // A named limitation rather than an oversight — see PodMediaEndpoint's class comment.
    assertNull(httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute().getHeader("Accept-Ranges"))
  }

  // ─── assignment ────────────────────────────────────────────────────────────

  @Test
  fun `a context-copy makes the media readable from the second context`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.test/${randomId()}"
    val source = sempodsUriBuilder.buildContext(pod.name, "tests/source-${randomId()}")
    val target = sempodsUriBuilder.buildContext(pod.name, "tests/target-${randomId()}")
    podContextsDao.create(checkNotNull(pod.id), source.toString(), null, null, "test")
    podFacade.createContext(podName = pod.name, contextUri = target, public = true, label = "", description = null)
    val token = mintScopedToken(pod.name, listOf("$source#read", "$source#write", "$target#write"), webId = webId)
    val mediaId = mediaIdOf(upload(pod, source, token))

    assertEquals(404, httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute().statusCode)

    val assign = http.prepare("PUT", mediaUrl(pod.name, mediaId, target))
      .addHeader("Authorization", "Bearer $token").execute()

    assertEquals(204, assign.statusCode)
    assertEquals(
      200,
      httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute().statusCode,
      "the target context is public, so the copy must make the media anonymously readable",
    )
  }

  @Test
  fun `a context-copy without read on the source is refused`() {
    val pod = sempodsTestFactory.newPod()
    val (source, ownerToken) = contextWithToken(pod, "tests/source-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, source, ownerToken))
    val (target, strangerToken) = contextWithToken(pod, "tests/target-${randomId()}")

    val response = http.prepare("PUT", mediaUrl(pod.name, mediaId, target))
      .addHeader("Authorization", "Bearer $strangerToken").execute()

    // 404, not 403: holding write somewhere must not become a way to ask whether the pod holds a
    // given file. Same reasoning as the read routes — see the roadmap's rule on the media id.
    assertEquals(404, response.statusCode)
  }

  @Test
  fun `unassigning the last context leaves the bytes and stops serving them`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/public-${randomId()}", public = true)
    val mediaId = mediaIdOf(upload(pod, context, token))
    assertEquals(200, httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute().statusCode)

    val unassign = http.prepare("DELETE", mediaUrl(pod.name, mediaId, context))
      .addHeader("Authorization", "Bearer $token").execute()

    assertEquals(204, unassign.statusCode)
    assertEquals(
      404,
      httpClient.prepareGet(contentUrl(pod.name, mediaId)).execute().statusCode,
      "with no assignment left, nobody can read it — the bytes stay only for the grace period",
    )
  }

  @Test
  fun `unassign is not an existence oracle`() {
    val pod = sempodsTestFactory.newPod()
    val (foreign, ownerToken) = contextWithToken(pod, "tests/foreign-${randomId()}")
    val hiddenMediaId = mediaIdOf(upload(pod, foreign, ownerToken))
    val (own, strangerToken) = contextWithToken(pod, "tests/own-${randomId()}")

    // Same request twice: once naming a media that exists in a context the caller cannot read, once
    // naming one that does not exist at all. Two different answers here would let anyone holding
    // write on a single context ask "does this pod hold these bytes" by hashing a file they have.
    val existsElsewhere = http.prepare("DELETE", mediaUrl(pod.name, hiddenMediaId, own))
      .addHeader("Authorization", "Bearer $strangerToken").execute()
    val neverExisted = http.prepare("DELETE", mediaUrl(pod.name, randomId(), own))
      .addHeader("Authorization", "Bearer $strangerToken").execute()

    assertEquals(204, existsElsewhere.statusCode)
    assertEquals(existsElsewhere.statusCode, neverExisted.statusCode)

    // And it really was a no-op: the foreign assignment is untouched.
    val stillThere = httpClient.prepareGet(contentUrl(pod.name, hiddenMediaId))
      .addHeader("Authorization", "Bearer $ownerToken").execute()
    assertEquals(200, stillThere.statusCode)
  }

  @Test
  fun `unassigning twice answers the same both times`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, context, token))

    val first = http.prepare("DELETE", mediaUrl(pod.name, mediaId, context))
      .addHeader("Authorization", "Bearer $token").execute()
    val second = http.prepare("DELETE", mediaUrl(pod.name, mediaId, context))
      .addHeader("Authorization", "Bearer $token").execute()

    assertEquals(204, first.statusCode)
    assertEquals(204, second.statusCode, "ensure-absent is satisfied by doing nothing")
  }

  @Test
  fun `unassigning without write on the named context is forbidden`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, context, token))
    val (_, readOnlyToken) = contextWithToken(pod, "tests/other-${randomId()}", permissions = listOf("read"))

    val response = http.prepare("DELETE", mediaUrl(pod.name, mediaId, context))
      .addHeader("Authorization", "Bearer $readOnlyToken").execute()

    assertEquals(403, response.statusCode)
  }

  @Test
  fun `a write targeting an unknown context is rejected before the media is looked at`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, context, token))
    val unknown = sempodsUriBuilder.buildContext(pod.name, "tests/never-created-${randomId()}")

    val response = http.prepare("PUT", mediaUrl(pod.name, mediaId, unknown))
      .addHeader("Authorization", "Bearer $token").execute()

    assertEquals(404, response.statusCode)
  }

  @Test
  fun `a write without a context parameter is rejected`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, context, token))

    val response = http.prepare("PUT", mediaUrl(pod.name, mediaId))
      .addHeader("Authorization", "Bearer $token").execute()

    assertEquals(400, response.statusCode)
    assertTrue(response.responseBody.contains("context"), "the failure must name what is missing")
  }

  // ─── copy from URL ─────────────────────────────────────────────────────────

  /**
   * A source the pod is allowed to fetch: loopback, and started by this test.
   *
   * The production composition would refuse it — `SempodsMediaModule` binds
   * `MediaSourceAddressGuard.DENY_PRIVATE`, and loopback is the first thing that policy rejects.
   * `SempodsTestModule` binds `LoopbackOnlyAddressGuard` instead, which is the only place in the
   * project where a non-public address is fetchable and is why it lives in a test source set. The
   * address policy itself is covered in `MediaSourceAddressGuardTest`; what these tests add is that
   * the *route* is wired to it.
   */
  private fun sourceUrl(path: String) = "http://127.0.0.1:${mediaSource.address.port}$path"

  private fun uploadFromSource(pod: PodDbo, context: URI, token: String?, body: String): TestHttpResponse {
    val request = httpClient.preparePost(uploadUrl(pod.name, context))
      .addHeader("Content-Type", PodMediaSource.MEDIA_TYPE)
      .setBody(body)
    token?.let { request.addHeader("Authorization", "Bearer $it") }
    return request.execute()
  }

  @Test
  fun `a source descriptor stores what the pod fetched`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = uploadFromSource(
      pod,
      context,
      token,
      """{"source_url": "${sourceUrl("/image.png")}", "filename": "poster.png"}""",
    )

    assertEquals(201, response.statusCode)
    val mediaId = mediaIdOf(response)
    assertEquals(contentUrl(pod.name, mediaId), response.getHeader("Location"))

    val content = httpClient.prepareGet(contentUrl(pod.name, mediaId))
      .addHeader("Authorization", "Bearer $token").execute()
    assertEquals(200, content.statusCode)
    assertEquals(SOURCE_BODY, content.responseBody)
    // The type is the source's claim, carried through to what the pod serves.
    assertTrue(
      content.contentType.orEmpty().startsWith("image/png"),
      "expected the source's type, got '${content.contentType}'",
    )
    assertEquals("poster.png", readAssignments(mediaUrl(pod.name, mediaId), token).single()["filename"])
  }

  @Test
  fun `the same bytes are one media whichever way they arrived`() {
    // The id is the content hash, so the two ingestion paths cannot produce two objects — and the
    // second one answers 201 like the first, because saying "already here" is the leak the uniform
    // status exists to avoid.
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val fetched = uploadFromSource(pod, context, token, """{"source_url": "${sourceUrl("/image.png")}"}""")
    val posted = upload(pod, context, token, content = SOURCE_BODY)

    assertEquals(201, fetched.statusCode)
    assertEquals(201, posted.statusCode)
    assertEquals(mediaIdOf(fetched), mediaIdOf(posted))
  }

  @Test
  fun `a source the guard refuses is a 400 that says nothing about why`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = uploadFromSource(
      pod,
      context,
      token,
      """{"source_url": "http://169.254.169.254/latest/meta-data/"}""",
    )

    assertEquals(400, response.statusCode)
    // Telling "resolves to a private address" apart from "does not resolve" or "answered 500" would
    // turn this route into a map of the network the pod server sits in.
    assertFalse(
      response.responseBody.contains("169.254"),
      "the response must not describe the refusal, got: ${response.responseBody}",
    )
  }

  @Test
  fun `a source over the size limit is refused with 413`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = uploadFromSource(pod, context, token, """{"source_url": "${sourceUrl("/oversized")}"}""")

    assertEquals(413, response.statusCode)
  }

  @Test
  fun `a descriptor without a source url is rejected`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = uploadFromSource(pod, context, token, """{"filename": "poster.png"}""")

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `a source whose Content-Type cannot be parsed is refused at upload, not at read`() {
    // Without the check this answered 201 and then failed on *every* later read of that assignment:
    // the stored string goes back into `TestHttpResponse.ok(entity, contentType)`, whose parser throws. The
    // 201 and the 500 are far enough apart that nothing would connect them.
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = uploadFromSource(pod, context, token, """{"source_url": "${sourceUrl("/broken-type")}"}""")

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `a descriptor that is not readable JSON is rejected`() {
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = uploadFromSource(pod, context, token, "not json at all")

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `a descriptor larger than a descriptor is rejected`() {
    // The descriptor type must not become a door that takes media-sized bodies without passing the
    // media size check — it carries a URL and a filename, and nothing on this route reads further.
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = uploadFromSource(
      pod,
      context,
      token,
      """{"source_url": "${sourceUrl("/image.png")}", "filename": "${"x".repeat(16 * 1024)}"}""",
    )

    assertEquals(400, response.statusCode)
  }

  @Test
  fun `a source descriptor without a token is rejected before anything is fetched`() {
    val pod = sempodsTestFactory.newPod()
    val (context, _) = contextWithToken(pod, "tests/media-${randomId()}")

    val response = uploadFromSource(pod, context, null, """{"source_url": "${sourceUrl("/image.png")}"}""")

    assertEquals(401, response.statusCode)
  }

  @Test
  fun `a source descriptor into a context the caller cannot write is forbidden`() {
    val pod = sempodsTestFactory.newPod()
    val (readable, token) = contextWithToken(pod, "tests/readable-${randomId()}", permissions = listOf("read"))

    val response = uploadFromSource(pod, readable, token, """{"source_url": "${sourceUrl("/image.png")}"}""")

    assertEquals(403, response.statusCode)
  }

  @Test
  fun `a JSON document is still an ordinary upload`() {
    // What the vendor media type buys: the upload route consumes anything, so JSON is a perfectly
    // ordinary media. If the descriptor sat on `application/json` this body would be read as an
    // instruction instead of stored.
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/media-${randomId()}")
    val document = """{"source_url": "${sourceUrl("/image.png")}"}"""

    val response = upload(pod, context, token, content = document, contentType = "application/json")

    assertEquals(201, response.statusCode)
    val content = httpClient.prepareGet(contentUrl(pod.name, mediaIdOf(response)))
      .addHeader("Authorization", "Bearer $token").execute()
    assertEquals(document, content.responseBody)
  }

  // ─── the context vanished mid-request ──────────────────────────────────────

  @Test
  fun `an upload whose context is deleted mid-request writes no assignment`() {
    // The genuine interleaving, not a stand-in for one: the pod authorizes the upload while the
    // context exists, then fetches the source — and the source deletes the context before it
    // answers. Everything that follows in the endpoint runs against a context that is gone.
    //
    // Without the second check the upload would re-create the very assignment
    // `PodFacade.removeContext` had just pulled: a media attached to a context that no longer
    // exists, never unreferenced and therefore never collectable, and readable for as long as
    // somebody holds an unexpired token naming it. The window is not hypothetical — on this path it
    // is a remote fetch under a timeout measured in tens of seconds.
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/vanishing-${randomId()}")
    val podId = checkNotNull(pod.id)

    val response = withContextDeletedMidFetch(podId, context) {
      upload(
        pod,
        context,
        token,
        content = """{"source_url": "${sourceUrl("/while-fetching")}"}""",
        contentType = PodMediaSource.MEDIA_TYPE,
      )
    }

    // 409, not 404: the request was well-formed and authorized when it arrived and the state moved
    // underneath it, so a retry is the sensible next move.
    assertEquals(409, response.statusCode, "body=${response.responseBody}")
    assertTrue(response.responseBody.contains("was deleted while this request was in flight"))
    // The point of the whole check: the deleted context holds no media afterwards.
    assertTrue(
      podMediaDao.iterate(podId) { rows -> rows.flatMap { it.contexts }.toList() }.none { it == context.toString() },
      "no assignment may name a context that was deleted mid-request",
    )
  }

  @Test
  fun `an upload into an already deleted context never gets that far`() {
    // The cheap half of the same rule, caught by the first gate — kept so that moving the second
    // check cannot silently become the *only* one that fires.
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/gone-${randomId()}")
    podContextsDao.delete(podId = checkNotNull(pod.id), contextUri = context.toString())

    assertEquals(404, upload(pod, context, token).statusCode)
  }

  /** Runs [block] with the `/while-fetching` source deleting [context] before it answers. */
  private fun <T> withContextDeletedMidFetch(podId: ObjectId, context: URI, block: () -> T): T {
    whileFetching = { podContextsDao.delete(podId = podId, contextUri = context.toString()) }
    try {
      return block()
    } finally {
      whileFetching = null
    }
  }

  // ─── a row whose object is gone ────────────────────────────────────────────

  @Test
  fun `content for a media whose object is missing fails without naming the store's path`() {
    // Reachable two ways: a crash between the registry write and the store write, and the narrow
    // window `PodMediaFacade.sweepUnreferenced` documents. The status is honest — the server *is*
    // inconsistent — but the generic exception mapper puts the exception's message in the body, and
    // for the filesystem store that message is the absolute path of the media root.
    val pod = sempodsTestFactory.newPod()
    val (context, token) = contextWithToken(pod, "tests/broken-${randomId()}")
    val mediaId = mediaIdOf(upload(pod, context, token))
    mediaStore.delete(PodMediaRef(checkNotNull(pod.id).toPodId(), mediaId))

    val response = httpClient.prepareGet(contentUrl(pod.name, mediaId))
      .addHeader("Authorization", "Bearer $token").execute()

    assertEquals(500, response.statusCode)
    assertEquals("media content is unavailable", response.responseBody)
    assertFalse(response.responseBody.contains("/"), "the response must not carry a filesystem path")
  }

  private fun readMetadata(pod: String, mediaId: String, token: String): TestHttpResponse =
    httpClient.prepareGet(mediaUrl(pod, mediaId)).addHeader("Authorization", "Bearer $token").execute()

  companion object {

    /** Small enough to post as a literal, and identical whichever path carries it. */
    private const val SOURCE_BODY = "png-bytes-from-a-source"

    private lateinit var mediaSource: HttpServer

    /** What the `/while-fetching` source does before answering. See its handler. */
    @Volatile
    private var whileFetching: (() -> Unit)? = null

    @BeforeAll
    @JvmStatic
    fun startMediaSource() {
      mediaSource = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
      mediaSource.executor = Executors.newCachedThreadPool()

      mediaSource.createContext("/image.png") { exchange ->
        respond(exchange, SOURCE_BODY.toByteArray())
      }
      mediaSource.createContext("/oversized") { exchange ->
        respond(exchange, ByteArray((SempodsTestModule.TEST_MAX_UPLOAD_BYTES + 1).toInt()))
      }
      mediaSource.createContext("/broken-type") { exchange ->
        respond(exchange, SOURCE_BODY.toByteArray(), contentType = "not-a-media-type")
      }
      // The one place a test can get *inside* a media request. Everything the pod does between
      // authorizing a copy-from-URL upload and writing its assignment happens after this handler
      // runs, so a test that changes the pod's state here reproduces an interleaving that a
      // sequential caller otherwise cannot.
      mediaSource.createContext("/while-fetching") { exchange ->
        whileFetching?.invoke()
        respond(exchange, "png-bytes-${System.nanoTime()}".toByteArray())
      }

      mediaSource.start()
    }

    @AfterAll
    @JvmStatic
    fun stopMediaSource() {
      mediaSource.stop(0)
    }

    private fun respond(exchange: HttpExchange, body: ByteArray, contentType: String = "image/png") {
      try {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
      } finally {
        exchange.close()
      }
    }
  }
}
