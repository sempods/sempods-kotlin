package org.sempods.pods.media

import com.google.inject.Inject
import org.sempods.pods.media.persist.MediaAssignment
import org.sempods.pods.media.persist.PodMedia
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.toObjectIdOrNull
import org.sempods.pods.mongo.persist.toPodId
import org.bson.types.ObjectId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Media as the rest of the pod server sees it: bytes in the [PodMediaStore], everything else in the
 * [PodMediaDao], and the two kept in step here.
 *
 * **This class requires a media store and is therefore bound only when a deployment configures one**
 * (`SempodsMediaModule`). The registry is bound unconditionally, because the lifecycle cascades on
 * context and pod deletion are pure registry work — see `docs/media.md` §"The seam". Anything
 * that needs bytes belongs here; anything that does not, belongs on the DAO. That split is why
 * [sweepUnreferenced] and [reconcile] live here while the cascades that feed them do not.
 *
 * Authorization is the caller's: this class trusts what it is told about what may be written and
 * read, and [findReadable] is the one concession — it takes the caller's readable contexts rather
 * than deciding for itself. The two batch operations have no context notion at all; they are reached
 * through host-level admin authority instead.
 *
 * **Every method here is `internal`; the class is not, and the asymmetry is deliberate.** The
 * methods speak `ObjectId` — this deployment's storage key — so none of them is callable from
 * outside anyway, and #29 took them out of the published ABI along with the rest of the persistence
 * layer. The *name* is a different matter: a composition selects the media backend, and
 * `SempodsMediaModule` in `:deployments:sempods:image` asserts on the binding for this key
 * (`Key.get(PodMediaFacade::class.java)`) to check that a deployment with no store binds no facade.
 * A composition outside this module has to be able to name what it binds.
 */
