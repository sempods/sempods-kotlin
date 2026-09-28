package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.PodFacade
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.GrantReplacement
import org.sempods.pods.grants.PodContextPermissionResolver
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A service's grants replaced through [PodGrantsFacade.replaceGrants]: an optimistic write against
 * every other path that changes them, bound to the registration it was prepared for, and followed by
 * a check against contexts deleted meanwhile.
 *
 * The other paths run as they do in production: the context deletion through
 * [PodFacade.removeContext], the approval through [PodServiceClientGrantFlow], the removal through
 * [PodServiceClientManagement].
 */
internal class ServiceGrantReplaceTest : PodBrowserFlowTest() {

  @Inject
  private lateinit var serviceClients: PodServiceClientStore

  @Inject
  private lateinit var management: PodServiceClientManagement

  @Inject
  private lateinit var provisioning: PodServiceClientProvisioning

  @Inject
  private lateinit var podFacade: PodFacade

  @Inject
  private lateinit var tokens: PodTokenExchange

  // ── a replace prepared before another write changed the grants ────────────

  @Test
  fun `a replace prepared before a context deletion writes nothing and reports a conflict`() {
    val owned = Owned()
    val doomed = context(owned, "doomed")
    val service = serviceClients.register(owned.pod, "notes-app", setOf("$doomed#read")).registration

    podFacade.removeContext(owned.pod.name, URI(doomed))

    assertConflict(owned, service, expectedScopes = emptySet())
  }

  @Test
  fun `a replace prepared before an approval at the grant consent writes nothing and reports a conflict`() {
    val owned = Owned()
    val service = serviceClients.registerInstallation(owned.pod, "notes").registration
    val screen = assertIs<PodServiceClientGrantResult.Screen>(openGrant(owned, service.clientId, owned.readScope)).screen

    assertIs<PodServiceClientGrantResult.Granted>(
      serviceClientGrants.submit(
        owned.pod,
        PodServiceClientGrantForm(screen.csrfToken, service.clientId, listOf(owned.readScope), "grant"),
        owned.session,
      ),
    )

    assertConflict(owned, service, expectedScopes = setOf(owned.readScope))
  }

  @Test
  fun `a replace prepared before an owner's removal writes nothing and reports a conflict`() {
    val owned = Owned()
    val notes = context(owned, "notes")
    val installed = serviceClients.registerInstallation(owned.pod, "notes").registration
    serviceClients.addScopes(owned.pod, installed.clientId, installed.id, setOf(owned.readScope, "$notes#read"), owned.webId)
    val prepared = current(owned, installed.clientId)

    assertIs<PodServiceClientManagementResult.Done<*>>(
      management.removeGrants(owned.pod, manager(owned), installed.clientId, setOf(owned.readScope)),
    )

    assertConflict(owned, prepared, expectedScopes = setOf("$notes#read"))
  }

  // ── bound to the registration it was prepared for ─────────────────────────

  @Test
  fun `a replace for a registration re-created under the same clientId reports not found`() {
    // Operator provisioning re-mints under a new id when the scopes drift, which is the one path
    // that re-creates a registration under a name somebody may already hold a replace for.
    val owned = Owned()
    val sandbox = "${sempodsUriBuilder.buildContext(owned.pod.name, "apps/notes-app")}#manage"
    val first = assertIs<PodServiceClientResult.Provisioned>(
      provisioning.provision(owned.pod, PodServiceClientRequest("notes-app", setOf(sandbox), null, null)),
    ).registration
    val second = assertIs<PodServiceClientResult.Provisioned>(
      provisioning.provision(owned.pod, PodServiceClientRequest("notes-app", emptySet(), null, first.id.value)),
    ).registration

    val answer = replace(owned, first, setOf(owned.readScope))

    assertEquals(GrantReplacement.NotFound, answer)
    val stored = current(owned, "notes-app")
    assertEquals(second.id, stored.id)
    assertEquals(emptySet(), stored.scopes, "the new registration keeps what it was created with")
    assertEquals(0L, stored.grantsVersion)
  }

  // ── contexts deleted while the replace runs ───────────────────────────────

  @Test
  fun `a context deleted before the write keeps no grant, and the answer says so`() {
    // The order only the check after the write can catch: both of the deletion's strips ran before
    // the grant existed, and the caller prepared its selection while the context still stood.
    val owned = Owned()
    val gone = context(owned, "gone")
    val service = serviceClients.registerInstallation(owned.pod, "notes").registration
    podFacade.removeContext(owned.pod.name, URI(gone))

    val answer = assertIs<GrantReplacement.Replaced>(replace(owned, service, setOf("$gone#read", owned.readScope)))

    assertEquals(setOf(owned.readScope), answer.granted)
    assertEquals(setOf(owned.readScope), current(owned, service.clientId).scopes)
  }

  @Test
  fun `a context deleted after the write keeps no grant either`() {
    val owned = Owned()
    val later = context(owned, "later")
    val service = serviceClients.registerInstallation(owned.pod, "notes").registration

    assertIs<GrantReplacement.Replaced>(replace(owned, service, setOf("$later#write")))
    podFacade.removeContext(owned.pod.name, URI(later))

    assertEquals(emptySet(), current(owned, service.clientId).scopes)
  }

