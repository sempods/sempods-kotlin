package org.sempods.pods.media.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.time.Instant
import java.util.Date

/**
 * The media registry — one row per (pod, media), on the MongoDB driver.
 *
 * **Driver rather than Morphia, deliberately.** Every other collection in `:sempods` is still
 * Morphia-mapped and had to be migrated one by one; a collection born now has
 * no reason to join that queue. The `commons-mongo` document helpers ([putInstant], [putStrings],
 * [putNotNull]) carry the same write conventions Morphia's codec follows, so the row looks like its
 * neighbours: **a field that says nothing is not written** — no `null`, no `[]`.
 *
 * **This DAO is bound unconditionally**, even on a deployment with no blob store. It is what keeps
 * `SEMPODS_MEDIA_BACKEND=none` from breaking the injector: the lifecycle cascades —
 * [removeContextFromAll] on a context deletion and [markPodUnreferenced] on a pod deletion — are
 * pure registry work and touch no byte, so `PodFacade` and `SempodsFacade` inject this DAO directly.
 * Only what needs the store lives behind the store's binding.
 */
class PodMediaDao(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `sempods-commons-mongo/docs/document-contract.md` §"Conventions" states.
   */
  @Inject
  constructor(db: MongoDatabase) : this(db, SempodsCollections.MEDIA)

  private val media = db.getCollection(collectionName)

  init {
    // One row per (pod, media): the id is a content hash, so the same bytes uploaded twice into one
    // pod are one object with two context assignments, not two rows.
    media.createIndex(Indexes.ascending(POD_ID, MEDIA_ID), IndexOptions().unique(true))
    // The read filter and the context cascade both ask "which of this pod's media carry context X".
    media.createIndex(Indexes.ascending(POD_ID, "$ASSIGNMENTS.$CONTEXT"))
    // The sweep asks the other question: which rows have been unreferenced since before a cutoff.
    // Sparse, because the field is absent on every referenced row — which is most of them.
    media.createIndex(Indexes.ascending(UNREFERENCED_SINCE), IndexOptions().sparse(true))
  }

  internal fun find(podId: ObjectId, mediaId: String): PodMedia? =
    media.find(keyFilter(podId, mediaId)).firstOrNull()?.toPodMedia()

  /**
   * Create the row for [podMedia] if this pod does not have one, and assign [context] either way.
   * Returns the row as it now stands.
   *
   * **One atomic statement, because "store the same bytes twice" has to be idempotent under
   * concurrency, not only in sequence.** Read-then-insert would let two uploads of identical
   * content both see no row; one would win the unique index on `(podId, mediaId)` and the other
   * would get a duplicate-key error instead of its context assignment — the promised idempotency
   * broken by exactly the case content addressing exists to make cheap.
   *
   * Only `size` is written on insert — it is the one descriptive field the bytes themselves settle,
   * so an existing row and a new one cannot disagree about it. Everything else travels with
   * [assignment], which is what keeps a second store of the same bytes from reading back the first
   * uploader's description; see [MediaAssignment].
   */
  internal fun upsertAndAssign(podMedia: PodMedia, assignment: MediaAssignment): PodMedia {
    // Replacing rather than `$addToSet`: an assignment is identified by its context, so re-storing
    // into a context you already hold must update your description, not leave two entries that
    // differ only in `createdAt`. Two statements in a pipeline so both see one document state.
    val row = media.findOneAndUpdate(
      keyFilter(podMedia.podId, podMedia.mediaId),
      listOf(
        set(SIZE, expr("\$ifNull", listOf("\$$SIZE", podMedia.size))),
        set(ASSIGNMENTS, expr("\$concatArrays", listOf(without(assignment.context), listOf(assignment.toDocument())))),
        // The row is referenced again by definition; on an insert the field is absent anyway.
        Document("\$unset", UNREFERENCED_SINCE),
      ),
      FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
    )
    return checkNotNull(row) { "upsert returned no document for media ${podMedia.mediaId}" }.toPodMedia()
  }

  /**
   * Add [assignment] to an existing media, and clear [PodMedia.unreferencedSince] because a row with
   * an assignment is by definition referenced again.
   *
   * **An assignment that is already there is left exactly as it is** — not replaced with an
   * otherwise-identical one carrying a fresh `createdAt`. This is the context-copy path, which the
   * endpoint and the roadmap both promise is idempotent, and "changes nothing" has to mean the
   * stored row too: a retry that keeps rewriting the timestamp is a retry that keeps changing the
   * answer. `upsertAndAssign` does replace, and should — there the caller supplied a new
   * description on purpose.
   *
   * Returns `false` when no such row exists.
   */
  internal fun addAssignment(podId: ObjectId, mediaId: String, assignment: MediaAssignment): Boolean =
    media.updateOne(
      keyFilter(podId, mediaId),
      listOf(
        set(
          ASSIGNMENTS,
          cond(
            hasContext(assignment.context),
            then = allAssignments(),
            otherwise = expr("\$concatArrays", listOf(allAssignments(), listOf(assignment.toDocument()))),
          ),
        ),
        Document("\$unset", UNREFERENCED_SINCE),
      ),
    ).matchedCount > 0

  /**
   * Remove [context] from a media's assignments and, **in the same operation**, stamp
   * [PodMedia.unreferencedSince] if that emptied the set — see [unassignPipeline] for why the two
   * cannot be separate statements.
   *
   * Returns `false` when no such row exists.
   */
  internal fun removeContext(podId: ObjectId, mediaId: String, context: String, now: Instant): Boolean =
    media.updateOne(keyFilter(podId, mediaId), unassignPipeline(context, now)).matchedCount > 0

  /**
   * Remove [context] from **every** media of [podId] that carries it, stamping the ones this leaves
   * unreferenced. The context cascade `PodFacade.removeContext` drives, beside the grant revocation.
   *
   * Returns how many rows carried the context.
   *
   * The same pipeline as [removeContext], over an `updateMany` — a media may be assigned to a
   * context alongside others, so this is exactly the per-row operation applied to a set, not a
   * different rule. Pre-filtered on the assignment so rows that never named the context are not
   * rewritten; without it every media of the pod would take a write for nothing, and an
   * already-unreferenced row would be re-evaluated on every unrelated context deletion.
   */
  internal fun removeContextFromAll(podId: ObjectId, context: String, now: Instant): Long =
    media.updateMany(
      Filters.and(Filters.eq(POD_ID, podId), Filters.eq("$ASSIGNMENTS.$CONTEXT", context)),
      unassignPipeline(context, now),
    ).matchedCount

  /**
   * Mark every media of a deleted pod unreferenced, so the sweep's grace period picks them up —
   * driven by `SempodsFacade.deletePod`.
   *
   * **Not a delete**, and that is the point: the grace period cushions an accidental pod deletion
   * the same way it cushions an accidental unassign. The rows stay, still carrying `podId` and
   * `mediaId` — which is the [PodMediaRef][org.sempods.pods.media.PodMediaRef] the store is
   * addressed by, so the sweep needs nothing else to reach the bytes. No tombstone of its own: the
   * media row *is* the tombstone.
   *
   * **The assignments go**, because the invariant everything downstream reads is "stamped ⟺ no
   * assignment". The pod's contexts are deleted in the same cascade, so a row still naming them
   * would claim to be referenced by contexts that no longer exist — and [findUnreferencedBefore]
   * would hand out rows whose own data contradicts the answer.
   *
   * `$ifNull` on the stamp for the same reason as in [removeContext]: a row that has been
   * unreferenced for 29 days must not have its grace period restarted by the pod deletion.
   *
   * Returns how many rows were marked.
   */
  internal fun markPodUnreferenced(podId: ObjectId, now: Instant): Long =
    media.updateMany(
      Filters.eq(POD_ID, podId),
      listOf(
        set(ASSIGNMENTS, REMOVE),
        set(UNREFERENCED_SINCE, expr("\$ifNull", listOf("\$$UNREFERENCED_SINCE", Date.from(now)))),
      ),
    ).matchedCount

  /**
   * Delete the row **only if** it is still unreferenced from before [cutoff]; `false` when it is
   * not, and when there is none.
   *
   * The condition is the sweep's compare-and-swap and the whole reason the sweep can run while the
   * server serves: an upload or a context-copy between the candidate query and this call clears
   * `unreferencedSince`, the filter stops matching, and the bytes are not touched. A plain delete by
   * key would race exactly that window and take away an object somebody had just re-assigned.
   */
  internal fun deleteIfUnreferencedBefore(podId: ObjectId, mediaId: String, cutoff: Instant): Boolean =
    media.deleteOne(
      Filters.and(keyFilter(podId, mediaId), Filters.lt(UNREFERENCED_SINCE, Date.from(cutoff))),
    ).deletedCount > 0

  /**
   * Rows unreferenced strictly before [cutoff] — the sweep's candidate set — for one pod or, with
   * [podId] `null`, for every pod including the ones that no longer exist. That last case is what
   * the sweep is for: after `SempodsFacade.deletePod` the rows outlive their pod by the grace
   * period, and nothing driven by the pod registry would ever see them again.
   *
   * Strictly before, so a caller passing `now` does not pick up a row that was unreferenced in the
   * same millisecond it is asking about.
   */
  internal fun findUnreferencedBefore(cutoff: Instant, podId: ObjectId? = null): List<PodMedia> =
    media.find(scoped(Filters.lt(UNREFERENCED_SINCE, Date.from(cutoff)), podId))
      .map { it.toPodMedia() }
      .toList()

  /**
   * The pods that still have at least one *assigned* media — the input to the sweep's check for rows
   * that outlived their pod without being marked.
   *
   * `markPodUnreferenced` covers the rows that exist when `SempodsFacade.deletePod` runs, and an
   * upload already in flight can commit one just after it. Such a row names contexts that no longer
   * exist, carries no `unreferencedSince`, and is therefore invisible to
   * [findUnreferencedBefore] for ever. Asking which pods hold assigned media is what lets the sweep
   * notice, and it is cheap: `podId` is the prefix of the unique index, and the answer is bounded by
   * the number of pods rather than by the number of media.
   */
  internal fun findPodIdsWithAssignments(): Set<ObjectId> =
    media.distinct(POD_ID, Filters.exists(ASSIGNMENTS), ObjectId::class.java).toSet()

  /**
   * Hands [consume] every row of one pod, or of all of them when [podId] is `null` — the registry
   * half of the reconcile report.
   *
   * Scoped rather than returning a `Sequence`, for the reason
   * [org.sempods.pods.media.PodMediaStore.iterate] states about its own signature: laziness over a
   * resource has to be closed, and here the resource is a database cursor. The sequence is valid
   * only inside [consume].
   */
  internal fun <T> iterate(podId: ObjectId? = null, consume: (Sequence<PodMedia>) -> T): T =
    media.find(scoped(Filters.empty(), podId)).iterator().use { cursor ->
      consume(cursor.asSequence().map { it.toPodMedia() })
    }

  /**
   * The two stages that make "pull the assignment" and "stamp if that emptied it" one statement.
   *
   * An aggregation-pipeline update rather than two statements, and that is the point: pulling first
   * and then testing the size would leave a window in which the row is unreferenced and does not say
   * so — a sweep running inside it would skip the row, and the next one would only catch it if
   * something wrote the field in between. Nothing would, so the object would be orphaned for good.
   * The second stage sees the first stage's output, so the test and the stamp cannot come apart.
   *
   * **The timestamp marks the transition, not the state.** An already-unreferenced row keeps the
   * stamp it has, because the field answers "since when" and the sweep's grace period counts from
   * it. Re-stamping on every call would let a repeated `unassign`, or a context cascade sweeping
   * over rows that are already unreferenced, push the deletion out indefinitely — the bytes would
   * never become collectable, and nothing would look wrong.
   *
   * Both fields are *removed* rather than written empty when they carry nothing — `$$REMOVE` for
   * the timestamp and for the emptied array — which is the `commons-mongo` convention this
   * collection follows everywhere else.
   */
  private fun unassignPipeline(context: String, now: Instant): List<Document> {
    val remaining = without(context)

    // Stage 1 — the assignments minus this context's, or the field removed when that leaves none.
    val pullAssignment = set(
      ASSIGNMENTS,
      cond(ifEmpty(remaining), then = REMOVE, otherwise = remaining),
    )

    // Stage 2 — `$assignments` is now what stage 1 wrote, so "did that empty it" needs no second
    // filter, only the same guard against the field stage 1 may have removed: `$size` of a missing
    // field is an error rather than zero, which is how unassigning twice used to throw instead of
    // being the no-op the caller is promised.
    // `$ifNull` on the existing stamp is what makes this the *transition*: a row that was already
    // unreferenced keeps the moment it became so, instead of restarting its grace period.
    val stampIfUnreferenced = set(
      UNREFERENCED_SINCE,
      cond(
        ifEmpty(allAssignments()),
        then = expr("\$ifNull", listOf("\$$UNREFERENCED_SINCE", Date.from(now))),
        otherwise = REMOVE,
      ),
    )

    return listOf(pullAssignment, stampIfUnreferenced)
  }

  /** [filter] narrowed to one pod, or left alone when [podId] is `null` ("every pod, live or not"). */
  private fun scoped(filter: Bson, podId: ObjectId?) =
    if (podId == null) filter else Filters.and(Filters.eq(POD_ID, podId), filter)

  private fun keyFilter(podId: ObjectId, mediaId: String) =
    Filters.and(Filters.eq(POD_ID, podId), Filters.eq(MEDIA_ID, mediaId))

  private fun Document.toPodMedia(): PodMedia = PodMedia(
    podId = getObjectId(POD_ID),
    mediaId = getString(MEDIA_ID),
    size = getLong(SIZE),
    assignments = getAssignments(),
    unreferencedSince = getInstant(UNREFERENCED_SINCE),
  )

  @Suppress("UNCHECKED_CAST")
  private fun Document.getAssignments(): Set<MediaAssignment> =
    (get(ASSIGNMENTS) as? List<Document> ?: emptyList()).mapTo(HashSet()) { it.toAssignment(getString(MEDIA_ID)) }

  private fun Document.toAssignment(mediaId: String) = MediaAssignment(
    context = getString(CONTEXT),
    contentType = getString(CONTENT_TYPE),
    filename = getString(FILENAME),
    createdAt = checkNotNull(getInstant(CREATED_AT)) { "media assignment without createdAt: $mediaId" },
    createdBy = getString(CREATED_BY),
  )

  companion object {

    /**
     * The aggregation-expression spellings [removeContext] needs. Named rather than inlined because
     * a four-deep nest of `Document("\$cond", listOf(...))` is unreadable at exactly the place where
     * being able to read it is the whole argument for the pipeline.
     */
    private const val REMOVE = "\$\$REMOVE"

    private fun expr(operator: String, argument: Any): Document = Document(operator, argument)

    private fun set(field: String, value: Any): Document = expr("\$set", Document(field, value))

    private fun cond(condition: Any, then: Any, otherwise: Any): Document =
      expr("\$cond", listOf(condition, then, otherwise))

    private fun ifEmpty(collection: Any): Document =
      expr("\$eq", listOf(expr("\$size", collection), 0))

    /**
     * The assignment array, or an empty one. `$ifNull` everywhere it is read: a row that is already
     * unreferenced has no `assignments` field at all, and `$filter`, `$map` or `$size` over a
     * missing input fails the whole update rather than treating it as empty.
     */
    private fun allAssignments(): Document =
      expr("\$ifNull", listOf("\$$ASSIGNMENTS", emptyList<Document>()))

    /** The assignments except the one for [context] — "replace this context's entry" and "remove it". */
    private fun without(context: String): Document = expr(
      "\$filter",
      Document("input", allAssignments())
        .append("as", "assignment")
        .append("cond", expr("\$ne", listOf("\$\$assignment.$CONTEXT", context))),
    )

    /** Whether [context] already has an assignment on this row. */
    private fun hasContext(context: String): Document = expr(
      "\$in",
      listOf(
        context,
        expr("\$map", Document("input", allAssignments()).append("as", "assignment").append("in", "\$\$assignment.$CONTEXT")),
      ),
    )

    private fun MediaAssignment.toDocument(): Document = Document()
      .append(CONTEXT, context)
      .append(CONTENT_TYPE, contentType)
      .putNotNull(FILENAME, filename)
      .putInstant(CREATED_AT, createdAt)
      .putNotNull(CREATED_BY, createdBy)

    private const val POD_ID = "podId"
    private const val MEDIA_ID = "mediaId"
    private const val SIZE = "size"
    private const val UNREFERENCED_SINCE = "unreferencedSince"

    /** The assignment array and the fields of one of its entries. */
    private const val ASSIGNMENTS = "assignments"
    private const val CONTEXT = "context"
    private const val CONTENT_TYPE = "contentType"
    private const val FILENAME = "filename"
    private const val CREATED_AT = "createdAt"
    private const val CREATED_BY = "createdBy"
  }
}
