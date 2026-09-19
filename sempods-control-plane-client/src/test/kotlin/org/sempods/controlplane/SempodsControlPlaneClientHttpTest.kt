package org.sempods.controlplane

import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.sempods.client.core.SempodsDecodingException
import org.sempods.client.core.SempodsOkHttp
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsRequestAuth
import org.sempods.client.core.SempodsSession
import org.sempods.client.core.SempodsStatusException
import org.slf4j.event.Level
import java.net.URI
import kotlin.test.assertNotNull

/**
 * Wire-format coverage for [SempodsControlPlaneClient]: URL shape, the admin credential, and the
 * status-code mapping each route's contract promises.
 *
 * Every assertion about what went out reads the request the server recorded, rather than inferring
 * it from an expectation that matched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsControlPlaneClientHttpTest {

  private lateinit var mockServer: ClientAndServer
  private lateinit var http: OkHttpClient
  private lateinit var client: SempodsControlPlaneClient

  @BeforeAll
  fun startServer() {
    mockServer = ClientAndServer.startClientAndServer(
      Configuration.configuration().logLevel(Level.WARN),
    )
    http = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    client = SempodsControlPlaneClient(
      // The server root, which is what this surface is under.
      SempodsSession(
        SempodsPodBase.of("http://localhost:${mockServer.port}"),
        SempodsRequestAuth.bearer(ADMIN_SECRET),
      ),
      http,
    )
  }

  @AfterAll
  fun stopServer() {
    mockServer.stop()
    http.dispatcher.executorService.shutdown()
    http.connectionPool.evictAll()
  }

  @BeforeEach
  fun resetServer() {
    mockServer.reset()
  }

  // ─── createPod ────────────────────────────────────────────────────────────────

  @Test
  fun `createPod PUTs the owner email with the admin credential and maps 201 to created`() {
    mockServer
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(201))

    val answer = client.createPod(podName = "alice", ownerEmail = "alice@example.com")

    assertEquals(201, answer.status)
    assertEquals(CreatePodResult.created, answer.body)

    val sent = sent("PUT")
    assertEquals("Bearer $ADMIN_SECRET", sent.getFirstHeader("Authorization"))
    assertEquals("application/json", sent.getFirstHeader("Content-Type"))
    assertEquals("application/json", sent.getFirstHeader("Accept"))
    assertEquals("""{"ownerEmail":"alice@example.com"}""", body(sent))
  }

  @Test
  fun `createPod maps 200 to alreadyExists rather than throwing`() {
    mockServer
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(200))

    val answer = client.createPod(podName = "alice", ownerEmail = "alice@example.com")

    assertEquals(200, answer.status)
    assertEquals(CreatePodResult.alreadyExists, answer.body)
  }

  @Test
  fun `createPod carries the server body and status on a refusal`() {
    mockServer
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(401).withBody("bad credential"))

    val refused = assertThrows<SempodsStatusException> {
      client.createPod(podName = "alice", ownerEmail = "alice@example.com")
    }

    assertEquals(401, refused.status)
    assertEquals("bad credential", refused.bodyExcerpt)
  }

  @Test
  fun `createPod encodes the pod name into one path segment`() {
    mockServer
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/a%20b"))
      .respond(response().withStatusCode(201))

    assertEquals(
      CreatePodResult.created,
      client.createPod(podName = "a b", ownerEmail = "alice@example.com").body,
    )
  }

  // ─── deletePod ────────────────────────────────────────────────────────────────

  @Test
  fun `deletePod accepts 204`() {
    mockServer
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(204))

    assertEquals(204, client.deletePod("alice").status)
    assertEquals("Bearer $ADMIN_SECRET", sent("DELETE").getFirstHeader("Authorization"))
  }

  @Test
  fun `deletePod throws on a status the route does not answer`() {
    mockServer
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(500).withBody("boom"))

    val refused = assertThrows<SempodsStatusException> { client.deletePod("alice") }

    assertEquals(500, refused.status)
    assertEquals("boom", refused.bodyExcerpt)
  }

  @Test
  fun `deletePod refuses a 200, which the route never answers`() {
    // Something other than the pod server answered — a proxy, or a drifted contract. Reading it as a
    // removal that happened would leave a caller believing a pod is gone that is still there.
    mockServer
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(200).withBody("ok"))

    assertEquals(200, assertThrows<SempodsStatusException> { client.deletePod("alice") }.status)
  }

  // ─── podExists ────────────────────────────────────────────────────────────────

  @Test
  fun `podExists answers 200 for a pod and 404 for one the server does not know`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody("{}"))
    mockServer
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/nobody"))
      .respond(response().withStatusCode(404))

    val known = client.podExists("alice")
    val unknown = client.podExists("nobody")

    assertEquals(200, known.status)
    assertEquals("{}", String(assertNotNull(known.body)))
    assertEquals(404, unknown.status)
    assertNull(unknown.body, "a listed status outside 2xx answers with no body")
  }

  @Test
  fun `podExists throws rather than answering an absence on a refusal`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(503).withBody("admin authority unconfigured"))

    val refused = assertThrows<SempodsStatusException> { client.podExists("alice") }

    assertEquals(503, refused.status)
  }

  // ─── provisionServiceClient ───────────────────────────────────────────────────

  @Test
  fun `provisionServiceClient posts the assertion and reads the minted registration`() {
    mockServer
      .`when`(request().withMethod("POST").withPath(SERVICE_CLIENT_PATH))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody(
            """
            {
              "result": "provisioned",
              "clientId": "notes-app",
              "registrationId": "r1",
              "scopes": ["https://pods.example/alice/_system/contexts/apps/notes#manage"],
              "contextRoot": "https://pods.example/alice/_system/contexts/apps/notes",
              "secret": "sc_secret"
            }
            """.trimIndent(),
          ),
      )

    val answer = client.provisionServiceClient(
      podName = "alice",
      clientId = "notes-app",
      expectedRegistrationId = null,
    )

    assertEquals(200, answer.status)
    val result = assertNotNull(answer.body)
    assertFalse(result.alreadyProvisioned)
    assertEquals("notes-app", result.clientId)
    assertEquals("r1", result.registrationId)
    assertEquals("sc_secret", result.secret)
    assertEquals(URI("https://pods.example/alice/_system/contexts/apps/notes"), result.contextRoot)
    assertEquals(setOf("https://pods.example/alice/_system/contexts/apps/notes#manage"), result.scopes)

    val sent = sent("POST")
    assertEquals("Bearer $ADMIN_SECRET", sent.getFirstHeader("Authorization"))
    assertEquals("application/json", sent.getFirstHeader("Content-Type"))
    assertEquals("""{"expectedRegistrationId":null}""", body(sent))
  }

  @Test
  fun `provisionServiceClient reports alreadyProvisioned without a secret`() {
    mockServer
      .`when`(request().withMethod("POST").withPath(SERVICE_CLIENT_PATH))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody(
            """
            {
              "result": "alreadyProvisioned",
              "clientId": "notes-app",
              "registrationId": "r1",
              "scopes": ["https://pods.example/alice/_system/contexts/apps/notes#manage"],
              "contextRoot": "https://pods.example/alice/_system/contexts/apps/notes"
            }
            """.trimIndent(),
          ),
      )

    val result = assertNotNull(
      client.provisionServiceClient(
        podName = "alice",
        clientId = "notes-app",
        expectedRegistrationId = "r1",
      ).body,
    )

    assertTrue(result.alreadyProvisioned)
    assertNull(result.secret, "the server hands a secret out exactly once")
    assertEquals("""{"expectedRegistrationId":"r1"}""", body(sent("POST")))
  }

  @Test
  fun `provisionServiceClient surfaces a 409 with its status for the caller's own retry`() {
    mockServer
      .`when`(request().withMethod("POST").withPath(SERVICE_CLIENT_PATH))
      .respond(response().withStatusCode(409).withBody("concurrent provisioning"))

    val refused = assertThrows<SempodsStatusException> {
      client.provisionServiceClient(podName = "alice", clientId = "notes-app", expectedRegistrationId = null)
    }

    assertEquals(409, refused.status)
    assertEquals("concurrent provisioning", refused.bodyExcerpt)
  }

  @Test
  fun `provisionServiceClient reports an answer missing a contract field as a decoding failure`() {
    // Which member is missing is [ControlPlaneJsonTest]'s question: `SempodsResponse.map` keeps a
    // decoder's own message out of what it reports, because such a message may quote the body.
    mockServer
      .`when`(request().withMethod("POST").withPath(SERVICE_CLIENT_PATH))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"result":"provisioned","contextRoot":"https://pods.example/a","scopes":["s"]}"""),
      )

    val refused = assertThrows<SempodsDecodingException> {
      client.provisionServiceClient(podName = "alice", clientId = "notes-app", expectedRegistrationId = null)
    }

    assertEquals(200, refused.status)
    assertTrue(refused.message!!.contains("_system/admin/pods/alice"), refused.message!!)
  }

  private fun sent(method: String): HttpRequest =
    mockServer.retrieveRecordedRequests(request().withMethod(method)).single()

  /** What the body says, rather than how MockServer renders a document it recognises as JSON. */
  private fun body(sent: HttpRequest): String =
    String(sent.bodyAsRawBytes).replace(Regex("\\s*\n\\s*"), "").replace(" : ", ":")

  private companion object {

    const val ADMIN_SECRET = "sc_test-admin-secret"

    const val SERVICE_CLIENT_PATH = "/_system/admin/pods/alice/service-clients/notes-app"
  }
}
