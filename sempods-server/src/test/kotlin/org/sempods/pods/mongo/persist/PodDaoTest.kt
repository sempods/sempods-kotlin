package org.sempods.pods.mongo.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.SempodsIntegrationTest
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [PodDao] stores and reads back.
 *
 * **The collection with the widest blast radius**: its `_id` is the `podId` every other collection
 * is keyed by, so a pod that stops resolving takes its contexts, grants, tokens and backups with it.
 *
 * Where a *legacy* shape matters, the fixture writes the document literally (see [legacyRow])
 * rather than arranging it through the DAO: on-disk shapes are worth stating.
 *
 * **Runs on a store of its own** ([SempodsIntegrationTest.ownStore]), because `fetchAll()` has no
 * pod scope to narrow by and the pod names here are fixed: "exactly alice and bobby" means
 * something only where nothing else writes.
 */
class PodDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private lateinit var podDao: PodDao

  /**
   * Derived rather than written out: [PodDao] rejects an owner that is not an email-based WebID at
   * the address this deployment mints them under, and that address is a configured value.
   */
  private val owner by lazy { webIdUriDeriver.deriveFromEmail("alice@example.org") }
  private val otherOwner by lazy { webIdUriDeriver.deriveFromEmail("bob@example.org") }

  private val collection = ownStore("pods")

  @BeforeEach
  fun setUpOwnCollection() {
    podDao = PodDao(db, webIdUriDeriver, collection)
  }


  @Test
  fun `a stored row reads back field for field`() {
    val alice = podDao.insert(name = "alice", owner = owner)
    podDao.setDisplayName(assertNotNull(alice.id), "Alice's pod")
    podDao.updateLastModifiedAt("alice", LAST_MODIFIED)
    podDao.insert(name = "bobby", owner = owner)

    val read = assertNotNull(podDao.fetchByName("alice"))
    assertEquals(alice.id, read.id, "the podId every other collection is keyed by must survive")
    assertEquals("alice", read.name)
    assertEquals(owner, read.owner)
    assertEquals("Alice's pod", read.displayName)
    assertEquals(LAST_MODIFIED, read.lastModifiedAt)
    assertEquals(0L, read.resourceRowCount, "a pod is registered with a baseline, not without one")

    assertEquals(read, podDao.fetchById(assertNotNull(alice.id)), "both lookups answer the same row")
    assertNull(podDao.fetchByName("nobody"))
    assertNull(podDao.fetchById(ObjectId()))

    // A pod that never carried a label: absent on the wire, `null` in the row, and not `""`.
    assertNull(assertNotNull(podDao.fetchByName("bobby")).displayName)

    assertEquals(setOf("alice", "bobby"), podDao.fetchAll().map { it.name }.toSet())
  }

  @Test
  fun `a row whose label was never written reads back as null`() {
    // The shape most rows on disk have: `displayName` absent rather than empty. Written as a
    // document so the absence is stated rather than arranged.
    val id = legacyRow(name = "alice")

    val read = assertNotNull(podDao.fetchByName("alice"))
    assertEquals(id, read.id)
    assertNull(read.displayName)
    assertEquals(LAST_MODIFIED, read.lastModifiedAt)
    // Absent, and read back as "unknown" rather than as zero: a row written before the count
    // existed says nothing about how many resource rows the pod has, and `0` would claim it does.
    assertNull(read.resourceRowCount)
  }

  @Test
  fun `the resource row count moves by increments and can be re-baselined`() {
    // What the backup sink and the recovery do to it. `$inc` because two replicas write the same
    // pod, `$set` because a recovery has counted the rows itself and its number wins.
    val id = legacyRow(name = "alice")

    podDao.bumpResourceRowCount(id, 3L)
    assertEquals(
      3L,
      assertNotNull(podDao.fetchByName("alice")).resourceRowCount,
      "an increment on an absent field starts from zero — below what the collection holds, which is the harmless direction",
    )

    podDao.bumpResourceRowCount(id, -1L)
    assertEquals(2L, assertNotNull(podDao.fetchByName("alice")).resourceRowCount)

    assertTrue(podDao.setResourceRowCount(id, expected = 2L, count = 50L), "a recovery's own count replaces it")
    assertEquals(50L, assertNotNull(podDao.fetchByName("alice")).resourceRowCount)
    assertEquals(1, podDao.fetchAll().size, "an update in place, not a second row")
  }

  @Test
  fun `the re-baseline gives way to a delta that landed after it read`() {
    // The recovery's number is a snapshot, and another replica may be writing the same pod while it
    // is taken. Guarding on the value that was read is what stops the snapshot from discarding a
    // delta — a discarded negative one would leave the count above the collection, which is the one
    // direction that raises a false alarm.
    val id = legacyRow(name = "alice")
    podDao.bumpResourceRowCount(id, 5L)

    assertFalse(
      podDao.setResourceRowCount(id, expected = 4L, count = 4L),
      "the count moved after the recovery read it, so the re-baseline must miss rather than win",
    )
    assertEquals(5L, assertNotNull(podDao.fetchByName("alice")).resourceRowCount, "the writer's count stands")

    // And the guard for a row that predates the field: an absent value, not a stored zero.
    val fresh = legacyRow(name = "bobby")
    assertTrue(podDao.setResourceRowCount(fresh, expected = null, count = 7L))
    assertEquals(7L, assertNotNull(podDao.fetchByName("bobby")).resourceRowCount)
    assertFalse(podDao.setResourceRowCount(fresh, expected = null, count = 9L), "it is no longer absent")
  }

  @Test
  fun `the baseline lifts a row that has none and one a stray delta created`() {
    // The two states a legacy pod can be in when its baseline is written. `$inc` creates the field,
    // so a write arriving mid-baseline leaves a small number rather than an absence — and leaving a
    // pod of fifty rows sitting at a count of one is the silent failure this whole count exists to
    // remove.
    val absent = legacyRow(name = "alice")
    assertTrue(podDao.raiseResourceRowCountTo(absent, 50L))
    assertEquals(50L, assertNotNull(podDao.fetchByName("alice")).resourceRowCount)

    val strayDelta = legacyRow(name = "bobby")
    podDao.bumpResourceRowCount(strayDelta, 1L)
    assertTrue(podDao.raiseResourceRowCountTo(strayDelta, 50L), "one is below fifty, so it is not a baseline")
    assertEquals(50L, assertNotNull(podDao.fetchByName("bobby")).resourceRowCount)
  }

  @Test
  fun `the baseline leaves a count another recovery already established`() {
    // A rolling deploy makes this ordinary: two replicas first-recover the same legacy pod, both
    // read the field as absent, both load the same rows. Adding on top would make the count twice
    // the collection and have the next recovery report the pod's whole history as missing.
    val id = legacyRow(name = "alice")
    assertTrue(podDao.raiseResourceRowCountTo(id, 50L), "the first replica establishes it")

    assertFalse(podDao.raiseResourceRowCountTo(id, 50L), "the second finds it already there and writes nothing")
    assertEquals(50L, assertNotNull(podDao.fetchByName("alice")).resourceRowCount)

    // Nor does it pull a higher count down — that is the shortfall branch's business, not this one.
    podDao.bumpResourceRowCount(id, 5L)
    assertFalse(podDao.raiseResourceRowCountTo(id, 50L))
    assertEquals(55L, assertNotNull(podDao.fetchByName("alice")).resourceRowCount)
  }

  @Test
  fun `the projected read answers the same instant as the full one`() {
    // `PodMetaEndpoint` answers a conditional GET from this field alone. The projection is the one
    // place a document arrives incomplete, so it reads the value directly rather than through the
    // row mapper — which would trip over every field the projection left out.
    legacyRow(name = "alice")

    assertEquals(LAST_MODIFIED, podDao.fetchLastModifiedAt("alice"))
    assertEquals(podDao.fetchByName("alice")?.lastModifiedAt, podDao.fetchLastModifiedAt("alice"))
    assertNull(podDao.fetchLastModifiedAt("nobody"))
  }

  @Test
  fun `the updates land in place rather than adding a row`() {
    // All three are single-field `$set` updates, and all three run `updateOne`.
    val id = legacyRow(name = "alice")

    podDao.setOwner(id, otherOwner)
    podDao.setDisplayName(id, "Alice's pod")
    podDao.updateLastModifiedAt("alice", LAST_MODIFIED.plusSeconds(60))

    val read = assertNotNull(podDao.fetchByName("alice"))
    assertEquals(id, read.id, "an update in place, not a second row")
    assertEquals(otherOwner, read.owner)
    assertEquals("Alice's pod", read.displayName)
    assertEquals(LAST_MODIFIED.plusSeconds(60), read.lastModifiedAt)
    assertEquals(1, podDao.fetchAll().size)

    // Clearing is a written empty string, not a removed field — the state the metadata endpoint
    // renders as "no label" without falling back to the one it had before.
    podDao.setDisplayName(id, "")
    assertEquals("", assertNotNull(podDao.fetchByName("alice")).displayName)
  }

  @Test
  fun `delete removes exactly the named pod`() {
    podDao.insert(name = "alice", owner = owner)
    podDao.insert(name = "bobby", owner = owner)

    assertTrue(podDao.delete("alice"))
    assertFalse(podDao.delete("alice"), "already gone is `false`, not an error")
    assertNull(podDao.fetchByName("alice"))
    assertEquals(listOf("bobby"), podDao.fetchAll().map { it.name })
  }

  /**
   * A row exactly as it sits on disk — `displayName` absent, which is the shape most rows have —
   * returning the `_id` it was stored under.
   *
   * `lastModifiedAt` is a BSON date, which is millisecond precision — there is nowhere to put the
   * nanoseconds, which is why [LAST_MODIFIED] has none.
   */
  private fun legacyRow(name: String): ObjectId {
    val id = ObjectId()
    db.getCollection(collection).insertOne(
      Document()
        .append(PodDboFields.id, id)
        .append(PodDboFields.name, name)
        .append(PodDboFields.owner, owner)
        .append(PodDboFields.lastModifiedAt, Date.from(LAST_MODIFIED)),
    )
    return id
  }

  private companion object {

    /** Millisecond-precise on purpose: BSON has nowhere to put the nanoseconds. */
    val LAST_MODIFIED: Instant = Instant.parse("2026-08-16T00:00:00Z")
  }
}
