package org.sempods.pods.contexts

import org.junit.jupiter.api.Test
import org.sempods.api.pod.system.auth.buildAuthorizationServerMetadata
import org.sempods.api.pod.system.auth.buildProtectedResourceMetadata
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.ContextPermissionEntry
import org.sempods.pods.grants.ContextPermissionSource
import org.sempods.pods.grants.EffectiveContextPermissions
import org.sempods.pods.grants.GRANTS_MANAGE_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.mongo.persist.PodDbo
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a pod advertises with the context-management module and without it.
 *
 * The suite's HTTP server runs the sempods composition, which installs the module, so the pod
 * without it is pinned here, below the HTTP surface: the scopes a request may name, what a service's
 * management authority carries, the two metadata documents, and the permissions every registry
 * representation reports.
 */
class ContextManagementSupportTest {

  private val with = ContextManagementSupport(Optional.of(object : ContextManagementInstalled {}))

  private val without = ContextManagementSupport(Optional.empty())

  private val pod = PodDbo(name = "alice", owner = "https://id.example/alice")

  @Test
  fun `with the module contexts manage is requestable and part of the management authority`() {
    assertTrue(with.offered)
    assertEquals(setOf(SERVICE_CLIENTS_MANAGE_SCOPE, CONTEXTS_MANAGE_SCOPE), with.requestablePrivilegedScopes)
    assertEquals(setOf(CONTEXTS_MANAGE_SCOPE, GRANTS_MANAGE_SCOPE), with.serviceManagementScopes)
    assertTrue(CONTEXTS_MANAGE_SCOPE in with.scopesSupported)
  }

  @Test
  fun `without the module contexts manage is neither requestable nor part of the management authority`() {
    assertFalse(without.offered)
    assertEquals(setOf(SERVICE_CLIENTS_MANAGE_SCOPE), without.requestablePrivilegedScopes)
    assertEquals(setOf(GRANTS_MANAGE_SCOPE), without.serviceManagementScopes)
    assertFalse(CONTEXTS_MANAGE_SCOPE in without.scopesSupported)
    assertTrue(SERVICE_CLIENTS_MANAGE_SCOPE in without.scopesSupported, "the other privileged scope stays")
  }

  @Test
  fun `the metadata documents follow the module`() {
    fun authorizationServer(offered: ContextManagementSupport) =
      buildAuthorizationServerMetadata(pod, "https://pods.example/", offered).entity as Map<*, *>
    fun protectedResource(offered: ContextManagementSupport) =
      buildProtectedResourceMetadata(pod, "https://pods.example/", publicContextsCount = 0, offered = offered).entity as Map<*, *>

    assertEquals(listOf(CONTEXTS_MANAGE_SCOPE, GRANTS_MANAGE_SCOPE), authorizationServer(with)["sempods_service_management_scopes"])
    assertEquals(listOf(GRANTS_MANAGE_SCOPE), authorizationServer(without)["sempods_service_management_scopes"])
    for (document in listOf(authorizationServer(without), protectedResource(without))) {
      assertFalse(CONTEXTS_MANAGE_SCOPE in (document["scopes_supported"] as List<*>), document.toString())
    }
    for (document in listOf(authorizationServer(with), protectedResource(with))) {
      assertTrue(CONTEXTS_MANAGE_SCOPE in (document["scopes_supported"] as List<*>), document.toString())
    }
  }

  @Test
  fun `without the module no representation reports manage, and a manage grant still reads and writes`() {
    val notes = "https://pods.example/alice/_system/contexts/notes"
    val resolved = EffectiveContextPermissions(
      byContext = mapOf(notes to ContextPermissionEntry(notes, listOf("read", "write", "manage"), ContextPermissionSource.MANAGE)),
      writableContexts = listOf(notes),
    )

    assertEquals(resolved, with.shown(resolved))
    val shown = without.shown(resolved)
    assertEquals(ContextPermissionEntry(notes, listOf("read", "write"), ContextPermissionSource.MANAGE), shown.byContext[notes])
    assertEquals(listOf(notes), shown.writableContexts)
  }
}
