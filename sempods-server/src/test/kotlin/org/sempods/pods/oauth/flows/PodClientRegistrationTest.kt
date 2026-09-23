package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.bson.types.ObjectId
import org.sempods.SempodsStoreTest
import org.sempods.SempodsTestFactory
import org.sempods.SempodsUriBuilder
import org.sempods.auth.core.DynamicClientFingerprint
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.HostedPod
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.oauth.DynamicClientRegistrationDao
import org.sempods.pods.oauth.DynamicClientStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Registering a client, without a server in front of it.
 *
 * `PodAuthEndpointHttpTest` drives the same route over HTTP and is what says the endpoint
 * delegates; this says what it delegates to. What a body has to look like to get this far is
 * `PodRegistrationMessagesTest`'s. The store is the real one — a fingerprint hit is a unique index
 * losing a race, so a fake store would be testing the fake.
 */
class PodClientRegistrationTest : SempodsStoreTest() {

  @Inject
  private lateinit var registration: PodClientRegistration

  @Inject
  private lateinit var dynamicClientStore: DynamicClientStore

  @Inject
  private lateinit var dynamicClientRegistrationDao: DynamicClientRegistrationDao

  @Inject
  private lateinit var sempodsTestFactory: SempodsTestFactory

  @Inject
  private lateinit var sempodsUriBuilder: SempodsUriBuilder

  @Test
  fun `a client that names no address it can be reached at is refused`() {
    val refused = refusal(register(client = PodClientMetadata()))

    assertEquals(PodRegistrationError.INVALID_REDIRECT_URI, refused.error)
    assertEquals("at least one redirect_uri is required", refused.description)
  }

  @Test
  fun `an address no login could honour is refused, and named`() {
    val refused = refusal(register(client = PodClientMetadata(redirectUris = setOf("ftp://app.example/cb"))))

    assertEquals(PodRegistrationError.INVALID_REDIRECT_URI, refused.error)
    assertTrue("ftp://app.example/cb" in refused.description, refused.description)
  }

  @Test
  fun `a script URL for something a person is shown is refused, and the field is named`() {
    // Four fields, one rule, and the answer says which one failed — a client with a bad `logo_uri`
    // cannot otherwise tell which of the four this server objected to.
    val script = "javascript:alert(1)"
    val perturbed = mapOf<String, (PodClientMetadata) -> PodClientMetadata>(
      "client_uri" to { it.copy(clientUri = script) },
      "logo_uri" to { it.copy(logoUri = script) },
      "tos_uri" to { it.copy(tosUri = script) },
      "policy_uri" to { it.copy(policyUri = script) },
    )

    perturbed.forEach { (field, perturb) ->
      val refused = refusal(register(client = perturb(ordinary())))

      assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error, field)
      assertEquals("$field must be https, or http on a loopback host: $script", refused.description)
    }
  }

  @Test
  fun `a registered client is given an opaque identity and its metadata back`() {
    val registered = registered(register(client = ordinary().copy(clientName = "Notes ${randomId()}")))

    assertTrue(registered.clientId.startsWith("dyn:"), registered.clientId)
    assertEquals(setOf(LOOPBACK_CALLBACK), registered.redirectUris)
    assertTrue(registered.clientName.orEmpty().startsWith("Notes "), registered.clientName)
    assertEquals("https://app.example", registered.clientUri)
  }

  @Test
  fun `a client that re-registers the same way keeps the identity it already has`() {
    // The reconnect case: a client with no stored state registers again on every launch, and the
    // grants hang off the id it was given the first time.
    val pod = pod()
    val client = ordinary().copy(clientName = "Reconnecting ${randomId()}")

    val first = registered(register(pod, client, userAgent = "Agent/1.0"))
    val again = registered(register(pod, client, userAgent = "Agent/1.0"))

    assertEquals(first.clientId, again.clientId)
  }

  @Test
  fun `the body reaches the row verbatim, members this pod does not read included`() {
    val pod = pod()
    val name = "Verbatim ${randomId()}"
    val raw = mapOf("client_name" to name, "some_extension" to listOf("kept"))

    val registered = registered(register(pod, ordinary().copy(clientName = name), raw = raw))

    val stored = assertNotNull(dynamicClientRegistrationDao.findByClientId(ObjectId(pod.id.value), registered.clientId))
    assertEquals(raw, stored.rawRequest)
  }

  @Test
  fun `a stored value the rule now refuses is not handed back`() {
    // A fingerprint hit answers with the *stored* row, so the check at registration never sees it.
    // A row written before the rule existed would otherwise keep serving its value for the life of
    // the client.
    val pod = pod()
    val clientName = "Legacy ${randomId()}"
    val userAgent = "LegacyAgent/1.0"
    dynamicClientRegistrationDao.create(
      clientId = "dyn:" + ObjectId().toHexString(),
      registeredForPodId = ObjectId(pod.id.value),
      registeredForPodName = pod.name,
      redirectUris = setOf(LOOPBACK_CALLBACK),
      clientName = clientName,
      clientUri = "javascript:alert(1)",
      logoUri = null,
      softwareId = null,
      softwareVersion = null,
      contacts = emptyList(),
      tosUri = null,
      policyUri = "https://app.example/policy",
      rawRequest = emptyMap(),
      userAgent = userAgent,
      fingerprint = DynamicClientFingerprint.compute(clientName, userAgent, realm = null, setOf(LOOPBACK_CALLBACK)),
    )

    val hit = registered(
      register(pod, PodClientMetadata(redirectUris = setOf(LOOPBACK_CALLBACK), clientName = clientName), userAgent),
    )

    assertNull(hit.clientUri, "the refused value must not reach the answer")
    assertEquals("https://app.example/policy", hit.policyUri, "the legal one beside it survives")
    assertNotNull(dynamicClientStore.lookup(pod.id, hit.clientId)?.clientUri, "and the row is left as it stands")
  }

  @Test
  fun `the address recorded is the one the proxy appended`() {
    val pod = pod()
    val name = "Proxied ${randomId()}"

    val registered = registered(
      register(pod, ordinary().copy(clientName = name), forwardedFor = "1.2.3.4, 203.0.113.7"),
    )

    val stored = dynamicClientRegistrationDao.findByClientId(ObjectId(pod.id.value), registered.clientId)
    assertEquals("203.0.113.7", assertNotNull(stored).remoteAddr)
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private companion object {
    const val LOOPBACK_CALLBACK = "http://localhost:5173/callback"
  }

  private fun pod(): HostedPod = sempodsTestFactory.newPod().toHostedPod(sempodsUriBuilder)

  /** A registration that passes every check, so each case names only what it perturbs. */
  private fun ordinary() = PodClientMetadata(
    redirectUris = setOf(LOOPBACK_CALLBACK),
    clientUri = "https://app.example",
  )

  private fun register(
    pod: HostedPod = pod(),
    client: PodClientMetadata = ordinary(),
    userAgent: String? = null,
    forwardedFor: String? = null,
    raw: Map<String, Any?> = emptyMap(),
  ): PodRegistrationResult =
    registration.register(pod, PodRegistrationRequest(client, raw, userAgent, forwardedFor))

  private fun refusal(result: PodRegistrationResult) = assertIs<PodRegistrationResult.Refused>(result)

  private fun registered(result: PodRegistrationResult) = assertIs<PodRegistrationResult.Registered>(result)
}
