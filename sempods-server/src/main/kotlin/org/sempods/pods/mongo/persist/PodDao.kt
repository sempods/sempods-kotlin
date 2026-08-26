package org.sempods.pods.mongo.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Updates
import org.sempods.SempodsCollections
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.sempods.SempodsUriBuilder
import org.bson.Document
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Persistence for [PodDbo] — the pod registry, and the row every other collection is keyed by.
 *
 * **On the MongoDB driver, mapped by hand** — see `sempods-commons-mongo/docs/document-contract.md`. The document this writes is identical
 * to what Morphia's `PojoCodec` wrote for the same entity — field order included — which is pinned
 * without a database by a wire-format test on the mapping side. No stored row was rewritten, and a
 * process on either side of the change reads what the other wrote.
 */
class PodDao(
  db: MongoDatabase,
  private val webIdUriDeriver: WebIdUriDeriver,
  collectionName: String,
) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states.
   */
  @Inject
  constructor(db: MongoDatabase, webIdUriDeriver: WebIdUriDeriver) : this(db, webIdUriDeriver, SempodsCollections.PODS)

  private val pods = db.getCollection(collectionName)

  init {
    // The one index `@Indexes` declared, with the same options — measured against the running
    // database, which carries `name_1` (unique). `createIndex` throws `IndexOptionsConflict`
    // against an existing index whose *options* differ, and that failure lands at boot rather than
    // at the first query.
    pods.createIndex(Indexes.ascending(PodDboFields.name), IndexOptions().unique(true))
  }

  /**
   * Registers a pod and returns the row **carrying the id it was stored under**.
   *
   * The generated id is not optional here the way it was for the append-only collections:
   * `SempodsTestFactory.newPod` and every caller downstream of it key their pod-scoped rows by it,
   * and `datastore.save()` used to write it back into the instance it was handed. `insertOne` does
   * not, so the id is minted before the write instead of read off it.
   *
   * A second pod under the same name is rejected by the unique index, as before — the write throws
   * rather than silently replacing the existing registration.
   */
  internal fun insert(name: String, owner: String): PodDbo {
    SempodsUriBuilder.checkPodName(name)
    webIdUriDeriver.requireEmailBasedWebId(owner)
    val dbo = PodDbo(id = ObjectId(), name = name, owner = owner, resourceRowCount = 0L)
    pods.insertOne(dbo.toDocument())
    return dbo
  }

  internal fun setOwner(podId: ObjectId, owner: String) {
    webIdUriDeriver.requireEmailBasedWebId(owner)
    pods.updateOne(
      Filters.eq(PodDboFields.id, podId),
      Updates.set(PodDboFields.owner, owner),
    )
  }

  /**
   * Sets the human-readable display name advertised in the pod's RFC 9728
   * protected-resource metadata. Pass a blank string to clear it.
   */
  internal fun setDisplayName(podId: ObjectId, displayName: String) {
    pods.updateOne(
      Filters.eq(PodDboFields.id, podId),
      Updates.set(PodDboFields.displayName, displayName),
    )
  }

  internal fun fetchByName(name: String): PodDbo? =
    pods.find(Filters.eq(PodDboFields.name, name)).first()?.toDbo()

  internal fun fetchById(id: ObjectId): PodDbo? =
    pods.find(Filters.eq(PodDboFields.id, id)).first()?.toDbo()

  /**
   * The pod's `dateModified`, without reading the rest of the row.
   *
   * The one projected read in the milestone. It stays projected because the caller is
   * `PodMetaEndpoint`, which answers a conditional GET from this single field — and reads the
   * value off the document directly rather than through [toDbo], which a partial document could
   * not satisfy.
   */
  internal fun fetchLastModifiedAt(name: String): Instant? =
    pods.find(Filters.eq(PodDboFields.name, name))
      .projection(Projections.include(PodDboFields.lastModifiedAt))
      .first()
      ?.getInstant(PodDboFields.lastModifiedAt)

  internal fun fetchAll(): List<PodDbo> = pods.find().map { it.toDbo() }.toList()

  internal fun updateLastModifiedAt(name: String, lastModifiedAt: Instant = Instant.now()) {
    pods.updateOne(
      Filters.eq(PodDboFields.name, name),
      Updates.set(PodDboFields.lastModifiedAt, lastModifiedAt),
    )
  }

  /**
   * Moves [PodDbo.resourceRowCount] by [delta] — the net number of `resources` rows one committed
   * change set created or removed.
   *
   * `$inc` rather than a read-modify-write: it is atomic, so two replicas writing the same pod
   * cannot lose a delta between them. On a row that predates the field Mongo creates it from `0`,
   * which is the safe direction — such a pod's count starts *below* what the collection holds, and
   * a count that is too low never raises the alarm in `PodRepositoryCache`. The first recovery
   * corrects it.
   */
  internal fun bumpResourceRowCount(podId: ObjectId, delta: Long) {
    pods.updateOne(
      Filters.eq(PodDboFields.id, podId),
      Updates.inc(PodDboFields.resourceRowCount, delta),
    )
  }

  /**
   * Sets [PodDbo.resourceRowCount] to what a recovery actually loaded — but only while it still
   * holds [expected]. Returns whether the write landed.
   *
   * The one caller is `PodRepositoryCache`, and re-baselining there is what keeps the alarm a
   * report of an event rather than a state a pod cannot leave: without it a pod whose rows were
   * restored through the write path would count them twice — the stale number plus the ones it just
   * saw arrive — and report a loss on every recovery from then on.
   *
   * **Compare-and-set rather than a plain `$set`**, because a recovery's number is a snapshot and
   * another process may be writing the same pod while it is taken. A bare `$set` would discard a
   * concurrent [bumpResourceRowCount] — and a discarded *negative* delta leaves the count above the
   * collection, which is the one direction that raises a false alarm. Guarding on the value that
   * was read means a delta landing in between makes this miss instead, which costs nothing: the
   * count is then the writer's, which is the newer of the two.
   *
   * `Filters.eq(field, null)` matches an absent field as well as a stored null, so [expected] of
   * `null` is the guard for a row that predates the count.
   */
  internal fun setResourceRowCount(podId: ObjectId, expected: Long?, count: Long): Boolean =
    pods.updateOne(
      Filters.and(
        Filters.eq(PodDboFields.id, podId),
        Filters.eq(PodDboFields.resourceRowCount, expected),
      ),
      Updates.set(PodDboFields.resourceRowCount, count),
    ).matchedCount > 0L

  /**
   * Raises [PodDbo.resourceRowCount] to [count], and only ever raises: the update matches a row
   * whose count is absent or below [count], never one already at or above it. Returns whether it
   * landed.
   *
   * This is how a pod that predates the field gets its first count, and the condition is the whole
   * of what makes that safe to issue blind. Two things can have touched the row since the recovery
   * read it as absent, and they need opposite answers: a stray `$inc` (which *creates* the field)
   * leaves a small number that has to be lifted to the loaded rows, while another replica recovering
   * the same pod leaves the loaded rows themselves, which must be left exactly alone. A
   * compare-and-set cannot tell them apart, and adding on top turns the second into double the
   * collection — a false loss of the pod's whole history at the next recovery. Comparing against the
   * value instead answers both: below means it is not a baseline yet, at or above means it is.
   *
   * A **negative** value is a third thing and is deliberately overwritten rather than preserved: a
   * count cannot legitimately go below zero, so it can only be a delete set's up-front subtraction
   * landing on a row that never had a baseline. Keeping it looks like the careful choice and is not,
   * because the sink subtracts *before* it removes the row, so which way the arithmetic errs depends
   * on where the caller's row read fell:
   *
   * | | rows read before the delete | rows read after it |
   * |---|---|---|
   * | overwriting (what this does) | one too high — reported by the next recovery and lowered | exact |
   * | preserving the subtraction | exact | one too low — nothing ever reports it |
   *
   * Overwriting is exact or self-correcting; preserving is exact or silently short for good. Every
   * outcome of this update is therefore one a later recovery can reach, and it can never write a
   * count above what the caller loaded, so it cannot manufacture an alarm out of nothing either.
   */
  internal fun raiseResourceRowCountTo(podId: ObjectId, count: Long): Boolean =
    pods.updateOne(
      Filters.and(
        Filters.eq(PodDboFields.id, podId),
        Filters.or(
          Filters.exists(PodDboFields.resourceRowCount, false),
          Filters.lt(PodDboFields.resourceRowCount, count),
        ),
      ),
      Updates.set(PodDboFields.resourceRowCount, count),
    ).matchedCount > 0L

  /**
   * Removes the registration. Returns `true` if a row was actually deleted.
   *
   * `deleteOne` rather than the `deleteMany` the other cascades take: the filter is the unique
   * index, so it can match at most one row, and Morphia's `deleteAll()` had nothing more to remove
   * here either.
   */
  internal fun delete(name: String): Boolean =
    pods.deleteOne(Filters.eq(PodDboFields.name, name)).deletedCount > 0L

  private companion object {

    /**
     * The field order Morphia wrote, kept because a row that differs from its neighbours only in
     * order reads differently in a dump. Pinned by that wire-format test.
     *
     * `resourceRowCount` came after Morphia and is therefore written last: appending leaves every
     * field a stored row already carries exactly where it is.
     */
    private fun PodDbo.toDocument(): Document = Document()
      .putNotNull(PodDboFields.id, id)
      .putNotNull(PodDboFields.name, name)
      .putNotNull(PodDboFields.owner, owner)
      .putNotNull(PodDboFields.displayName, displayName)
      .putInstant(PodDboFields.lastModifiedAt, lastModifiedAt)
      .putNotNull(PodDboFields.resourceRowCount, resourceRowCount)

    private fun Document.toDbo(): PodDbo = PodDbo(
      id = getObjectId(PodDboFields.id),
      name = getString(PodDboFields.name),
      owner = getString(PodDboFields.owner),
      // Null for a pod that never carried a label, `""` for one whose label was cleared — the two
      // are different states on the wire and the RFC 9728 metadata renders them differently.
      displayName = getString(PodDboFields.displayName),
      // Every row has one — it is non-null in the entity and has been since the collection existed.
      // Failing loudly beats defaulting to `now`, which would make every conditional GET on a
      // corrupt row answer "modified just now" and never serve a 304 again.
      lastModifiedAt = checkNotNull(getInstant(PodDboFields.lastModifiedAt)) {
        "pod row without lastModifiedAt: ${getString(PodDboFields.name)}"
      },
      // Absent on every row written before the field existed, and `null` says exactly that — not
      // `0`, which would claim the write path had recorded an empty pod and turn the first recovery
      // of such a pod into a false all-clear. Read through `Number` rather than `getLong`, which
      // throws on an Int32: everything writing this field writes an Int64, but a row that stops
      // mapping stops resolving, and this is the collection every other one is keyed by.
      resourceRowCount = (get(PodDboFields.resourceRowCount) as? Number)?.toLong(),
    )
  }
}
