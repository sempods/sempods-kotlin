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
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.grants.SERVICE_CLIENTS_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PodInstallationAuthorityStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

  @Inject
  private lateinit var serviceClients: PodServiceClientStore

  @Inject
  private lateinit var serviceClientDao: PodServiceClientDao

  @Inject
  private lateinit var installationAuthorities: PodInstallationAuthorityStore

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

  // ─── The installation profile ─────────────────────────────────────────────

  @Test
  fun `an owner's installation is given a server-named client, a secret once, and no grants`() {
    val pod = pod()
    val jti = randomId()
    recordAuthority(pod, jti)

    val installed = installed(register(pod, client = named("Notes Sync"), raw = installation(), caller = owner(pod, jti)))

    assertTrue(installed.clientId.startsWith("svc:"), installed.clientId)
    assertEquals("Notes Sync", installed.clientName)
    assertTrue(installed.secret.startsWith("sc_"), "the secret is the store's, minted once")

    val stored = assertNotNull(serviceClients.find(pod.id, installed.clientId))
    assertEquals(emptySet(), stored.scopes, "the contexts are the owner's to grant in the consent that follows")
    assertEquals("Notes Sync", stored.label)
    assertEquals(stored.createdAt, installed.issuedAt, "what the caller opens the grant consent with")
  }

  @Test
  fun `one authorization registers one client, however many times it is presented`() {
    val pod = pod()
    val jti = randomId()
    recordAuthority(pod, jti)

    installed(register(pod, client = named("First"), raw = installation(), caller = owner(pod, jti)))
    val again = unauthorized(register(pod, client = named("Second"), raw = installation(), caller = owner(pod, jti)))

    assertEquals(PodRegistrationRefusal.AUTHORITY_SPENT, again.reason)
    // A caller whose answer was lost in transit retries and lands here. The secret existed only in
    // that answer — the store keeps a bcrypt hash — so there is nothing to hand back, and a second
    // client is exactly what the authority is one-shot to prevent.
    assertEquals(1, serviceClientDao.findByPod(ObjectId(pod.id.value)).size, "one client from one approval")
  }

  @Test
  fun `eight calls on one authority create one client`() {
    val pod = pod()
    val jti = randomId()
    recordAuthority(pod, jti)

    val callers = 8
    val ready = CountDownLatch(callers)
    val go = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(callers)
    try {
      val attempts = (1..callers).map {
        pool.submit<PodRegistrationResult> {
          ready.countDown()
          go.await()
          register(pod, client = named("Racing"), raw = installation(), caller = owner(pod, jti))
        }
      }
      ready.await()
      go.countDown()

      val results = attempts.map { it.get(30, TimeUnit.SECONDS) }
      assertEquals(1, results.count { it is PodRegistrationResult.ServiceRegistered }, "one client, whatever the interleaving")
      assertTrue(
        results.filterIsInstance<PodRegistrationResult.Unauthorized>()
          .all { it.reason == PodRegistrationRefusal.AUTHORITY_SPENT },
        "and the losers hear what a second attempt hears",
      )
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `an unauthenticated caller is given no secret`() {
    val refused = refusal(register(client = named("Uninvited"), raw = installation()))

    assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error)
    assertTrue("unauthenticated" in refused.description, refused.description)
  }

  @Test
  fun `a bearer that carries no installation authority is refused`() {
    val pod = pod()
    val ordinary = owner(pod, randomId()).copy(oauthScopes = setOf("public-read"))

    val refused = unauthorized(register(pod, client = named("Ordinary"), raw = installation(), caller = ordinary))

    assertEquals(PodRegistrationRefusal.NOT_AUTHORIZED, refused.reason)
  }

  @Test
  fun `an installer authority granted by someone who is not the owner registers nothing`() {
    // Ownership is asked again here: the authority was granted an hour ago at most, and a pod can
    // change hands in that time.
    val pod = pod()
    val jti = randomId()
    recordAuthority(pod, jti)
    val stranger = owner(pod, jti).copy(tokenSub = "https://id.sempods.org/e/${"0".repeat(64)}")

    val refused = unauthorized(register(pod, client = named("Stranger"), raw = installation(), caller = stranger))

    assertEquals(PodRegistrationRefusal.NOT_AUTHORIZED, refused.reason)
    assertNotNull(
      installationAuthorities.consume(pod.id, jti),
      "a refusal before the authority is spent leaves it to be spent",
    )
  }

  @Test
  fun `the owner is recognised through either spelling of their address`() {
    val pod = pod()
    val jti = randomId()
    recordAuthority(pod, jti)
    val urn = assertNotNull(
      webIdUriDeriver.derivableAliases(pod.owner).firstOrNull { it.startsWith("urn:sempods:") },
      "the owner's address has a urn twin",
    )

    val installed = installed(
      register(pod, client = named("Aliased"), raw = installation(), caller = owner(pod, jti).copy(tokenSub = urn)),
    )

    assertTrue(installed.clientId.startsWith("svc:"))
  }

  @Test
  fun `a body naming the identity, the place or the grants is refused`() {
    for (member in listOf("client_id", "clientId", "contextRoot", "context_root", "scope", "scopes",
                          "redirect_uris", "response_types")) {
      val pod = pod()
      val jti = randomId()
      recordAuthority(pod, jti)

      val refused = refusal(
        register(pod, client = named("Presumptuous"), raw = installation() + (member to "anything"), caller = owner(pod, jti)),
      )

      assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error, member)
      assertTrue(member in refused.description, refused.description)
      assertNotNull(installationAuthorities.consume(pod.id, jti), "and the authority is still there")
    }
  }

  @Test
  fun `an installation without a name is refused`() {
    // The consent that grants this service its contexts names it. There is nothing else to show
    // an owner: the identifier is 18 random bytes.
    val pod = pod()
    val jti = randomId()
    recordAuthority(pod, jti)

    val refused = refusal(register(pod, client = PodClientMetadata(), raw = installation(), caller = owner(pod, jti)))

    assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error)
    assertTrue("client_name" in refused.description, refused.description)
  }

  @Test
  fun `an installer bearer on a public registration is refused`() {
    val pod = pod()
    val jti = randomId()
    recordAuthority(pod, jti)

    val refused = refusal(register(pod, caller = owner(pod, jti)))

    assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error)
    assertTrue("client_credentials" in refused.description, refused.description)
  }

  @Test
  fun `a confidential shape this pod does not serve is refused, whoever asks`() {
    // `client_secret_post` and `private_key_jwt` are registrations for a client that holds a
    // secret. Answering one as a public registration would hand back a `dyn:` client that quietly
    // does something else.
    val pod = pod()
    val jti = randomId()
    recordAuthority(pod, jti)
    val bodies = listOf(
      mapOf("token_endpoint_auth_method" to "client_secret_post", "grant_types" to listOf("client_credentials")),
      mapOf("token_endpoint_auth_method" to "client_secret_basic", "grant_types" to listOf("authorization_code")),
      mapOf("grant_types" to listOf("client_credentials", "refresh_token")),
    )

    bodies.forEach { body ->
      val refused = refusal(register(pod, client = named("Confidential"), raw = body, caller = owner(pod, jti)))
      assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error, body.toString())
    }
    assertNotNull(installationAuthorities.consume(pod.id, jti), "none of them spent the authority")
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private companion object {
    const val LOOPBACK_CALLBACK = "http://localhost:5173/callback"

    /** The installing program, which is not the service it is asking the pod to create. */
    const val INSTALLER = "dyn:installer"
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

  private fun named(clientName: String) = PodClientMetadata(clientName = clientName)

  /** The metadata #124 settled on for an installation, and the only confidential shape served. */
  private fun installation(): Map<String, Any?> = mapOf(
    "token_endpoint_auth_method" to "client_secret_basic",
    "grant_types" to listOf("client_credentials"),
  )

  /** What the code exchange writes when the owner approves an installation. */
  private fun recordAuthority(pod: HostedPod, jti: String) =
    installationAuthorities.record(pod = pod.id, jti = jti, clientId = INSTALLER, webId = pod.owner)

  private fun owner(pod: HostedPod, jti: String) = SempodsCredentials(
    pod = pod.ref,
    restrictedContexts = emptySet(),
    oauthClientId = INSTALLER,
    oauthScopes = setOf(SERVICE_CLIENTS_SCOPE),
    tokenJti = jti,
    tokenSub = pod.owner,
  )

  private fun refusal(result: PodRegistrationResult) = assertIs<PodRegistrationResult.Refused>(result)

  private fun registered(result: PodRegistrationResult) = assertIs<PodRegistrationResult.Registered>(result)

  private fun installed(result: PodRegistrationResult) = assertIs<PodRegistrationResult.ServiceRegistered>(result)

  private fun unauthorized(result: PodRegistrationResult) = assertIs<PodRegistrationResult.Unauthorized>(result)
}
