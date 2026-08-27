package org.sempods.pods.mongo.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.sempods.rdf.RdfWriterUtil
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import java.net.URI
import java.time.Instant

/**
 * Data access for `resources`, keyed per `(podId, resourceUri, context)` — the pod's durable
 * persistence, from which the volatile MemoryStore is rebuilt (see `pods/write-through.md`).
 * Writes are serialized per pod by the repository write lock, so the fetch-then-save upsert needs
 * no extra concurrency control.
 *
 * The collection was `pods.resources.backup` and the class is still named for it. Neither word
 * was ever accurate: nothing else holds this data, so it is the primary copy rather than a backup
 * of one — which is why the collection dropped both. Renaming the class is a separate edit and a
 * larger one; the maintainer's internal roadmap is where the shape of this row changes anyway.
 *
 * **On the MongoDB driver, mapped by hand** — see `sempods-commons-mongo/docs/document-contract.md`. The document this writes is identical
 * to what Morphia's `PojoCodec` wrote for the same entity — field order included — which is pinned
 * without a database by a wire-format test on the mapping side. No stored row was rewritten, and a
 * process on either side of the change reads what the other wrote.
 *
 * The collection with the most bytes in it and the simplest mapping: every field is non-null, so
 * nothing here is omitted and nothing has to be defaulted on the way back.
 */
