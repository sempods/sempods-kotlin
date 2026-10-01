package org.sempods.pods.grants

import org.sempods.pods.contexts.persist.PodContextDbo
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.spi.PodAccessToken
import org.sempods.pods.oauth.spi.SERVICE_CLIENT_TYPE
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDbo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Docker-free unit test of the consolidated slash-delimited `<root>#manage` semantics
 * (`SPS-GRANT-007` (sempods-spec)). Covers manage-root coverage and
 * manage-cascade expansion, including the sibling-prefix isolation rule that a raw
 * `startsWith` would violate (`tasks#manage` must not reach `tasks-private`).
 */
class PodContextPermissionResolverTest {

  private val podId = ObjectId()
  private val podBaseUrl = "https://pods.test/mypod/"

  private val validator = PodScopeValidator()
  private val contextsDao = mockk<PodContextsDao>()
  private val grantsDao = mockk<PodGrantsDao>(relaxed = true)
  private val serviceClientDao = mockk<PodServiceClientDao>(relaxed = true)
  private val resolver = PodContextPermissionResolver(contextsDao, grantsDao, serviceClientDao, validator)

  private val podDbo = mockk<PodDbo> { every { id } returns podId }

  private fun ctx(path: String) = "${podBaseUrl}$path"
  private fun registered(vararg paths: String) {
    every { contextsDao.fetchByPod(podId) } returns paths.map {
      PodContextDbo(podId = podId, contextUri = ctx(it), label = null, description = null, createdBy = null)
    }
  }

  // --- isCoveredByManageScope -------------------------------------------------------------

  @Test
  fun `manage root covers itself and slash-delimited descendants`() {
    val scopes = setOf("${ctx("tasks")}#manage")
    assertTrue(resolver.isCoveredByManageScope(scopes, podBaseUrl, URI(ctx("tasks"))))
    assertTrue(resolver.isCoveredByManageScope(scopes, podBaseUrl, URI(ctx("tasks/today"))))
  }

  @Test
  fun `a manage root above the namespace covers nothing, even if it reached the store`() {
    // The end-to-end half of the validator rule: coverage is decided by routing every scope string
    // through `PodScopeValidator`, so a wildcard that somehow got persisted — an older row, a hand
    // edit — still cannot act. The rule is enforced where it is read, not only where it is written.
    for (root in listOf(
      "${podBaseUrl.trimEnd('/')}/_system/contexts",
      "${podBaseUrl.trimEnd('/')}/_system",
      podBaseUrl.trimEnd('/'),
    )) {
      assertFalse(
        resolver.isCoveredByManageScope(setOf("$root#manage"), podBaseUrl, URI(ctx("tasks"))),
        "'$root#manage' must not cover an ordinary context",
      )
    }
  }

  @Test
  fun `manage root does not cross sibling-prefix boundary`() {
    val scopes = setOf("${ctx("tasks")}#manage")
    assertFalse(resolver.isCoveredByManageScope(scopes, podBaseUrl, URI(ctx("tasks-private"))))
  }

  @Test
  fun `write or read scope does not grant manage coverage`() {
    assertFalse(
      resolver.isCoveredByManageScope(setOf("${ctx("tasks")}#write"), podBaseUrl, URI(ctx("tasks")))
    )
    assertFalse(
      resolver.isCoveredByManageScope(setOf("${ctx("tasks")}#read"), podBaseUrl, URI(ctx("tasks")))
    )
    assertFalse(resolver.isCoveredByManageScope(emptySet(), podBaseUrl, URI(ctx("tasks"))))
  }

  // --- expandManageCascade ----------------------------------------------------------------

  @Test
  fun `no manage scope returns input unchanged without a DAO round-trip`() {
    val scopes = setOf("${ctx("tasks")}#write", "public-read")
    val result = resolver.expandManageCascade(scopes, podId, podBaseUrl)
    assertTrue(result == scopes)
    verify(exactly = 0) { contextsDao.fetchByPod(any()) }
  }

