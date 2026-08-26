package org.sempods.pods.oauth.serviceclients.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import org.sempods.SempodsCollections
import org.sempods.SempodsConfig
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.bson.Document
import org.bson.types.ObjectId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Persistence for [PodServiceAuditLogDbo].
 *
 * **On the MongoDB driver, mapped by hand** — see `docs/persistence.md`. Every field
 * Morphia's `PojoCodec` wrote for this entity is written exactly as it wrote it — field order
 * included — which is pinned without a database by a wire-format test on the mapping side. No
 * stored row was rewritten, and a process on either side of the change reads what the other wrote.
 * `expiresAt` is the one field Morphia never wrote; it is appended last, and the same test pins
 * that a row without one carries no such field at all.
 *
 * **Retention-bounded** by a Mongo TTL index on the per-row [PodServiceAuditLogDbo.expiresAt],
 * which [record] computes at write time from [retentionDays]. The anchor being per row rather
 * than in the index is what makes a retention change a configuration change: the index never
 * moves, and only rows written afterwards are affected. Same shape as the hosted MCP service's
 * `AuditLogDao`, which this collection is the counterpart of.
 *
 * The narrowest DAO in the milestone: one filter field, one sort, no upsert, no update path, and
 * nothing optional that a query relies on.
 *
 * @param retentionDays how long a row is kept — [SempodsConfig.serviceAuditRetentionDays].
 */
