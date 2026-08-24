package org.sempods.pods.grants.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.sempods.SempodsIntegrationTest
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PodGrantsDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podGrantsDao: PodGrantsDao

  @Inject
  private lateinit var db: MongoDatabase

  @Test
  fun `delete-by-pod should remove only grants from given pod`() {
    val pod1 = sempodsTestFactory.newPod()
    val pod2 = sempodsTestFactory.newPod()

    val appId1 = "did:web:client-1.example"
    val appId2 = "did:web:client-2.example"
    val webId1 = "https://id.sempods.org/e/owner-1"
    val webId2 = "https://id.sempods.org/e/owner-2"

    podGrantsDao.addGrants(
      podId = pod1.id!!,
      appId = appId1,
      webId = webId1,
      grants = listOf("scope:a", "scope:b"),
      grantedBy = webId1,
    )
    podGrantsDao.addGrants(
      podId = pod2.id!!,
      appId = appId2,
      webId = webId2,
      grants = listOf("scope:x"),
      grantedBy = webId2,
    )

    assertEquals(setOf("scope:a", "scope:b"), podGrantsDao.fetchGrantStrings(pod1.id, appId1, listOf(webId1)))
    assertEquals(setOf("scope:x"), podGrantsDao.fetchGrantStrings(pod2.id, appId2, listOf(webId2)))

    val deletedCount = podGrantsDao.deleteByPod(pod1.id)
    assertEquals(2L, deletedCount)

    assertEquals(emptySet(), podGrantsDao.fetchGrantStrings(pod1.id, appId1, listOf(webId1)))
    assertEquals(setOf("scope:x"), podGrantsDao.fetchGrantStrings(pod2.id, appId2, listOf(webId2)))
  }

  @Test
  fun `addGrants and fetchGrantStrings should store and return per-user grants`() {
    val pod = sempodsTestFactory.newPod()
    val appId = "did:web:focus.sempods.org"
    val webId = "https://id.sempods.org/e/abc123"

    podGrantsDao.addGrants(
      podId = pod.id!!,
      appId = appId,
      webId = webId,
      grants = listOf("scope:read", "scope:write"),
      grantedBy = webId,
    )

    val result = podGrantsDao.fetchGrantStrings(pod.id, appId, listOf(webId))
    assertEquals(setOf("scope:read", "scope:write"), result)
  }

  @Test
  fun `fetchGrantStrings should match any webId from the provided list (also_known_as)`() {
    val pod = sempodsTestFactory.newPod()
    val appId = "did:web:focus.sempods.org"
    val primaryWebId = "https://id.sempods.org/e/primary"
    val aliasWebId = "urn:sempods:e:alias"

    podGrantsDao.addGrants(
      podId = pod.id!!,
      appId = appId,
      webId = primaryWebId,
      grants = listOf("scope:manage"),
      grantedBy = null,
    )

    // Lookup with alias list that includes the actual stored webId
    val result = podGrantsDao.fetchGrantStrings(pod.id, appId, listOf(aliasWebId, primaryWebId))
    assertEquals(setOf("scope:manage"), result)
  }

  @Test
  fun `fetchGrantStrings should return empty set when no matching grant exists`() {
    val pod = sempodsTestFactory.newPod()
    val appId = "did:web:focus.sempods.org"
    val webId = "https://id.sempods.org/e/unknown"

    val result = podGrantsDao.fetchGrantStrings(pod.id!!, appId, listOf(webId))
    assertEquals(emptySet(), result)
  }

  @Test
  fun `addGrants should be idempotent`() {
    val pod = sempodsTestFactory.newPod()
    val appId = "did:web:focus.sempods.org"
    val webId = "https://id.sempods.org/e/idempotent"

    repeat(3) {
      podGrantsDao.addGrants(
        podId = pod.id!!,
        appId = appId,
        webId = webId,
        grants = listOf("scope:read"),
        grantedBy = null,
      )
    }

    assertEquals(setOf("scope:read"), podGrantsDao.fetchGrantStrings(pod.id!!, appId, listOf(webId)))
  }

  @Test
  fun `fetchGrantsForSubject returns rows across apps with appId populated`() {
    val pod = sempodsTestFactory.newPod()
    val appId1 = "did:web:app-one.example"
    val appId2 = "did:web:app-two.example"
    val webId = "https://id.sempods.org/e/subject"

    podGrantsDao.addGrants(pod.id!!, appId1, webId, listOf("scope:read"), grantedBy = null)
    podGrantsDao.addGrants(pod.id!!, appId2, webId, listOf("scope:write"), grantedBy = null)

    val rows = podGrantsDao.fetchGrantsForSubject(pod.id!!, listOf(webId))

    assertEquals(2, rows.size)
    assertEquals(setOf(appId1, appId2), rows.map { it.appId }.toSet())
    assertEquals(setOf("scope:read", "scope:write"), rows.map { it.scope }.toSet())
  }

  @Test
  fun `fetchGrantsForSubject matches a recorded subjectUris alias, not just the primary webId`() {
    val pod = sempodsTestFactory.newPod()
    val appId = "did:web:focus.sempods.org"
    val primaryWebId = "https://id.sempods.org/e/primary-alias-case"
    val aliasWebId = "https://acme.example/people/jane"

    podGrantsDao.addGrants(
      podId = pod.id!!,
      appId = appId,
      webId = primaryWebId,
      grants = listOf("scope:read"),
      subjectUris = listOf(primaryWebId, aliasWebId),
      grantedBy = null,
    )

    // The owner-level grant may have been written under the alias — the cascade starts from that
    // URI and must still find this row.
    val rows = podGrantsDao.fetchGrantsForSubject(pod.id!!, listOf(aliasWebId))
    assertEquals(1, rows.size)
    assertEquals(primaryWebId, rows.single().webId)
  }

  @Test
  fun `fetchGrantsForSubject finds legacy rows without subjectUris via webId`() {
    val pod = sempodsTestFactory.newPod()
    val appId = "did:web:focus.sempods.org"
    val webId = "https://id.sempods.org/e/legacy"

    // No subjectUris — the shape of every row written before the field existed.
    podGrantsDao.addGrants(pod.id!!, appId, webId, listOf("scope:read"), grantedBy = null)

    val rows = podGrantsDao.fetchGrantsForSubject(pod.id!!, listOf(webId))
    assertEquals(1, rows.size)
    assertEquals(null, rows.single().subjectUris)
  }

  @Test
  fun `deleteGrants removes only the listed grants of one app`() {
    val pod = sempodsTestFactory.newPod()
    val appId = "did:web:focus.sempods.org"
    val otherAppId = "did:web:other.example"
    val webId = "https://id.sempods.org/e/partial"

    podGrantsDao.addGrants(pod.id!!, appId, webId, listOf("scope:read", "scope:write"), grantedBy = null)
    podGrantsDao.addGrants(pod.id!!, otherAppId, webId, listOf("scope:write"), grantedBy = null)

    assertEquals(1L, podGrantsDao.deleteGrants(pod.id!!, appId, webId, listOf("scope:write")))

    assertEquals(setOf("scope:read"), podGrantsDao.fetchGrantStrings(pod.id!!, appId, listOf(webId)))
    // The other app's identically-named grant is untouched.
    assertEquals(setOf("scope:write"), podGrantsDao.fetchGrantStrings(pod.id!!, otherAppId, listOf(webId)))
  }

  @Test
  fun `replaceGrants records the current subjectUris set`() {
    val pod = sempodsTestFactory.newPod()
    val appId = "did:web:focus.sempods.org"
    val webId = "https://id.sempods.org/e/re-consent"
    val alias = "urn:sempods:e:re-consent"

    podGrantsDao.addGrants(pod.id!!, appId, webId, listOf("scope:read"), grantedBy = null)
    podGrantsDao.replaceGrants(
      podId = pod.id!!,
      appId = appId,
      webId = webId,
      grants = listOf("scope:read", "scope:write"),
      subjectUris = listOf(webId, alias),
      grantedBy = webId,
    )

    val rows = podGrantsDao.fetchGrantsForSubject(pod.id!!, listOf(alias))
    assertEquals(setOf("scope:read", "scope:write"), rows.map { it.scope }.toSet())
  }

  /**
   * What an upserted grant row looks like on disk.
   *
   * **This collection is the exception to the field-order rule the rest of the schema follows.**
   * Every other collection is written by an encoder, so a row comes out in the DBO's declaration
   * order. These rows are written by an upsert, and the server composes the inserted document out
   * of the query's equality fields in an order it picks: repeating the loop below by hand against a
   * local server produced `_id, webId, podId, scope, grantedAt`, `_id, podId, scope, webId,
   * grantedAt` and `_id, scope, webId, podId, grantedAt` for one and the same command, varying only
   * with the values. The rows already on disk are therefore laid out inconsistently among
   * themselves, and there is no single layout for a new row to match.
   *
   * So the assertion is the half that must hold — the field *set* — over enough rounds that a stray
   * field would show up. The instability itself is deliberately not asserted: a test that demands
   * non-determinism fails the day it happens not to see it.
   */
  @Test
  fun `an upserted row carries the same field set however the server orders it`() {
    val collection = db.getCollection(OWN_COLLECTION)
    val grantsDao = PodGrantsDao(db, OWN_COLLECTION)

    val fieldSets = (1..8).map {
      collection.drop()
      // A fresh pod id per round is the only thing that varies — the shape of the command does not.
      grantsDao.addGrants(
        podId = ObjectId(),
        appId = "notes-app",
        webId = "https://id.sempods.org/e/abc",
        grants = listOf("https://sempods.org/alice/events#read"),
        subjectUris = null,
        grantedBy = null,
      )
      rawRow(collection).keys.toSet()
    }

    assertEquals(
      listOf(setOf("_id", "appId", "podId", "webId", "scope", "grantedAt")),
      fieldSets.distinct(),
      "the field set is what a row is, and it does not move",
    )
  }

  @Test
  fun `an upsert without an alias set writes no subjectUris field at all`() {
    // `putNotNull`, on the write path where getting it wrong is cheapest to miss: an empty list
    // stored as `[]` would make `fetchGrantsForSubject`'s alias branch match rows it must not.
    val collection = db.getCollection(OWN_COLLECTION)
    collection.drop()
    val grantsDao = PodGrantsDao(db, OWN_COLLECTION)

    grantsDao.addGrants(
      podId = ObjectId(),
      appId = "notes-app",
      webId = "https://id.sempods.org/e/abc",
      grants = listOf("https://sempods.org/alice/events#read"),
      subjectUris = emptyList(),
      grantedBy = null,
    )

    val raw = rawRow(collection)
    assertFalse(raw.containsKey(PodGrantDboFields.subjectUris), raw.toJson())
    assertFalse(raw.containsKey(PodGrantDboFields.grantedBy), raw.toJson())
  }

  private fun rawRow(collection: com.mongodb.client.MongoCollection<Document>): Document =
    collection.find(Filters.eq(PodGrantDboFields.scope, "https://sempods.org/alice/events#read")).single()

  private companion object {

    /**
     * A collection of this test's own for the two wire-shape assertions: they read whole documents
     * back, which means something only where nothing else writes.
     */
    const val OWN_COLLECTION = "test.grants.wireshape"
  }
}
