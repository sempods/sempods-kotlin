package org.sempods.api.system.admin.pods

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsFacade
import org.sempods.SempodsModule
import org.sempods.commons.logging.CapturedLog
import org.sempods.admin.AdminAuthorizerTestDouble
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pod lifecycle over the host-level admin surface (control-plane admin roadmap A1):
 * `PUT` / `GET` / `DELETE /_system/admin/pods/{pod}`.
 *
 * Also the wire-level contract of the [org.sempods.admin.AdminAuthorizer] seam (A0) — 401 without
 * disclosure, 503 when the deployment configured no admin authority. This is the only root resource
 * under `_system/admin` since the maintenance route was retired, so the contract is pinned here.
 */
class AdminPodsEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var adminAuthorizer: AdminAuthorizerTestDouble

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val objectMapper = ObjectMapper()

  private val adminBearer = "Bearer ${AdminAuthorizerTestDouble.TEST_ADMIN_SECRET}"

  private fun podUrl(pod: String) = "${SempodsModule.config.apiBaseUrl}_system/admin/pods/$pod"

  private fun newPodName() = "pod-${randomId()}"

  private fun put(pod: String, body: String, authorization: String? = adminBearer): TestHttpResponse =
    http.preparePut(podUrl(pod))
      .addHeader("Content-Type", "application/json")
      .apply { authorization?.let { addHeader("Authorization", it) } }
      .setBody(body)
      .execute()

  private fun get(pod: String, authorization: String? = adminBearer): TestHttpResponse =
    http.prepareGet(podUrl(pod))
      .apply { authorization?.let { addHeader("Authorization", it) } }
      .execute()

  private fun delete(pod: String, authorization: String? = adminBearer): TestHttpResponse =
    http.prepareDelete(podUrl(pod))
      .apply { authorization?.let { addHeader("Authorization", it) } }
      .execute()

  private fun TestHttpResponse.field(name: String): String =
    objectMapper.readTree(responseBody).path(name).asText()

  @Test
  fun `PUT creates the pod and stores the owner as the WebID derived from the email`() {
    val pod = newPodName()
    val ownerEmail = "owner-${randomId()}@test.com"

    val response = put(pod, """{"ownerEmail":"$ownerEmail"}""")

    assertEquals(201, response.statusCode, "body=${response.responseBody}")
    assertEquals("created", response.field("result"))
    assertEquals(pod, response.field("pod"))
    val stored = assertNotNull(podDao.fetchByName(pod), "pod row missing")
    assertEquals(webIdUriDeriver.deriveFromEmail(ownerEmail), stored.owner)
  }

  @Test
  fun `PUT is idempotent and does not transfer ownership`() {
    val pod = newPodName()
    val originalEmail = "owner-${randomId()}@test.com"
    assertEquals(201, put(pod, """{"ownerEmail":"$originalEmail"}""").statusCode)
    val originalOwner = assertNotNull(podDao.fetchByName(pod)).owner

    // A second PUT — with a *different* owner — must not silently re-home the pod.
    val response = put(pod, """{"ownerEmail":"someone-else-${randomId()}@test.com"}""")

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    assertEquals("alreadyExists", response.field("result"))
    assertEquals(originalOwner, assertNotNull(podDao.fetchByName(pod)).owner, "owner must be untouched")
  }

  @Test
  fun `PUT without a usable ownerEmail is a 400`() {
    val pod = newPodName()

    assertEquals(400, put(pod, """{"ownerEmail":"  "}""").statusCode)
    assertEquals(400, put(pod, "{}").statusCode)
    assertEquals(400, put(pod, "").statusCode)
    // unknown field — a typo'd payload must not be half-honored
    assertEquals(400, put(pod, """{"owner_email":"x@test.com"}""").statusCode)
    assertNull(podDao.fetchByName(pod), "no pod may be created by a rejected request")
  }

  @Test
  fun `a pod name the server cannot accept is a 400, not a 500`() {
    val validBody = """{"ownerEmail":"owner-${randomId()}@test.com"}"""

    // rejected by SempodsUriBuilder.checkPodName: too short, and illegal characters
    assertEquals(400, put("ab", validBody).statusCode)
    assertEquals(400, put("Pod_Upper", validBody).statusCode)
    assertNull(podDao.fetchByName("Pod_Upper"))
  }

  @Test
  fun `GET reports existence and 404 for an unknown pod`() {
    val pod = sempodsTestFactory.newPod()

    val found = get(pod.name)
    assertEquals(200, found.statusCode, "body=${found.responseBody}")
    assertTrue(objectMapper.readTree(found.responseBody).path("exists").asBoolean())

    assertEquals(404, get("pod-${randomId()}").statusCode)
  }

  @Test
  fun `DELETE removes the pod and is idempotent`() {
    val pod = sempodsTestFactory.newPod()

    assertEquals(204, delete(pod.name).statusCode)
    assertFalse(podAccess.clientFor(pod.name).exists(), "pod must be gone")
    assertEquals(404, get(pod.name).statusCode)

    // deleting again — and deleting a pod that never existed — stays a no-op
    assertEquals(204, delete(pod.name).statusCode)
    assertEquals(204, delete("pod-${randomId()}").statusCode)
  }

  @Test
  fun `a pod name on DELETE cannot forge a log line`() {
    // Unlike create, delete never resolves the name — deleting an unknown pod is a no-op that
    // still answers 204 — so nothing has vouched for it by the time two layers log it.
    // `docs/logging.md` §"Three rules".
    val forged = "no-such-pod-${randomId()}\u2028ERROR forged"

    val endpointLines = CapturedLog.linesFrom(AdminPodsEndpoint::class.java) {
      assertEquals(204, delete(URLEncoder.encode(forged, Charsets.UTF_8)).statusCode)
    }

    val line = endpointLines.single { forged.substringBefore('\u2028') in it }
    assertFalse('\u2028' in line, "the line carries a raw U+2028: $line")
    assertTrue("\\u2028" in line, line)
  }

  @Test
  fun `the facade names the same value, and escapes it too`() {
    // `SempodsFacade.deletePod` logs ahead of its own lookup, so it sees the path parameter exactly
    // as the route was called with it.
    val forged = "no-such-pod-${randomId()}\u2028ERROR forged"

    val facadeLines = CapturedLog.linesFrom(SempodsFacade::class.java) {
      assertEquals(204, delete(URLEncoder.encode(forged, Charsets.UTF_8)).statusCode)
    }

    val line = facadeLines.single { forged.substringBefore('\u2028') in it }
    assertFalse('\u2028' in line, "the line carries a raw U+2028: $line")
    assertTrue("\\u2028" in line, line)
  }

  @Test
  fun `every lifecycle route requires an admin credential`() {
    val pod = sempodsTestFactory.newPod()
    val body = """{"ownerEmail":"x-${randomId()}@test.com"}"""

    assertEquals(401, put(pod.name, body, authorization = null).statusCode)
    assertEquals(401, put(pod.name, body, authorization = "Bearer sc_wrong").statusCode)
    assertEquals(401, get(pod.name, authorization = null).statusCode)
    assertEquals(401, get(pod.name, authorization = "Bearer sc_wrong").statusCode)
    assertEquals(401, delete(pod.name, authorization = null).statusCode)
    val wrong = delete(pod.name, authorization = "Bearer sc_wrong")
    assertEquals(401, wrong.statusCode)
    assertFalse(
      wrong.responseBody.contains(AdminAuthorizerTestDouble.TEST_ADMIN_CLIENT_ID),
      "the error must not disclose which admin clients exist: ${wrong.responseBody}",
    )

    assertTrue(podAccess.clientFor(pod.name).exists(), "an unauthorized DELETE must not have run")
  }

  @Test
  fun `an unconfigured admin authority fails closed with 503, not with a pass-through`() {
    val pod = sempodsTestFactory.newPod()
    val body = """{"ownerEmail":"x-${randomId()}@test.com"}"""

    adminAuthorizer.withAuthorizer(AdminAuthorizerTestDouble.unconfigured) {
      // With and without a credential alike: while the authority is unset, nothing gets through —
      // and the distinct status says the *server* is misconfigured, not that the caller is wrong.
      assertEquals(503, put(pod.name, body, authorization = null).statusCode)
      assertEquals(503, put(pod.name, body).statusCode)
      assertEquals(503, get(pod.name).statusCode)
      assertEquals(503, delete(pod.name).statusCode)
    }

    assertTrue(podAccess.clientFor(pod.name).exists(), "no route may run while the admin authority is unset")
  }
}