  @Test
  fun `the check after the write touches only the registration that wrote`() {
    // Between the write and the check, the registration is replaced under the same clientId by one
    // that holds a grant on the deleted context. The check drops it from the registration it wrote,
    // which is gone, and leaves the new one alone.
    val owned = Owned()
    val gone = context(owned, "gone")
    podFacade.removeContext(owned.pod.name, URI(gone))
    val service = serviceClients.register(owned.pod, "notes-app", emptySet()).registration
    val contexts = spyk(injector.getInstance(PodContextsDao::class.java))
    every { contexts.fetchByPod(any()) } answers {
      serviceClients.remove(owned.pod.id, "notes-app", service.id)
      serviceClients.register(owned.pod, "notes-app", setOf("$gone#read"))
      callOriginal()
    }

    val answer = facadeReading(contexts).replaceGrants(
      owned.pod,
      GrantRecipient.Service(service.id, service.clientId, service.grantsVersion),
      setOf("$gone#read", owned.readScope),
      grantedBy = owned.webId,
    )

    assertEquals(GrantReplacement.Replaced(setOf(owned.readScope)), answer)
    val recreated = current(owned, "notes-app")
    assertEquals(setOf("$gone#read"), recreated.scopes, "the registration that did not write is untouched")
    assertEquals(0L, recreated.grantsVersion)
  }

  // ── what a replace writes ─────────────────────────────────────────────────

  @Test
  fun `a replace moves the version once and records who made it`() {
    val owned = Owned()
    val service = serviceClients.registerInstallation(owned.pod, "notes").registration

    assertEquals(GrantReplacement.Replaced(setOf(owned.readScope)), replace(owned, service, setOf(owned.readScope)))

    assertEquals(1L, current(owned, service.clientId).grantsVersion)
  }

  @Test
  fun `an empty replace keeps the registration and mints nothing`() {
    val owned = Owned()
    val registered = serviceClients.register(owned.pod, "notes-app", setOf(owned.readScope))

    assertEquals(GrantReplacement.Replaced(emptySet()), replace(owned, registered.registration, emptySet()))

    assertEquals(emptySet(), current(owned, "notes-app").scopes)
    val refused = assertIs<PodTokenResult.Refused>(
      tokens.exchangeServiceClient(owned.pod.id, owned.pod.name, "notes-app", registered.secret, requestedScope = null),
    )
    assertEquals(OAuthErrorCode.INVALID_SCOPE, refused.code)
  }

  @Test
  fun `a selection a service cannot hold is refused before anything is written`() {
    val owned = Owned()
    val service = serviceClients.registerInstallation(owned.pod, "notes").registration

    assertThrows<IllegalArgumentException> { replace(owned, service, setOf("public-read")) }

    assertEquals(0L, current(owned, service.clientId).grantsVersion)
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private fun replace(owned: Owned, prepared: ServiceClientRegistration, selection: Set<String>): GrantReplacement =
    podGrantsFacade.replaceGrants(
      owned.pod,
      GrantRecipient.Service(prepared.id, prepared.clientId, prepared.grantsVersion),
      selection,
      grantedBy = owned.webId,
    )

  /** A replace prepared at [prepared]'s version is refused, and leaves [expectedScopes] standing. */
  private fun assertConflict(owned: Owned, prepared: ServiceClientRegistration, expectedScopes: Set<String>) {
    val before = current(owned, prepared.clientId)
    assertTrue(before.grantsVersion > prepared.grantsVersion, "the other write moved the version")

    assertEquals(GrantReplacement.Conflict, replace(owned, prepared, setOf(owned.readScope, "${owned.contextUri}#write")))

    val after = current(owned, prepared.clientId)
    assertEquals(expectedScopes, after.scopes)
    assertEquals(before.grantsVersion, after.grantsVersion, "a refused replace writes nothing")
  }

  private fun current(owned: Owned, clientId: String): ServiceClientRegistration =
    assertNotNull(serviceClients.find(owned.pod.id, clientId))

  /** A registered context on [owned]'s pod, answered as the IRI a grant names. */
  private fun context(owned: Owned, path: String): String {
    val uri = sempodsUriBuilder.buildContext(owned.pod.name, path)
    podFacade.createContext(owned.pod, uri, createdBy = owned.webId)
    return uri.toString()
  }

  /** The facade as production wires it, except for the registry it reads. */
  private fun facadeReading(contexts: PodContextsDao) = PodGrantsFacade(
    podWebIdGrantsDao = injector.getInstance(PodWebIdGrantsDao::class.java),
    podGrantsDao = injector.getInstance(PodGrantsDao::class.java),
    podContextsDao = contexts,
    podServiceClientStore = serviceClients,
    podScopeValidator = injector.getInstance(PodScopeValidator::class.java),
    podContextPermissionResolver = injector.getInstance(PodContextPermissionResolver::class.java),
    refreshTokenStore = injector.getInstance(PodRefreshTokenStore::class.java),
    webIdUriDeriver = injector.getInstance(WebIdUriDeriver::class.java),
  )
}