class RdfResourceBackupDao internal constructor(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states.
   */
  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.RESOURCES)

  private val backups = db.getCollection(collectionName)

  init {
    // The two indexes `@Indexes` declared, with the same options — measured against the running
    // database, which carries `podId_1_resourceUri_1_context_1` (unique) and `podId_1_context_1`.
    // `createIndex` throws `IndexOptionsConflict` against an existing index whose *options* differ,
    // and that failure lands at boot rather than at the first query, so they are reproduced exactly
    // rather than equivalently.
    backups.createIndex(
      Indexes.ascending(
        RdfResourceBackupDboFields.podId,
        RdfResourceBackupDboFields.resourceUri,
        RdfResourceBackupDboFields.context,
      ),
      IndexOptions().unique(true),
    )
    backups.createIndex(
      Indexes.ascending(RdfResourceBackupDboFields.podId, RdfResourceBackupDboFields.context),
    )
  }

  /**
   * Insert or replace the backup of a resource's statements in one context with its N-Quads.
   *
   * Still fetch-then-write rather than a `$set` upsert, and deliberately: an update built from the
   * key fields would let Mongo compose the inserted document out of the filter plus the update,
   * which lays the fields out in a different order than the one already on disk. Replacing a whole
   * document keeps [toDocument] the single writer of the layout.
   *
   * `upsert(true)` on the replace is what `datastore.save()` did with a set `_id`. Under the
   * repository write lock the row cannot vanish between the fetch and the write for the same pod —
   * but the pod-cascade delete runs outside that lock, and re-inserting is what Morphia did there.
   *
   * Returns `true` when this call created the row — read off the write itself (`upsertedId` is set
   * exactly when the upsert inserted) rather than off the fetch. The two disagree, and the write is
   * the one telling the truth: the fetch is guarded only by a process-local lock, so another replica
   * can drop the row in between, and the replace then re-creates it while the fetch still says it
   * was there. Counting from the fetch would suppress that `+1` and leave the count permanently
   * short by one, which raises nothing but costs exactly that much detection for good.
   *
   * The fetch stays for what only it can do — carrying the existing `_id` so the replace lands on
   * the same document.
   */
  internal fun upsert(podId: ObjectId, resourceUri: URI, context: URI, nquads: String, updatedAt: Instant = Instant.now()): Boolean {
    val existing = fetch(podId, resourceUri, context)
    val dbo = existing?.copy(nquads = nquads, updatedAt = updatedAt)
      ?: RdfResourceBackupDbo(
        id = ObjectId(),
        podId = podId,
        resourceUri = resourceUri.toString(),
        context = context.toString(),
        nquads = nquads,
        updatedAt = updatedAt,
      )
    return backups.replaceOne(
      Filters.eq(RdfResourceBackupDboFields.id, dbo.id),
      dbo.toDocument(),
      ReplaceOptions().upsert(true),
    ).upsertedId != null
  }

  /**
   * Remove the backup of a resource's statements in one context. Idempotent, and `false` when the
   * row was already gone — the retry in `BackupSinkPodChangeListener` re-runs a whole set, so a
   * second pass over the same delete must not count as a second row removed.
   *
   * One command where Morphia needed two: `deleteOne` over the key filter is exactly the
   * fetch-then-delete-by-id it replaces, because the triple carries a unique index and therefore
   * matches at most one row.
   */
  internal fun delete(podId: ObjectId, resourceUri: URI, context: URI): Boolean =
    backups.deleteOne(keyFilter(podId, resourceUri, context)).deletedCount > 0L

  internal fun fetch(podId: ObjectId, resourceUri: URI, context: URI): RdfResourceBackupDbo? =
    backups.find(keyFilter(podId, resourceUri, context))
      .first()
      ?.toDbo()

  /** All backup rows of a pod — the whole-pod recovery read path and the cascade-delete scope. */
  internal fun fetchAllByPod(podId: ObjectId): List<RdfResourceBackupDbo> =
    find(Filters.eq(RdfResourceBackupDboFields.podId, podId))

  /** All backup rows of a single resource, across its contexts — what a restart would recover for it. */
  internal fun fetchByPodAndResource(podId: ObjectId, resourceUri: URI): List<RdfResourceBackupDbo> =
    find(
      Filters.and(
        Filters.eq(RdfResourceBackupDboFields.podId, podId),
        Filters.eq(RdfResourceBackupDboFields.resourceUri, resourceUri.toString()),
      ),
    )

  /**
   * How many rows the pod has, without materializing any of them.
   *
   * The one caller is `PodRepositoryCache`, re-checking a shortfall before it raises one: the rows
   * and the pod's recorded count are two documents written one after the other, so a recovery that
   * lands inside a concurrent write sees a gap that closes by itself moments later. Counting is
   * enough to tell that apart from a real one, and the `(podId, …)` index answers it without
   * reading an N-Quads blob — which `fetchAllByPod` would, for every row of the pod.
   */
  internal fun countByPod(podId: ObjectId): Long =
    backups.countDocuments(Filters.eq(RdfResourceBackupDboFields.podId, podId))

  /**
   * Drop every backup row of a pod. Part of pod deletion — otherwise the N-Quads outlive the pod.
   *
   * `deleteMany`, because Morphia's `deleteAll()` is `DeleteOptions().multi(true)`: a `deleteOne`
   * carried over here would leave all but one row of the pod behind, on the one path that exists
   * to remove them.
   */
  internal fun deleteByPod(podId: ObjectId): Long =
    backups.deleteMany(Filters.eq(RdfResourceBackupDboFields.podId, podId)).deletedCount

  private fun find(filter: Bson): List<RdfResourceBackupDbo> =
    backups.find(filter).map { it.toDbo() }.toList()

  private companion object {

    private fun keyFilter(podId: ObjectId, resourceUri: URI, context: URI): Bson = Filters.and(
      Filters.eq(RdfResourceBackupDboFields.podId, podId),
      Filters.eq(RdfResourceBackupDboFields.resourceUri, resourceUri.toString()),
      Filters.eq(RdfResourceBackupDboFields.context, context.toString()),
    )

    /**
     * The field order Morphia wrote, kept because a row that differs from its neighbours only in
     * order reads differently in a dump. Pinned by that wire-format test.
     */
    private fun RdfResourceBackupDbo.toDocument(): Document = Document()
      .putNotNull(RdfResourceBackupDboFields.id, id)
      .putNotNull(RdfResourceBackupDboFields.podId, podId)
      .putNotNull(RdfResourceBackupDboFields.resourceUri, resourceUri)
      .putNotNull(RdfResourceBackupDboFields.context, context)
      .putNotNull(RdfResourceBackupDboFields.nquads, nquads)
      .putInstant(RdfResourceBackupDboFields.updatedAt, updatedAt)

    private fun Document.toDbo(): RdfResourceBackupDbo = RdfResourceBackupDbo(
      id = getObjectId(RdfResourceBackupDboFields.id),
      podId = getObjectId(RdfResourceBackupDboFields.podId),
      resourceUri = getString(RdfResourceBackupDboFields.resourceUri),
      context = getString(RdfResourceBackupDboFields.context),
      // Defaulted rather than checked: an absent `nquads` and an empty one mean the same thing to
      // every reader below — a resource with no statements in this context — and `reconstructModel`
      // parses "" into the empty model.
      nquads = getString(RdfResourceBackupDboFields.nquads) ?: "",
      // Non-null in the entity since the collection existed. Failing loudly beats defaulting to
      // `now`, which would make a corrupt row look like the freshest copy of a resource.
      updatedAt = checkNotNull(getInstant(RdfResourceBackupDboFields.updatedAt)) {
        "backup row without updatedAt: ${getObjectId(RdfResourceBackupDboFields.id)}"
      },
    )
  }
}

/**
 * Reconstruct a [Model] from backup rows: parse each row's N-Quads and merge them. Each row is
 * self-contained per context (N-Quads carry the named graph, and blank nodes are forbidden), so a
 * plain union restores the model — whether [rows] is a whole pod ([RdfResourceBackupDao.fetchAllByPod])
 * or a single resource ([RdfResourceBackupDao.fetchByPodAndResource]).
 */
internal fun reconstructModel(rows: List<RdfResourceBackupDbo>): Model {
  val model = LinkedHashModel()
  rows.forEach { model.addAll(RdfWriterUtil.readNQuads(it.nquads.byteInputStream())) }
  return model
}
