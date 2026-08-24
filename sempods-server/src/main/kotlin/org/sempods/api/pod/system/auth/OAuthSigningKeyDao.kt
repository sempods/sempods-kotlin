package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/**
 * Persistence for [OAuthSigningKeyDbo].
 *
 * `findAll()` is called exactly once at [PodTokenIssuer] construction — there is no
 * hot-path read of this collection. A later rotation mechanism would add a scheduled
 * reload; until then, one process reads once at boot and holds the keys for its
 * lifetime.
 *
 * **On the MongoDB driver, mapped by hand** — see `docs/persistence.md`. The document this writes is
 * byte-identical to what Morphia's `PojoCodec` wrote for the same entity — field order included —
 * which is pinned without a database by a wire-format test on the mapping side. That parity is the whole
 * migration: no stored key was rewritten, and a process on either side of the change reads what the
 * other wrote.
 */
class OAuthSigningKeyDao(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for.
   *
   * The name is a parameter only so that a test can point an instance at a collection of its own,
   * and that is worth the extra constructor: on the shared one, "no signing key exists yet" can be
   * arranged only by deleting the keys that are there, which makes the test both destructive and
   * dependent on running alone. See `PodTokenIssuerPersistenceTest`.
   */
  @Inject
  constructor(db: MongoDatabase) : this(db, SempodsCollections.OAUTH_SIGNING_KEYS)

  private val keys = db.getCollection(collectionName)

  init {
    // The two indexes `@Indexes` declared, with the same options. Morphia created them through the
    // `ensureIndexes()` in `MorphiaDao`'s constructor, which did it for every mapped type at once;
    // a driver-level DAO owns its own. The options have to match what is on disk exactly —
    // `createIndex` throws `IndexOptionsConflict` against a differing index that already exists,
    // and that failure lands at boot rather than at the first query.
    keys.createIndex(Indexes.ascending(OAuthSigningKeyDboFields.kid), IndexOptions().unique(true))
    keys.createIndex(Indexes.ascending(OAuthSigningKeyDboFields.createdAt))
  }

  /**
   * Persists [dbo] and returns it **carrying the id it was stored under**.
   *
   * The copy is not cosmetic. `datastore.save()` wrote the generated `_id` back into the instance
   * it was handed; `insertOne` does not, so a caller reading the id off the return value would get
   * the `null` it passed in. No caller does today — but that is a property of the callers, not of
   * this method.
   */
  fun create(dbo: OAuthSigningKeyDbo): OAuthSigningKeyDbo {
    val stored = dbo.copy(id = dbo.id ?: ObjectId())
    keys.insertOne(stored.toDocument())
    return stored
  }

  /**
   * Persists [dbo] as the **first** key, and answers whether this insert was the one that won.
   *
   * The row goes into a singleton slot — the fixed [BOOTSTRAP_ID] — so of N replicas booting
   * against an empty collection exactly one insert survives and the rest get a duplicate-key
   * error. Without it two replicas behind the reverse proxy would each mint their own key, sign
   * with it, and publish a JWKS the other's tokens are not in; a client would see
   * `Token signature verification failed` on every other request. [create] cannot say this — it
   * mints an `_id`, so every caller wins.
   *
   * Only the bootstrap uses the fixed id. A later rotation appends through [create] as usual,
   * which is what keeps the slot a *first*-key slot rather than a lock on the collection.
   */
  fun createInitial(dbo: OAuthSigningKeyDbo): Boolean = try {
    keys.insertOne(dbo.copy(id = BOOTSTRAP_ID).toDocument())
    true
  } catch (e: MongoWriteException) {
    // `kid` is a fresh UUID, so the only unique index a duplicate can be hitting is `_id`:
    // another replica won the race.
    if (ErrorCategory.fromErrorCode(e.error.code) != ErrorCategory.DUPLICATE_KEY) throw e
    false
  }

  /** Returns all keys, newest first. */
  fun findAll(): List<OAuthSigningKeyDbo> = find(Filters.empty())

  /**
   * Returns all keys that are still usable for verification (i.e. not retired).
   *
   * `eq(field, null)` rather than `exists(false)`, which is the filter Morphia issued and is not
   * the same question: `{retiredAt: null}` matches a missing field *and* an explicit BSON `null`,
   * while `exists(false)` matches only the former. Retiring a key writes a timestamp and nothing
   * writes `null`, so the two agree on today's data — but they would diverge on a row touched by
   * hand or by a migration, and this is the filter that decides whether a key still signs.
   */
  fun findActive(): List<OAuthSigningKeyDbo> =
    find(Filters.eq(OAuthSigningKeyDboFields.retiredAt, null))

  private fun find(filter: Bson): List<OAuthSigningKeyDbo> =
    keys.find(filter)
      .sort(Sorts.descending(OAuthSigningKeyDboFields.createdAt))
      .map { it.toDbo() }
      .toList()

  private companion object {

    /**
     * Fixed `_id` of the bootstrap key row — the singleton slot the first-key race is decided on.
     *
     * All-zero, which is a value no generated ObjectId can take: the leading four bytes are a
     * Unix timestamp, so this one reads as 1970 and is self-evidently synthetic rather than
     * something a driver produced.
     */
    private val BOOTSTRAP_ID = ObjectId("000000000000000000000000")

    /**
     * The field order Morphia wrote, kept because the wire-format test asserts on it and a
     * document that differs only in order is a document that reads differently in a diff.
     */
    private fun OAuthSigningKeyDbo.toDocument(): Document = Document()
      .putNotNull(OAuthSigningKeyDboFields.id, id)
      .putNotNull(OAuthSigningKeyDboFields.kid, kid)
      .putNotNull(OAuthSigningKeyDboFields.algorithm, algorithm)
      .putNotNull(OAuthSigningKeyDboFields.jwk, jwk)
      .putInstant(OAuthSigningKeyDboFields.createdAt, createdAt)
      .putInstant(OAuthSigningKeyDboFields.retiredAt, retiredAt)

    private fun Document.toDbo(): OAuthSigningKeyDbo = OAuthSigningKeyDbo(
      id = getObjectId(OAuthSigningKeyDboFields.id),
      kid = getString(OAuthSigningKeyDboFields.kid),
      algorithm = getString(OAuthSigningKeyDboFields.algorithm),
      jwk = getString(OAuthSigningKeyDboFields.jwk),
      // Every row has one — it is non-null in the entity and has been since the collection existed.
      // Failing loudly beats defaulting to `now`, which would make a corrupt key look freshly
      // minted and therefore the one chosen for signing.
      createdAt = checkNotNull(getInstant(OAuthSigningKeyDboFields.createdAt)) {
        "signing key without createdAt: ${getString(OAuthSigningKeyDboFields.kid)}"
      },
      retiredAt = getInstant(OAuthSigningKeyDboFields.retiredAt),
    )
  }
}
