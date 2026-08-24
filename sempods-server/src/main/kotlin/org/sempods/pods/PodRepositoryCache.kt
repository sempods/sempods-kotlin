package org.sempods.pods

import com.google.inject.Inject
import org.sempods.SempodsFacade
import org.sempods.pods.changes.PodChangeDispatcher
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.sempods.rdf.RdfWriterUtil
import org.bson.types.ObjectId
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

class PodRepositoryCache @Inject constructor(
  private val sempodsFacade: SempodsFacade,
  private val rdfResourceBackupDao: RdfResourceBackupDao,
  private val podDao: PodDao,
  private val podChangeDispatcher: PodChangeDispatcher,
) {

  private val cache = ConcurrentHashMap<String, PodRepository>()

  /**
   * Returns a [PodRepository] for the given pod.
   * The underlying in-memory store is loaded once from the DB on first access.
   */
  fun get(pod: String): PodRepository? {
    return cache.compute(pod) { _, existing ->
      existing ?: initialize(pod)
    }
  }

  /**
   * Drops the cached repository for a pod. Next access will reload from DB.
   */
  fun invalidate(pod: String) {
    val repo = cache.remove(pod)
    if (repo is InMemoryPodRepository) {
      repo.shutDown()
      logger.debug { "Cache invalidated for pod: $pod" }
    }
  }

  /**
   * Invalidate all cached repositories (useful in tests).
   */
  fun invalidateAll() {
    cache.keys.toList().forEach { invalidate(it) }
  }

  private fun initialize(pod: String): PodRepository? {
    logger.debug { "Initializing in-memory repository for pod: $pod" }

    val podId = sempodsFacade.getPodId(pod) ?: return null

    val repo = SailRepository(MemoryStore())
    repo.init()

    try {
      repo.connection.use { conn ->
        loadAllResourcesInto(conn, pod, podId)
      }
    } catch (e: Exception) {
      try {
        repo.shutDown()
      } catch (_: Exception) {
      }
      throw e
    }

    logger.debug { "Initialized repository for pod: $pod" }
    return InMemoryPodRepository(podId, pod, repo, podChangeDispatcher)
  }

  /**
   * Reconstruct the pod's MemoryStore from the per-context backup collection — the pod's only
   * durable persistence. Each backup row is self-contained N-Quads (carrying
   * its named graph), so the rows just get parsed and added.
   *
   * What is loaded is checked against `PodDbo.resourceRowCount`, the count
   * `BackupSinkPodChangeListener` maintains as rows appear and vanish. The count is what makes the
   * recovery of an empty pod legible: on its own, an empty pod is a pod nothing was ever written to,
   * a pod whose owner deleted its last resource, and a pod whose durable state went missing, and the
   * current state cannot tell those apart — the first two are the ordinary case, so warning on all
   * three warns mostly about nothing. Against a recorded count only the third is a shortfall.
   */
  private fun loadAllResourcesInto(conn: RepositoryConnection, pod: String, podId: ObjectId) {
    val backupRows = rdfResourceBackupDao.fetchAllByPod(podId)
    val loaded = backupRows.size.toLong()
    // Bookkeeping, and never a reason a pod fails to come up: this runs inside the cache's
    // per-pod compute, so a throw here would take the repository with it and turn a diagnostic
    // into an outage.
    try {
      reportRowCount(pod, podId, loaded)
    } catch (e: Exception) {
      logger.warn(e) { "Could not check pod $pod's resource row count; the rows themselves are unaffected" }
    }

    logger.debug { "Loading $loaded backup row(s) for podId=$podId into MemoryStore" }
    backupRows.forEach { row ->
      try {
        conn.add(RdfWriterUtil.readNQuads(row.nquads.byteInputStream()))
      } catch (e: Exception) {
        logger.warn { "Error loading backup row ${row.resourceUri}/${row.context} for podId=$podId: ${e.message}" }
      }
    }
  }

  /**
   * Compare what the recovery found against what the write path recorded, then make the recovery's
   * own count the new baseline.
   *
   * **Re-baselining is what keeps the shortfall a report of an event rather than a state the pod
   * cannot leave.** Restoring a pod's rows through the write path adds them to a count that already
   * claims them, so without this a repaired pod would report the same loss on every recovery
   * afterwards, for ever. With it, one incident produces one line and the pod returns to a
   * consistent baseline. The price is that a recovery — reached from read paths as well as writes —
   * writes one document, once per pod per process, and only when the two numbers disagree.
   *
   * A count *below* what was loaded is drift rather than an incident: a row predating the field, or
   * a bump that did not land. Nothing is missing, so it is stated and corrected, not raised.
   *
   * **Neither number is a snapshot of the other, and the recovery never raises the count.** The
   * rows and the count are two documents, and another replica may be moving them while this
   * recovery reads. A recovery landing in that gap sees rows whose delta has not been applied yet —
   * so writing what it loaded would be counted a second time when the delta lands, leaving the count
   * *above* the collection, which is the one shape that raises a false alarm. Waiting for the two to
   * agree cannot fix it either: both numbers are stable throughout that gap, so there is nothing for
   * a stability check to see.
   *
   * What removes it is the direction. A count left *below* the collection raises nothing — it costs
   * sensitivity equal to the drift, and a real loss larger than that still reports — so the recovery
   * only ever lowers the count, never lifts it. `recorded < loaded` is therefore stated and left
   * alone, and the two writes that remain are the ones that have to happen:
   *
   * - the confirmed shortfall, which lowers;
   * - the first baseline of a row that predates the count, which is a pod's one and only lift and
   *   the alternative to leaving it permanently blind. It carries the residual above, once in a
   *   pod's lifetime, and the recovery after it lowers it away.
   *
   * Each write is a compare-and-set on the value that was read, so a delta landing in between makes
   * it miss rather than discarding that delta.
   */
  private fun reportRowCount(pod: String, podId: ObjectId, loaded: Long) {
    val recorded = podDao.fetchById(podId)?.resourceRowCount
    when {
      recorded == null -> {
        logger.info {
          "Pod $pod recovered $loaded resource row(s) with no recorded count — the row predates the count, so this " +
            "recovery establishes its baseline and nothing can be concluded about it this once"
        }
        establishBaseline(pod, podId, loaded)
      }

      recorded > loaded -> {
        if (!shortfallPersists(podId, recorded, loaded)) {
          logger.debug {
            "Pod $pod recovered $loaded resource row(s) against a recorded $recorded, and the gap was gone on the " +
              "re-check — a write was in flight, so there is nothing to report and nothing to re-baseline"
          }
          return
        }
        logger.error {
          "Pod $pod recovered $loaded of $recorded recorded resource row(s) — ${recorded - loaded} row(s) the write " +
            "path recorded are no longer in the collection, so this pod's durable state is incomplete and what is " +
            "missing did not go through a delete"
        }
        podDao.setResourceRowCount(podId, expected = recorded, count = loaded)
      }

      recorded < loaded -> logger.info {
        "Pod $pod recovered $loaded resource row(s) against a recorded $recorded — nothing is missing, the count is " +
          "behind, and it is left that way: lifting it here would double-count any row whose delta is still in " +
          "flight, and a count that is behind costs sensitivity where one that is ahead costs a false alarm"
      }

      else -> logger.debug { "Pod $pod recovered $loaded resource row(s), matching its recorded count" }
    }
  }

  /**
   * Give a pod that predates the count its first one.
   *
   * **Raised to what was loaded, never added to it**, because two different things can have touched
   * the row since it was read as absent and they need opposite answers. A stray `$inc` *creates* the
   * field, so a write landing in between leaves a small number — one, say, where the pod holds fifty
   * rows — which has to be lifted, or the pod sits at a baseline of one for good and a loss of its
   * whole history passes unremarked. Another replica recovering the same pod, which a rolling
   * deploy makes ordinary, leaves the loaded rows themselves — which must be left alone, or the
   * count becomes twice the collection and the next recovery reports the pod's entire history as
   * missing.
   *
   * Nothing can tell those two apart by *how* they arrived; the value itself answers both, which is
   * why `PodDao.raiseResourceRowCountTo` compares rather than sets or adds. Below what this recovery
   * loaded means no baseline yet; at or above means someone got there first.
   */
  private fun establishBaseline(pod: String, podId: ObjectId, loaded: Long) {
    if (!podDao.raiseResourceRowCountTo(podId, loaded)) {
      logger.info { "Pod $pod already carried a resource row count by the time its baseline was written — left as it stands" }
    }
  }

  /**
   * Re-read both numbers and say whether the shortfall is still there.
   *
   * **What this rules out is values in motion**, and only that: a set that started or finished
   * between the recovery's two reads moves one of them, and the mismatch does not survive the
   * second look. It is deliberately not the guard against a recovery landing *inside* a set, where
   * both numbers are perfectly still and a second look sees the same pair — nothing here could tell
   * that apart. What covers that case is the order the sink writes in: deletes are subtracted
   * before their rows go, so a set in flight leaves the count below the collection, never above it,
   * and the branch this guards is not reached at all.
   *
   * Between them they leave one way for a shortfall to be wrong — a delta that never landed — which
   * `BackupSinkPodChangeListener.moveRowCount` bounds at a single report.
   *
   * Counted rather than loaded: the pod's rows are already in hand, and re-reading their N-Quads to
   * learn a number the index can answer would be the expensive way to ask.
   */
  private fun shortfallPersists(podId: ObjectId, recorded: Long, loaded: Long): Boolean {
    val recordedNow = podDao.fetchById(podId)?.resourceRowCount ?: return false
    val rowsNow = rdfResourceBackupDao.countByPod(podId)
    return recordedNow == recorded && rowsNow == loaded
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
