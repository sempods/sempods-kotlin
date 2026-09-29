package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.bson.types.ObjectId
import org.sempods.SempodsModule
import org.sempods.SempodsStoreTest
import org.sempods.SempodsTestFactory
import org.sempods.SempodsUriBuilder
import org.sempods.auth.core.DynamicClientFingerprint
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.HostedPod
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.oauth.DynamicClientRegistrationDao
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PodManagementAuthorityStore
import org.sempods.pods.oauth.PrivilegedAuthorityRows
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

  @Inject
  private lateinit var serviceClients: PodServiceClientStore

  @Inject
  private lateinit var serviceClientDao: PodServiceClientDao

  @Inject
  private lateinit var managementAuthorities: PodManagementAuthorityStore

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

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
    assertEquals(setOf(LOOPBACK_CALLBACK), registered.client.redirectUris)
    assertTrue(registered.client.clientName.orEmpty().startsWith("Notes "), registered.client.clientName)
    assertEquals("https://app.example", registered.client.clientUri)
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

    assertNull(hit.client.clientUri, "the refused value must not reach the answer")
    assertEquals("https://app.example/policy", hit.client.policyUri, "the legal one beside it survives")
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

  // ─── The service profile ──────────────────────────────────────────────────

  @Test
  fun `a service registering itself is given a server-named client, a secret once, no grants and a deadline`() {
    val pod = pod()
    val before = Instant.now()

    val registered = service(register(pod, client = named("Notes Sync"), raw = serviceBody()))

    assertTrue(registered.clientId.startsWith("svc:"), registered.clientId)
    assertEquals("Notes Sync", registered.clientName)
    assertTrue(registered.secret.startsWith("sc_"), "the secret is the store's, minted once")
    val window = PodServiceClientStore.ACTIVATION_WINDOW
    assertTrue(registered.activationExpiresAt in before.plus(window).minusSeconds(1)..Instant.now().plus(window))

    val stored = assertNotNull(serviceClients.find(pod.id, registered.clientId))
    assertEquals(emptySet(), stored.scopes, "the contexts are the owner's to grant")
    assertEquals("Notes Sync", stored.label)
    assertEquals(registered.activationExpiresAt, stored.pendingUntil)
    assertEquals(stored.createdAt, registered.issuedAt)
  }

  @Test
  fun `the owner's standing authority registers a service active`() {
    val pod = pod()

    val registered = service(register(pod, client = named("With bearer"), raw = serviceBody(), caller = manager(pod, recorded = true)))

    assertNull(registered.activationExpiresAt)
    val stored = assertNotNull(serviceClients.find(pod.id, registered.clientId))
    assertNull(stored.pendingUntil, "no deadline: the owner's authority is the owner's consent")
    assertEquals(emptySet(), stored.scopes, "the grants are set afterwards")
  }

  @Test
  fun `a spent service budget does not hold up the owner's registration`() {
    val pod = pod()
    repeat(serviceBudget) { service(register(pod, client = named("Filler $it"), raw = serviceBody())) }
    assertEquals(PodRegistrationResult.RateLimited, register(pod, client = named("Late"), raw = serviceBody()))

    val registered = service(register(pod, client = named("Owner's"), raw = serviceBody(), caller = manager(pod, recorded = true)))

    assertNull(registered.activationExpiresAt)
  }

  @Test
  fun `a bearer that holds no authority to register is refused`() {
    // A caller presenting authority expects it to count; a provisional registration would surprise it.
    val pod = pod()

    for ((caller, reason) in listOf(
      manager(pod, recorded = false) to PodOwnerAuthorityRefusal.AUTHORITY_WITHDRAWN,
      manager(pod, recorded = true, consent = PrivilegedAuthorityRows.FIRST_CONSENT) to PodOwnerAuthorityRefusal.CONSENT_OUTDATED,
      manager(pod, recorded = true).copy(oauthScopes = setOf("${pod.baseUrl}/_system/contexts/notes#read")) to PodOwnerAuthorityRefusal.SCOPE_REQUIRED,
    )) {
      assertEquals(PodRegistrationResult.Unauthorized(reason), register(pod, client = named("With bearer"), raw = serviceBody(), caller = caller))
    }
    assertEquals(emptyList(), serviceClientDao.findByPod(ObjectId(pod.id.value)), "nothing was registered")
  }

  @Test
  fun `a service registration is budgeted per pod, and only an accepted body spends it`() {
    val pod = pod()
    repeat(serviceBudget + 1) {
      refusal(register(pod, client = PodClientMetadata(), raw = serviceBody()))
    }
    repeat(serviceBudget) { service(register(pod, client = named("Filler $it"), raw = serviceBody())) }

    assertEquals(PodRegistrationResult.RateLimited, register(pod, client = named("Late"), raw = serviceBody()))
    assertEquals(serviceBudget, serviceClientDao.findByPod(ObjectId(pod.id.value)).size, "the refused call wrote nothing")
  }

  @Test
  fun `a member asking for a key, a scope or a browser flow is refused`() {
    val pod = pod()

    for ((member, value) in listOf(
      "jwks" to mapOf("keys" to emptyList<Any>()),
      "jwks_uri" to "https://app.example/jwks",
      "scope" to "https://pod.example/_system/contexts/notes#read",
      "response_types" to listOf("code"),
    )) {
      val refused = refusal(register(pod, client = named("Presumptuous"), raw = serviceBody() + (member to value)))

      assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error, member)
      assertTrue(member in refused.description, refused.description)
    }
  }

  @Test
  fun `members this profile does not use are dropped, and unknown ones ignored`() {
    val pod = pod()
    val raw = serviceBody() + mapOf(
      "logo_uri" to "https://app.example/logo.png",
      "contacts" to listOf("ops@app.example"),
      "response_types" to emptyList<String>(),
      "x_vendor_hint" to "anything",
    )

    val registered = service(register(pod, client = named("Tolerant"), raw = raw))

    assertEquals("Tolerant", assertNotNull(serviceClients.find(pod.id, registered.clientId)).label)
  }

  @Test
  fun `a service without a name is refused`() {
    // The consent that activates this service names it. There is nothing else to show an owner:
    // the identifier is 18 random bytes.
    val refused = refusal(register(client = PodClientMetadata(), raw = serviceBody()))

    assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error)
    assertTrue("client_name" in refused.description, refused.description)
  }

  @Test
  fun `a service's redirect is kept where it is valid and refused where it is not`() {
    val pod = pod()

    val registered = service(
      register(pod, client = named("Laptop").copy(redirectUris = setOf(LOOPBACK_CALLBACK)), raw = serviceBody()),
    )
    assertEquals(listOf(LOOPBACK_CALLBACK), registered.redirectUris)
    assertEquals(listOf(LOOPBACK_CALLBACK), assertNotNull(serviceClients.find(pod.id, registered.clientId)).redirectUris)

    val refused = refusal(
      register(pod, client = named("Plain").copy(redirectUris = setOf("http://app.example/cb")), raw = serviceBody()),
    )
    assertEquals(PodRegistrationError.INVALID_REDIRECT_URI, refused.error)
  }

  @Test
  fun `a privileged bearer on a public registration is refused`() {
    val pod = pod()

    val refused = refusal(register(pod, caller = manager(pod)))

    assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error)
    assertTrue("client_credentials" in refused.description, refused.description)
  }

  @Test
  fun `a confidential shape this pod does not serve is refused`() {
    // `client_secret_post` and `private_key_jwt` are registrations for a client that holds a
    // secret. Answering one as a public registration would hand back a `dyn:` client that quietly
    // does something else.
    val pod = pod()
    val bodies = listOf(
      mapOf("token_endpoint_auth_method" to "client_secret_post", "grant_types" to listOf("client_credentials")),
      mapOf("token_endpoint_auth_method" to "client_secret_basic", "grant_types" to listOf("authorization_code")),
      mapOf("grant_types" to listOf("client_credentials", "refresh_token")),
    )

    bodies.forEach { body ->
      val refused = refusal(register(pod, client = named("Confidential"), raw = body))
      assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error, body.toString())
    }
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
    caller: SempodsCredentials? = null,
  ): PodRegistrationResult =
    registration.register(pod, PodRegistrationRequest(client, raw, userAgent, forwardedFor, caller))

  /** What a fresh pod may register before it is throttled — the build sets it for this JVM. */
  private val serviceBudget = SempodsModule.config.registerRateLimitServiceBurst

  private fun named(clientName: String) = PodClientMetadata(clientName = clientName)

  /** The one confidential shape this pod serves. */
  private fun serviceBody(): Map<String, Any?> = mapOf(
    "token_endpoint_auth_method" to "client_secret_basic",
    "grant_types" to listOf("client_credentials"),
  )

  /**
   * An owner's `service-clients:manage` bearer, as the adapter would have verified it; with the
   * authority the dialog records behind it where [recorded], approved under [consent].
   */
  private fun manager(pod: HostedPod, recorded: Boolean = false, consent: Int = PrivilegedAuthorityRows.SERVICE_CLIENTS_CONSENT): SempodsCredentials {
    val jti = randomId()
    if (recorded) managementAuthorities.record(pod.id, jti, "dyn:manager", pod.owner, 0L, setOf(pod.owner), consent)
    return SempodsCredentials(
      pod = pod.ref,
      restrictedContexts = emptySet(),
      oauthClientId = "dyn:manager",
      oauthScopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE),
      tokenJti = jti,
      tokenSub = pod.owner,
    )
  }

  private fun refusal(result: PodRegistrationResult) = assertIs<PodRegistrationResult.Refused>(result)

  private fun registered(result: PodRegistrationResult) = assertIs<PodRegistrationResult.Registered>(result)

  private fun service(result: PodRegistrationResult) = assertIs<PodRegistrationResult.ServiceRegistered>(result)
}
