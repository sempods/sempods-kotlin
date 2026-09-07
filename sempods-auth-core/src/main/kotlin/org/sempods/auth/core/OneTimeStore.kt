package org.sempods.auth.core

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import org.bson.Document
import org.bson.conversions.Bson
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Something the server has to remember while a browser is somewhere else.
 *
 * Every interrupted flow in this codebase needs the same thing: park what the request was about,
 * hand the browser an opaque key, and pick it up again when the browser comes back — once. That
 * covers login state, consent screens and authorization codes alike; the mechanism had been written
 * seven times, and the copies had already drifted: one wrote BSON nulls against the contract
 * `sempods-commons-mongo/docs/document-contract.md` states, and one kept its rows in a `ConcurrentHashMap`, so every
 * login in flight died with the process and a second replica never saw them at all.
 *
 * Four properties, and losing any of them is silent:
 *
 * - **The key is not stored.** `_id` is its SHA-256, so a database dump holds nothing resumable.
 * - **[consume] is an atomic `findOneAndDelete`.** Exactly one of N concurrent callbacks wins,
 *   which is what "one-time" has to mean under concurrency rather than under a single reader.
 * - **Expiry is checked on read.** The TTL index reaps on its own schedule, so a row can outlive
 *   its expiry by minutes; nothing may act on one that has.
 * - **It is durable.** A deploy mid-flow does not invalidate what is parked.
 *
 * What is stored is the caller's: [write] and [read] are the only things that know the payload.
 * They are given a `Document` rather than a map so a codec can use the `commons-mongo` helpers —
 * and it should: the wire contract (a null or empty field is omitted, not written as BSON `null`)
 * applies to these collections like any other.
 *
 * @param read returns `null` for a row missing a field it should have. Such a row cannot serve the
 *   flow it belongs to either way, and throwing would turn a bad row into a 500 on a request that
 *   can only ever fail.
 */
class OneTimeStore<T>(
  db: MongoDatabase,
  collectionName: String,
  private val ttl: Duration,
  private val write: Document.(T) -> Unit,
  private val read: Document.() -> T?,
) {

  private val rows = db.getCollection(collectionName)

  init {
    rows.createIndex(Indexes.ascending(FIELD_EXPIRES_AT), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
  }

  /**
   * Declares a secondary index over payload fields — for a store whose rows are also looked up or
   * deleted by something other than their key.
   *
   * Idempotent, and called from the owning store's constructor: what the fields *mean* is the
   * caller's, since [write] is the only thing that knows the payload.
   */
  fun index(keys: Bson) {
    rows.createIndex(keys)
  }

  /**
   * An unpredictable key, minted without storing anything.
   *
   * For the flows whose key has to exist before the payload does — an OAuth `state` is sent
   * upstream and only then is there something to park under it. Storing it under a second,
   * separately minted value would be two values tying one callback to one request, and two chances
   * for them to disagree about which.
   */
  fun newKey(): String = Secrets.newSecret()

  /** Parks [payload] under a fresh key, and returns the key to hand to the browser. */
  fun issue(payload: T): String = newKey().also { create(it, payload) }

  /** Parks [payload] under a key the caller already holds — see [newKey]. */
  fun create(key: String, payload: T) {
    rows.insertOne(
      Document().apply {
        put("_id", sha256Hex(key))
        write(payload)
        putInstant(FIELD_EXPIRES_AT, Instant.now().plus(ttl))
      },
    )
  }

  /** The payload, removed in the same operation. `null` if unknown, already used, expired, or unreadable. */
  fun consume(key: String): T? {
    val doc = rows.findOneAndDelete(Filters.eq("_id", sha256Hex(key))) ?: return null
    return doc.readIfLive()
  }

  /**
   * The payload, left where it is.
   *
   * For a flow that returns to the same screen more than once — the consent page survives a
   * detour through another server's OAuth and is spent only when the user finally decides.
   */
  fun peek(key: String): T? = rows.find(Filters.eq("_id", sha256Hex(key))).firstOrNull()?.readIfLive()

  /**
   * [peek], and slide the expiry out by a full [ttl] if the row is still alive.
   *
   * So that a screen someone is actively working on never expires under them, while the TTL still
   * reaps an abandoned one. An already-expired row is not resurrected.
   */
  fun touch(key: String): T? {
    val hashed = sha256Hex(key)
    val payload = rows.find(Filters.eq("_id", hashed)).firstOrNull()?.readIfLive() ?: return null
    rows.updateOne(Filters.eq("_id", hashed), Updates.set(FIELD_EXPIRES_AT, Date.from(Instant.now().plus(ttl))))
    return payload
  }

  /**
   * Deletes every row matching [filter], live or expired, and returns how many went.
   *
   * The one bulk path into a store whose whole point is one key at a time: something outside the
   * flow has ended what the parked rows were for, and they must not be redeemable afterwards. What
   * "belongs to" means is the caller's — the payload fields are written by [write] and this store
   * cannot name them — so the filter is built where the payload is known.
   *
   * Index the fields a caller filters on with [index]. The TTL keeps the collection to what is in
   * flight rather than to a history, but "small" is not "bounded": one collection holds every
   * tenant's rows, and a caller that can repeat the operation this backs turns each repeat into a
   * scan of all of them.
   */
  fun deleteWhere(filter: Bson): Long = rows.deleteMany(filter).deletedCount

  private fun Document.readIfLive(): T? {
    val expiresAt = getInstant(FIELD_EXPIRES_AT) ?: return null
    if (!Instant.now().isBefore(expiresAt)) return null
    return read()
  }

  private companion object {
    const val FIELD_EXPIRES_AT = "expiresAt"
  }
}
