package org.sempods.pods.mongo.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.sempods.SempodsCollections
import org.sempods.SempodsIntegrationTest
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [RdfResourceBackupDao] stores, reads and sweeps.
 *
 * The collection this covers is the pod's durable persistence — the MemoryStore is rebuilt from it
 * on every start — so these are not data-quality assertions but whether a pod comes back at all.
 *
 * **Isolated by its pod ids, on the ordinary collection.** The recovery reads have no narrower
 * scope than a pod id, and [probePodId] and [otherPodId] are fresh per test method, so that is
 * scope enough. What this suite writes it removes again in [removeOwnRows].
 */
class RdfResourceBackupDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  @Inject
  private lateinit var backupDao: RdfResourceBackupDao

  /** Two pod ids — the second one is how "scoped to this pod" gets asserted. */
  private val probePodId = ObjectId()
  private val otherPodId = ObjectId()

  private val resource = URI("https://sempods.org/alice/events/summer-party")
  private val otherResource = URI("https://sempods.org/alice/events/winter-party")
  private val contextA = URI("https://sempods.org/alice/events")
  private val contextB = URI("https://sempods.org/alice/notes")

  /** The pod-deletion cascade, used here as the cleanup it is: this suite's rows and no others. */
  @AfterEach
  fun removeOwnRows() {
    backupDao.deleteByPod(probePodId)
    backupDao.deleteByPod(otherPodId)
  }

  @Test
  fun `a stored row reads back field for field`() {
    backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"a\" <g> .\n", UPDATED_AT)
    backupDao.upsert(probePodId, resource, contextB, "<s> <p> \"b\" <g> .\n", UPDATED_AT)
    backupDao.upsert(probePodId, otherResource, contextA, "<s2> <p> \"c\" <g> .\n", UPDATED_AT)
    backupDao.upsert(otherPodId, resource, contextA, "<s3> <p> \"d\" <g> .\n", UPDATED_AT)

    val read = assertNotNull(backupDao.fetch(probePodId, resource, contextA))
    assertNotNull(read.id, "the insert must carry an id, not leave one for a later write to invent")
    assertEquals(probePodId, read.podId)
    assertEquals(resource.toString(), read.resourceUri)
    assertEquals(contextA.toString(), read.context)
    assertEquals("<s> <p> \"a\" <g> .\n", read.nquads)
    assertEquals(UPDATED_AT, read.updatedAt)

    // The two recovery reads — this is what a restart does.
    assertEquals(3, backupDao.fetchAllByPod(probePodId).size, "the pod's rows, and no other pod's")
    assertEquals(
      setOf(contextA.toString(), contextB.toString()),
      backupDao.fetchByPodAndResource(probePodId, resource).map { it.context }.toSet(),
    )
    assertEquals(1, backupDao.fetchAllByPod(otherPodId).size)
  }

  @Test
  fun `the upsert updates in place rather than adding a second row`() {
    // Hitting the unique index instead of the existing row would be the failure here.
    backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"old\" <g> .\n", UPDATED_AT)
    val first = assertNotNull(backupDao.fetch(probePodId, resource, contextA))

    backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"new\" <g> .\n", UPDATED_AT.plusSeconds(60))

    val rows = backupDao.fetchAllByPod(probePodId)
    assertEquals(1, rows.size, "an update, not a second row")
    assertEquals(first.id, rows.single().id)
    assertEquals("<s> <p> \"new\" <g> .\n", rows.single().nquads)
    assertEquals(UPDATED_AT.plusSeconds(60), rows.single().updatedAt)
  }

  @Test
  fun `an emptied resource is stored as an empty string, not as an absence`() {
    // The path a resource takes when its last statement in a context is removed. It is a value, and
    // the recovery read has to see a row that says "this context holds nothing" rather than no row.
    backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"a\" <g> .\n", UPDATED_AT)
    val before = assertNotNull(backupDao.fetch(probePodId, resource, contextA))

    backupDao.upsert(probePodId, resource, contextA, "", UPDATED_AT.plusSeconds(60))

    val emptied = assertNotNull(backupDao.fetch(probePodId, resource, contextA))
    assertEquals(before.id, emptied.id, "an update keeps the row's id — it must not insert a second one")
    assertEquals("", emptied.nquads)
    assertEquals(UPDATED_AT.plusSeconds(60), emptied.updatedAt)
  }

  @Test
  fun `delete and deleteByPod remove exactly their rows`() {
    backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"a\" <g> .\n", UPDATED_AT)
    backupDao.upsert(probePodId, resource, contextB, "<s> <p> \"b\" <g> .\n", UPDATED_AT)
    backupDao.upsert(otherPodId, resource, contextA, "<s3> <p> \"d\" <g> .\n", UPDATED_AT)

    backupDao.delete(probePodId, resource, contextA)
    assertNull(backupDao.fetch(probePodId, resource, contextA))
    assertNotNull(backupDao.fetch(probePodId, resource, contextB), "only the named context goes")

    // The pod-deletion cascade in `SempodsFacade`.
    assertEquals(1L, backupDao.deleteByPod(probePodId), "deleteMany, not deleteOne")
    assertEquals(emptyList(), backupDao.fetchAllByPod(probePodId))
    assertEquals(1, backupDao.fetchAllByPod(otherPodId).size, "another pod's backup is not swept")

    assertEquals(0L, backupDao.deleteByPod(probePodId), "a repeated cascade is a no-op, not an error")
  }

  @Test
  fun `the write answers whether a row appeared or vanished`() {
    // What the backup sink counts the pod's rows by. A replacement and a delete of something already
    // gone both move nothing, and saying so is the difference between a count that tracks the
    // collection and one that drifts every time a set is retried.
    assertTrue(backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"a\" <g> .\n", UPDATED_AT), "a new row")
    assertFalse(
      backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"b\" <g> .\n", UPDATED_AT),
      "the same key again is a replacement, not a second row",
    )

    assertTrue(backupDao.delete(probePodId, resource, contextA), "the row was there")
    assertFalse(backupDao.delete(probePodId, resource, contextA), "and now it is not")
    assertFalse(backupDao.delete(probePodId, otherResource, contextB), "a key that never existed")
  }

  @Test
  fun `a row re-created after it vanished under the write counts as new`() {
    // The upsert reads the row before it writes, and only a process-local lock stands between the
    // two — another replica can drop it in between. The answer comes off the write rather than off
    // that read, so a replace that turned into an insert says so instead of counting for nothing.
    backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"a\" <g> .\n", UPDATED_AT)
    val standing = assertNotNull(backupDao.fetch(probePodId, resource, contextA))

    // What the DAO would be holding at that moment, with the row gone underneath it.
    db.getCollection(SempodsCollections.RESOURCES).deleteOne(Filters.eq(RdfResourceBackupDboFields.id, standing.id))

    assertTrue(
      backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"b\" <g> .\n", UPDATED_AT),
      "the row is back because this call put it there, whatever the read before it saw",
    )
    assertEquals(1L, backupDao.countByPod(probePodId))
  }

  @Test
  fun `the row count is scoped to its pod and answers zero for one with nothing`() {
    // What a recovery re-checks a shortfall with, so it has to mean the same thing `fetchAllByPod`
    // would return the size of — for this pod, and for no other.
    backupDao.upsert(probePodId, resource, contextA, "<s> <p> \"a\" <g> .\n", UPDATED_AT)
    backupDao.upsert(probePodId, resource, contextB, "<s> <p> \"b\" <g> .\n", UPDATED_AT)
    backupDao.upsert(otherPodId, resource, contextA, "<s3> <p> \"d\" <g> .\n", UPDATED_AT)

    assertEquals(2L, backupDao.countByPod(probePodId))
    assertEquals(backupDao.fetchAllByPod(probePodId).size.toLong(), backupDao.countByPod(probePodId))
    assertEquals(1L, backupDao.countByPod(otherPodId))
    assertEquals(0L, backupDao.countByPod(ObjectId()), "a pod with nothing counts zero, it does not fail")
  }

  private companion object {

    /** Millisecond-precise on purpose: BSON has nowhere to put the nanoseconds. */
    val UPDATED_AT: Instant = Instant.parse("2026-08-16T10:15:30.123Z")
  }
}
