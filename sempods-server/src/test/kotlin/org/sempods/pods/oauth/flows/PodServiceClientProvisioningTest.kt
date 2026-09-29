package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.SempodsStoreTest
import org.sempods.SempodsTestFactory
import org.sempods.SempodsUriBuilder
import org.sempods.pods.HostedPod
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Provisioning a service client, without a server in front of it.
 *
 * The idempotency contract, one case per thing a caller can claim to already hold. #216 asks for
 * exactly this — a test of the decision that needs no server — and
 * `AdminServiceClientProvisionHttpTest` keeps the wire shape it reaches over.
 *
 * The two concurrent refusals are not here. Both need a second writer between two statements of one
 * call, which no single-threaded test reaches — `AdminPodsEndpoint`'s KDoc argues what they cost.
 */
class PodServiceClientProvisioningTest : SempodsStoreTest() {

  @Inject
  private lateinit var provisioning: PodServiceClientProvisioning

  @Inject
  private lateinit var serviceClients: PodServiceClientStore

  @Inject
  private lateinit var sempodsTestFactory: SempodsTestFactory

  @Inject
  private lateinit var sempodsUriBuilder: SempodsUriBuilder

  @Test
  fun `a client nobody has provisioned gets an identity and a secret`() {
    val pod = pod()

    val result = provisioned(provision(pod))

    assertTrue(result.secret.startsWith("sc_"), result.secret)
    assertEquals(setOf(manageScope(pod)), result.registration.scopes)
    assertEquals(result.registration, serviceClients.find(pod.id, CLIENT_ID), "and it is what is stored")
  }

  @Test
  fun `naming the registration that is there changes nothing, and hands back no secret`() {
    // The point of the assertion: a caller re-running its provisioning must not be given a second
    // secret it then races its own health check against.
    val pod = pod()
    val first = provisioned(provision(pod))

    val again = provision(pod, expectedRegistrationId = first.registration.id.value, expectedSecretId = first.registration.secretId)

    assertEquals(PodServiceClientResult.AlreadyProvisioned(first.registration), again)
    assertIs<PodServiceClientResult.Provisioned>(
      provision(pod, expectedRegistrationId = first.registration.id.value),
      "the registration alone does not say the caller holds the current secret",
    )
  }

  @Test
  fun `naming a registration that is not there issues a new secret for the one that is`() {
    // The self-healing path: a caller whose stored credential drifted from the pod gets a usable
    // one back on its next run instead of a refusal it cannot act on. The registration, and the
    // grants the owner may have given it since, stay.
    val pod = pod()
    val first = provisioned(provision(pod))

    val second = provisioned(provision(pod, expectedRegistrationId = "000000000000000000000000"))

    assertEquals(first.registration.id, second.registration.id)
    assertNotEquals(first.secret, second.secret)
    assertNotEquals(first.registration.secretId, second.registration.secretId)
    assertEquals(second.registration.secretId, serviceClients.find(pod.id, CLIENT_ID)?.secretId, "the answer names the stored secret")
    assertNull(serviceClients.authenticate(pod.id, CLIENT_ID, first.secret), "only the newer secret stands")
    assertNotNull(serviceClients.authenticate(pod.id, CLIENT_ID, second.secret))
  }

  @Test
  fun `claiming nothing issues a new secret and keeps the registration`() {
    val pod = pod()
    val first = provisioned(provision(pod))

    val second = provisioned(provision(pod, expectedRegistrationId = null))

    assertEquals(first.registration.id, second.registration.id)
  }

  @Test
  fun `an existing registration keeps its grants, whatever the caller asks for`() {
    // The owner decides a service's grants once it exists, so provisioning never writes them.
    val pod = pod()
    val narrow = setOf("${sempodsUriBuilder.buildContext(pod.name, "apps/notes/public")}#manage")
    val first = provisioned(provision(pod, scopes = narrow))

    assertEquals(
      PodServiceClientResult.AlreadyProvisioned(first.registration),
      provision(pod, expectedRegistrationId = first.registration.id.value, expectedSecretId = first.registration.secretId),
    )
    assertEquals(narrow, provisioned(provision(pod)).registration.scopes)
    assertEquals(narrow, serviceClients.find(pod.id, CLIENT_ID)?.scopes)
  }

  @Test
  fun `what is set up for a new client runs only when one is created`() {
    val pod = pod()
    var runs = 0

    provision(pod) { runs++ }
    provision(pod) { runs++ }

    assertEquals(1, runs)
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private companion object {
    const val CLIENT_ID = "notes-app"
  }

  private fun pod(): HostedPod =
    sempodsTestFactory.newPod(createPublicContext = false).toHostedPod(sempodsUriBuilder)

  private fun manageScope(pod: HostedPod) =
    "${sempodsUriBuilder.buildContext(pod.name, "apps/$CLIENT_ID")}#manage"

  private fun provision(
    pod: HostedPod,
    expectedRegistrationId: String? = null,
    scopes: Set<String> = setOf(manageScope(pod)),
    expectedSecretId: String? = null,
    beforeCreating: () -> Unit = {},
  ): PodServiceClientResult = provisioning.provision(
    pod,
    PodServiceClientRequest(
      clientId = CLIENT_ID,
      scopes = scopes,
      label = CLIENT_ID,
      expectedRegistrationId = expectedRegistrationId,
      expectedSecretId = expectedSecretId,
    ),
    beforeCreating,
  )

  private fun provisioned(result: PodServiceClientResult) =
    assertIs<PodServiceClientResult.Provisioned>(result)
}
