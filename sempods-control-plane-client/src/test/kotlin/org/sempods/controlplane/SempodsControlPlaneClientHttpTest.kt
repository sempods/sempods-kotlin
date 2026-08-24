package org.sempods.controlplane

import org.sempods.client.SempodsClientException
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
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.slf4j.event.Level
import java.net.URI

/**
 * Wire-format coverage for [SempodsControlPlaneClient]: URL shape, the admin bearer, and the
 * status-code mapping each route's contract promises.
 *
 * These routes had no client-side test while they lived in `SempodsClient` — the only coverage was
 * a consumer's integration suite and `AdminPodsEndpointHttpTest` on the server side, neither of
 * which pins what this client sends.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsControlPlaneClientHttpTest {

  private lateinit var mockServer: ClientAndServer
  private lateinit var client: SempodsControlPlaneClient

  @BeforeAll
  fun startServer() {
    mockServer = ClientAndServer.startClientAndServer(
      Configuration.configuration().logLevel(Level.WARN),
    )
    client = SempodsControlPlaneClient(
      // No trailing slash on purpose: the client has to add one, or `URI.resolve` would replace
      // the last path segment instead of appending to it.
      serverBaseUrl = URI("http://localhost:${mockServer.port}"),
      adminSecret = ADMIN_SECRET,
    )
  }

  @AfterAll
  fun stopServer() {
    mockServer.stop()
  }

  @BeforeEach
  fun resetServer() {
    mockServer.reset()
  }

  // ─── createPod ────────────────────────────────────────────────────────────────

  @Test
  fun `createPod PUTs the owner email with the admin bearer and maps 201 to created`() {
    mockServer
      .`when`(
        request()
          .withMethod("PUT")
          .withPath("/_system/admin/pods/alice")
          .withHeader("Authorization", "Bearer $ADMIN_SECRET")
          .withBody("{\"ownerEmail\":\"alice@example.com\"}"),
      )
      .respond(response().withStatusCode(201))

    assertEquals(
      CreatePodResult.created,
      client.createPod(podName = "alice", ownerEmail = "alice@example.com"),
    )
  }

  @Test
  fun `createPod maps 200 to alreadyExists rather than throwing`() {
    mockServer
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(200))

    assertEquals(
      CreatePodResult.alreadyExists,
      client.createPod(podName = "alice", ownerEmail = "alice@example.com"),
    )
  }

  @Test
  fun `createPod carries the server body and status on a refusal`() {
    mockServer
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(401).withBody("bad credential"))

    val ex = assertThrows<SempodsClientException> {
      client.createPod(podName = "alice", ownerEmail = "alice@example.com")
    }
    assertEquals(401, ex.statusCode)
    assertTrue(ex.message!!.contains("bad credential"), ex.message!!)
  }

  @Test
  fun `createPod encodes the pod name into one path segment`() {
    mockServer
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/a%20b"))
      .respond(response().withStatusCode(201))

    assertEquals(
      CreatePodResult.created,
      client.createPod(podName = "a b", ownerEmail = "alice@example.com"),
    )
  }

  // ─── deletePod ────────────────────────────────────────────────────────────────

  @Test
  fun `deletePod accepts 204`() {
    mockServer
      .`when`(
        request()
          .withMethod("DELETE")
          .withPath("/_system/admin/pods/alice")
          .withHeader("Authorization", "Bearer $ADMIN_SECRET"),
      )
      .respond(response().withStatusCode(204))

    client.deletePod("alice")
  }

  @Test
  fun `deletePod throws on a non-2xx`() {
    mockServer
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(500).withBody("boom"))

    val ex = assertThrows<SempodsClientException> { client.deletePod("alice") }
    assertEquals(500, ex.statusCode)
  }

  // ─── podExists ────────────────────────────────────────────────────────────────

  @Test
  fun `podExists is true on 200 and false on 404`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody("{}"))
    mockServer
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/nobody"))
      .respond(response().withStatusCode(404))

    assertTrue(client.podExists("alice"))
    assertFalse(client.podExists("nobody"))
  }

  @Test
  fun `podExists throws rather than answering false on a refusal`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(503).withBody("admin authority unconfigured"))

    val ex = assertThrows<SempodsClientException> { client.podExists("alice") }
    assertEquals(503, ex.statusCode)
  }

  // ─── provisionServiceClient ───────────────────────────────────────────────────

  @Test
  fun `provisionServiceClient posts the assertion and reads the minted registration`() {
    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/_system/admin/pods/alice/service-clients/notes-app")
          .withHeader("Authorization", "Bearer $ADMIN_SECRET")
          .withBody("{\"expectedRegistrationId\":null}"),
      )
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

    val result = client.provisionServiceClient(
      podName = "alice",
      clientId = "notes-app",
      expectedRegistrationId = null,
    )

    assertFalse(result.alreadyProvisioned)
    assertEquals("notes-app", result.clientId)
    assertEquals("r1", result.registrationId)
    assertEquals("sc_secret", result.secret)
    assertEquals(
      URI("https://pods.example/alice/_system/contexts/apps/notes"),
      result.contextRoot,
    )
    assertEquals(
      setOf("https://pods.example/alice/_system/contexts/apps/notes#manage"),
      result.scopes,
    )
  }

  @Test
  fun `provisionServiceClient reports alreadyProvisioned without a secret`() {
    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/_system/admin/pods/alice/service-clients/notes-app")
          .withBody("{\"expectedRegistrationId\":\"r1\"}"),
      )
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

    val result = client.provisionServiceClient(
      podName = "alice",
      clientId = "notes-app",
      expectedRegistrationId = "r1",
    )

    assertTrue(result.alreadyProvisioned)
    assertNull(result.secret, "the server hands a secret out exactly once")
  }

  @Test
  fun `provisionServiceClient surfaces a 409 with its status for the caller's own retry`() {
    mockServer
      .`when`(request().withMethod("POST").withPath("/_system/admin/pods/alice/service-clients/notes-app"))
      .respond(response().withStatusCode(409).withBody("concurrent provisioning"))

    val ex = assertThrows<SempodsClientException> {
      client.provisionServiceClient(podName = "alice", clientId = "notes-app", expectedRegistrationId = null)
    }
    assertEquals(409, ex.statusCode)
  }

  @Test
  fun `provisionServiceClient fails loudly on each missing contract field`() {
    val complete = mapOf(
      "result" to "\"provisioned\"",
      "registrationId" to "\"r1\"",
      "contextRoot" to "\"https://pods.example/alice/_system/contexts/apps/notes\"",
      "scopes" to "[\"s\"]",
    )

    complete.keys.forEach { missing ->
      mockServer.reset()
      val body = complete.filterKeys { it != missing }
        .entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
      mockServer
        .`when`(request().withMethod("POST").withPath("/_system/admin/pods/alice/service-clients/notes-app"))
        .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(body))

      val ex = assertThrows<SempodsClientException>("missing '$missing' must not pass silently") {
        client.provisionServiceClient(podName = "alice", clientId = "notes-app", expectedRegistrationId = null)
      }
      assertTrue(ex.message!!.contains(missing), "for missing '$missing': ${ex.message}")
    }
  }

  companion object {
    private const val ADMIN_SECRET = "sc_test-admin-secret"
  }
}
