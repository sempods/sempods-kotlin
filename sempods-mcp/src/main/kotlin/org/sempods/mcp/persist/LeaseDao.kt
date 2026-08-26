package org.sempods.mcp.persist

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import java.util.Date
import org.sempods.mcp.SempodsMcpCollections

/**
 * This replica's identity for Mongo leases and per-token refresh claims (M6.3). Generated fresh
 * at boot — no configuration, no registration: a crashed holder's lease/claim simply expires.
 * The hostname prefix is purely for log forensics ("which replica held it").
 */
data class InstanceId(val value: String)

/**
 * Best-effort singleton leases over Mongo (M6.3): at most one replica holds a named lease at a
 * time, so periodic work (the token-refresh sweep) runs once across replicas instead of N times.
 *
 * **Efficiency, not correctness.** A lease expires on wall-clock time, so two holders can briefly
 * overlap (expiry mid-work, clock skew). Anything guarded only by a lease must therefore be
 * idempotent or otherwise safe under overlap — the refresh sweep is (per-token claims in the
 * vault serialise the actual refreshes). Clock-skew assumption: lease TTLs (60 s+) dwarf
 * NTP-level skew between replicas; the comparison is writer's `now + ttl` against a later
 * reader's `now`.
 *
 * [tryAcquire] is idempotent for the current holder and thereby doubles as the renewal heartbeat.
 */
/**
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md` §"Conventions" states.
 */
class LeaseDao(db: MongoDatabase, collectionName: String = SempodsMcpCollections.LEASES) {

  private val leases = db.getCollection(collectionName)

  /**
   * Acquire (or renew) the lease: succeeds when it is absent, expired, or already held by
   * [holder]. Returns false when another live holder has it.
   */
  fun tryAcquire(name: String, holder: String, ttlMs: Long): Boolean {
    val now = Date()
    return try {
      val result = leases.updateOne(
        Filters.and(
          Filters.eq("_id", name),
          Filters.or(
            Filters.lt("expiresAt", now),
            Filters.eq("holder", holder),
          ),
        ),
        Updates.combine(
          Updates.set("holder", holder),
          Updates.set("expiresAt", Date(now.time + ttlMs)),
          Updates.setOnInsert("acquiredAt", now),
        ),
        UpdateOptions().upsert(true),
      )
      // matchedCount (not modifiedCount): a same-millisecond renewal matches but modifies nothing —
      // the filter matching at all means the lease is ours (expired or already held by us).
      result.matchedCount > 0 || result.upsertedId != null
    } catch (e: MongoWriteException) {
      // Upsert race: the filter matched nothing (another holder's live lease exists), so Mongo
      // tried to insert and hit the _id — that holder wins.
      if (ErrorCategory.fromErrorCode(e.error.code) != ErrorCategory.DUPLICATE_KEY) throw e
      false
    }
  }

  /** Release the lease early (graceful shutdown / end of one-shot work); only the holder may. */
  fun release(name: String, holder: String) {
    leases.deleteOne(Filters.and(Filters.eq("_id", name), Filters.eq("holder", holder)))
  }

  companion object {
    const val TOKEN_REFRESH_SWEEP = "tokenRefreshSweep"
  }
}
