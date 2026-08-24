package org.sempods.pods.changes

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.sail.SailConnectionListener

/**
 * Captures the statement-level delta of a write transaction by listening on the store's
 * [org.eclipse.rdf4j.sail.NotifyingSailConnection]. Statement events fire *during* the
 * transaction, so we buffer and read the result after commit.
 *
 * A store-near capture: it is the single source for the changed-resource set, the change events,
 * and the rollback undo log, so the write-path `doWork` block declares nothing. It will also see
 * writes that do not go through `doWork` once those open up (SPARQL UPDATE, bulk loads).
 *
 * Being Sail-specific is exactly what pins the pod to an RDF4J Sail backend — deriving the delta
 * from the operation instead of from the store is the first step in
 * the maintainer's internal roadmap.
 *
 * Not thread-safe: a [SailChangeCapture] instance belongs to exactly one connection/transaction,
 * and `doWork` serializes writes per pod.
 */
internal class SailChangeCapture : SailConnectionListener {

  private val rawAdded: Model = LinkedHashModel()
  private val rawRemoved: Model = LinkedHashModel()

  override fun statementAdded(st: Statement) {
    rawAdded.add(st)
  }

  override fun statementRemoved(st: Statement) {
    rawRemoved.add(st)
  }

  /**
   * Net statements gained: a replace-all write removes then re-adds unchanged statements, which
   * surface as both an add and a remove. Those cancel out — only genuinely new statements remain.
   */
  fun netAdded(): Model = LinkedHashModel(rawAdded).apply { removeAll(rawRemoved) }

  /** Net statements lost (the symmetric difference complement of [netAdded]). */
  fun netRemoved(): Model = LinkedHashModel(rawRemoved).apply { removeAll(rawAdded) }
}
