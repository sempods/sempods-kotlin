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
    // The point of the assertion: a caller re-running its installation must not be given a second
    // secret it then races its own health check against.
    val pod = pod()
    val first = provisioned(provision(pod))

    val again = provision(pod, expectedRegistrationId = first.registration.id.value)

    assertEquals(PodServiceClientResult.AlreadyProvisioned(first.registration), again)
  }

  @Test
  fun `naming a registration that is no longer there mints a new one`() {
    // The self-healing path: a caller whose stored credential drifted from the pod gets a usable
    // one back on its next run instead of a refusal it cannot act on.
    val pod = pod()
    val first = provisioned(provision(pod))

    val second = provisioned(provision(pod, expectedRegistrationId = "000000000000000000000000"))

    assertNotEquals(first.registration.id, second.registration.id)
    assertNotEquals(first.secret, second.secret)
    assertEquals(second.registration, serviceClients.find(pod.id, CLIENT_ID), "only the newer one stands")
  }

  @Test
  fun `claiming nothing always mints`() {
    val pod = pod()
    val first = provisioned(provision(pod))

    val second = provisioned(provision(pod, expectedRegistrationId = null))

    assertNotEquals(first.registration.id, second.registration.id)
  }

  @Test
  fun `a registration whose scopes drifted is re-minted, id match or not`() {
    // Drift would otherwise surface as 403s, and only once the tokens are actually used.
    val pod = pod()
    val narrow = setOf("${sempodsUriBuilder.buildContext(pod.name, "apps/notes/public")}#manage")
    val first = provisioned(provision(pod, scopes = narrow))

    val second = provisioned(provision(pod, expectedRegistrationId = first.registration.id.value))

    assertNotEquals(first.registration.id, second.registration.id)
    assertEquals(setOf(manageScope(pod)), second.registration.scopes)
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
  ): PodServiceClientResult = provisioning.provision(
    pod,
    PodServiceClientRequest(
      clientId = CLIENT_ID,
      scopes = scopes,
      label = CLIENT_ID,
      expectedRegistrationId = expectedRegistrationId,
    ),
  )

  private fun provisioned(result: PodServiceClientResult) =
    assertIs<PodServiceClientResult.Provisioned>(result)
}
