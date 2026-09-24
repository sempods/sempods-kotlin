package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_INSTALL_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PodManagementAuthorityStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.junit.jupiter.api.Test
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The owner-management operations and the grant consent, decided without HTTP: what each
 * authority reaches, and what a redemption asks again.
 */
internal class PodServiceClientManagementTest : PodBrowserFlowTest() {

  @Inject
  private lateinit var management: PodServiceClientManagement

  @Inject
  private lateinit var grants: PodServiceClientGrantFlow

  @Inject
  private lateinit var authorities: PodManagementAuthorityStore

  @Inject
  private lateinit var serviceClients: PodServiceClientStore

  @Test
  fun `an installer bearer reaches no existing registration`() {
    val owned = Owned()
    val existing = serviceClients.registerInstallation(owned.pod, "backup").registration

    val installer = SempodsCredentials(
      pod = owned.pod.ref,
      restrictedContexts = emptySet(),
      oauthClientId = clientId,
      oauthScopes = setOf(SERVICE_CLIENTS_INSTALL_SCOPE),
      tokenJti = randomId(),
      tokenSub = owned.webId,
    )

    for (result in listOf(
      management.list(owned.pod, installer),
      management.rotate(owned.pod, installer, existing.clientId),
      management.revoke(owned.pod, installer, existing.clientId),
    )) {
      assertEquals(PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.SCOPE_REQUIRED), result)
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
      PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.AUTHORITY_WITHDRAWN),
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
      PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_OWNER),
      management.list(owned.pod, manager(owned, webId = alias, subjectUris = setOf(alias))),
    )
  }

  @Test
  fun `removing a grant narrows and never needs the consent`() {
    val owned = Owned()
    val installed = serviceClients.registerInstallation(owned.pod, "notes").registration
    serviceClients.addScopes(owned.pod, installed.clientId, installed.id, setOf(owned.readScope))

    val result = management.removeGrants(owned.pod, manager(owned), installed.clientId, setOf(owned.readScope))

    assertEquals(emptySet(), assertIs<PodServiceClientManagementResult.Done<*>>(result).let {
      (it.value as org.sempods.pods.oauth.serviceclients.ServiceClientRegistration).scopes
    })
  }

  @Test
  fun `a grant consent opened and answered grants what was ticked, once`() {
    val owned = Owned()
    val installed = serviceClients.registerInstallation(owned.pod, "notes").registration

    val screen = assertIs<PodServiceClientGrantResult.Screen>(open(owned, installed.clientId, owned.readScope)).screen
    assertEquals("notes", screen.serviceLabel)
    assertEquals(installed.createdAt, screen.registeredAt)

    val form = PodServiceClientGrantForm(screen.csrfToken, installed.clientId, listOf(owned.readScope), "grant")
    val granted = assertIs<PodServiceClientGrantResult.Granted>(grants.submit(owned.pod, form, owned.session))
    assertEquals(setOf(owned.readScope), granted.scopes)
    assertEquals(setOf(owned.readScope), serviceClients.find(owned.pod.id, installed.clientId)?.scopes)

    assertEquals(
      PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.FORM_EXPIRED),
      grants.submit(owned.pod, form, owned.session),
    )
  }

  @Test
  fun `an approval stops counting once the pod changes hands`() {
    val owned = Owned()
    val installed = serviceClients.registerInstallation(owned.pod, "notes").registration
    val screen = assertIs<PodServiceClientGrantResult.Screen>(open(owned, installed.clientId, owned.readScope)).screen

    // The session's person, no longer the owner: the grant is measured against now.
    val transferred = owned.pod.copy(ref = owned.pod.ref.copy(owner = "https://id.test/new-owner"))
    val answered = grants.submit(
      transferred,
      PodServiceClientGrantForm(screen.csrfToken, installed.clientId, listOf(owned.readScope), "grant"),
      owned.session,
    )

    val delivery = assertIs<OAuthErrorDelivery.Redirect>(assertIs<PodServiceClientGrantResult.Error>(answered).delivery)
    assertEquals(OAuthErrorCode.ACCESS_DENIED, delivery.code)
    assertEquals(emptySet(), serviceClients.find(owned.pod.id, installed.clientId)?.scopes)
  }

  @Test
  fun `an operator-provisioned client is not this consent's`() {
    val owned = Owned()
    val provisioned = serviceClients.register(owned.pod, "backend", emptySet()).registration

    val opened = open(owned, provisioned.clientId, owned.readScope)

    assertIs<PodServiceClientGrantResult.Error>(opened)
    assertNull(serviceClients.find(owned.pod.id, provisioned.clientId)?.scopes?.firstOrNull())
  }

  private fun open(owned: Owned, serviceClient: String, scope: String): PodServiceClientGrantResult =
    grants.open(
      owned.pod,
      PodServiceClientGrantRequest(clientId, redirectUri, "s", serviceClient, scope),
      owned.session,
    )

  private fun manager(
    owned: Owned,
    webId: String = owned.webId,
    subjectUris: Set<String> = setOf(webId),
  ): SempodsCredentials {
    val jti = randomId()
    val disconnects = consentDecisionStore.recordWithoutLifetime(owned.pod.id, clientId, webId).disconnects
    authorities.record(owned.pod.id, jti, clientId, webId, disconnects, subjectUris)
    return SempodsCredentials(
      pod = owned.pod.ref,
      restrictedContexts = emptySet(),
      oauthClientId = clientId,
      oauthScopes = setOf(SERVICE_CLIENTS_MANAGE_SCOPE),
      tokenJti = jti,
      tokenSub = webId,
    )
  }
}
