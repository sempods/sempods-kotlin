package org.sempods.pods.changes

import com.google.inject.Inject
import org.bson.types.ObjectId
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.sempods.rdf.RdfWriterUtil
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/**
 * Backup sink: mirrors every committed change into the `resources` collection
 * **per `(resource, context)`** as N-Quads.
 *
 * Since the legacy decommission this is the pod's **only durable persistence** — the
 * MemoryStore is volatile and is rebuilt from these rows on restart (`PodRepositoryCache`). It is
 * therefore the single [Durability.CRITICAL] listener: if MongoDB fails, the store change is rolled
 * back by the write path's compensating transaction and the request fails, rather than reporting
 * success for a write that the next restart would silently drop.
 *
 * Context-grained because context is the first-class unit in sempods; sound because blank nodes are
 * forbidden (a resource's per-context statements are self-contained).
 *
 * Only the **touched** contexts ([ResourceChange.contexts]) are written: for each, upsert its
 * N-Quads if the resource still has statements there, else delete that `(resource, context)` row (a
 * context drop, or — when the whole resource was deleted — all of its rows).
 *
 * **Partial application (bounded, not closed).** One logical change becomes N row writes, and the
 * write path's compensating rollback only undoes the *store* — so a failure part-way through a
 * multi-row set leaves the backup holding a mix of pre- and post-image while the request reports
 * failure. A later restart would then materialize that mix. Two things bound the damage: the rows
 * are idempotent full replacements, so the whole set is **retried** and a transient failure
 * self-heals to a full apply; and a persistent failure is **logged loudly** naming the affected
 * resources, so the divergence can be reconciled rather than merely suspected. Single-resource,
 * single-context writes — the common case — are one row and unaffected either way.
 *
 * The retry narrows the window; it does not close it. Closing it needs the change to become **one**
 * durable record instead of N, which is the journal in
 * the maintainer's internal roadmap.
 *
 * **Row count.** This is also where the pod's `resourceRowCount` is maintained
 * (`PodDbo.resourceRowCount`), because this class is the only thing that creates or removes a row.
 * It is what lets `PodRepositoryCache` tell a pod whose durable state went missing from one that was
 * never written to — a distinction the collection's current contents cannot supply. Deletes are
 * subtracted before the rows go and additions applied after they arrive, so the count is never above
 * the collection while a set is in flight; [onChange] argues why that ordering is what makes the
 * distinction survive a concurrent recovery.
 *
 * TODO: drop the retry once the journal lands — a single append needs no set-level retry.
 */
