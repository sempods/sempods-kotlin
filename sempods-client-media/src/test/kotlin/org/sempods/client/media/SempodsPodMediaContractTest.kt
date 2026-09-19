package org.sempods.client.media

import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.core.SempodsClientException
import org.sempods.client.core.SempodsDecodingException
import org.sempods.client.core.SempodsOkHttp
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsRequestAuth
import org.sempods.client.core.SempodsSession
import org.sempods.client.core.SempodsStatusException
import org.sempods.media.PodMediaSource
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the media group asks the pod for, and what it makes of the answers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsPodMediaContractTest {

  private lateinit var server: ClientAndServer
  private lateinit var client: OkHttpClient
  private lateinit var media: SempodsPodMedia

  private val tasks = "https://pods.example/alice/_system/contexts/tasks"

  @BeforeAll
  fun start() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(org.slf4j.event.Level.WARN))
    client = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    val session = SempodsSession(
      SempodsPodBase.of("http://localhost:${server.port}/alice"),
      SempodsRequestAuth.bearer("service-token"),
    )
    media = SempodsPodMedia(SempodsPod(session, client))
  }

  @AfterAll
  fun stop() {
    server.stop()
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }

  @BeforeEach
  fun reset() {
    server.reset()
  }

  private fun stored(id: String = "abc123") {
    server.`when`(request().withMethod("POST").withPath("/alice/_system/media")).respond(
      response()
        .withStatusCode(201)
        .withBody("""{"id":"$id","content_url":"https://pods.example/alice/_system/media/$id/content"}"""),
    )
  }

  @Test
  fun `an upload posts the bytes to the media collection of its context`() {
    stored()

    val answer = media.upload(tasks, "image/png", { "PNGBYTES".byteInputStream() }, 8, "plan.png")

    assertEquals(201, answer.status)
    val uploaded = assertNotNull(answer.body)
    assertEquals("abc123", uploaded.mediaId)
    assertEquals(URI("https://pods.example/alice/_system/media/abc123/content"), uploaded.contentUrl)

    val sent = server.retrieveRecordedRequests(request().withMethod("POST")).single()
    assertEquals(tasks, sent.getFirstQueryStringParameter("context"))
    assertEquals("plan.png", sent.getFirstQueryStringParameter("filename"))
    assertEquals("image/png", sent.getFirstHeader("Content-Type"))
    assertEquals("Bearer service-token", sent.getFirstHeader("Authorization"))
    assertEquals("PNGBYTES", String(sent.bodyAsRawBytes))
  }

  @Test
  fun `a source upload sends the descriptor under its own media type, and no filename in the query`() {
    stored("fetched")

    val answer = media.uploadFromUrl(tasks, "https://drive.example/signed?token=secret", "plan.png")

    assertEquals("fetched", assertNotNull(answer.body).mediaId)
    val sent = server.retrieveRecordedRequests(request().withMethod("POST")).single()
    assertEquals(PodMediaSource.MEDIA_TYPE, sent.getFirstHeader("Content-Type"))
    assertNull(sent.getFirstQueryStringParameter("filename").takeIf { it.isNotEmpty() })
    // What the body says, rather than how MockServer renders a document it recognises as JSON.
    assertEquals(
      """{"source_url":"https://drive.example/signed?token=secret","filename":"plan.png"}""",
      String(sent.bodyAsRawBytes).replace(Regex("\\s*\n\\s*"), "").replace(" : ", ":"),
    )
  }

  @Test
  fun `an assignment puts the media's own URL with the context it is being reached through`() {
    server.`when`(request().withMethod("PUT")).respond(response().withStatusCode(204))

    assertEquals(204, media.assign("abc123", tasks).status)

    val sent = server.retrieveRecordedRequests(request().withMethod("PUT")).single()
    assertEquals("/alice/_system/media/abc123", sent.path.value)
    assertEquals(tasks, sent.getFirstQueryStringParameter("context"))
  }

  @Test
  fun `an unassignment deletes the same URL`() {
    server.`when`(request().withMethod("DELETE")).respond(response().withStatusCode(204))

    assertEquals(204, media.unassign("abc123", tasks).status)

    val sent = server.retrieveRecordedRequests(request().withMethod("DELETE")).single()
    assertEquals("/alice/_system/media/abc123", sent.path.value)
  }

  /**
   * The id arrives from a caller, and one carrying separators is refused before anything is sent:
   * `%2F` is a separator to a server that decodes before it routes, so the session does not treat the
   * encoded form as a segment that stays under the pod.
   */
  @Test
  fun `a media id that would leave its route is refused rather than sent`() {
    server.`when`(request().withMethod("DELETE")).respond(response().withStatusCode(204))

    assertThrows<SempodsClientException> { media.unassign("../../../etc/passwd", tasks) }

    assertEquals(0, server.retrieveRecordedRequests(request()).size, "nothing left this client")
  }

  @Test
  fun `a 404 on an assignment is a refusal rather than nothing to do`() {
    server.`when`(request().withMethod("PUT")).respond(response().withStatusCode(404).withBody("no such media"))

    val refused = assertThrows<SempodsStatusException> { media.assign("abc123", tasks) }

    assertEquals(404, refused.status)
    assertEquals("no such media", refused.bodyExcerpt)
  }

  @Test
  fun `an upload answered 200 is still an answer, although SPS-MEDIA-011 asks for 201`() {
    // That pod has broken the requirement and stored the media all the same; `docs/pod-client.md`
    // §"Endpoint groups" says why such a status is listed.
    server.`when`(request().withMethod("POST")).respond(
      response().withStatusCode(200).withBody("""{"id":"abc123","content_url":"https://pods.example/a"}"""),
    )

    val answer = media.upload(tasks, "image/png", { "x".byteInputStream() })

    assertEquals(200, answer.status, "the status is on the answer, for a caller that wants to notice")
    assertEquals("abc123", assertNotNull(answer.body).mediaId)
  }

  @Test
  fun `an upload answered without the members the route promises is a decoding failure`() {
    server.`when`(request().withMethod("POST")).respond(response().withStatusCode(201).withBody("""{"id":"abc123"}"""))

    val refused = assertThrows<SempodsDecodingException> {
      media.upload(tasks, "image/png", { "x".byteInputStream() })
    }

    assertEquals(201, refused.status)
    assertTrue(refused.message!!.contains("_system/media"), refused.message!!)
  }

  @Test
  fun `the source is opened once per attempt, so a refused credential can be answered`() {
    val opened = AtomicInteger()
    val rotating = ArrayDeque(listOf("stale", "fresh"))
    val session = SempodsSession(
      SempodsPodBase.of("http://localhost:${server.port}/alice"),
      SempodsRequestAuth.refreshable({ _, _ -> rotating.removeFirst() }),
    )
    server.`when`(request().withHeader("Authorization", "Bearer stale")).respond(response().withStatusCode(401))
    server.`when`(request().withHeader("Authorization", "Bearer fresh")).respond(
      response().withStatusCode(201).withBody("""{"id":"a","content_url":"https://pods.example/a"}"""),
    )

    val answer = SempodsPodMedia(SempodsPod(session, client))
      .upload(tasks, "image/png", { opened.incrementAndGet(); "x".byteInputStream() }, 1)

    assertEquals(201, answer.status)
    assertEquals(2, opened.get(), "the refused attempt and the one that answered it")
  }
}