  @Test
  fun `manage scope expands to registered descendants across all permissions`() {
    registered("tasks", "tasks/today", "tasks-private")
    val result = resolver.expandManageCascade(setOf("${ctx("tasks")}#manage"), podId, podBaseUrl)

    for (perm in listOf("read", "write", "manage")) {
      assertTrue("${ctx("tasks")}#$perm" in result, "expected tasks#$perm")
      assertTrue("${ctx("tasks/today")}#$perm" in result, "expected tasks/today#$perm")
      // sibling-prefix context must NOT be expanded
      assertFalse("${ctx("tasks-private")}#$perm" in result, "tasks-private must stay out")
    }
  }

  @Test
  fun `manage scope without matching registered contexts returns input unchanged`() {
    registered("notes", "notes/pinned")
    val scopes = setOf("${ctx("tasks")}#manage")
    val result = resolver.expandManageCascade(scopes, podId, podBaseUrl)
    assertTrue(result == scopes)
  }

  // --- describeEffectivePermissions ------------------------------------------------------

  @Test
  fun `the owner's registry authority is reported as manage alone`() {
    val effective = resolver.describeEffectivePermissions(
      effectiveScopes = emptySet(),
      rawScopes = emptySet(),
      visibleContexts = emptySet(),
      podBaseUrl = podBaseUrl,
      registryContexts = listOf(ctx("notes"), ctx("diary")),
    )

    assertEquals(listOf(ctx("diary"), ctx("notes")), effective.byContext.keys.toList())
    effective.byContext.values.forEach {
      assertEquals(listOf("manage"), it.permissions, "no read or write is implied: ${it.contextUri}")
      assertEquals(ContextPermissionSource.OWNER, it.source)
    }
    assertEquals(emptyList(), effective.writableContexts)
  }

  // --- resolve: one entry, the token's grant type names the recipient ---------------------

  @Test
  fun `a service token and a user token of the same client read different stores, once each`() {
    // The grant type decides, never the `client_id`: one registration may later carry both kinds
    // of access (#325), and a person's delegation must not leak into the service's tokens.
    registered()
    every { serviceClientDao.findByClientId(podId, "svc:notes") } returns PodServiceClientDbo(
      id = ObjectId(),
      podId = podId,
      clientId = "svc:notes",
      secretHash = "hash",
      scopes = setOf("${ctx("service")}#read"),
    )
    every { grantsDao.fetchGrantStrings(podId, "svc:notes", listOf("https://id.test/e/person")) } returns
      setOf("${ctx("person")}#read")

    val asService = resolver.resolve(podId, token(sub = null, clientType = SERVICE_CLIENT_TYPE), podBaseUrl)
    val asPerson = resolver.resolve(podId, token(sub = "https://id.test/e/person", clientType = null), podBaseUrl)

    assertEquals(setOf(URI(ctx("service"))), asService.contexts)
    assertEquals(setOf(URI(ctx("person"))), asPerson.contexts)
    verify(exactly = 1) { serviceClientDao.findByClientId(podId, "svc:notes") }
    verify(exactly = 1) { grantsDao.fetchGrantStrings(podId, "svc:notes", listOf("https://id.test/e/person")) }
  }

  @Test
  fun `a service token whose registration is gone resolves nothing`() {
    every { serviceClientDao.findByClientId(podId, "svc:notes") } returns null

    val resolved = resolver.resolve(podId, token(sub = null, clientType = SERVICE_CLIENT_TYPE), podBaseUrl)

    assertEquals(emptySet(), resolved.contexts)
  }

  private fun token(sub: String?, clientType: String?) = PodAccessToken(
    clientId = "svc:notes",
    sub = sub,
    clientType = clientType,
    scopeValues = emptySet(),
    jti = null,
    issuedAt = null,
  )
}
