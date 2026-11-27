package org.sempods.controlplane

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.sempods.client.SempodsDecodingException
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.SempodsSession
import org.sempods.client.SempodsStatusException
import java.net.URI
import kotlin.test.assertNotNull
import org.sempods.testsupport.MockPodTest

/**
 * Wire-format coverage for [SempodsControlPlaneClient]: URL shape, the admin credential, and the
 * status-code mapping each route's contract promises.
 *
 * Every assertion about what went out reads the request the server recorded, rather than inferring
 * it from an expectation that matched.
 */
class SempodsControlPlaneClientHttpTest : MockPodTest() {

  private val controlPlane by lazy {
    SempodsControlPlaneClient(
      // The server root, which is what this surface is under.
      SempodsSession(SempodsPodBase.of(origin), SempodsRequestAuth.bearer(ADMIN_SECRET)),
      client,
    )
  }

  // ─── createPod ────────────────────────────────────────────────────────────────

  @Test
  fun `createPod PUTs the owner email with the admin credential and maps 201 to created`() {
    server
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(201))

    val answer = controlPlane.createPod(podName = "alice", ownerEmail = "alice@example.com")

    assertEquals(201, answer.status)
    assertEquals(CreatePodResult.created, answer.body)

    val sent = sent("PUT")
    assertEquals("Bearer $ADMIN_SECRET", sent.getFirstHeader("Authorization"))
    assertEquals("application/json", sent.getFirstHeader("Content-Type"))
    assertEquals("application/json", sent.getFirstHeader("Accept"))
    assertEquals("""{"ownerEmail":"alice@example.com"}""", body(sent))
  }

  @Test
  fun `createPod for a WebID PUTs the owner WebID`() {
    server
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(201))

    val answer = controlPlane.createPod(podName = "alice", ownerWebId = URI(OWNER_WEB_ID))

    assertEquals(CreatePodResult.created, answer.body)
    assertEquals("""{"ownerWebId":"$OWNER_WEB_ID"}""", body(sent("PUT")))
  }

  @Test
  fun `createPod maps 200 to alreadyExists rather than throwing`() {
    server
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(200))

    val answer = controlPlane.createPod(podName = "alice", ownerEmail = "alice@example.com")

    assertEquals(200, answer.status)
    assertEquals(CreatePodResult.alreadyExists, answer.body)
  }

  @Test
  fun `createPod carries the server body and status on a refusal`() {
    server
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(401).withBody("bad credential"))

    val refused = assertThrows<SempodsStatusException> {
      controlPlane.createPod(podName = "alice", ownerEmail = "alice@example.com")
    }

    assertEquals(401, refused.status)
    assertEquals("bad credential", refused.bodyExcerpt)
  }

  @Test
  fun `createPod encodes the pod name into one path segment`() {
    server
      .`when`(request().withMethod("PUT").withPath("/_system/admin/pods/a%20b"))
      .respond(response().withStatusCode(201))

    assertEquals(
      CreatePodResult.created,
      controlPlane.createPod(podName = "a b", ownerEmail = "alice@example.com").body,
    )
  }

  /**
   * OkHttp collapses `..` and drops `.` and ``, so each of them appended to the route would reach
   * another one: `..` as a pod name addressed `/_system/admin`, and the confinement lets that through
   * because it is still on this server.
   */
  @Test
  fun `a pod name or client id that is not one path segment is refused and nothing is sent`() {
    server.`when`(request()).respond(response().withStatusCode(204))

    listOf("..", ".", "", "a/b").forEach { bad ->
      assertThrows<IllegalArgumentException>("pod '$bad'") { controlPlane.createPod(bad, "alice@example.com") }
      assertThrows<IllegalArgumentException>("pod '$bad'") { controlPlane.deletePod(bad) }
      assertThrows<IllegalArgumentException>("pod '$bad'") { controlPlane.deletePod(bad, "https://id.example/e/1") }
      assertThrows<IllegalArgumentException>("pod '$bad'") { controlPlane.podExists(bad) }
      assertThrows<IllegalArgumentException>("pod '$bad'") { controlPlane.pod(bad) }
      assertThrows<IllegalArgumentException>("pod '$bad'") { controlPlane.provisionServiceClient(bad, "app", null, null) }
      assertThrows<IllegalArgumentException>("client '$bad'") { controlPlane.provisionServiceClient("alice", bad, null, null) }
    }

    assertEquals(0, server.retrieveRecordedRequests(request()).size, "nothing left this client")
  }

  // ─── deletePod ────────────────────────────────────────────────────────────────

  @Test
  fun `deletePod accepts 204`() {
    server
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(204))

    assertEquals(204, controlPlane.deletePod("alice").status)
    assertEquals("Bearer $ADMIN_SECRET", sent("DELETE").getFirstHeader("Authorization"))
  }

  @Test
  fun `deletePod throws on a status the route does not answer`() {
    server
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(500).withBody("boom"))

    val refused = assertThrows<SempodsStatusException> { controlPlane.deletePod("alice") }

    assertEquals(500, refused.status)
    assertEquals("boom", refused.bodyExcerpt)
  }

  @Test
  fun `deletePod refuses a 200, which the route never answers`() {
    // Something other than the pod server answered — a proxy, or a drifted contract. Reading it as a
    // removal that happened would leave a caller believing a pod is gone that is still there.
    server
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(200).withBody("ok"))

    assertEquals(200, assertThrows<SempodsStatusException> { controlPlane.deletePod("alice") }.status)
  }

  @Test
  fun `deletePod with an expected owner sends it as the owner parameter`() {
    server
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(204))
    val owner = "https://id.example/e/a b?c&d"

    assertEquals(204, controlPlane.deletePod("alice", owner).status)

    val sent = sent("DELETE")
    assertEquals("/_system/admin/pods/alice", sent.path.value)
    assertEquals("Bearer $ADMIN_SECRET", sent.getFirstHeader("Authorization"))
    assertEquals(
      listOf(owner),
      sent.queryStringParameterList.filter { it.name.value == "owner" }.flatMap { it.values.map { value -> value.value } },
      "decoded back to what was passed",
    )
  }

  @Test
  fun `deletePod with an expected owner answers 412 and 404 as answers`() {
    listOf(412, 404).forEach { status ->
      server.reset()
      server
        .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
        .respond(response().withStatusCode(status).withBody("""{"error":"refused"}"""))

      val answer = controlPlane.deletePod("alice", "https://id.example/e/1")

      assertEquals(status, answer.status)
      assertNull(answer.body, "a listed status outside 2xx answers with no body")
    }
  }

  @Test
  fun `deletePod with an expected owner throws on a 409, which the route never answers`() {
    server
      .`when`(request().withMethod("DELETE").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(409))

    assertEquals(409, assertThrows<SempodsStatusException> { controlPlane.deletePod("alice", "https://id.example/e/1") }.status)
  }

  @Test
  fun `deletePod refuses a blank expected owner and sends nothing`() {
    server.`when`(request()).respond(response().withStatusCode(204))

    listOf("", " ").forEach { blank ->
      assertThrows<IllegalArgumentException>("owner '$blank'") { controlPlane.deletePod("alice", blank) }
    }

    assertEquals(0, server.retrieveRecordedRequests(request()).size, "nothing left this client")
  }

  // ─── podExists ────────────────────────────────────────────────────────────────

  @Test
  fun `podExists answers 200 for a pod and 404 for one the server does not know`() {
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody("{}"))
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/nobody"))
      .respond(response().withStatusCode(404))

    val known = controlPlane.podExists("alice")
    val unknown = controlPlane.podExists("nobody")

    assertEquals(200, known.status)
    assertEquals("{}", String(assertNotNull(known.body)))
    assertEquals(404, unknown.status)
    assertNull(unknown.body, "a listed status outside 2xx answers with no body")
  }

  @Test
  fun `podExists throws rather than answering an absence on a refusal`() {
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(503).withBody("admin authority unconfigured"))

    val refused = assertThrows<SempodsStatusException> { controlPlane.podExists("alice") }

    assertEquals(503, refused.status)
  }

  // ─── pod ──────────────────────────────────────────────────────────────────────

  @Test
  fun `pod reads the owner of a known pod and answers 404 with no body`() {
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"pod":"alice","exists":true,"owner":"$OWNER_WEB_ID"}"""),
      )
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/nobody"))
      .respond(response().withStatusCode(404))

    val known = controlPlane.pod("alice")
    assertEquals(OwnedPod(pod = "alice", displayName = null, owner = OWNER_WEB_ID), known.body)
    assertEquals("Bearer $ADMIN_SECRET", sent("GET").getFirstHeader("Authorization"))

    val unknown = controlPlane.pod("nobody")
    assertEquals(404, unknown.status)
    assertNull(unknown.body, "a listed status outside 2xx answers with no body")
  }

  @Test
  fun `pod refuses an answer without its owner`() {
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"pod":"alice","exists":true}"""),
      )

    assertEquals(200, assertThrows<SempodsDecodingException> { controlPlane.pod("alice") }.status)
  }

  @Test
  fun `pod throws on a refusal`() {
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods/alice"))
      .respond(response().withStatusCode(401).withBody("bad credential"))

    assertEquals(401, assertThrows<SempodsStatusException> { controlPlane.pod("alice") }.status)
  }

  // ─── podsOwnedBy ──────────────────────────────────────────────────────────────

  @Test
  fun `podsOwnedBy sends one owner parameter per WebID and reads the pods`() {
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods"))
      .respond(
        response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(
          """{"pods":[{"pod":"alice","displayName":"Alice","owner":"https://id.example/e/1"}]}""",
        ),
      )
    val owners = listOf("https://id.example/e/1", "urn:sempods:oidc:2", "https://id.example/e/a b?c&d")

    val answer = controlPlane.podsOwnedBy(owners)

    assertEquals(200, answer.status)
    assertEquals(listOf(OwnedPod(pod = "alice", displayName = "Alice", owner = "https://id.example/e/1")), answer.body)

    val sent = sent("GET")
    assertEquals("/_system/admin/pods", sent.path.value)
    assertEquals("Bearer $ADMIN_SECRET", sent.getFirstHeader("Authorization"))
    assertEquals("application/json", sent.getFirstHeader("Accept"))
    assertEquals(
      owners,
      sent.queryStringParameterList.filter { it.name.value == "owner" }.flatMap { it.values.map { value -> value.value } },
      "every value arrives as its own parameter, decoded back to what was passed",
    )
  }

  @Test
  fun `podsOwnedBy refuses an empty set and sends nothing`() {
    server.`when`(request()).respond(response().withStatusCode(200))

    assertThrows<IllegalArgumentException> { controlPlane.podsOwnedBy(emptyList()) }

    assertEquals(0, server.retrieveRecordedRequests(request()).size, "nothing left this client")
  }

  @Test
  fun `podsOwnedBy throws a 400 with its status`() {
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods"))
      .respond(response().withStatusCode(400).withBody("""{"error":"at most 20 owner values are accepted"}"""))

    val refused = assertThrows<SempodsStatusException> { controlPlane.podsOwnedBy(listOf("https://id.example/e/1")) }

    assertEquals(400, refused.status)
  }

  @Test
  fun `podsOwnedBy reports an answer without its pods as a decoding failure`() {
    server
      .`when`(request().withMethod("GET").withPath("/_system/admin/pods"))
      .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody("{}"))

    assertThrows<SempodsDecodingException> { controlPlane.podsOwnedBy(listOf("https://id.example/e/1")) }
  }

  // ─── provisionServiceClient ───────────────────────────────────────────────────

  @Test
  fun `provisionServiceClient posts the assertion and reads the minted registration`() {
    server
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
              "secretId": "s2",
              "scopes": ["https://pods.example/alice/_system/contexts/apps/notes#manage"],
              "contextRoot": "https://pods.example/alice/_system/contexts/apps/notes",
              "secret": "sc_secret"
            }
            """.trimIndent(),
          ),
      )

    val answer = controlPlane.provisionServiceClient(
      podName = "alice",
      clientId = "notes-app",
      expectedRegistrationId = null,
      expectedSecretId = null,
    )

    assertEquals(200, answer.status)
    val result = assertNotNull(answer.body)
    assertFalse(result.alreadyProvisioned)
    assertEquals("notes-app", result.clientId)
    assertEquals("r1", result.registrationId)
    assertEquals("s2", result.secretId)
    assertEquals("sc_secret", result.secret)
    assertEquals(URI("https://pods.example/alice/_system/contexts/apps/notes"), result.contextRoot)
    assertEquals(setOf("https://pods.example/alice/_system/contexts/apps/notes#manage"), result.scopes)

    val sent = sent("POST")
    assertEquals("Bearer $ADMIN_SECRET", sent.getFirstHeader("Authorization"))
    assertEquals("application/json", sent.getFirstHeader("Content-Type"))
    assertEquals("""{"expectedRegistrationId":null,"expectedSecretId":null}""", body(sent))
  }

  @Test
  fun `provisionServiceClient reports alreadyProvisioned without a secret`() {
    server
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
              "secretId": "s1",
              "scopes": ["https://pods.example/alice/_system/contexts/apps/notes#manage"],
              "contextRoot": "https://pods.example/alice/_system/contexts/apps/notes"
            }
            """.trimIndent(),
          ),
      )

    val result = assertNotNull(
      controlPlane.provisionServiceClient(
        podName = "alice",
        clientId = "notes-app",
        expectedRegistrationId = "r1",
        expectedSecretId = "s1",
      ).body,
    )

    assertTrue(result.alreadyProvisioned)
    assertNull(result.secret, "the server hands a secret out exactly once")
    assertEquals("""{"expectedRegistrationId":"r1","expectedSecretId":"s1"}""", body(sent("POST")))
  }

  @Test
  fun `provisionServiceClient surfaces a 409 with its status for the caller's own retry`() {
    server
      .`when`(request().withMethod("POST").withPath(SERVICE_CLIENT_PATH))
      .respond(response().withStatusCode(409).withBody("concurrent provisioning"))

    val refused = assertThrows<SempodsStatusException> {
      controlPlane.provisionServiceClient(podName = "alice", clientId = "notes-app", expectedRegistrationId = null, expectedSecretId = null)
    }

    assertEquals(409, refused.status)
    assertEquals("concurrent provisioning", refused.bodyExcerpt)
  }

  @Test
  fun `provisionServiceClient reports an answer missing a contract field as a decoding failure`() {
    // Which member is missing is [ControlPlaneJsonTest]'s question: `SempodsResponse.map` keeps a
    // decoder's own message out of what it reports, because such a message may quote the body.
    server
      .`when`(request().withMethod("POST").withPath(SERVICE_CLIENT_PATH))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"result":"provisioned","contextRoot":"https://pods.example/a","scopes":["s"]}"""),
      )

    val refused = assertThrows<SempodsDecodingException> {
      controlPlane.provisionServiceClient(podName = "alice", clientId = "notes-app", expectedRegistrationId = null, expectedSecretId = null)
    }

    assertEquals(200, refused.status)
    assertTrue(refused.message!!.contains("_system/admin/pods/alice"), refused.message!!)
  }

  @Test
  fun `provisionServiceClient reports a result it does not know as a decoding failure`() {
    server
      .`when`(request().withMethod("POST").withPath(SERVICE_CLIENT_PATH))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody(
            """{"result":"reprovisioned","registrationId":"r1","secretId":"s2","scopes":["s"],"contextRoot":"https://pods.example/a","secret":"sc_secret"}""",
          ),
      )

    val refused = assertThrows<SempodsDecodingException> {
      controlPlane.provisionServiceClient(podName = "alice", clientId = "notes-app", expectedRegistrationId = "r1", expectedSecretId = "s1")
    }

    assertEquals(200, refused.status)
    assertFalse(refused.message!!.contains("sc_secret"), "the report quotes no body: ${refused.message}")
  }

  private fun sent(method: String): HttpRequest =
    server.retrieveRecordedRequests(request().withMethod(method)).single()

  /** What the body says, rather than how MockServer renders a document it recognises as JSON. */
  private fun body(sent: HttpRequest): String =
    String(sent.bodyAsRawBytes).replace(Regex("\\s*\n\\s*"), "").replace(" : ", ":")

  private companion object {

    const val ADMIN_SECRET = "sc_test-admin-secret"

    const val SERVICE_CLIENT_PATH = "/_system/admin/pods/alice/service-clients/notes-app"

    const val OWNER_WEB_ID = "https://id.example/e/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