class BackupSinkPodChangeListener @Inject constructor(
  private val backupDao: RdfResourceBackupDao,
  private val podDao: PodDao,
) : PodChangeListener {

  override val durability = Durability.CRITICAL

  /**
   * The count moves **down before the rows go and up after they arrive**, and that ordering is the
   * whole of what keeps a concurrent recovery from crying loss.
   *
   * Rows and count are two documents; whichever moves first, there is a gap where they disagree, and
   * a recovery on another replica can read both inside it. The gap only lies when the count sits
   * *above* the collection — that is the shape of a data loss, and nothing else produces it. So the
   * set's deletes are subtracted up front, pessimistically, before a single row is dropped, and
   * everything that adds is applied after its row exists. The count is then never above the
   * collection while a write is in flight, only ever below it, and a recovery reading mid-set sees
   * the harmless direction. Waiting for the two to agree could not achieve this: inside the gap
   * both numbers are perfectly still, so there is nothing for a stability check to see.
   *
   * The up-front subtraction is a guess — a delete of a row that was already gone moves nothing —
   * so the closing write adds back whatever did not happen along with the inserts that did. A pod
   * whose set only replaces rows still pays no count write at all.
   *
   * A delete whose outcome the retry could not recover is added back too, deliberately: it is the
   * choice that errs toward a count above the collection, which [moveRowCount] argues is the one
   * worth erring toward.
   */
  override fun onChange(changeSet: PodChangeSet) {
    val plan = plan(changeSet)
    val plannedDeletes = plan.count { it.nquads == null }.toLong()
    // Issued once and then treated as done, whatever it reported. A write that fails is not a write
    // that did not happen — the response can be lost after the server applied it — and re-applying
    // the same subtraction at the end would double it, silently and for good. This is the only
    // delta here that removes; see [moveRowCount] for why that is what decides it is not retried.
    //
    // TODO: a hard kill between this and the closing write leaves the count short by up to this
    //   much, with the rows still there — the silent direction, and the one window the sign rule
    //   cannot reach, because nothing here is wrong about what it did, the process simply stopped.
    //   It is the same kill that already leaves the backup rows partially applied (see
    //   `write-through.md` §"Partial application"), and it closes the same way: one durable record
    //   per change set, the journal in the maintainer's internal roadmap. A
    //   pending marker short of that cannot tell a crashed set from a live one without a lease.
    if (plannedDeletes > 0L) moveRowCount(changeSet, -plannedDeletes)

    // Tallied across attempts, not per attempt. A retry re-runs the whole set, so rows a failed
    // attempt already wrote come back as replacements and report nothing the second time — a
    // per-attempt tally would drop them. And the closing write is in a `finally` because rows
    // written are rows written: a set that gave up part-way still left them behind, and the count
    // has to name what the collection holds rather than what the request reported.
    var inserted = 0L
    var deleted = 0L
    try {
      var attempt = 1
      while (true) {
        try {
          writeRows(changeSet.podId, plan, onInsert = { inserted++ }, onDelete = { deleted++ })
          return
        } catch (e: Exception) {
          if (attempt >= MAX_ATTEMPTS) {
            logger.error(e) {
              "Backup sink failed for pod ${changeSet.podName} after $attempt attempt(s); the backup may hold a partially " +
                "applied change for resources ${changeSet.resources.map { it.uri }} while the store is rolled back — a restart would " +
                "materialize that state, so reconcile these resources"
            }
            throw e
          }
          logger.warn(e) {
            "Backup sink write failed for pod ${changeSet.podName} (attempt $attempt/$MAX_ATTEMPTS), retrying the whole set"
          }
          attempt++
        }
      }
    } finally {
      // Never negative: a set cannot see more deletes happen than it planned, so this only ever
      // adds — which is why it is the delta that gets retried.
      val closing = inserted + (plannedDeletes - deleted)
      if (closing != 0L) moveRowCount(changeSet, closing)
    }
  }

  /**
   * One `$inc` against the pod registry row, **retried only when it adds**.
   *
   * `$inc` is not idempotent, and a manual retry is a *new* operation: the driver's retryable writes
   * are at-most-once within one operation, not across two. Past the driver's own retry a throw means
   * the outcome is *unknown*, not that nothing happened — so whether to repeat is a choice about
   * which way to be wrong.
   *
   * **The two mistakes are not symmetric, and that decides it.** A count left above the collection
   * is reported by the next recovery and lowered in the same breath — one line, then gone. A count
   * left below it is what the `recorded < loaded` branch deliberately leaves alone, so it never
   * repairs and quietly costs that much detection for ever. Every unknown outcome therefore resolves
   * toward *high* — and the sign of the delta is exactly what says how to get there:
   *
   * - **Adding**: repeating risks applying it twice, which lands high. Not repeating risks losing it
   *   altogether, which lands low. So it is retried.
   * - **Removing**: repeating risks subtracting twice, which lands low. Not repeating risks losing
   *   it, which lands high. So it is issued once.
   *
   * The one delta that removes is the up-front subtraction in [onChange]; the closing write cannot
   * be negative, because a set never sees more deletes than it planned.
   *
   * (The in-flight ordering in [onChange] prefers the opposite direction, and does not contradict
   * this: a gap that closes when the set finishes is transient, and below is the direction that
   * raises nothing while it lasts. This is about a drift that outlives the set.)
   *
   * A failure is logged and swallowed rather than thrown: the caller runs this in a `finally`, and
   * throwing would replace the write failure it is about to surface with an unrelated one. Closing
   * the residual outright means making a change set one durable record: the journal in
   * the maintainer's internal roadmap.
   */
  private fun moveRowCount(changeSet: PodChangeSet, delta: Long) {
    val allowed = if (delta > 0L) MAX_ATTEMPTS else 1
    var attempt = 1
    while (true) {
      try {
        podDao.bumpResourceRowCount(changeSet.podId, delta)
        return
      } catch (e: Exception) {
        if (attempt >= allowed) {
          logger.warn(e) {
            "Could not move the resource row count of pod ${changeSet.podName} by $delta after $attempt attempt(s); " +
              if (delta > 0L) {
                "the count may now sit below the collection, where nothing reports it — reconcile this pod"
              } else {
                "it is treated as applied rather than repeated, so the count may sit above the collection until the " +
                  "pod's next recovery reports it"
              }
          }
          return
        }
        logger.warn(e) { "Resource row count move of $delta failed for pod ${changeSet.podName} (attempt $attempt/$allowed), retrying" }
        attempt++
      }
    }
  }

  /**
   * The set as rows, resolved once: one entry per touched `(resource, context)`, carrying the
   * N-Quads to store or `null` where the resource no longer has statements in that context and the
   * row is to go.
   *
   * Resolved before anything is written because the number of deletes has to be known up front —
   * see [onChange] — and serializing once rather than per attempt makes the retry cheaper besides.
   */
  private fun plan(changeSet: PodChangeSet): List<RowWrite> =
    changeSet.resources.flatMap { rc ->
      val afterContexts = rc.after.contexts().filterIsInstance<IRI>().toSet()
      rc.contexts.map { ctx ->
        RowWrite(
          resource = rc.uri,
          context = URI(ctx.stringValue()),
          nquads = if (ctx in afterContexts) {
            RdfWriterUtil.writeNQuads(LinkedHashModel(rc.after.filter { it.context == ctx }))
          } else {
            null
          },
        )
      }
    }

  /**
   * Idempotent: every row is a full replacement (or a delete), so re-running the set is safe.
   *
   * Reports each row that actually appeared or vanished as it goes rather than on return, so a set
   * that throws part-way has still reported what it wrote. A replacement reports nothing — the
   * common write moves no count.
   */
  // TODO: an upsert that inserted but whose response was indeterminate throws, and the retry then
  //   sees the row it made and reports a replacement — so the set undercounts by one, permanently,
  //   since a recovery never lifts a count. Resolving it needs a durable record of what this change
  //   set did, which is the journal (the maintainer's internal roadmap); there
  //   is nothing to key an idempotent retry on before that lands.
  private fun writeRows(podId: ObjectId, plan: List<RowWrite>, onInsert: () -> Unit, onDelete: () -> Unit) {
    plan.forEach { row ->
      if (row.nquads != null) {
        if (backupDao.upsert(podId, row.resource, row.context, row.nquads)) onInsert()
      } else if (backupDao.delete(podId, row.resource, row.context)) {
        onDelete()
      }
    }
  }

  /** One `(resource, context)` row of a change set; [nquads] is `null` where the row is to go. */
  private data class RowWrite(val resource: URI, val context: URI, val nquads: String?)

  companion object {
    private val logger = KotlinLogging.logger {}

    /** Bounded idempotent retries of a change set's row writes before giving up. */
    private const val MAX_ATTEMPTS = 3
  }
}
