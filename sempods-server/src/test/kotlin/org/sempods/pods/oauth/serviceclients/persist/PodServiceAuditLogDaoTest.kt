package org.sempods.pods.oauth.serviceclients.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsIntegrationTest
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [PodServiceAuditLogDao] records, orders and sweeps.
 *
 * **Runs on a collection this test owns**, named by [TEST_COLLECTION] and dropped before each test.
 * The server's own audit log is never read and never written here: it holds the real request
 * history, and a test that had to empty it to know what it was looking at would be both destructive
 * and order-dependent.
 */
class PodServiceAuditLogDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private lateinit var auditLogDao: PodServiceAuditLogDao

  /** Two pod ids — the second one is how "scoped to this pod" gets asserted. */
  private val probePodId = ObjectId()
  private val otherPodId = ObjectId()

  /**
   * The fixtures are anchored here rather than to a calendar date, and that is not a style choice:
   * the collection now carries a TTL index, so a row whose deadline has passed is one the server's
   * reaper may remove between the insert and the read. Fixed dates put every fixture in the past
   * the moment they age past [RETENTION_DAYS], and each assertion below is about a row that has to
   * still be there. The one deliberate exception is the pre-retention row, which carries no
   * deadline at all and is therefore invisible to the reaper whatever its date.
   *
   * Truncated to milliseconds so the round trip is the identity function — see
   * `docs/persistence.md`, and `putInstant`, which truncates on the way in.
   */
  private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  /**
   * Dropped rather than cleared: the collection holds nothing but fixtures, and the DAO built right
   * after it recreates all three indexes in its constructor.
   */
  @BeforeEach
  fun setUpOwnCollection() {
    db.getCollection(TEST_COLLECTION).drop()
    auditLogDao = PodServiceAuditLogDao(db, TEST_COLLECTION, RETENTION_DAYS)
  }

  @Test
  fun `findRecent answers this pod's rows, newest first`() {
    val newest = now.minusSeconds(60)
    record(probePodId, "GET", now.minusSeconds(3600), clientId = "notes-app")
    val newer = record(probePodId, "PUT", newest, clientId = "reader-app")
    record(otherPodId, "DELETE", now)

    val rows = auditLogDao.findRecent(probePodId)
    assertEquals(listOf("reader-app", "notes-app"), rows.map { it.clientId }, "newest first, and only this pod")

    val read = rows.first()
    assertEquals(newer.id, read.id)
    assertEquals(newest, read.ts)
    assertEquals(probePodId, read.podId)
    assertEquals("PUT", read.operation)
    assertEquals("/pods/demo/data?context=events", read.path)
    // Nothing writes this field yet; reading it as 0 instead of null is the mistake the
    // two-argument `getInteger` overload makes, and it would be the normal case, not an edge.
    assertNull(read.statusCode)
    // Every row the DAO writes carries one — that is what the TTL index reaps by.
    assertEquals(newest.plus(RETENTION_DAYS, ChronoUnit.DAYS), read.expiresAt, "ts + RETENTION_DAYS")

    assertEquals(listOf("reader-app"), auditLogDao.findRecent(probePodId, limit = 1).map { it.clientId })
  }

  @Test
  fun `record returns the id it stored under, and carries a status code when one is given`() {
    val stored = record(probePodId, "PUT", now)
    assertNotNull(stored.id, "record must return the id it stored under, not the null it took")
    assertEquals(stored.id, auditLogDao.findRecent(probePodId).single().id)

    // The path no stored row takes yet, and therefore the one nothing else would catch: the
    // follow-up response filter (TODO in the DBO) will start writing this field.
    auditLogDao.record(
      PodServiceAuditLogDbo(
        ts = now,
        podId = otherPodId,
        clientId = "notes-app",
        operation = "GET",
        path = "/pods/demo/data",
        statusCode = 204,
      ),
    )
    assertEquals(204, auditLogDao.findRecent(otherPodId).single().statusCode)
  }

  @Test
  fun `record stamps the expiry from the row's own ts, over whatever the caller passed`() {
    // The retention is a property of the collection, not of a call site: a caller that could hand
    // in its own anchor could write a row that outlives every other one.
    val stored = auditLogDao.record(
      PodServiceAuditLogDbo(
        ts = now,
        podId = probePodId,
        clientId = "notes-app",
        operation = "GET",
        path = "/pods/demo/data",
        // Far enough out that a row keeping it would sit in the collection for a century — which
        // is the failure this asserts against, not a plausible caller.
        expiresAt = now.plus(36500, ChronoUnit.DAYS),
      ),
    )
    val expected = now.plus(RETENTION_DAYS, ChronoUnit.DAYS)
    assertEquals(expected, stored.expiresAt, "record returns the anchor it stored, not the one it took")
    assertEquals(expected, auditLogDao.findRecent(probePodId).single().expiresAt)
  }

  @Test
  fun `a row written before the retention existed carries no expiry, and the reaper cannot see it`() {
    // The 100,140 rows already on the live host. A TTL index only reaps documents that carry the
    // field, so these are kept until an operator backfills them — see
    // `docs/auth/service-clients.md`. Written past the DAO on purpose: nothing in the
    // codebase can produce this row any more, which is exactly why the read path has to.
    db.getCollection(TEST_COLLECTION).insertOne(
      Document()
        .append(PodServiceAuditLogDboFields.ts, Date.from(Instant.parse("2026-06-11T08:00:00Z")))
        .append(PodServiceAuditLogDboFields.podId, probePodId)
        .append(PodServiceAuditLogDboFields.clientId, "legacy-app")
        .append(PodServiceAuditLogDboFields.operation, "GET")
        .append(PodServiceAuditLogDboFields.path, "/pods/demo/data"),
    )

    assertNull(auditLogDao.findRecent(probePodId).single().expiresAt)
  }

  @Test
  fun `the TTL index is on expiresAt, with the stored instant as the deadline itself`() {
    val ttlIndex = db.getCollection(TEST_COLLECTION).listIndexes()
      .firstOrNull { (it["key"] as Document).containsKey(PodServiceAuditLogDboFields.expiresAt) }
    assertNotNull(ttlIndex, "expected a TTL index on expiresAt")
    // Zero, not the retention: the offset lives in the row, so a retention change never touches
    // the index. `RefreshTokenStore` and `OneTimeStore` read the same way.
    assertEquals(0L, (ttlIndex["expireAfterSeconds"] as Number).toLong())

    // And the two the collection has always carried are still plain, still unnamed by us.
    val names = db.getCollection(TEST_COLLECTION).listIndexes().map { it.getString("name") }.toSet()
    assertTrue("podId_1_ts_1" in names && "podId_1_clientId_1_ts_1" in names, "indexes: $names")
  }

  @Test
  fun `deleteByPod removes exactly the pod's rows`() {
    // The pod-deletion cascade in `SempodsFacade`.
    record(probePodId, "GET", now.minusSeconds(3600))
    record(probePodId, "PUT", now.minusSeconds(1800))
    record(otherPodId, "DELETE", now)

    assertEquals(2L, auditLogDao.deleteByPod(probePodId), "deleteMany, not deleteOne")
    assertEquals(emptyList(), auditLogDao.findRecent(probePodId))
    assertEquals(listOf("DELETE"), auditLogDao.findRecent(otherPodId).map { it.operation })

    assertEquals(0L, auditLogDao.deleteByPod(probePodId), "a repeated cascade is a no-op, not an error")
  }

  private fun record(
    podId: ObjectId,
    operation: String,
    ts: Instant,
    clientId: String = "notes-app",
  ): PodServiceAuditLogDbo = auditLogDao.record(
    PodServiceAuditLogDbo(
      ts = ts,
      podId = podId,
      clientId = clientId,
      operation = operation,
      path = if (operation == "PUT") "/pods/demo/data?context=events" else "/pods/demo/data",
    ),
  )

  private companion object {

    /** This test's own collection, outside the `sempods.` namespace the server addresses. */
    const val TEST_COLLECTION = "test.podServiceAuditLog.dao"

    /**
     * Deliberately not the shipped 90: an expiry two days past `ts` can only come from the
     * retention this DAO was constructed with, where 90 would also match a default read from
     * somewhere else. Short is safe only because every fixture is anchored at [now] — two days
     * behind a *fixed* date is two days in the past, and the reaper would be free to take the
     * rows out from under the assertions.
     */
    const val RETENTION_DAYS = 2L
  }
}
