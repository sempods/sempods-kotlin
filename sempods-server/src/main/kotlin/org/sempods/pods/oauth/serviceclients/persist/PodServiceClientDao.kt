package org.sempods.pods.oauth.serviceclients.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Persistence for [PodServiceClientDbo]. The hot-path read is
 * [findByClientId], called from the token endpoint when an incoming
 * `grant_type=client_credentials` request authenticates via HTTP Basic.
 *
 * **On the MongoDB driver, mapped by hand** — see `docs/persistence.md`. The document this writes is identical
 * to what Morphia's `PojoCodec` wrote for the same entity — field order included — which is pinned
 * without a database by a wire-format test on the mapping side. No stored row was rewritten, and a
 * process on either side of the change reads what the other wrote.
 */
class PodServiceClientDao(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states.
   */
  @Inject
  constructor(db: MongoDatabase) : this(db, SempodsCollections.OAUTH_SERVICE_CLIENTS)

  private val serviceClients = db.getCollection(collectionName)

  init {
    // The one index `@Indexes` declared, with the same options — measured against the running
    // database, which carries `podId_1_clientId_1` (unique). `createIndex` throws
    // `IndexOptionsConflict` against an existing index whose *options* differ, and that failure
    // lands at boot rather than at the first query.
    serviceClients.createIndex(
      Indexes.ascending(PodServiceClientDboFields.podId, PodServiceClientDboFields.clientId),
      IndexOptions().unique(true),
    )
  }

  /**
   * Registers [dbo] and returns it **carrying the id it was stored under**.
   *
   * `datastore.save()` wrote the generated `_id` back into the instance it was handed; `insertOne`
   * does not. The bootstrap path reads that id back — `delete(…, expectedId)` below is a
   * compare-and-swap over exactly this value — so it is minted before the write rather than hoped
   * for afterwards.
   */
  internal fun create(dbo: PodServiceClientDbo): PodServiceClientDbo {
    val stored = dbo.copy(id = dbo.id ?: ObjectId())
    serviceClients.insertOne(stored.toDocument())
    return stored
  }

  internal fun findByClientId(podId: ObjectId, clientId: String): PodServiceClientDbo? =
    serviceClients.find(keyFilter(podId, clientId)).first()?.toDbo()

  internal fun findByPod(podId: ObjectId): List<PodServiceClientDbo> =
    serviceClients.find(Filters.eq(PodServiceClientDboFields.podId, podId))
      .map { it.toDbo() }
      .toList()

  /**
   * Best-effort liveness bump. Returns `true` if the row was updated.
   *
   * `updateOne`, because `MorphiaDao.updateFields` issued `UpdateOptions().upsert(false)` without
   * `multi` — and the unique index means there is never a second row to reach anyway.
   */
  internal fun touchLastUsed(podId: ObjectId, clientId: String, at: Instant = Instant.now()): Boolean =
    serviceClients.updateOne(
      keyFilter(podId, clientId),
      Updates.set(PodServiceClientDboFields.lastUsedAt, at),
    ).modifiedCount > 0L

  /**
   * Context-deletion cascade — the static-registration counterpart of
   * `PodRefreshTokenStore.revokeByContextScope`: strips every scope anchored exactly at
   * [contextUri] from the pod's service clients and deletes registrations left with no
   * scopes (the token endpoint refuses scopes outside the registered set, and a client
   * without any scope could not have been registered in the first place). Without this,
   * deleting a `#manage` root would revoke user grants and refresh tokens but leave the
   * static client able to mint fresh tokens for the deleted root — and with them manage
   * surviving descendants or recreate the root. Outstanding service tokens ride out
   * their ≤600 s TTL, same trade-off as for user access tokens.
   *
   * Returns the number of registrations deleted outright (scope-stripped survivors are
   * not counted).
   *
   * **`updateMany`, and it has to be:** Morphia issued `UpdateOptions().multi(true)` here, and a
   * pod can hold several clients anchored at the same context. The sweep that follows asks
   * `Filters.size(scopes, 0)`, which works because `$pullAll` leaves an emptied array as `[]`
   * rather than removing the field — an array that is *absent* would not match, and that is the
   * shape a row inserted without scopes would have. Nothing inserts one, and this is the only
   * place the two spellings could diverge, so it is measured in
   * `PodServiceClientDaoTest` rather than argued about here.
   */
  internal fun revokeByContextScope(podId: ObjectId, contextUri: String): Long {
    val anchoredScopes = listOf("$contextUri#read", "$contextUri#write", "$contextUri#manage")
    serviceClients.updateMany(
      Filters.and(
        Filters.eq(PodServiceClientDboFields.podId, podId),
        Filters.`in`(PodServiceClientDboFields.scopes, anchoredScopes),
      ),
      Updates.pullAll(PodServiceClientDboFields.scopes, anchoredScopes),
    )
    return serviceClients.deleteMany(
      Filters.and(
        Filters.eq(PodServiceClientDboFields.podId, podId),
        Filters.size(PodServiceClientDboFields.scopes, 0),
      ),
    ).deletedCount
  }

  /**
   * Removes a single registration. Used by the provisioning bootstrap to replace a
   * half-provisioned client (registration succeeded but the consumer-side
   * credential row was never written, so the plaintext secret is lost) with a
   * freshly minted one. Returns `true` if a row was removed.
   *
   * [expectedId] makes the delete conditional on the row the caller actually observed —
   * compare-and-swap rather than delete-by-key. A replace is `find` → `delete` → `insert`, and
   * two of those interleaved would otherwise let the second caller's key-scoped delete remove
   * the *first* caller's freshly inserted row: the first caller keeps a `200` response whose
   * secret no longer authenticates. With the id in the filter that delete removes nothing, and
   * the caller can answer `409` instead of handing out a dead secret. Pass `null` only where
   * unconditional removal is intended (pod deletion, cleanup).
   */
  internal fun delete(podId: ObjectId, clientId: String, expectedId: ObjectId? = null): Boolean {
    val filter = if (expectedId == null) {
      keyFilter(podId, clientId)
    } else {
      Filters.and(keyFilter(podId, clientId), Filters.eq(PodServiceClientDboFields.id, expectedId))
    }
    return serviceClients.deleteOne(filter).deletedCount > 0L
  }

  /**
   * Pod-cascade delete: when a pod is removed every service-client row
   * scoped to it loses its meaning. Returns the number of rows removed.
   *
   * `deleteMany`, because Morphia's `deleteAll()` is `DeleteOptions().multi(true)`: a `deleteOne`
   * carried over here would leave every registration but one behind on a deleted pod.
   */
  internal fun deleteByPod(podId: ObjectId): Long =
    serviceClients.deleteMany(Filters.eq(PodServiceClientDboFields.podId, podId)).deletedCount

  private companion object {

    private fun keyFilter(podId: ObjectId, clientId: String): Bson = Filters.and(
      Filters.eq(PodServiceClientDboFields.podId, podId),
      Filters.eq(PodServiceClientDboFields.clientId, clientId),
    )

    /**
     * The field order Morphia wrote, kept because a row that differs from its neighbours only in
     * order reads differently in a dump. Pinned by that wire-format test.
     */
    private fun PodServiceClientDbo.toDocument(): Document = Document()
      .putNotNull(PodServiceClientDboFields.id, id)
      .putNotNull(PodServiceClientDboFields.podId, podId)
      .putNotNull(PodServiceClientDboFields.clientId, clientId)
      .putNotNull(PodServiceClientDboFields.secretHash, secretHash)
      .putStrings(PodServiceClientDboFields.scopes, scopes)
      .putNotNull(PodServiceClientDboFields.label, label)
      .putInstant(PodServiceClientDboFields.createdAt, createdAt)
      .putInstant(PodServiceClientDboFields.lastUsedAt, lastUsedAt)

    private fun Document.toDbo(): PodServiceClientDbo = PodServiceClientDbo(
      id = getObjectId(PodServiceClientDboFields.id),
      podId = getObjectId(PodServiceClientDboFields.podId),
      clientId = getString(PodServiceClientDboFields.clientId),
      secretHash = getString(PodServiceClientDboFields.secretHash),
      // Empty for a row the cascade has stripped and not yet swept — `getStringSet` answers the
      // same empty set for an absent field and for `[]`, which is the reader's side of the
      // distinction `revokeByContextScope` has to keep on the query side.
      scopes = getStringSet(PodServiceClientDboFields.scopes),
      label = getString(PodServiceClientDboFields.label),
      // Every row has one — it is non-null in the entity and has been since the collection existed.
      // Failing loudly beats defaulting to `now`, which would make a corrupt registration look
      // freshly provisioned to whoever is auditing it.
      createdAt = checkNotNull(getInstant(PodServiceClientDboFields.createdAt)) {
        "service client without createdAt: ${getString(PodServiceClientDboFields.clientId)}"
      },
      lastUsedAt = getInstant(PodServiceClientDboFields.lastUsedAt),
    )
  }
}