class PodServiceAuditLogDao(
  db: MongoDatabase,
  collectionName: String,
  private val retentionDays: Long,
) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states, and the retention so that a test can state a short one without
   * waiting on the server's TTL reaper.
   */
  @Inject
  constructor(db: MongoDatabase, config: SempodsConfig) : this(
    db,
    SempodsCollections.OAUTH_SERVICE_AUDIT_LOG,
    config.serviceAuditRetentionDays,
  )

  private val auditLog = db.getCollection(collectionName)

  init {
    // The two indexes `@Indexes` declared, both plain ascending with every Morphia default — so no
    // `IndexOptions` on either, since any option set would differ from what is on disk.
    // `Indexes.ascending(...)` leaves the name to the server, which is what Morphia did too: the
    // running database carries `podId_1_ts_1` and `podId_1_clientId_1_ts_1`, measured, so these
    // calls are a no-op against it rather than an `IndexOptionsConflict` at boot.
    //
    // Morphia created them through the `ensureIndexes()` in `MorphiaDao`'s constructor. Nothing
    // else mapped this entity, so that already happened on the first construction of this DAO —
    // the moment does not move.
    auditLog.createIndex(
      Indexes.ascending(PodServiceAuditLogDboFields.podId, PodServiceAuditLogDboFields.ts),
    )
    auditLog.createIndex(
      Indexes.ascending(
        PodServiceAuditLogDboFields.podId,
        PodServiceAuditLogDboFields.clientId,
        PodServiceAuditLogDboFields.ts,
      ),
    )
    // The third is new, so its options are free — nothing on disk to match. `expireAfterSeconds = 0`
    // makes the stored `expiresAt` the deadline itself rather than an offset from it, the same
    // reading `RefreshTokenStore` and `OneTimeStore` rely on. The server names it `expiresAt_1`.
    //
    // **It reaps only rows that carry the field.** The 100,140 rows written before this existed
    // have no `expiresAt` and are invisible to the reaper — see
    // `docs/auth/service-clients.md` for the backfill that gives them one.
    auditLog.createIndex(
      Indexes.ascending(PodServiceAuditLogDboFields.expiresAt),
      IndexOptions().expireAfter(0, TimeUnit.SECONDS),
    )
  }

  /**
   * Appends [dbo] and returns it **carrying the id it was stored under**.
   *
   * The copy is not cosmetic. `datastore.save()` wrote the generated `_id` back into the instance
   * it was handed; `insertOne` does not, so a caller reading the id off the return value would get
   * the `null` it passed in. No caller does today — but that is a property of the callers, not of
   * this method, and the one call site sits inside a log-and-swallow `catch` where a silent `null`
   * would never surface.
   *
   * The returned `ts` is the one the caller passed, nanoseconds and all; what was *stored* is
   * truncated to milliseconds, exactly as under `datastore.save()`.
   *
   * `insertOne` rather than a replace: Morphia's `save()` was an upsert when the id was set, and
   * this is not. The one caller always builds a fresh row with no id, so the two agree — and an
   * audit log should be append-only anyway.
   *
   * **The TTL anchor is stamped here**, over whatever the caller passed, from the row's own `ts`
   * and the configured retention. Unconditionally rather than only when absent: the retention is
   * a property of the collection, not of a call site, and a caller that could opt out of it could
   * write a row that outlives every other one. The id keeps the opposite rule — an id the caller
   * chose is theirs — because a chosen id says something a generated one cannot.
   */
  internal fun record(dbo: PodServiceAuditLogDbo): PodServiceAuditLogDbo {
    val stored = dbo.copy(
      id = dbo.id ?: ObjectId(),
      expiresAt = dbo.ts.plus(retentionDays, ChronoUnit.DAYS),
    )
    auditLog.insertOne(stored.toDocument())
    return stored
  }

  /** Test/operator helper: fetches the most recent [limit] entries for [podId]. */
  internal fun findRecent(podId: ObjectId, limit: Int = 50): List<PodServiceAuditLogDbo> =
    auditLog.find(Filters.eq(PodServiceAuditLogDboFields.podId, podId))
      // Descending over an ascending index — the server walks it backwards, so the `(podId, ts)`
      // index serves this without a second one, as it did under Morphia.
      .sort(Sorts.descending(PodServiceAuditLogDboFields.ts))
      .limit(limit)
      .map { it.toDbo() }
      .toList()

  /**
   * Pod-cascade delete: drop every audit row scoped to [podId], and return how many there were.
   *
   * `deleteMany`, because Morphia's `deleteAll()` is `DeleteOptions().multi(true)` — a `deleteOne`
   * carried over here would silently leave a pod's history behind on the one path that exists to
   * remove it.
   */
  internal fun deleteByPod(podId: ObjectId): Long =
    auditLog.deleteMany(Filters.eq(PodServiceAuditLogDboFields.podId, podId)).deletedCount

  private companion object {

    /**
     * The field order Morphia wrote, kept because a row that differs from its neighbours only in
     * order reads differently in a dump. Pinned by that wire-format test, which needs an
     * assertion of its own for it: `BsonDocument.equals` compares `entrySet()` and is blind to
     * order.
     */
    private fun PodServiceAuditLogDbo.toDocument(): Document = Document()
      .putNotNull(PodServiceAuditLogDboFields.id, id)
      .putInstant(PodServiceAuditLogDboFields.ts, ts)
      .putNotNull(PodServiceAuditLogDboFields.podId, podId)
      .putNotNull(PodServiceAuditLogDboFields.clientId, clientId)
      .putNotNull(PodServiceAuditLogDboFields.operation, operation)
      .putNotNull(PodServiceAuditLogDboFields.path, path)
      .putNotNull(PodServiceAuditLogDboFields.statusCode, statusCode)
      .putInstant(PodServiceAuditLogDboFields.expiresAt, expiresAt)

    private fun Document.toDbo(): PodServiceAuditLogDbo = PodServiceAuditLogDbo(
      id = getObjectId(PodServiceAuditLogDboFields.id),
      // Every row has one — it is non-null in the entity and has been since the collection existed.
      // Failing loudly beats defaulting to `now`, which would make a corrupt row look like it was
      // just written and put it at the top of `findRecent`.
      ts = checkNotNull(getInstant(PodServiceAuditLogDboFields.ts)) {
        "audit row without ts: ${getObjectId(PodServiceAuditLogDboFields.id)}"
      },
      podId = getObjectId(PodServiceAuditLogDboFields.podId),
      clientId = getString(PodServiceAuditLogDboFields.clientId),
      operation = getString(PodServiceAuditLogDboFields.operation),
      path = getString(PodServiceAuditLogDboFields.path),
      // The one-argument overload on purpose: `getInteger(key, 0)` returns a primitive, and it
      // would turn "no status was known" into HTTP 0 — which is not an edge case here but every
      // row in the collection, since nothing writes this field yet.
      statusCode = getInteger(PodServiceAuditLogDboFields.statusCode),
      // Null on every row written before the retention existed, and the reader has to say so:
      // those rows are the ones the TTL reaper cannot see, which is a fact about the collection
      // rather than a decoding detail.
      expiresAt = getInstant(PodServiceAuditLogDboFields.expiresAt),
    )
  }
}
