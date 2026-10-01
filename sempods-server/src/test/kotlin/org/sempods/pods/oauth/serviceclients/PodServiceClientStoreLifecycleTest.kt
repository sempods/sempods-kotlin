package org.sempods.pods.oauth.serviceclients

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [PodServiceClientStore] does to a registration that already exists: list it, grant it,
 * take grants away, give it a new secret.
 */
class PodServiceClientStoreLifecycleTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var store: PodServiceClientStore

  @Test
  fun `a list is this pod's registrations, oldest first, with when each was last used`() {
    val pod = sempodsTestFactory.newPod()
    val other = sempodsTestFactory.newPod()
    val first = store.registerProvisional(pod.hosted, "first", emptyList())
    val second = store.registerProvisional(pod.hosted, "second", emptyList())
    store.registerProvisional(other.hosted, "elsewhere", emptyList())
    store.touchLastUsed(pod.hosted.id, second.registration.clientId)

    val listed = store.list(pod.hosted.id)

    assertEquals(listOf(first.registration.clientId, second.registration.clientId), listed.map { it.clientId })
    assertNull(listed[0].lastUsedAt)
    assertNotNull(listed[1].lastUsedAt)
    assertTrue(listed.all { it.registered })
  }

  @Test
  fun `a registration the owner's authority makes is active and holds nothing`() {
    val pod = sempodsTestFactory.newPod()

    val active = store.registerService(pod.hosted, "notes", emptyList(), provisional = false).registration

    assertTrue(active.registered)
    assertNull(active.pendingUntil)
    assertEquals(emptySet(), active.scopes)
  }

  @Test
  fun `grants are replaced on the registration named, and replacing them with none keeps it`() {
    val pod = sempodsTestFactory.newPod()
    val notes = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/notes"
    val service = store.registerProvisional(pod.hosted, "notes", emptyList()).registration

    assertEquals(
      PodServiceClientStore.ScopeReplacement.Replaced,
      store.replaceScopes(pod.hosted, service.clientId, service.id, 0L, setOf("$notes#read", "$notes#write"), changedBy = OWNER),
    )
    assertEquals(setOf("$notes#read", "$notes#write"), store.find(pod.hosted.id, service.clientId)?.scopes)

    assertEquals(
      PodServiceClientStore.ScopeReplacement.Replaced,
      store.replaceScopes(pod.hosted, service.clientId, service.id, 1L, emptySet(), changedBy = OWNER),
    )
    assertEquals(emptySet(), store.find(pod.hosted.id, service.clientId)?.scopes, "the registration outlives its last grant")
  }

  @Test
  fun `an approval for one registration does not land on another under the same name`() {
    val pod = sempodsTestFactory.newPod()
    val notes = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/notes"
    val original = store.register(pod.hosted, "reused", emptySet()).registration
    store.remove(pod.hosted.id, "reused", original.id)
    store.register(pod.hosted, "reused", emptySet())

    assertEquals(
      PodServiceClientStore.ScopeReplacement.NotFound,
      store.replaceScopes(pod.hosted, "reused", original.id, 0L, setOf("$notes#read"), changedBy = OWNER),
    )
    assertEquals(emptySet(), store.find(pod.hosted.id, "reused")?.scopes)
  }

  @Test
  fun `a grant a service client cannot hold is refused before anything is written`() {
    val pod = sempodsTestFactory.newPod()
    val service = store.registerProvisional(pod.hosted, "notes", emptyList()).registration

    assertThrows<IllegalArgumentException> {
      store.replaceScopes(pod.hosted, service.clientId, service.id, 0L, setOf("public-read"), changedBy = OWNER)
    }
    assertEquals(emptySet(), store.find(pod.hosted.id, service.clientId)?.scopes)
  }

  @Test
  fun `a rotation decided on one secret does not replace another issued meanwhile`() {
    val pod = sempodsTestFactory.newPod()
    val registered = store.registerProvisional(pod.hosted, "notes", emptyList()).registration
    val meanwhile = store.rotateSecret(pod.hosted.id, registered.clientId) as ServiceClientSecretRotation.Rotated

    assertEquals(
      ServiceClientSecretRotation.Conflict,
      store.rotateSecret(pod.hosted.id, registered.clientId, expectedSecretId = registered.secretId),
    )
    assertNotNull(store.authenticate(pod.hosted.id, meanwhile.registration.clientId, meanwhile.secret), "the one issued meanwhile stands")
  }

  @Test
  fun `a rotation keeps the registration and replaces the secret`() {
    val pod = sempodsTestFactory.newPod()
    val service = store.registerProvisional(pod.hosted, "notes", emptyList())

    val rotated = store.rotateSecret(pod.hosted.id, service.registration.clientId)

    rotated as ServiceClientSecretRotation.Rotated
    assertEquals(service.registration.id, rotated.registration.id)
    assertNull(store.authenticate(pod.hosted.id, service.registration.clientId, service.secret))
    assertNotNull(store.authenticate(pod.hosted.id, service.registration.clientId, rotated.secret))
    assertEquals(ServiceClientSecretRotation.NotFound, store.rotateSecret(pod.hosted.id, "svc:absent"))
  }

  private companion object {
    const val OWNER = "https://id.sempods.org/e/owner"
  }
}