class PodMediaFacade @Inject constructor(
  private val mediaStore: PodMediaStore,
  private val dao: PodMediaDao,
  private val podDao: PodDao,
  private val config: PodMediaConfig,
) {

  /**
   * Take [source] into the pod under [context] and return the resulting row.
   *
   * The media id is the base64url SHA-256 of the bytes, which makes the operation **idempotent per
   * pod**: storing the same bytes again finds the existing row and only adds the context
   * assignment. That is the dedup, and it is per pod on purpose — a shared object across pods would
   * make one pod's deletion depend on another's, which is the sovereignty violation this whole
   * design exists to remove.
   *
   * Self-healing on the way: when the row exists but its object does not, the bytes are written
   * again. That is the "registry row without an object" case [reconcile] can otherwise only
   * complain about, repaired by the next upload of the same content.
   *
   * @param source a readable file the caller owns; this method neither moves nor deletes it.
   */
  internal fun store(
    podId: ObjectId,
    context: URI,
    source: Path,
    contentType: String,
    filename: String? = null,
    createdBy: String? = null,
  ): PodMedia {
    val size = Files.size(source)
    require(size <= config.maxUploadBytes) {
      "media exceeds the configured maximum of ${config.maxUploadBytes} bytes: $size"
    }
    require(size > 0) { "refusing to store an empty media object" }
    // The invariant behind every assignment: the recorded type is one the content route can serve.
    // A `require` rather than a request outcome, for the same reason the size limit is one here and
    // a `413` at the endpoint — each ingestion path answers for its own input, and this catches the
    // path that forgot to. Without it an unservable type is accepted at upload and only fails on
    // every later *read*, which is the kind of distance that makes a bug expensive.
    require(isServableMediaType(contentType)) { "not a servable media type: '$contentType'" }

    val ref = PodMediaRef(podId.toPodId(), digest(source))
    val contextUri = context.toString()

    // Bytes before the row: a row whose object is missing is a broken media (a `404` waiting to
    // happen), while an object with no row is inert and the reconcile report picks it up. Of the two
    // orders, this is the one whose failure mode is harmless. Skipped when the object is already
    // there, which is the ordinary dedup case — the bytes cannot differ, the id is their hash.
    if (!mediaStore.exists(ref)) {
      // A row without its object is the "broken media" the reconcile report can otherwise only
      // complain about. Repairing it here is free, and worth saying out loud when it happens.
      if (dao.find(podId, ref.mediaId) != null) {
        logger.warn { "Media ${ref.mediaId} of pod $podId had no object — restoring it from this upload" }
      }
      mediaStore.put(ref, contentType, source)
    }

    val assignment = MediaAssignment(
      context = contextUri,
      contentType = contentType,
      filename = filename,
      // Truncated to milliseconds, which is all a BSON date holds. Without this the object handed
      // back would never equal the one a subsequent `find` returns — `commons-mongo`'s `putInstant`
      // warns about exactly that, and here the caller has both values in hand.
      createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
      createdBy = createdBy,
    )
    val candidate = PodMedia(
      podId = podId,
      mediaId = ref.mediaId,
      size = size,
      // Managed by the upsert; passed as the state an insert would produce.
      assignments = setOf(assignment),
      unreferencedSince = null,
    )

    // One statement, so two concurrent uploads of the same bytes both end up assigned rather than
    // one of them hitting the unique index — see PodMediaDao.upsertAndAssign.
    val stored = dao.upsertAndAssign(candidate, assignment)
    logger.info { "Stored media ${ref.mediaId} (${stored.size} bytes, $contentType) in pod $podId" }
    return stored
  }

  /**
   * The bytes of [podMedia]. The caller closes.
   *
   * Takes the row rather than an id so that the read check has already happened: the only way to
   * get a [PodMedia] is [findReadable] or [store], and neither hands one out to a caller who may not
   * see it.
   */
  internal fun open(podMedia: PodMedia): InputStream = mediaStore.open(podMedia.ref)

  /**
   * Assign an existing media to another context — the "context-copy" that makes one upload visible
   * in a second place without a second copy of the bytes.
   *
   * Returns `false` when the pod holds no such media. **Both permissions are the caller's to check**
   * (write on [context], read on one the media already carries); this method only records the
   * decision.
   *
   * The new assignment describes the media in the caller's own terms: [describedBy] is an assignment
   * they can already read — the two-permission rule guarantees they have one — so the description is
   * copied from what they can see rather than from whatever the first uploader wrote. `createdAt`
   * and `createdBy` are the copy's own, because this assignment is theirs.
   */
  internal fun assign(
    podId: ObjectId,
    mediaId: String,
    context: URI,
    describedBy: MediaAssignment,
    createdBy: String? = null,
  ): Boolean = dao.addAssignment(
    podId,
    mediaId,
    MediaAssignment(
      context = context.toString(),
      contentType = describedBy.contentType,
      filename = describedBy.filename,
      createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
      createdBy = createdBy,
    ),
  )

  /**
   * Drop one context assignment. When it was the last, the row is stamped unreferenced and becomes
   * a candidate for [sweepUnreferenced] — the bytes are **not** deleted here, and that is the grace
   * period the design rests on.
   *
   * Returns `false` when the pod holds no such media.
   */
  internal fun unassign(podId: ObjectId, mediaId: String, context: URI): Boolean =
    dao.removeContext(podId, mediaId, context.toString(), Instant.now())

  /**
   * The media, if this caller may see it — `null` both when it does not exist and when its
   * assignments do not intersect [readableContexts].
   *
   * **The two answers are deliberately the same.** The id is a content hash, so a distinguishable
   * "exists but forbidden" would answer the question "does this pod hold exactly this file" for
   * anyone holding the bytes. This is the same no-topology-leak rule the context registry already
   * follows.
   *
   * [readableContexts] follows `SempodsCredentials.restrictedContexts`: **`null` means
   * unrestricted**, an empty set means nothing is readable. Getting that backwards would deny the
   * one caller who may see everything, so the two are kept apart here rather than normalised away.
   */
  internal fun findReadable(podId: ObjectId, mediaId: String, readableContexts: Set<URI>?): PodMedia? {
    if (readableContexts != null && readableContexts.isEmpty()) return null
    val podMedia = dao.find(podId, mediaId) ?: return null
    if (readableContexts == null) return podMedia
    val readable = readableContexts.mapTo(HashSet()) { it.toString() }
    return podMedia.takeIf { it.contexts.any(readable::contains) }
  }

  /**
   * Delete the bytes of every media that has been unreferenced for longer than [gracePeriod] — the
   * garbage collection the reference counting exists for, driven by the host-level admin route and
   * an operator's cron.
   *
   * **The object goes first, and the row second, conditionally.** Both halves are load-bearing:
   *
   * - **The row is the only record that these bytes are collectable**, so it must outlive every step
   *   that can fail. A failed object delete — an `unlink` error today, an ordinary S3 round trip once
   *   that backend lands — leaves the row untouched and the next run picks the same candidate up.
   *   Deleting the row first turned a transient failure into a permanent leak that only a human
   *   reading a reconcile report would ever find.
   * - The conditional delete ([PodMediaDao.deleteIfUnreferencedBefore]) is what makes this safe to
   *   run against a live server. A plain delete by key would remove a row that had been re-assigned
   *   while its object was going.
   *
   * **The earlier version of this comment had the order the other way round and defended it, so the
   * correction belongs here.** The argument was that a crash between the two must not leave "a row
   * with no object — a broken media for whoever still holds its URL". That compared the wrong two
   * states: the row in question is *unreferenced*, which is why it was a candidate at all, so nobody
   * holds a claim on it — [findReadable] answers `null` for every caller with a context filter, and
   * the next sweep clears it. The genuine alternative was an object with no row: invisible, never
   * retried, and reclaimable only by hand — [reconcile] names it, and nothing in this server removes
   * it.
   *
   * **What no ordering fixes.** Between an upload's `exists` check and its row write, this method
   * can delete the object it saw — the upload skips its `put` on a decision that has gone stale and
   * ends up with a row whose bytes are gone, after being answered `201`. The registry and the store
   * are separate systems with no shared transaction, so any check-then-act across them has a window;
   * closing it would take a lock or a conditional delete at the object store, which is a large
   * mechanism for microseconds in a nightly job.
   *
   * So it is narrowed, detected and left: the row is re-read immediately before the object goes, the
   * conditional delete afterwards distinguishes a re-assignment from the harmless cases, a
   * re-assignment is logged as an error and counted as [MediaSweepReport.brokenMedia], [reconcile]
   * lists it under `rowsWithoutObject`, and [store] repairs it the next time the same content is
   * uploaded. Recorded in `docs/media.md` §"Named limitations".
   *
   * **Nothing here consults `PodDao`, and that is the point of the whole design.** The sweep keys on
   * `unreferencedSince` and `podId` alone, so it reaches the rows of a pod that no longer exists:
   * `SempodsFacade.deletePod` stamps them and then removes the pod, and an enumeration driven by the
   * pod registry would never see them again.
   *
   * @param gracePeriod how long a media stays reclaimable after its last assignment went. Zero is
   *   allowed and means "everything unreferenced up to now" — an operator's deliberate choice, which
   *   is why [PodMediaConfig.sweepGracePeriod] refuses it as a *default*.
   * @param dryRun count and log what would go, delete nothing.
   * @param podId one pod, or every pod when `null` — including the ones that no longer exist.
   */
  internal fun sweepUnreferenced(
    gracePeriod: Duration = config.sweepGracePeriod,
    dryRun: Boolean = false,
    podId: ObjectId? = null,
  ): MediaSweepReport {
    require(!gracePeriod.isNegative) { "gracePeriod must not be negative, got $gracePeriod" }

    val cutoff = Instant.now().minus(gracePeriod)
    val strandedPods = if (dryRun) 0 else markMediaOfDeletedPods(podId)
    val candidates = dao.findUnreferencedBefore(cutoff, podId)

    var deleted = 0
    var revived = 0
    var failed = 0
    var broken = 0
    var reclaimedBytes = 0L

    for (candidate in candidates) {
      if (dryRun) {
        logger.info {
          "[media/audit] outcome=sweep_candidate pod='${candidate.podId}' media='${candidate.mediaId}' size=${candidate.size} unreferenced_since=${candidate.unreferencedSince}"
        }
        reclaimedBytes += candidate.size
        continue
      }

      // Re-read before touching anything. The candidate list was taken in one query and acted on row
      // by row, so an upload or a context-copy in between is the ordinary case rather than a corner
      // one — and a row that re-entered its grace period (re-assigned, then unassigned again with a
      // fresh stamp) must keep its bytes for the *new* period, which is why this compares against
      // the cutoff rather than merely asking whether the field is set.
      val stamp = dao.find(candidate.podId, candidate.mediaId)?.unreferencedSince
      if (stamp == null || !stamp.isBefore(cutoff)) {
        revived++
        continue
      }

      try {
        mediaStore.delete(candidate.ref)
      } catch (e: Exception) {
        // Best-effort per row: one unreachable object must not abandon the rest of the sweep. **The
        // row is deliberately left in place** — it still says `unreferencedSince`, so the next run
        // picks the same candidate up and tries again. Deleting the row first would have thrown away
        // the only record that these bytes are collectable, turning a transient failure (an `unlink`
        // error today, an ordinary S3 round trip once that backend lands) into a permanent leak.
        failed++
        logger.warn(e) {
          "[media/audit] outcome=sweep_object_failed pod='${candidate.podId}' media='${candidate.mediaId}' — the row is kept, the next sweep retries"
        }
        continue
      }

      if (!dao.deleteIfUnreferencedBefore(candidate.podId, candidate.mediaId, cutoff)) {
        // The row moved while the object was being deleted. Two very different things look the same
        // here, so ask which one it was: an assignment means somebody now references bytes that are
        // gone — the one outcome of this whole method that hurts a user, and it is reported rather
        // than repaired because the upload has already been answered `201`. Anything else (the row
        // deleted by a parallel sweep, or unreferenced again with a newer stamp) is harmless and
        // resolves itself: an unreferenced row is readable by nobody, and the next sweep clears it.
        val current = dao.find(candidate.podId, candidate.mediaId)
        if (current != null && current.assignments.isNotEmpty()) {
          broken++
          logger.error {
            "[media/audit] outcome=swept_live_media pod='${candidate.podId}' media='${candidate.mediaId}' — a row was assigned while " +
                "its object was being deleted; the media is broken until the same bytes are uploaded again"
          }
        } else {
          revived++
        }
        continue
      }

      deleted++
      reclaimedBytes += candidate.size
      logger.info {
        "[media/audit] outcome=swept pod='${candidate.podId}' media='${candidate.mediaId}' size=${candidate.size}"
      }
    }

    return MediaSweepReport(
      cutoff = cutoff,
      gracePeriod = gracePeriod,
      dryRun = dryRun,
      candidates = candidates.size,
      deleted = deleted,
      revived = revived,
      objectDeletionFailures = failed,
      brokenMedia = broken,
      strandedPodsMarked = strandedPods,
      reclaimedBytes = reclaimedBytes,
    )
  }

  /**
   * Stamp the media of pods that no longer exist, so the ordinary grace period reaches them.
   *
   * `SempodsFacade.deletePod` marks the rows that exist when it runs. An upload authorized moments
   * earlier can commit one just after — and that row then names contexts that are gone, carries no
   * `unreferencedSince`, and is invisible to [PodMediaDao.findUnreferencedBefore] for ever. Nobody
   * can reach it either: without a pod there is no route to the media at all. Left alone it is a
   * permanent leak that only a human reading a reconcile report would ever find.
   *
   * **This is the one place the sweep consults `PodDao`, and it is a backstop rather than the
   * driver.** The rule that the sweep keys on `unreferencedSince` and `podId` alone is what lets it
   * reach a deleted pod's *marked* rows; enumerating from the pod registry would miss exactly those.
   * This runs the other way round — from media to pods — and only asks whether a pod that a row
   * claims still exists. Marking rather than deleting is deliberate too: a stranded row gets the
   * same 30 days everything else gets, from the moment it is noticed.
   *
   * Returns how many pods were found stranded. Skipped on a dry run, and when the caller scoped the
   * sweep to a pod that still exists.
   */
  private fun markMediaOfDeletedPods(podId: ObjectId?): Int {
    val candidates = podId?.let { setOf(it) } ?: dao.findPodIdsWithAssignments()
    var marked = 0
    for (candidate in candidates) {
      if (podDao.fetchById(candidate) != null) continue
      val rows = dao.markPodUnreferenced(candidate, Instant.now())
      if (rows == 0L) continue
      marked++
      logger.warn {
        "[media/audit] outcome=stranded_pod_marked pod='$candidate' rows=$rows — media outlived their pod " +
            "unmarked; the grace period starts now"
      }
    }
    return marked
  }

  /**
   * What the store holds and what the registry knows, compared — **a report, and it corrects
   * nothing.** Both directions occur and mean different things:
   *
   * - an **object with no row** is a leaked byte: a crash between [PodMediaStore.put] and the DAO
   *   insert, or between the row delete and the object delete in [sweepUnreferenced]. Inert, but
   *   paid for.
   * - a **row with no object** is a broken media, a `404` waiting to happen for whoever holds its
   *   URL. [store] repairs this one by itself the next time the same bytes are uploaded.
   *
   * **Objects are matched against rows, never against live pods.** Going through `PodDao` would
   * report every object of a pod inside its grace period as an orphan, which is exactly backwards —
   * those rows exist and are waiting to be swept.
   *
   * One database cursor and one store walk: the registry keys go into a set, the walk removes its
   * hits and collects its misses, and whatever stays in the set is the other direction. A `find` per
   * object plus an `exists` per row would be the same answer for twice the round trips.
   *
   * **Objects whose pod id is not shaped like one this deployment mints are skipped.** A store
   * cannot judge that — a [org.sempods.pods.PodId] is opaque to it — so a backend holding other
   * data hands its prefixes back as pods, and they would otherwise be reported as leaks.
   *
   * A shape check, not an ownership one: a second sempods deployment sharing the backend mints the
   * same shape and its objects have no row here, so they *are* reported. Nothing can tell them
   * apart, since an orphan is by definition an object no row claims. **Two deployments must not
   * share one media backend unpartitioned.**
   *
   * // TODO: the key set is O(rows) in memory. Fine for a registry of this size, and the way out
   * //   when it is not is a per-pod run in a loop (the `podId` parameter is already here) rather
   * //   than a cleverer whole-store pass.
   */
  internal fun reconcile(podId: ObjectId? = null): MediaReconcileReport {
    val rows = HashSet<PodMediaRef>()
    dao.iterate(podId) { podMedia -> podMedia.forEach { rows.add(it.ref) } }
    val checkedRows = rows.size

    var checkedObjects = 0
    var skippedObjects = 0
    val objectsWithoutRow = ArrayList<PodMediaRef>()
    mediaStore.iterate(podId?.toPodId()) { entries ->
      entries.forEach { entry ->
        if (entry.ref.podId.toObjectIdOrNull() == null) {
          skippedObjects++
          return@forEach
        }
        checkedObjects++
        // `remove` answers "was it known" and takes it out of the leftovers in one step — what stays
        // behind at the end is precisely the set of rows nothing on disk answers for.
        if (!rows.remove(entry.ref)) objectsWithoutRow.add(entry.ref)
      }
    }

    // One line per walk rather than one per object: a store sharing its space would otherwise fill
    // the log with a line per skipped object, and the count is the whole diagnosis.
    if (skippedObjects > 0) {
      logger.debug { "[media/audit] skipped $skippedObjects object(s) whose pod id is not shaped like one of ours" }
    }

    val report = MediaReconcileReport(
      checkedObjects = checkedObjects,
      checkedRows = checkedRows,
      // Sorted, so two runs over an unchanged store produce the same report and a diff between them
      // means something. A HashSet's order does not.
      objectsWithoutRow = objectsWithoutRow.sortedWith(REF_ORDER),
      rowsWithoutObject = rows.sortedWith(REF_ORDER),
    )
    logger.info {
      "[media/audit] outcome=reconciled objects=${report.checkedObjects} rows=${report.checkedRows} objects_without_row=${report.objectsWithoutRow.size} rows_without_object=${report.rowsWithoutObject.size}"
    }
    return report
  }

  companion object {

    private val REF_ORDER = compareBy<PodMediaRef>({ it.podId.value }, { it.mediaId })

    private val logger = KotlinLogging.logger {}

    /**
     * The media id: SHA-256 over the content, base64url without padding.
     *
     * Not in `commons`' `HashUtil`, which hashes a `String` into hex. The difference is not the
     * algorithm but what the value *is*: this is the `{id}` in `{pod}/_system/media/{id}/content`,
     * so the encoding is a decision about the shape of a public URL and belongs where that URL is
     * defined. Base64url gives 43 characters against hex's 64, and needs no escaping in a path.
     */
    internal fun digest(source: Path): String {
      val digest = MessageDigest.getInstance("SHA-256")
      Files.newInputStream(source).use { input ->
        DigestInputStream(input, digest).use { hashing ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (hashing.read(buffer) != -1) {
            // reading is the point — DigestInputStream updates the digest as it goes
          }
        }
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }
  }
}

/**
 * What one run of [PodMediaFacade.sweepUnreferenced] did.
 *
 * The four outcome counts add up to [candidates] on a real run, which is the property that makes the
 * report readable: nothing was quietly skipped.
 *
 * @property cutoff `now - gracePeriod`; a media unreferenced at or after this instant was left
 *   alone. Reported rather than derived, because it is the answer to "what did this run actually
 *   consider" and the caller does not know when the run started.
 * @property revived rows that stopped being candidates before their bytes went, or that were
 *   unreferenced again with a fresh stamp. Ordinary and harmless — a number here means the sweep is
 *   racing live traffic and losing politely.
 * @property objectDeletionFailures rows whose bytes could not be deleted. **The row is kept**, so
 *   the next run retries the same candidate; this counts transient trouble, not a leak.
 * @property brokenMedia the one outcome that hurts a user: a row was assigned while its object was
 *   being deleted, so something now references bytes that are gone. Logged as an error, listed by
 *   [PodMediaFacade.reconcile] under `rowsWithoutObject`, and healed by the next upload of the same
 *   content.
 * @property reclaimedBytes what the deleted objects weighed, per the registry — or, on a
 *   [dryRun], what a real run would have reclaimed.
 */
data class MediaSweepReport(
  val cutoff: Instant,
  val gracePeriod: Duration,
  val dryRun: Boolean,
  val candidates: Int,
  val deleted: Int,
  val revived: Int,
  val objectDeletionFailures: Int,
  val brokenMedia: Int,

  /**
   * Pods that no longer exist but still held assigned media, marked so the grace period reaches
   * them. Anything but zero means an upload committed a row while its pod was being deleted — rare,
   * self-healing from here, and worth noticing.
   */
  val strandedPodsMarked: Int,

  val reclaimedBytes: Long,
)

/**
 * Where the store and the registry disagree — [PodMediaFacade.reconcile]'s whole output.
 *
 * Both lists are complete; whoever renders this over HTTP decides what fits in a response. A report
 * that had already been truncated by the time it reached its consumer would be the kind of silent
 * cap this codebase refuses elsewhere.
 *
 * @property objectsWithoutRow bytes nothing in the registry claims — leaked, inert, and paid for.
 * @property rowsWithoutObject rows whose bytes are gone — a broken media, and a `404` for whoever
 *   still holds the URL.
 */
data class MediaReconcileReport(
  val checkedObjects: Int,
  val checkedRows: Int,
  val objectsWithoutRow: List<PodMediaRef>,
  val rowsWithoutObject: List<PodMediaRef>,
)
