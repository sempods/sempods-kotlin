package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The owner-management operations and the service consent, decided without HTTP: what each
 * authority reaches, and what a redemption asks again.
 */
internal class PodServiceClientManagementTest : PodBrowserFlowTest() {

  @Inject
  private lateinit var management: PodServiceClientManagement

  @Inject
  private lateinit var consents: PodServiceConsentFlow

  @Inject
  private lateinit var serviceClients: PodServiceClientStore

  @Test
  fun `a contexts-management bearer reaches no existing registration`() {
    val owned = Owned()
    val existing = serviceClients.registerProvisional(owned.pod, "backup", emptyList()).registration

    val contextsManager = SempodsCredentials(
      pod = owned.pod.ref,
      restrictedContexts = emptySet(),
      oauthClientId = clientId,
      oauthScopes = setOf(CONTEXTS_MANAGE_SCOPE),
      tokenJti = randomId(),
      tokenSub = owned.webId,
    )

    for (result in listOf(
      management.list(owned.pod, contextsManager),
      management.rotate(owned.pod, contextsManager, existing.clientId),
      management.revoke(owned.pod, contextsManager, existing.clientId),
    )) {
      assertEquals(PodServiceClientManagementResult.Unauthorized(PodOwnerAuthorityRefusal.SCOPE_REQUIRED), result)
    }
    assertEquals(existing.id, serviceClients.find(owned.pod.id, existing.clientId)?.id)
  }

  @Test
  fun `a management authority dies when the app is disconnected`() {
    val owned = Owned()
    val manager = manager(owned)
    assertIs<PodServiceClientManagementResult.Done<*>>(management.list(owned.pod, manager))

    consentDecisionStore.recordWithoutLifetime(owned.pod.id, clientId, owned.webId)
    assertIs<PodServiceClientManagementResult.Done<*>>(management.list(owned.pod, manager), "another consent removes nothing")

    consentDecisionStore.recordDisconnect(owned.pod.id, clientId, owned.webId)

    assertEquals(
      PodServiceClientManagementResult.Unauthorized(PodOwnerAuthorityRefusal.AUTHORITY_WITHDRAWN),
      management.list(owned.pod, manager),
    )
  }

  @Test
  fun `a management authority recognised only through an alias is the owner's`() {
    val owned = Owned()
    val alias = "https://id.test/oidc/${randomId()}"

    val manager = manager(owned, webId = alias, subjectUris = setOf(alias, owned.webId))

    assertIs<PodServiceClientManagementResult.Done<*>>(management.list(owned.pod, manager))
    assertEquals(
      PodServiceClientManagementResult.Unauthorized(PodOwnerAuthorityRefusal.NOT_OWNER),
      management.list(owned.pod, manager(owned, webId = alias, subjectUris = setOf(alias))),
    )
  }

  @Test
  fun `removing a grant narrows and never needs the consent`() {
    val owned = Owned()
    val installed = serviceClients.registerProvisional(owned.pod, "notes", emptyList()).registration
    serviceClients.replaceScopes(owned.pod, installed.clientId, installed.id, 0L, setOf(owned.readScope), changedBy = owned.webId)

    val result = management.removeGrants(owned.pod, manager(owned), installed.clientId, setOf(owned.readScope))

    assertEquals(emptySet(), assertIs<PodServiceClientManagementResult.Done<*>>(result).let {
      (it.value as org.sempods.pods.oauth.serviceclients.ServiceClientRegistration).scopes
    })
  }

  @Test
  fun `a service consent opened and confirmed grants what was ticked, once`() {
    val owned = Owned()
    val installed = serviceClients.registerProvisional(owned.pod, "notes", emptyList()).registration

    val screen = assertIs<PodServiceConsentResult.Screen>(openServiceConsent(owned, installed.clientId)).screen
    assertEquals("notes", screen.clientName)
    assertEquals(installed.createdAt, screen.registeredAt)
    assertEquals(installed.pendingUntil, screen.activationExpiresAt)

    val form = PodServiceConsentForm(screen.csrfToken, null, listOf(owned.readScope), null, null, null)
    val answered = assertIs<PodServiceConsentResult.Answered>(consents.submit(owned.pod, form, owned.session))
    assertEquals(PodServiceConsentOutcome.CONFIRMED, answered.outcome)
    val stored = assertNotNull(serviceClients.find(owned.pod.id, installed.clientId))
    assertEquals(setOf(owned.readScope), stored.scopes)
    assertNull(stored.pendingUntil, "confirming activates")

    assertEquals(
      PodServiceConsentResult.Refused(PodServiceConsentRefusal.FORM_EXPIRED),
      consents.submit(owned.pod, form, owned.session),
    )
  }

  @Test
  fun `an approval stops counting once the pod changes hands`() {
    val owned = Owned()
    val installed = serviceClients.registerProvisional(owned.pod, "notes", emptyList()).registration
    val screen = assertIs<PodServiceConsentResult.Screen>(openServiceConsent(owned, installed.clientId)).screen

    // The session's person, no longer the owner: the approval is measured against now.
    val transferred = owned.pod.copy(ref = owned.pod.ref.copy(owner = "https://id.test/new-owner"))
    val answered = consents.submit(
      transferred,
      PodServiceConsentForm(screen.csrfToken, null, listOf(owned.readScope), null, null, null),
      owned.session,
    )

    assertEquals(PodServiceConsentResult.Refused(PodServiceConsentRefusal.NOT_OWNER), answered)
    assertEquals(emptySet(), serviceClients.find(owned.pod.id, installed.clientId)?.scopes)
  }

  @Test
  fun `an operator-provisioned client is not this consent's`() {
    val owned = Owned()
    val provisioned = serviceClients.register(owned.pod, "backend", emptySet()).registration

    val opened = openServiceConsent(owned, provisioned.clientId)

    assertEquals(PodServiceConsentResult.Refused(PodServiceConsentRefusal.UNKNOWN_SERVICE), opened)
    assertNull(serviceClients.find(owned.pod.id, provisioned.clientId)?.scopes?.firstOrNull())
  }
}
