package org.sempods.pods.oauth.serviceclients.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
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
 * **On the MongoDB driver, mapped by hand** — see `sempods-commons-mongo/docs/document-contract.md`. An
 * insert writes the fields in [PodServiceClientDbo]'s declaration order, which is the order Morphia's
 * `PojoCodec` wrote; an update that sets fields the row lacks appends them, in an order the server
 * picks. `PodServiceClientDaoTest` pins the order of a new row and the field set of a replaced one.
 *
 * Every write to `scopes` is built by one private helper, which also moves `grantsVersion`
 * ([PodServiceClientDbo.grantsVersion] states the rule).
 */
class PodServiceClientDao internal constructor(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states.
   */
  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.OAUTH_SERVICE_CLIENTS)

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
   * Registers [dbo] and returns **the row as it is now stored**, read back through the same
   * encoder and decoder every other read uses.
   *
   * `datastore.save()` wrote the generated `_id` back into the instance it was given and
   * `insertOne` does not, so it is minted here — the bootstrap path reads that id back, and
   * `delete(…, expectedId)` below is a compare-and-swap over exactly this value. The round trip
   * covers the rest: a stored `Instant` carries milliseconds and an empty collection is not
   * written at all (`sempods-commons-mongo/docs/document-contract.md`), so a registration's answer
   * equals the answer to the next read of it without this method knowing either rule.
   */
  internal fun create(dbo: PodServiceClientDbo): PodServiceClientDbo {
    val document = dbo.copy(id = dbo.id ?: ObjectId()).toDocument()
    serviceClients.insertOne(document)
    return document.toDbo()
  }

  internal fun findByClientId(podId: ObjectId, clientId: String): PodServiceClientDbo? =
    serviceClients.find(keyFilter(podId, clientId)).first()?.toDbo()

  internal fun findByPod(podId: ObjectId): List<PodServiceClientDbo> =
    serviceClients.find(Filters.eq(PodServiceClientDboFields.podId, podId))
      .sort(Sorts.ascending(PodServiceClientDboFields.createdAt))
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
   * Context-deletion cascade — the static-registration counterpart of the user-grant deletion in
   * `PodGrantsFacade.revokeContextGrants`: strips every scope anchored exactly at
   * [contextUri] from the pod's service clients. Without this,
   * deleting a `#manage` root would revoke the user grants but leave the
   * static client able to mint fresh tokens for the deleted root — and with them manage
   * surviving descendants or recreate the root. A registration's context scopes are read by the
   * resolver on every request, which is what distinguishes them from the feature scopes a refresh
   * row carries. Outstanding service tokens ride out
   * their ≤600 s TTL, same trade-off as for user access tokens.
   *
   * A registration this empties stays where it is, holding a credential and no authority —
   * see `PodServiceClientStore.register`. Returns how many registrations lost a scope.
   *
   * **`updateMany`, and it has to be:** Morphia issued `UpdateOptions().multi(true)` here, and a
   * pod can hold several clients anchored at the same context. It moves the version of the rows it
   * changes, and only those: the filter names the scopes it pulls.
   */
  internal fun revokeByContextScope(podId: ObjectId, contextUri: String): Long {
    val anchoredScopes = listOf("$contextUri#read", "$contextUri#write", "$contextUri#manage")
    return serviceClients.updateMany(
      Filters.and(
        Filters.eq(PodServiceClientDboFields.podId, podId),
        Filters.`in`(PodServiceClientDboFields.scopes, anchoredScopes),
      ),
      grantsUpdate(Updates.pullAll(PodServiceClientDboFields.scopes, anchoredScopes), changedBy = null),
    ).modifiedCount
  }

  /**
   * Makes [scopes] the grants of the registration [expectedId] names, provided they are still at
   * [expectedVersion]. One `updateOne`, so the grants, the version and who changed them move
   * together or not at all. `false` where nothing matched; [exists] tells a replaced registration
   * from a version that moved on. Version `0` also matches the absent field
   * (`sempods-server/docs/collections.md`).
   */
  internal fun replaceScopes(
    podId: ObjectId,
    clientId: String,
    expectedId: ObjectId,
    expectedVersion: Long,
    scopes: Set<String>,
    changedBy: String,
    at: Instant = Instant.now(),
  ): Boolean {
    val version = if (expectedVersion == 0L) {
      Filters.or(
        Filters.eq(PodServiceClientDboFields.grantsVersion, 0L),
        Filters.exists(PodServiceClientDboFields.grantsVersion, false),
      )
    } else {
      Filters.eq(PodServiceClientDboFields.grantsVersion, expectedVersion)
    }
    return serviceClients.updateOne(
      Filters.and(registrationFilter(podId, clientId, expectedId), version),
      // `[]` for an empty selection, the spelling every emptying update leaves.
      grantsUpdate(Updates.set(PodServiceClientDboFields.scopes, scopes.toList()), changedBy, at),
    ).matchedCount > 0L
  }

  /** Whether the registration [expectedId] names is still stored under `(podId, clientId)`. */
  internal fun exists(podId: ObjectId, clientId: String, expectedId: ObjectId): Boolean =
    serviceClients.find(registrationFilter(podId, clientId, expectedId)).limit(1).first() != null

  /**
   * Adds [scopes] to the registration [expectedId] names, and answers whether it was still there.
   * The id keeps an approval off a registration re-created under the same `clientId`.
   */
  internal fun addScopes(
    podId: ObjectId,
    clientId: String,
    expectedId: ObjectId,
    scopes: Set<String>,
    changedBy: String,
    at: Instant = Instant.now(),
  ): Boolean =
    serviceClients.updateOne(
      registrationFilter(podId, clientId, expectedId),
      grantsUpdate(Updates.addEachToSet(PodServiceClientDboFields.scopes, scopes.toList()), changedBy, at),
    ).matchedCount > 0L

  /** [changedBy] removes [scopes]; answers the row afterwards, or `null` where there is none. */
  internal fun removeScopes(
    podId: ObjectId,
    clientId: String,
    scopes: Set<String>,
    changedBy: String,
    at: Instant = Instant.now(),
  ): PodServiceClientDbo? =
    serviceClients.findOneAndUpdate(
      keyFilter(podId, clientId),
      grantsUpdate(Updates.pullAll(PodServiceClientDboFields.scopes, scopes.toList()), changedBy, at),
      FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
    )?.toDbo()

  /**
   * Drops [scopes] from the registration [expectedId] names, as the server's check after a grant
   * write does: a registration re-created under the same `clientId` in between is left alone.
   */
  internal fun dropScopes(podId: ObjectId, clientId: String, expectedId: ObjectId, scopes: Set<String>): Boolean =
    serviceClients.updateOne(
      registrationFilter(podId, clientId, expectedId),
      grantsUpdate(Updates.pullAll(PodServiceClientDboFields.scopes, scopes.toList()), changedBy = null),
    ).matchedCount > 0L

  /**
   * Replaces the secret hash if it is still [expectedHash]. Of two interleaved rotations only one
   * writes, so neither answers a secret that does not work.
   */
  internal fun replaceSecretHash(podId: ObjectId, clientId: String, expectedHash: String, newHash: String): Boolean =
    serviceClients.updateOne(
      Filters.and(keyFilter(podId, clientId), Filters.eq(PodServiceClientDboFields.secretHash, expectedHash)),
      Updates.set(PodServiceClientDboFields.secretHash, newHash),
    ).modifiedCount > 0L

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
    val filter = if (expectedId == null) keyFilter(podId, clientId) else registrationFilter(podId, clientId, expectedId)
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

    private fun registrationFilter(podId: ObjectId, clientId: String, id: ObjectId): Bson =
      Filters.and(keyFilter(podId, clientId), Filters.eq(PodServiceClientDboFields.id, id))

    /**
     * Every write to `scopes` is built here: [change], the next version, and who changed them where
     * a person did. The version is an Int64 from its first write on.
     */
    private fun grantsUpdate(change: Bson, changedBy: String?, at: Instant = Instant.now()): Bson =
      Updates.combine(
        listOfNotNull(
          change,
          Updates.inc(PodServiceClientDboFields.grantsVersion, 1L),
          changedBy?.let { Updates.set(PodServiceClientDboFields.grantsChangedBy, it) },
          changedBy?.let { Updates.set(PodServiceClientDboFields.grantsChangedAt, at) },
        ),
      )

    /**
     * The field order Morphia wrote, kept because a row that differs from its neighbours only in
     * order reads differently in a dump.
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
      .putNotNull(PodServiceClientDboFields.grantsVersion, grantsVersion.takeIf { it != 0L })
      .putInstant(PodServiceClientDboFields.grantsChangedAt, grantsChangedAt)
      .putNotNull(PodServiceClientDboFields.grantsChangedBy, grantsChangedBy)

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
      grantsVersion = get(PodServiceClientDboFields.grantsVersion, Number::class.java)?.toLong() ?: 0L,
      grantsChangedAt = getInstant(PodServiceClientDboFields.grantsChangedAt),
      grantsChangedBy = getString(PodServiceClientDboFields.grantsChangedBy),
    )
  }
}
