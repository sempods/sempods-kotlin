package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PrivilegedAuthorityRows
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration
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
  fun `a replace narrows, and an empty one keeps the registration`() {
    val owned = Owned()
    val notes = owned.context("notes")
    val installed = serviceClients.registerProvisional(owned.pod, "notes", emptyList()).registration
    serviceClients.replaceScopes(owned.pod, installed.clientId, installed.id, 0L, setOf(owned.readScope, "$notes#read"), changedBy = owned.webId)

    val narrowed = assertIs<PodServiceClientManagementResult.Done<ServiceClientRegistration>>(
      management.replaceGrants(owned.pod, manager(owned), installed.clientId, 1L, setOf("$notes#read")),
    ).value
    assertEquals(setOf("$notes#read"), narrowed.scopes)
    assertEquals(2L, narrowed.grantsVersion)

    val emptied = assertIs<PodServiceClientManagementResult.Done<ServiceClientRegistration>>(
      management.replaceGrants(owned.pod, manager(owned), installed.clientId, 2L, emptySet()),
    ).value
    assertEquals(emptySet(), emptied.scopes)
    assertEquals(installed.id, emptied.id)
  }

  @Test
  fun `a replace reaches an operator's registration, which rotation and removal do not`() {
    val owned = Owned()
    val sandbox = "${owned.context("apps/backup")}#manage"
    val provisioned = serviceClients.register(owned.pod, "backup", setOf(sandbox)).registration
    val manager = manager(owned)

    assertIs<PodServiceClientManagementResult.Done<*>>(
      management.replaceGrants(owned.pod, manager, provisioned.clientId, provisioned.grantsVersion, setOf(owned.readScope)),
    )
    assertEquals(setOf(owned.readScope), serviceClients.find(owned.pod.id, "backup")?.scopes)
    for (result in listOf(management.rotate(owned.pod, manager, "backup"), management.revoke(owned.pod, manager, "backup"))) {
      assertEquals(PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.PROVISIONED_BY_OPERATOR), result)
    }
  }

  @Test
  fun `a replace at a stale version writes nothing`() {
    val owned = Owned()
    val installed = serviceClients.registerProvisional(owned.pod, "notes", emptyList()).registration
    val manager = manager(owned)

    assertEquals(
      PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.VERSION_MISMATCH),
      management.replaceGrants(owned.pod, manager, installed.clientId, 7L, setOf(owned.readScope)),
    )
    val stored = assertNotNull(serviceClients.find(owned.pod.id, installed.clientId))
    assertEquals(emptySet(), stored.scopes)
    assertNotNull(stored.pendingUntil, "nothing activated it")
  }

  @Test
  fun `a replace is refused whole for a scope a service cannot hold or no context of the pod`() {
    val owned = Owned()
    val installed = serviceClients.registerProvisional(owned.pod, "notes", emptyList()).registration
    val namespace = owned.contextUri.substringBeforeLast('/')

    for (scope in listOf("public-read", "openid", "$namespace#manage", "${owned.pod.baseUrl}#manage", "$namespace/absent#read", " ${owned.readScope}")) {
      val result = management.replaceGrants(owned.pod, manager(owned), installed.clientId, 0L, setOf(owned.readScope, scope))
      assertEquals(PodServiceClientManagementRefusal.UNGRANTABLE, assertIs<PodServiceClientManagementResult.Refused>(result).reason, scope)
    }
    assertEquals(emptySet(), serviceClients.find(owned.pod.id, installed.clientId)?.scopes)
  }

  @Test
  fun `an authority approved under the first consent reads and removes, and neither assigns`() {
    val owned = Owned()
    val installed = serviceClients.registerProvisional(owned.pod, "notes", emptyList()).registration
    val earlier = manager(owned, consent = PrivilegedAuthorityRows.FIRST_CONSENT)

    assertIs<PodServiceClientManagementResult.Done<*>>(management.list(owned.pod, earlier))
    assertIs<PodServiceClientManagementResult.Done<*>>(management.get(owned.pod, earlier, installed.clientId))
    assertEquals(
      PodServiceClientManagementResult.Unauthorized(PodOwnerAuthorityRefusal.CONSENT_OUTDATED),
      management.replaceGrants(owned.pod, earlier, installed.clientId, 0L, setOf(owned.readScope)),
    )
    assertIs<PodServiceClientManagementResult.Done<*>>(management.revoke(owned.pod, earlier, installed.clientId))
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
  fun `a cancel by someone no longer the owner is not delivered to the service`() {
    val owned = Owned()
    val installed = serviceClients.registerProvisional(owned.pod, "notes", listOf("http://127.0.0.1/cb")).registration
    val screen = assertIs<PodServiceConsentResult.Screen>(
      consents.open(owned.pod, PodServiceConsentRequest(installed.clientId, "http://127.0.0.1/cb", "s"), owned.session),
    ).screen

    val transferred = owned.pod.copy(ref = owned.pod.ref.copy(owner = "https://id.test/new-owner"))
    val answered = consents.submit(transferred, PodServiceConsentForm(screen.csrfToken, null, null, null, null, "cancel"), owned.session)

    assertEquals(PodServiceConsentResult.Refused(PodServiceConsentRefusal.NOT_OWNER), answered)
  }

  @Test
  fun `an operator-provisioned client's grants are decided at this consent too`() {
    val owned = Owned()
    val sandbox = "${owned.context("apps/backend")}#manage"
    val provisioned = serviceClients.register(owned.pod, "backend", setOf(sandbox)).registration

    val answered = confirmServiceConsent(owned, provisioned.clientId, setOf(owned.readScope))

    assertEquals(PodServiceConsentOutcome.CONFIRMED, assertIs<PodServiceConsentResult.Answered>(answered).outcome)
    assertEquals(setOf(owned.readScope), serviceClients.find(owned.pod.id, provisioned.clientId)?.scopes)
  }

  @Test
  fun `an authority approved under the first consent may narrow a registered service and nothing more`() {
    val owned = Owned()
    val notes = owned.context("notes")
    val installed = serviceClients.registerProvisional(owned.pod, "notes", emptyList()).registration
    serviceClients.replaceScopes(owned.pod, installed.clientId, installed.id, 0L, setOf(owned.readScope, "$notes#read"), changedBy = owned.webId)
    val earlier = manager(owned, consent = PrivilegedAuthorityRows.FIRST_CONSENT)

    assertEquals(
      PodServiceClientManagementResult.Unauthorized(PodOwnerAuthorityRefusal.CONSENT_OUTDATED),
      management.replaceGrants(owned.pod, earlier, installed.clientId, 1L, setOf(owned.readScope, "$notes#write")),
      "widening is the new text's",
    )
    val narrowed = assertIs<PodServiceClientManagementResult.Done<ServiceClientRegistration>>(
      management.replaceGrants(owned.pod, earlier, installed.clientId, 1L, setOf("$notes#read")),
    ).value
    assertEquals(setOf("$notes#read"), narrowed.scopes)
    assertEquals(2L, narrowed.grantsVersion)
  }
}
