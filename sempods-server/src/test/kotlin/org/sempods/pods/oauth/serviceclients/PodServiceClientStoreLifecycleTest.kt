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
    val first = store.registerInstallation(pod.hosted, "first")
    val second = store.registerInstallation(pod.hosted, "second")
    store.registerInstallation(other.hosted, "elsewhere")
    store.touchLastUsed(pod.hosted.id, second.registration.clientId)

    val listed = store.list(pod.hosted.id)

    assertEquals(listOf(first.registration.clientId, second.registration.clientId), listed.map { it.clientId })
    assertNull(listed[0].lastUsedAt)
    assertNotNull(listed[1].lastUsedAt)
    assertTrue(listed.all { it.installed })
  }

  @Test
  fun `grants are added to the registration named, and removing the last one keeps it`() {
    val pod = sempodsTestFactory.newPod()
    val notes = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/notes"
    val installed = store.registerInstallation(pod.hosted, "notes").registration

    assertTrue(store.addScopes(pod.hosted, installed.clientId, installed.id, setOf("$notes#read", "$notes#write")))
    assertEquals(setOf("$notes#read", "$notes#write"), store.find(pod.hosted.id, installed.clientId)?.scopes)

    val remaining = store.removeScopes(pod.hosted.id, installed.clientId, setOf("$notes#read", "$notes#write"))
    assertEquals(emptySet(), remaining?.scopes, "the registration outlives its last grant")
    assertNotNull(store.find(pod.hosted.id, installed.clientId))
    assertNull(store.removeScopes(pod.hosted.id, "svc:absent", setOf("$notes#read")))
  }

  @Test
  fun `an approval for one registration does not land on another under the same name`() {
    val pod = sempodsTestFactory.newPod()
    val notes = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/notes"
    val original = store.register(pod.hosted, "reused", emptySet()).registration
    store.remove(pod.hosted.id, "reused", original.id)
    store.register(pod.hosted, "reused", emptySet())

    assertFalse(store.addScopes(pod.hosted, "reused", original.id, setOf("$notes#read")))
    assertEquals(emptySet(), store.find(pod.hosted.id, "reused")?.scopes)
  }

  @Test
  fun `a grant a service client cannot hold is refused before anything is written`() {
    val pod = sempodsTestFactory.newPod()
    val installed = store.registerInstallation(pod.hosted, "notes").registration

    assertThrows<IllegalArgumentException> {
      store.addScopes(pod.hosted, installed.clientId, installed.id, setOf("public-read"))
    }
    assertEquals(emptySet(), store.find(pod.hosted.id, installed.clientId)?.scopes)
  }

  @Test
  fun `a rotation keeps the registration and replaces the secret`() {
    val pod = sempodsTestFactory.newPod()
    val installed = store.registerInstallation(pod.hosted, "notes")

    val rotated = store.rotateSecret(pod.hosted.id, installed.registration.clientId)

    rotated as PodServiceClientStore.SecretRotation.Rotated
    assertEquals(installed.registration.id, rotated.registration.id)
    assertNull(store.authenticate(pod.hosted.id, installed.registration.clientId, installed.secret))
    assertNotNull(store.authenticate(pod.hosted.id, installed.registration.clientId, rotated.secret))
    assertEquals(PodServiceClientStore.SecretRotation.NotFound, store.rotateSecret(pod.hosted.id, "svc:absent"))
  }
}
