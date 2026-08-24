package org.sempods.api.system.admin.pods

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.admin.AdminAuthorizerTestDouble
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.serviceclients.PodServiceClientFacade
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Service-client provisioning over the admin surface (control-plane admin roadmap A1):
 * `POST /_system/admin/pods/{pod}/service-clients/{clientId}`.
 *
 * Covers the half of provisioning that lives server-side — root-context creation, public-root
 * demotion, idempotency, and the self-healing re-mint. The credential-row half (decrypt check,
 * clientId drift, per-user bookkeeping) belongs to whichever application calls this route and is
 * covered on its side.
 */
class AdminServiceClientProvisionHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podServiceClientFacade: PodServiceClientFacade

  private val objectMapper = ObjectMapper()

  private val adminBearer = "Bearer ${AdminAuthorizerTestDouble.TEST_ADMIN_SECRET}"

  private fun provisionUrl(pod: String, clientId: String) =
    "${SempodsModule.config.apiBaseUrl}_system/admin/pods/$pod/service-clients/$clientId"

  private fun provision(
    pod: String,
    clientId: String = CLIENT_ID,
    expectedRegistrationId: String? = null,
    authorization: String? = adminBearer,
  ): TestHttpResponse {
    val body = expectedRegistrationId
      ?.let { """{"expectedRegistrationId":"$it"}""" }
      ?: "{}"
    return http.preparePost(provisionUrl(pod, clientId))
      .addHeader("Content-Type", "application/json")
      .apply { authorization?.let { addHeader("Authorization", it) } }
      .setBody(body)
      .execute()
  }

  private fun TestHttpResponse.field(name: String): String? =
    objectMapper.readTree(responseBody).path(name).takeIf { !it.isMissingNode }?.asText()

  private fun TestHttpResponse.hasField(name: String): Boolean =
    objectMapper.readTree(responseBody).has(name)

  private fun rootContext(pod: PodDbo): URI = sempodsUriBuilder.buildContext(pod.name, "apps/$CLIENT_ID")

  /** RFC 6749 §2.3.1 `client_secret_basic` (form-urlencoded before base64). */
  private fun basicHeader(clientId: String, secret: String): String {
    val encId = java.net.URLEncoder.encode(clientId, Charsets.UTF_8)
    val encSecret = java.net.URLEncoder.encode(secret, Charsets.UTF_8)
    return "Basic " + Base64.getEncoder().encodeToString("$encId:$encSecret".toByteArray(Charsets.UTF_8))
  }

  @Test
  fun `a fresh pod is provisioned with a private root context, a sandboxed registration and the secret`() {
    val pod = sempodsTestFactory.newPod()

    val response = provision(pod.name)

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    assertEquals("provisioned", response.field("result"))
    assertEquals(CLIENT_ID, response.field("clientId"))

    val root = rootContext(pod)
    assertTrue(podAccess.clientFor(pod.name).contexts().contains(root), "root context must be registered")
    assertFalse(podAccess.clientFor(pod.name).publicContexts().contains(root), "root context must be private")

    val registration = assertNotNull(podServiceClientFacade.find(pod.name, CLIENT_ID), "registration missing")
    assertEquals(setOf("$root#manage"), registration.scopes)
    assertEquals(registration.id.toString(), response.field("registrationId"))

    val secret = assertNotNull(response.field("secret"), "the minted secret must be returned once")
    assertNotNull(
      podServiceClientFacade.authenticate(pod.name, CLIENT_ID, secret),
      "the returned secret must authenticate against the pod-side hash",
    )
  }

  @Test
  fun `the returned secret obtains a service token from the pod token endpoint`() {
    val pod = sempodsTestFactory.newPod()
    val secret = assertNotNull(provision(pod.name).field("secret"))

    val response = http.preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/token")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(CLIENT_ID, secret))
      .setBody("grant_type=client_credentials")
      .execute()

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    assertNotNull(response.field("access_token"), "access_token missing in ${response.responseBody}")
  }

  @Test
  fun `a matching expectedRegistrationId is a no-op and returns no secret`() {
    val pod = sempodsTestFactory.newPod()
    val first = provision(pod.name)
    val registrationId = assertNotNull(first.field("registrationId"))
    val firstSecret = assertNotNull(first.field("secret"))

    val second = provision(pod.name, expectedRegistrationId = registrationId)

    assertEquals(200, second.statusCode, "body=${second.responseBody}")
    assertEquals("alreadyProvisioned", second.field("result"))
    assertEquals(registrationId, second.field("registrationId"))
    assertFalse(second.hasField("secret"), "no secret may be produced when nothing was written: ${second.responseBody}")
    assertNotNull(
      podServiceClientFacade.authenticate(pod.name, CLIENT_ID, firstSecret),
      "the caller's existing secret must stay valid",
    )
  }

  @Test
  fun `a half-provisioned caller without an expectedRegistrationId is re-minted`() {
    // Registration exists on the pod, the caller lost its credential row — it can assert nothing,
    // so the only way back to a working state is a fresh secret.
    val pod = sempodsTestFactory.newPod()
    val first = provision(pod.name)
    val lostSecret = assertNotNull(first.field("secret"))

    val second = provision(pod.name)

    assertEquals("provisioned", second.field("result"))
    assertNotEquals(first.field("registrationId"), second.field("registrationId"), "re-minting replaces the row")
    val newSecret = assertNotNull(second.field("secret"))
    assertNotNull(podServiceClientFacade.authenticate(pod.name, CLIENT_ID, newSecret))
    assertNull(
      podServiceClientFacade.authenticate(pod.name, CLIENT_ID, lostSecret),
      "the replaced secret must no longer authenticate",
    )
  }

  @Test
  fun `a stale expectedRegistrationId is re-minted`() {
    // Manual re-registration on the pod, or a restored dump on one side: the caller's assertion
    // refers to a row that is no longer current.
    val pod = sempodsTestFactory.newPod()
    val stale = assertNotNull(provision(pod.name).field("registrationId"))
    podServiceClientFacade.unregister(pod.name, CLIENT_ID)
    podServiceClientFacade.register(
      podName = pod.name,
      clientId = CLIENT_ID,
      scopes = setOf("${rootContext(pod)}#manage"),
      label = CLIENT_ID,
    )

    val response = provision(pod.name, expectedRegistrationId = stale)

    assertEquals("provisioned", response.field("result"))
    assertNotEquals(stale, response.field("registrationId"))
    assertNotNull(response.field("secret"))
  }

  @Test
  fun `scope drift is re-minted even when the registration id still matches`() {
    // Drift would otherwise surface as 403s only once the tokens are actually used.
    val pod = sempodsTestFactory.newPod()
    podServiceClientFacade.register(
      podName = pod.name,
      clientId = CLIENT_ID,
      // narrower than the sandbox root
      scopes = setOf("${rootContext(pod)}/public#manage"),
      label = CLIENT_ID,
    )
    val drifted = assertNotNull(podServiceClientFacade.find(pod.name, CLIENT_ID)).id.toString()

    val response = provision(pod.name, expectedRegistrationId = drifted)

    assertEquals("provisioned", response.field("result"))
    assertEquals(
      setOf("${rootContext(pod)}#manage"),
      assertNotNull(podServiceClientFacade.find(pod.name, CLIENT_ID)).scopes,
      "scopes must be reset to the exact sandbox",
    )
  }

  @Test
  fun `a pre-existing public root context is demoted to private`() {
    val pod = sempodsTestFactory.newPod()
    val root = rootContext(pod)
    podFacade.createContext(podName = pod.name, contextUri = root, public = true, label = CLIENT_ID, description = null)
    assertTrue(podAccess.clientFor(pod.name).publicContexts().contains(root), "precondition: root is public")

    provision(pod.name)

    assertFalse(
      podAccess.clientFor(pod.name).publicContexts().contains(root),
      "a public root would expose every future descendant write to anonymous reads",
    )
    assertTrue(podAccess.clientFor(pod.name).contexts().contains(root), "root context must stay registered")
  }

  @Test
  fun `the demotion also runs on an otherwise idempotent call`() {
    // Self-repair is part of the contract, not of the "fresh registration" path.
    val pod = sempodsTestFactory.newPod()
    val root = rootContext(pod)
    val registrationId = assertNotNull(provision(pod.name).field("registrationId"))
    podFacade.setContextPublic(podName = pod.name, contextUri = root, public = true)

    val response = provision(pod.name, expectedRegistrationId = registrationId)

    assertEquals("alreadyProvisioned", response.field("result"))
    assertFalse(podAccess.clientFor(pod.name).publicContexts().contains(root), "root must have been demoted")
  }

  @Test
  fun `a clientId that is not a safe path segment is rejected`() {
    val pod = sempodsTestFactory.newPod()

    // `..%2F..` would escape `apps/<clientId>` and anchor the manage scope somewhere else entirely.
    assertEquals(400, provision(pod.name, clientId = "..%2Fadmin").statusCode)
    assertEquals(400, provision(pod.name, clientId = "app%20name").statusCode)
    assertNull(podServiceClientFacade.find(pod.name, ".."), "no registration may be created")
  }

  @Test
  fun `replacing a registration only deletes the row the caller observed`() {
    // The delete in provisionServiceClient is conditional on the registration read moments
    // earlier. Without that, two interleaved replacements delete each other's fresh row.
    val pod = sempodsTestFactory.newPod()
    val staleId = ObjectId(assertNotNull(provision(pod.name).field("registrationId")))
    provision(pod.name)  // someone else re-mints — the row now has a different id
    val current = assertNotNull(podServiceClientFacade.find(pod.name, CLIENT_ID))
    assertNotEquals(staleId, current.id, "precondition: re-minting replaces the row")

    assertFalse(
      podServiceClientFacade.unregister(pod.name, CLIENT_ID, expectedId = staleId),
      "a delete conditioned on a stale id must remove nothing",
    )
    assertNotNull(
      podServiceClientFacade.find(pod.name, CLIENT_ID),
      "the current registration must survive a stale-id delete",
    )

    assertTrue(podServiceClientFacade.unregister(pod.name, CLIENT_ID, expectedId = current.id))
    assertNull(podServiceClientFacade.find(pod.name, CLIENT_ID))
  }

  @Test
  fun `concurrent provisioning answers 200 or 409, never a server error`() {
    // Racing callers may lose — but they must be told so (409), not handed a 500 from the
    // unique index on (podId, clientId), and the pod must end up with exactly one registration
    // whose secret one of the successful responses actually carries.
    //
    // Deliberately `any`, not `all`: rotation is last-one-wins by contract, so an earlier 200's
    // secret being dead is correct behaviour, not a defect — the same thing happens with two
    // sequential calls (see `a half-provisioned caller without an expectedRegistrationId is
    // re-minted`). What this asserts is that the surviving registration was actually handed out
    // to somebody, i.e. no caller's write was silently dropped mid-flight.
    val pod = sempodsTestFactory.newPod()

    // The sends are blocking, so the four callers have to be four threads — collecting the futures
    // before awaiting any of them is what puts the requests in flight together, which is the whole
    // subject of this test.
    val responses = Executors.newVirtualThreadPerTaskExecutor().use { callers ->
      (1..4)
        .map {
          callers.submit<TestHttpResponse> {
            http.preparePost(provisionUrl(pod.name, CLIENT_ID))
              .addHeader("Content-Type", "application/json")
              .addHeader("Authorization", adminBearer)
              .setBody("{}")
              .execute()
          }
        }
        .map { it.get() }
    }

    responses.forEach { response ->
      assertTrue(
        response.statusCode == 200 || response.statusCode == 409,
        "unexpected status ${response.statusCode}: ${response.responseBody}",
      )
    }

    val registration = assertNotNull(
      podServiceClientFacade.find(pod.name, CLIENT_ID),
      "exactly one registration must remain",
    )
    val secrets = responses.filter { it.statusCode == 200 }.mapNotNull { it.field("secret") }
    assertTrue(
      secrets.any { podServiceClientFacade.authenticate(pod.name, CLIENT_ID, it) != null },
      "no returned secret authenticates against the surviving registration ${registration.id}",
    )
  }

  @Test
  fun `the sandbox root is returned on both results, so the caller never derives it`() {
    // The caller hangs its own sub-contexts under this root. Sending it keeps the naming
    // convention in one place — a caller rebuilding the string would break silently the day the
    // convention moves — as runtime 403s rather than a compile error. It moved once already.
    val pod = sempodsTestFactory.newPod()
    val expectedRoot = rootContext(pod).toString()

    val first = provision(pod.name)
    assertEquals("provisioned", first.field("result"))
    assertEquals(expectedRoot, first.field("contextRoot"))

    val registrationId = assertNotNull(first.field("registrationId"))
    val second = provision(pod.name, expectedRegistrationId = registrationId)
    assertEquals("alreadyProvisioned", second.field("result"))
    assertEquals(expectedRoot, second.field("contextRoot"), "also present when nothing was written")

    // And it is exactly what the granted scope is anchored to.
    assertEquals(
      setOf("$expectedRoot#manage"),
      assertNotNull(podServiceClientFacade.find(pod.name, CLIENT_ID)).scopes,
    )
  }

  @Test
  fun `the response mirrors the stored scope set`() {
    // `scopes` is state (what the registration has), `contextRoot` is an action (what this call
    // created). Redundant while there is exactly one scope, distinct once a caller may pass its
    // own — so the response must not derive one from the other.
    val pod = sempodsTestFactory.newPod()
    val expectedScopes = setOf("${rootContext(pod)}#manage")

    listOf(provision(pod.name), provision(pod.name)).forEach { response ->
      val scopes = objectMapper.readTree(response.responseBody).path("scopes").map { it.asText() }.toSet()
      assertEquals(expectedScopes, scopes, "body=${response.responseBody}")
    }
    assertEquals(
      expectedScopes,
      assertNotNull(podServiceClientFacade.find(pod.name, CLIENT_ID)).scopes,
      "the reported set must be the stored one",
    )
  }

  @Test
  fun `an unknown pod is a 404`() {
    assertEquals(404, provision("pod-${randomId()}").statusCode)
  }

  @Test
  fun `provisioning requires an admin credential`() {
    val pod = sempodsTestFactory.newPod()

    assertEquals(401, provision(pod.name, authorization = null).statusCode)
    assertEquals(401, provision(pod.name, authorization = "Bearer sc_wrong").statusCode)
    assertNull(
      podServiceClientFacade.find(pod.name, CLIENT_ID),
      "an unauthorized call must not have registered anything",
    )
  }

  companion object {
    private const val CLIENT_ID = "notes-app"
  }
}
