package org.sempods.pods.grants.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.sempods.SempodsCollections
import org.sempods.SempodsIntegrationTest
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodWebIdGrantsDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podWebIdGrantsDao: PodWebIdGrantsDao

  @Inject
  private lateinit var db: MongoDatabase


  @Test
  fun `addGrants and fetchGrantStrings store and return app-independent grants`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/abc123"
    val ctx = "https://pod.test/${pod.name}/reports"

    podWebIdGrantsDao.addGrants(
      podId = pod.id!!,
      webId = webId,
      grants = listOf("$ctx#read", "$ctx#write"),
      grantedBy = "https://id.sempods.org/e/owner",
    )

    assertEquals(
      setOf("$ctx#read", "$ctx#write"),
      podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(webId)),
    )
  }

  @Test
  fun `fetchGrantStrings returns empty set for a WebID without grants`() {
    val pod = sempodsTestFactory.newPod()
    assertEquals(emptySet(), podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf("https://id.sempods.org/e/stranger")))
  }

  @Test
  fun `fetchGrantStrings matches any of the provided WebIDs (also_known_as)`() {
    val pod = sempodsTestFactory.newPod()
    val primaryWebId = "https://id.sempods.org/e/primary"
    val aliasWebId = "urn:sempods:e:primary"
    val ctx = "https://pod.test/${pod.name}/shared"

    podWebIdGrantsDao.addGrants(pod.id!!, primaryWebId, listOf("$ctx#read"), null)

    // Authenticated under the alias, but the list includes the granted primary URI.
    assertEquals(setOf("$ctx#read"), podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(aliasWebId, primaryWebId)))
  }

  @Test
  fun `addGrants is idempotent per grant`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/idem"
    val ctx = "https://pod.test/${pod.name}/notes"

    repeat(3) {
      podWebIdGrantsDao.addGrants(
        podId = pod.id!!,
        webId = webId,
        grants = listOf("$ctx#read"),
        grantedBy = null,
      )
    }

    assertEquals(setOf("$ctx#read"), podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(webId)))
  }

  @Test
  fun `fetchGrants carries provenance that fetchGrantStrings drops`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/provenance"
    val owner = "https://id.sempods.org/e/owner"
    val ctx = "https://pod.test/${pod.name}/reports"

    podWebIdGrantsDao.addGrants(pod.id!!, webId, listOf("$ctx#read"), owner)

    val rows = podWebIdGrantsDao.fetchGrants(pod.id!!, listOf(webId))
    assertEquals(1, rows.size)
    assertEquals("$ctx#read", rows.single().scope)
    assertEquals(owner, rows.single().grantedBy)
  }

  @Test
  fun `replaceGrants makes the submitted set authoritative and reports what it revoked`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/replace"
    val ctx = "https://pod.test/${pod.name}/tasks"

    podWebIdGrantsDao.addGrants(pod.id!!, webId, listOf("$ctx#read", "$ctx#write"), null)

    // Narrow to read-only: `#write` must go, `#read` must survive as the same row.
    val revoked = podWebIdGrantsDao.replaceGrants(pod.id!!, webId, listOf("$ctx#read"), null)

    assertEquals(1L, revoked)
    assertEquals(setOf("$ctx#read"), podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(webId)))
  }

  @Test
  fun `replaceGrants adds newly listed grants`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/widen"
    val ctx = "https://pod.test/${pod.name}/tasks"

    podWebIdGrantsDao.addGrants(pod.id!!, webId, listOf("$ctx#read"), null)

    assertEquals(0L, podWebIdGrantsDao.replaceGrants(pod.id!!, webId, listOf("$ctx#read", "$ctx#write"), null))
    assertEquals(
      setOf("$ctx#read", "$ctx#write"),
      podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(webId)),
    )
  }

  @Test
  fun `deleteGrants removes only the listed grants`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/partial"
    val ctx = "https://pod.test/${pod.name}/tasks"
    val other = "https://pod.test/${pod.name}/notes"

    podWebIdGrantsDao.addGrants(pod.id!!, webId, listOf("$ctx#read", "$ctx#write", "$other#read"), null)

    assertEquals(1L, podWebIdGrantsDao.deleteGrants(pod.id!!, webId, listOf("$ctx#write")))
    assertEquals(
      setOf("$ctx#read", "$other#read"),
      podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(webId)),
    )
  }

  @Test
  fun `deleteByWebId spares other WebIDs on the same pod`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/leaving"
    val otherWebId = "https://id.sempods.org/e/staying"
    val ctx = "https://pod.test/${pod.name}/tasks"

    podWebIdGrantsDao.addGrants(pod.id!!, webId, listOf("$ctx#read", "$ctx#write"), null)
    podWebIdGrantsDao.addGrants(pod.id!!, otherWebId, listOf("$ctx#read"), null)

    assertEquals(2L, podWebIdGrantsDao.deleteByWebId(pod.id!!, webId))

    assertEquals(emptySet(), podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(webId)))
    assertEquals(setOf("$ctx#read"), podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(otherWebId)))
  }

  @Test
  fun `deleteByContext removes the context's grants but spares a prefix-overlapping sibling`() {
    val pod = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/cascade"
    val ctx = "https://pod.test/${pod.name}/tasks"
    val sibling = "https://pod.test/${pod.name}/tasks-private"

    podWebIdGrantsDao.addGrants(
      podId = pod.id!!,
      webId = webId,
      grants = listOf("$ctx#read", "$ctx#write", "$ctx#manage", "$sibling#read"),
      grantedBy = null,
    )

    val deleted = podWebIdGrantsDao.deleteByContext(pod.id!!, ctx)
    assertEquals(3L, deleted)

    // The sibling that merely shares a string prefix must be untouched (slash-delimited isolation).
    assertEquals(setOf("$sibling#read"), podWebIdGrantsDao.fetchGrantStrings(pod.id!!, listOf(webId)))
  }

  @Test
  fun `deleteByPod removes only the given pod's grants`() {
    val pod1 = sempodsTestFactory.newPod()
    val pod2 = sempodsTestFactory.newPod()
    val webId = "https://id.sempods.org/e/multi"

    podWebIdGrantsDao.addGrants(pod1.id!!, webId, listOf("https://pod.test/a#read"), null)
    podWebIdGrantsDao.addGrants(pod2.id!!, webId, listOf("https://pod.test/b#read"), null)

    assertEquals(1L, podWebIdGrantsDao.deleteByPod(pod1.id!!))

    assertFalse(podWebIdGrantsDao.anyForPod(pod1.id!!))
    assertTrue(podWebIdGrantsDao.anyForPod(pod2.id!!))
  }

  /**
   * What an upserted WebID-grant row looks like on disk.
   *
   * The sibling of `PodGrantsDaoTest`'s wire-shape assertion, and for the same reason: no encoder
   * writes this collection either, so the server picks the field order of an inserted row and picks
   * it differently from row to row. The field *set* is what holds, and `grantedBy` must be absent
   * rather than null when nobody recorded one — the provenance surfaces read "no value" off the
   * field's absence.
   */
  @Test
  fun `an upserted row carries the same field set, and no grantedBy when none was recorded`() {
    val podId = ObjectId()
    val webId = "https://id.sempods.org/e/abc"
    val scope = "https://sempods.org/alice/events#read"

    podWebIdGrantsDao.addGrants(podId, webId, listOf(scope), grantedBy = null)

    val raw = db.getCollection(SempodsCollections.WEB_ID_GRANTS)
      .find(Filters.eq(PodWebIdGrantDboFields.podId, podId))
      .single()
    assertEquals(
      setOf(
        PodWebIdGrantDboFields.id,
        PodWebIdGrantDboFields.podId,
        PodWebIdGrantDboFields.webId,
        PodWebIdGrantDboFields.scope,
        PodWebIdGrantDboFields.grantedAt,
      ),
      raw.keys.toSet(),
      raw.toJson(),
    )
    assertFalse(raw.containsKey(PodWebIdGrantDboFields.grantedBy), raw.toJson())
  }
}
