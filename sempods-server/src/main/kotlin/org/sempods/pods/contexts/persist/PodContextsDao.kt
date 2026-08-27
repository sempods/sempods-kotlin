package org.sempods.pods.contexts.persist

import com.google.inject.Inject
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.isDuplicateKey
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/**
 * Persistence for [PodContextDbo] — the registry of a pod's contexts.
 *
 * **On the MongoDB driver, mapped by hand** — see `sempods-commons-mongo/docs/document-contract.md`. The document this writes is identical
 * to what Morphia's `PojoCodec` wrote for the same entity — field order included — which is pinned
 * without a database by a wire-format test on the mapping side. No stored row was rewritten, and a
 * process on either side of the change reads what the other wrote.
 */
class PodContextsDao internal constructor(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states.
   */
  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.CONTEXTS)

  private val contexts = db.getCollection(collectionName)

  init {
    // The two indexes `@Indexes` declared, with the same options — measured against the running
    // database, which carries `podId_1_contextUri_1` (unique) and `podId_1`. `createIndex` throws
    // `IndexOptionsConflict` against an existing index whose *options* differ, and that failure
    // lands at boot rather than at the first query.
    contexts.createIndex(
      Indexes.ascending(PodContextDboFields.podId, PodContextDboFields.contextUri),
      IndexOptions().unique(true),
    )
    contexts.createIndex(Indexes.ascending(PodContextDboFields.podId))
  }

  internal fun exists(podId: ObjectId, contextUri: String): Boolean =
    contexts.find(keyFilter(podId, contextUri))
      // `limit(1)` rather than `countDocuments`: the question is whether one row exists, and the
      // unique index answers it from the first key it meets. This is what Morphia's `exists()`
      // extension did.
      .limit(1)
      .first() != null

  internal fun fetchByContextUri(podId: ObjectId, contextUri: String): PodContextDbo? =
    contexts.find(keyFilter(podId, contextUri)).first()?.toDbo()

  internal fun create(
    podId: ObjectId,
    contextUri: String,
    label: String?,
    description: String?,
    createdBy: String?,
    isPublic: Boolean = false,
  ): PodContextDbo? {
    if (exists(podId = podId, contextUri = contextUri)) {
      return null
    }
    val dbo = PodContextDbo(
      id = ObjectId(),
      podId = podId,
      contextUri = contextUri,
      label = label,
      description = description,
      isPublic = isPublic,
      createdBy = createdBy,
    )
    return try {
      contexts.insertOne(dbo.toDocument())
      dbo
    } catch (e: MongoWriteException) {
      // Unique index on (podId, contextUri): a concurrent create won the race between the
      // existence check above and this insert. The caller's post-condition — "the context
      // exists" — holds either way, so this is the same `null` ("was already there") answer
      // rather than a 500. Two callers provisioning the same app root at once is the case
      // that actually hits this. Any other write failure is a real fault and must not be
      // disguised as a benign race.
      if (!e.isDuplicateKey()) throw e
      null
    }
  }

  /**
   * Flips the visibility flag of an existing context row. Returns `true` if a row
   * was actually modified; `false` if no matching row existed or the value was
   * already set (callers treat both as "nothing to do" — the backfill relies on
   * this idempotency).
   *
   * `updateOne`, because Morphia's `UpdateOptions()` without `multi(true)` was one too — and the
   * unique index means there is never a second row to reach anyway.
   */
  internal fun setPublic(podId: ObjectId, contextUri: String, isPublic: Boolean): Boolean =
    contexts.updateOne(
      keyFilter(podId, contextUri),
      Updates.set(PodContextDboFields.isPublic, isPublic),
    ).modifiedCount > 0L

  /**
   * Fetches context rows for a pod. Pass `public = true` / `false` to restrict
   * to the matching visibility; `null` (default) returns all rows regardless of
   * the [PodContextDbo.isPublic] flag.
   *
   * Sorted in the process rather than by the server, as before: the `(podId)` index orders by
   * `_id` within a pod, a pod holds a handful of contexts, and asking Mongo for a `contextUri`
   * sort would need an index that does not exist.
   */
  internal fun fetchByPod(podId: ObjectId, public: Boolean? = null): List<PodContextDbo> {
    val filter = if (public == null) {
      Filters.eq(PodContextDboFields.podId, podId)
    } else {
      Filters.and(
        Filters.eq(PodContextDboFields.podId, podId),
        Filters.eq(PodContextDboFields.isPublic, public),
      )
    }
    return contexts.find(filter)
      .map { it.toDbo() }
      .sortedBy { it.contextUri }
  }

  /**
   * Drop every context row of a pod.
   *
   * `deleteMany`, because Morphia's `deleteAll()` is `DeleteOptions().multi(true)`: a `deleteOne`
   * carried over here would leave all but one of a deleted pod's contexts registered.
   */
  internal fun deleteByPod(podId: ObjectId): Long =
    contexts.deleteMany(Filters.eq(PodContextDboFields.podId, podId)).deletedCount

  /**
   * Removes the context registry row for a single context. Returns `true` if a row was
   * actually deleted; `false` if no matching row existed (treat as "already gone" by
   * callers — pod-cascade and direct delete share this idempotency).
   */
  internal fun delete(podId: ObjectId, contextUri: String): Boolean =
    contexts.deleteOne(keyFilter(podId, contextUri)).deletedCount > 0L

  private companion object {

    private fun keyFilter(podId: ObjectId, contextUri: String): Bson = Filters.and(
      Filters.eq(PodContextDboFields.podId, podId),
      Filters.eq(PodContextDboFields.contextUri, contextUri),
    )

    /**
     * The field order Morphia wrote, kept because a row that differs from its neighbours only in
     * order reads differently in a dump. Pinned by that wire-format test.
     *
     * `isPublic` goes through `putNotNull` like the rest, and a `false` is therefore written rather
     * than omitted — which is what Morphia did and what the visibility filter reads.
     */
    private fun PodContextDbo.toDocument(): Document = Document()
      .putNotNull(PodContextDboFields.id, id)
      .putNotNull(PodContextDboFields.podId, podId)
      .putNotNull(PodContextDboFields.contextUri, contextUri)
      .putNotNull(PodContextDboFields.label, label)
      .putNotNull(PodContextDboFields.description, description)
      .putNotNull(PodContextDboFields.isPublic, isPublic)
      .putInstant(PodContextDboFields.createdAt, createdAt)
      .putNotNull(PodContextDboFields.createdBy, createdBy)

    private fun Document.toDbo(): PodContextDbo = PodContextDbo(
      id = getObjectId(PodContextDboFields.id),
      podId = getObjectId(PodContextDboFields.podId),
      contextUri = getString(PodContextDboFields.contextUri),
      label = getString(PodContextDboFields.label),
      description = getString(PodContextDboFields.description),
      // The two-argument overload on purpose here, unlike `PodServiceAuditLogDao.statusCode`: a row
      // written before the flag existed carries no field, and "private" is the answer the DBO's
      // KDoc promises for it — not an error and not `true`.
      isPublic = getBoolean(PodContextDboFields.isPublic, false),
      // Every row has one — it is non-null in the entity and has been since the collection existed.
      // Failing loudly beats defaulting to `now`, which would silently restamp a corrupt row.
      createdAt = checkNotNull(getInstant(PodContextDboFields.createdAt)) {
        "context row without createdAt: ${getString(PodContextDboFields.contextUri)}"
      },
      createdBy = getString(PodContextDboFields.createdBy),
    )
  }
}
