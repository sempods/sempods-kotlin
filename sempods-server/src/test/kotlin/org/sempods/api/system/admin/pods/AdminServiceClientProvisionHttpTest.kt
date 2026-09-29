package org.sempods.api.system.admin.pods

import com.google.inject.Inject
import org.sempods.pods.oauth.flows.PodServiceClientProvisioning
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.logging.CapturedLog
import org.sempods.admin.AdminAuthorizerTestDouble
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistrationId
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Service-client provisioning over the admin surface:
 * `POST /_system/admin/pods/{pod}/service-clients/{clientId}`.
 *
 * Covers the half of provisioning that lives server-side — root-context creation and public-root
 * demotion on the creating call, idempotency, a new secret for a caller that lost its own, and the
 * owner's decisions a later call leaves alone. The credential-row half (decrypt check, clientId
 * drift, per-user bookkeeping) belongs to whichever application calls this route and is covered on
 * its side.
 */
class AdminServiceClientProvisionHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podServiceClientStore: PodServiceClientStore

  private val objectMapper = ObjectMapper()

  private val adminBearer = "Bearer ${AdminAuthorizerTestDouble.TEST_ADMIN_SECRET}"

  private fun provisionUrl(pod: String, clientId: String) =
    "${SempodsModule.config.apiBaseUrl}_system/admin/pods/$pod/service-clients/$clientId"

  private fun provision(
    pod: String,
    clientId: String = CLIENT_ID,
    expectedRegistrationId: String? = null,
    expectedSecretId: String? = null,
    authorization: String? = adminBearer,
  ): TestHttpResponse {
    val body = objectMapper.writeValueAsString(
      listOfNotNull(
        expectedRegistrationId?.let { "expectedRegistrationId" to it },
        expectedSecretId?.let { "expectedSecretId" to it },
      ).toMap(),
    )
    return http.preparePost(provisionUrl(pod, clientId))
      .addHeader("Content-Type", "application/json")
      .apply { authorization?.let { addHeader("Authorization", it) } }
      .setBody(body)
      .execute()
  }

  private fun TestHttpResponse.field(name: String): String? =
    objectMapper.readTree(responseBody).path(name).takeIf { !it.isMissingNode }?.asString()

  private fun TestHttpResponse.hasField(name: String): Boolean =
    objectMapper.readTree(responseBody).has(name)

  private fun rootContext(pod: PodDbo): URI = sempodsUriBuilder.buildContext(pod.name, "apps/$CLIENT_ID")

  @Test
  fun `a fresh pod is provisioned with a private root context, a sandboxed registration and the secret`() {
    val pod = sempodsTestFactory.newPod()

    val response = provision(pod.name)

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    assertEquals("provisioned", response.field("result"))
    assertEquals(CLIENT_ID, response.field("clientId"))

    val root = rootContext(pod)
    assertTrue(podAccess.contextsOf(pod.name).contains(root), "root context must be registered")
    assertFalse(podAccess.publicContextsOf(pod.name).contains(root), "root context must be private")

    val registration = assertNotNull(podServiceClientStore.find(pod.podId(), CLIENT_ID), "registration missing")
    assertEquals(setOf("$root#manage"), registration.scopes)
    assertEquals(registration.id.value, response.field("registrationId"))

    val secret = assertNotNull(response.field("secret"), "the minted secret must be returned once")
    assertNotNull(
      podServiceClientStore.authenticate(pod.podId(), CLIENT_ID, secret),
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
  fun `a matching registration and secret is a no-op and returns no secret`() {
    val pod = sempodsTestFactory.newPod()
    val first = provision(pod.name)
    val registrationId = assertNotNull(first.field("registrationId"))
    val secretId = assertNotNull(first.field("secretId"))
    val firstSecret = assertNotNull(first.field("secret"))

    val second = provision(pod.name, expectedRegistrationId = registrationId, expectedSecretId = secretId)

    assertEquals(200, second.statusCode, "body=${second.responseBody}")
    assertEquals("alreadyProvisioned", second.field("result"))
    assertEquals(registrationId, second.field("registrationId"))
    assertEquals(secretId, second.field("secretId"))
    assertFalse(second.hasField("secret"), "no secret may be produced when nothing was written: ${second.responseBody}")
    assertNotNull(
      podServiceClientStore.authenticate(pod.podId(), CLIENT_ID, firstSecret),
      "the caller's existing secret must stay valid",
    )
  }

  @Test
  fun `a half-provisioned caller without an expectedRegistrationId gets a new secret for the same registration`() {
    // Registration exists on the pod, the caller lost its credential row — it can assert nothing,
    // so the only way back to a working state is a fresh secret.
    val pod = sempodsTestFactory.newPod()
    val first = provision(pod.name)
    val lostSecret = assertNotNull(first.field("secret"))

    val second = provision(pod.name)

    assertEquals("provisioned", second.field("result"))
    assertEquals(first.field("registrationId"), second.field("registrationId"), "the registration stays")
    val newSecret = assertNotNull(second.field("secret"))
    assertNotNull(podServiceClientStore.authenticate(pod.podId(), CLIENT_ID, newSecret))
    assertNull(
      podServiceClientStore.authenticate(pod.podId(), CLIENT_ID, lostSecret),
      "the replaced secret must no longer authenticate",
    )
  }

  @Test
  fun `a caller whose secret another call replaced gets a new one, though its registration matches`() {
    // Two runs that both held nothing: each got a secret for the one registration, and only the
    // later works. The earlier caller's secretId tells it apart.
    val pod = sempodsTestFactory.newPod()
    val earlier = provision(pod.name)
    val later = provision(pod.name)
    assertEquals(earlier.field("registrationId"), later.field("registrationId"))
    assertNotEquals(earlier.field("secretId"), later.field("secretId"))

    val healed = provision(pod.name, expectedRegistrationId = earlier.field("registrationId"), expectedSecretId = earlier.field("secretId"))

    assertEquals("provisioned", healed.field("result"))
    assertNotNull(podServiceClientStore.authenticate(pod.podId(), CLIENT_ID, assertNotNull(healed.field("secret"))))
    assertEquals(
      "alreadyProvisioned",
      provision(pod.name, expectedRegistrationId = healed.field("registrationId"), expectedSecretId = healed.field("secretId")).field("result"),
      "the new pair is the current one",
    )
  }

  @Test
  fun `a stale expectedRegistrationId gets a new secret and keeps the registration`() {
    // A restored dump on the caller's side: its assertion refers to a row that is not the current one.
    val pod = sempodsTestFactory.newPod()
    val current = assertNotNull(provision(pod.name).field("registrationId"))

    val response = provision(pod.name, expectedRegistrationId = "0123456789abcdef01234567")

    assertEquals("provisioned", response.field("result"))
    assertEquals(current, response.field("registrationId"))
    assertNotNull(podServiceClientStore.authenticate(pod.podId(), CLIENT_ID, assertNotNull(response.field("secret"))))
  }

  @Test
  fun `an expectedRegistrationId cannot forge a log line`() {
    // A body field, compared against the stored id and named in the line announcing a new secret
    // whether or not it matched — so it reaches the log exactly as sent. `docs/logging.md`
    // §"Three rules".
    val pod = sempodsTestFactory.newPod()
    provision(pod.name)
    // A real newline, which the JSON body carries escaped and the endpoint parses back.
    val forged = "6890abc-${randomId()}\n2026-01-01 21:00:00,000 WARN  [jetty] pod deleted by admin"

    val lines = CapturedLog.linesFrom(PodServiceClientProvisioning::class.java) {
      assertEquals("provisioned", provision(pod.name, expectedRegistrationId = forged).field("result"))
    }

    val line = lines.single { forged.substringBefore('\n') in it }
    assertFalse('\n' in line, "was: $line")
    assertTrue("\\u000a" in line, line)
  }

  @Test
  fun `grants the owner narrowed or emptied stay through every later provisioning`() {
    val pod = sempodsTestFactory.newPod()
    val first = provision(pod.name)
    val registrationId = assertNotNull(first.field("registrationId"))
    val below = sempodsUriBuilder.buildContext(pod.name, "apps/$CLIENT_ID/public")
    podFacade.createContext(podName = pod.name, contextUri = below, public = false, label = "public", description = null)
    val manager = mintServiceClientsManagerToken(pod.name, pod.owner)

    assertEquals(200, replaceGrants(pod, manager, """["$below#read"]""", version = 0).statusCode)
    var secretId = first.field("secretId")
    for (expected in listOf(registrationId, null, "0123456789abcdef01234567")) {
      val again = provision(pod.name, expectedRegistrationId = expected, expectedSecretId = secretId)
      assertEquals(200, again.statusCode, again.responseBody)
      assertEquals(setOf("$below#read"), scopesOf(again), "expectedRegistrationId=$expected")
      assertEquals(registrationId, again.field("registrationId"))
      secretId = again.field("secretId")
    }
    assertEquals(setOf("$below#read"), assertNotNull(podServiceClientStore.find(pod.podId(), CLIENT_ID)).scopes)

    assertEquals(200, replaceGrants(pod, manager, "[]", version = 1).statusCode)
    assertEquals(emptySet(), scopesOf(provision(pod.name)), "an emptied service is found and left empty")
    assertEquals(emptySet(), assertNotNull(podServiceClientStore.find(pod.podId(), CLIENT_ID)).scopes)
  }

  @Test
  fun `a pre-existing public root context is demoted to private`() {
    val pod = sempodsTestFactory.newPod()
    val root = rootContext(pod)
    podFacade.createContext(podName = pod.name, contextUri = root, public = true, label = CLIENT_ID, description = null)
    assertTrue(podAccess.publicContextsOf(pod.name).contains(root), "precondition: root is public")

    provision(pod.name)

    assertFalse(
      podAccess.publicContextsOf(pod.name).contains(root),
      "a public root would expose every future descendant write to anonymous reads",
    )
    assertTrue(podAccess.contextsOf(pod.name).contains(root), "root context must stay registered")
  }

  @Test
  fun `a later provisioning neither recreates a root the owner deleted nor demotes one they made public`() {
    // Only the creating call touches the root; afterwards it is the owner's.
    val pod = sempodsTestFactory.newPod()
    val root = rootContext(pod)
    val registrationId = assertNotNull(provision(pod.name).field("registrationId"))

    podFacade.setContextPublic(podName = pod.name, contextUri = root, public = true)
    for (expected in listOf(registrationId, null)) {
      assertEquals(200, provision(pod.name, expectedRegistrationId = expected).statusCode)
      assertTrue(podAccess.publicContextsOf(pod.name).contains(root), "expectedRegistrationId=$expected")
    }

    podFacade.removeContext(pod.name, root)
    for (expected in listOf(registrationId, null)) {
      val again = provision(pod.name, expectedRegistrationId = expected)
      assertEquals(200, again.statusCode, again.responseBody)
      assertEquals(root.toString(), again.field("contextRoot"), "the root is still named")
      assertFalse(podAccess.contextsOf(pod.name).contains(root), "expectedRegistrationId=$expected")
    }
  }

  @Test
  fun `a clientId that is not a safe path segment is rejected`() {
    val pod = sempodsTestFactory.newPod()

    // `..%2F..` would escape `apps/<clientId>` and anchor the manage scope somewhere else entirely.
    assertEquals(400, provision(pod.name, clientId = "..%2Fadmin").statusCode)
    assertEquals(400, provision(pod.name, clientId = "app%20name").statusCode)
    assertNull(podServiceClientStore.find(pod.podId(), ".."), "no registration may be created")
  }

  @Test
  fun `a removal only deletes the row the caller observed`() {
    val pod = sempodsTestFactory.newPod()
    provision(pod.name)
    val current = assertNotNull(podServiceClientStore.find(pod.podId(), CLIENT_ID))

    assertFalse(
      podServiceClientStore.remove(pod.podId(), CLIENT_ID, ServiceClientRegistrationId("0123456789abcdef01234567")),
      "a delete conditioned on another id must remove nothing",
    )
    assertNotNull(podServiceClientStore.find(pod.podId(), CLIENT_ID), "the current registration must survive it")

    assertTrue(podServiceClientStore.remove(pod.podId(), CLIENT_ID, current.id))
    assertNull(podServiceClientStore.find(pod.podId(), CLIENT_ID))
  }

  @Test
  fun `concurrent provisioning answers 200 or 409, never a server error`() {
    // Racing callers may lose — but they must be told so (409), not handed a 500 from the
    // unique index on (podId, clientId), and the pod must end up with exactly one registration
    // whose secret one of the successful responses actually carries.
    //
    // Deliberately `any`, not `all`: rotation is last-one-wins by contract, so an earlier 200's
    // secret being dead is correct behaviour, not a defect — the same thing happens with two
    // sequential calls (see `a half-provisioned caller without an expectedRegistrationId gets a new
    // secret for the same registration`). What this asserts is that the surviving secret was
    // actually handed out to somebody, i.e. no caller's write was silently dropped mid-flight.
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
      podServiceClientStore.find(pod.podId(), CLIENT_ID),
      "exactly one registration must remain",
    )
    val secrets = responses.filter { it.statusCode == 200 }.mapNotNull { it.field("secret") }
    assertTrue(
      secrets.any { podServiceClientStore.authenticate(pod.podId(), CLIENT_ID, it) != null },
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

    val second = provision(pod.name, expectedRegistrationId = first.field("registrationId"), expectedSecretId = first.field("secretId"))
    assertEquals("alreadyProvisioned", second.field("result"))
    assertEquals(expectedRoot, second.field("contextRoot"), "also present when nothing was written")

    // And it is exactly what the granted scope is anchored to.
    assertEquals(
      setOf("$expectedRoot#manage"),
      assertNotNull(podServiceClientStore.find(pod.podId(), CLIENT_ID)).scopes,
    )
  }

  @Test
  fun `the response mirrors the stored scope set`() {
    // `scopes` is state (what the registration has), `contextRoot` names the sandbox. They agree
    // only until the owner changes the grants, so the response must not derive one from the other.
    val pod = sempodsTestFactory.newPod()
    val expectedScopes = setOf("${rootContext(pod)}#manage")

    listOf(provision(pod.name), provision(pod.name)).forEach { response ->
      assertEquals(expectedScopes, scopesOf(response), "body=${response.responseBody}")
    }
    assertEquals(
      expectedScopes,
      assertNotNull(podServiceClientStore.find(pod.podId(), CLIENT_ID)).scopes,
      "the reported set must be the stored one",
    )
  }

  private fun scopesOf(response: TestHttpResponse): Set<String> =
    objectMapper.readTree(response.responseBody).path("scopes").values().map { it.asString() }.toSet()

  /** The owner's replace of the service's grants, at [version]. */
  private fun replaceGrants(pod: PodDbo, bearer: String, body: String, version: Long): TestHttpResponse =
    http.preparePut("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/service-clients/$CLIENT_ID/grants")
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $bearer")
      .addHeader("If-Match", "\"$version\"")
      .setBody(body)
      .execute()

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
      podServiceClientStore.find(pod.podId(), CLIENT_ID),
      "an unauthorized call must not have registered anything",
    )
  }

  companion object {
    private const val CLIENT_ID = "notes-app"
  }
}
