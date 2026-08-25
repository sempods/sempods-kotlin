package org.sempods.pods

import org.sempods.pods.changes.ChangeOperation
import org.sempods.pods.changes.PodChangeDispatcher
import org.sempods.pods.changes.PodChangeSet
import org.sempods.pods.changes.ResourceChange
import org.sempods.pods.changes.SailChangeCapture
import org.sempods.rdf.toIri
import org.eclipse.rdf4j.common.transaction.IsolationLevels
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection
import org.eclipse.rdf4j.sail.NotifyingSailConnection
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Write-through [PodRepository] — the behaviour `write-through.md` (next to this file) describes.
 *
 * Writes go against the in-memory RDF store first — the store is the source of truth on write.
 * A [SailChangeCapture] hangs off the store connection and records the statement-level delta;
 * on commit the change is grouped per affected resource into a [PodChangeSet] and handed to the
 * [PodChangeDispatcher]. The dispatcher fans it out to every registered
 * [org.sempods.pods.changes.PodChangeListener] — among them the backup sink, which mirrors the
 * change into the `resources` collection the store is rebuilt from on restart. Dispatch
 * runs synchronously in the request, after the store commit; a failure of the *critical* listener
 * rolls the store change back and fails the request.
 *
 * Reads come from the **store**: `getResource`, `existsResource`,
 * `findReferencingResources` and the ETag validator are served by `getStatements`/`hasStatement`
 * over a read connection. Reads take no [writeLock] — the MemoryStore serves concurrent reads while
 * a write holds the lock (SNAPSHOT isolation), and a read sees a consistent snapshot.
 */
internal class InMemoryPodRepository(
  private val podId: PodId,
  private val podName: String,
  private val rdfRepository: Repository,
  private val podChangeDispatcher: PodChangeDispatcher,
) : PodRepository {

  /** Serializes writes (store mutation + sink + verification) per pod. */
  private val writeLock = ReentrantLock()

  // -- reads (from the store) --

  override fun getResource(uri: URI): Model? {
    return withConnection { conn ->
      val model = readResource(conn, uri.toIri())
      if (model.isEmpty()) null else model
    }
  }

  override fun getResource(uri: URI, context: URI): Model? {
    return withConnection { conn ->
      val model = LinkedHashModel()
      conn.getStatements(uri.toIri(), null, null, false, context.toIri()).use { result -> result.forEach(model::add) }
      if (model.isEmpty()) null else model
    }
  }

  override fun fetchResourceValidator(uri: URI): String? {
    return withConnection { conn ->
      val model = readResource(conn, uri.toIri())
      if (model.isEmpty()) null else ResourceValidator.compute(model)
    }
  }

  /**
   * Existence, evaluated over the store with the semantics the MongoDB query had: the resource has at least
   * one own-subject statement, AND (if [types] given) at least one matching `rdf:type`, AND (if
   * [contexts] given) at least one statement in one of those contexts. An empty (non-null) [types]
   * or [contexts] matches nothing, mirroring an empty `$in`.
   */
  override fun existsResource(uri: URI, types: Collection<URI>?, contexts: Collection<URI>?): Boolean {
    val resourceIri = uri.toIri()
    return withConnection { conn ->
      if (!conn.hasStatement(resourceIri, null, null, false)) return@withConnection false
      if (types != null && types.none { conn.hasStatement(resourceIri, RDF.TYPE, it.toIri(), false) }) {
        return@withConnection false
      }
      if (contexts != null) {
        if (contexts.isEmpty()) return@withConnection false
        val contextIris = contexts.map { it.toIri() }.toTypedArray()
        if (!conn.hasStatement(resourceIri, null, null, false, *contextIris)) return@withConnection false
      }
      true
    }
  }

  /**
   * Subjects of the non-`rdf:type` statements that point at [objectUri] within [context] — the
   * reverse-edge lookup the former MongoDB `references` index served (which also excluded `rdf:type`).
   */
  override fun findReferencingResources(context: URI, objectUri: URI): Set<URI> {
    val objectIri = objectUri.toIri()
    val contextIri = context.toIri()
    return withConnection { conn ->
      val result = mutableSetOf<URI>()
      conn.getStatements(null, null, objectIri, false, contextIri).use { result_ ->
        result_.forEach { stmt ->
          if (stmt.predicate != RDF.TYPE) {
            (stmt.subject as? IRI)?.let { result.add(URI(it.stringValue())) }
          }
        }
      }
      result
    }
  }

  override fun <T> withConnection(block: (RepositoryConnection) -> T): T {
    rdfRepository.connection.use { conn ->
      return block(conn)
    }
  }

  // -- writes (store mutation in a doWork block; the captured delta drives the change dispatch) --

  override fun putResource(uri: URI, model: Model): Boolean {
    if (!model.any { it.subject.stringValue() == uri.toString() }) {
      throw IllegalArgumentException("Model does not contain resource URI $uri")
    }
    val resourceIri = uri.toIri()
    // Fail fast before touching the store — a change event's post-image only carries the resource's
    // own closure, so a foreign-subject body would be silently dropped by every sink otherwise.
    ResourceBoundary.requireWithinBoundary(resourceIri, model)
    requireIriContexts(uri, model)

    return doWork { conn ->
      val current = readResource(conn, resourceIri)
      // Identical content is a genuine no-op: the store is left untouched, the write reports
      // `false`, and the pod timestamp is not bumped. (No blank nodes ⇒ equality, no isomorphism
      // subtleties.)
      if (!Models.isomorphic(current, model)) {
        replaceResource(conn, resourceIri, model)
      }
    }
  }

  override fun removeFromContext(uri: URI, context: URI): Boolean {
    val resourceIri = uri.toIri()
    val contextIri = context.toIri()
    // Blank nodes are forbidden, so a resource is exactly its own-subject statements — drop the
    // ones in this context directly. The captured delta drives the no-op detection and the events.
    return doWork { conn -> conn.remove(resourceIri, null, null, contextIri) }
  }

  override fun removeContext(context: URI): Boolean {
    val contextIri = context.toIri()
    // Clear the whole named graph in one operation — every statement in it has a resource IRI as
    // subject (no blank nodes), so nothing is orphaned. The capture groups the removals back into
    // per-resource change events.
    return doWork { conn -> conn.remove(null as Resource?, null, null, contextIri) }
  }

  override fun deleteResource(uri: URI): Boolean {
    val resourceIri = uri.toIri()
    return doWork { conn -> conn.remove(resourceIri, null, null) }
  }

  /**
   * Pod data must live in **IRI-named graphs** — a context is the authorization unit and is
   * registered as an IRI. Reject default-graph (null) or blank-node contexts before the store
   * mutation: they are unauthorizable / unreadable via context-scoped reads, and would silently
   * drop out of the [org.sempods.pods.changes.ResourceChange.contexts] set, breaking that
   * contract. Mirrors [ResourceBoundary.requireWithinBoundary] as an in-process write backstop.
   */
  private fun requireIriContexts(uri: URI, model: Model) {
    val offending = model.firstOrNull { it.context !is IRI } ?: return
    throw IllegalArgumentException(
      "Model for resource '$uri' contains a statement in a non-IRI context " +
        "(${offending.context ?: "default graph"}); pod data must live in IRI-named graphs (contexts)",
    )
  }

  internal fun shutDown() {
    try {
      rdfRepository.shutDown()
    } catch (_: Exception) {
    }
  }

  // -- doWork machinery --

  /**
   * Runs an arbitrary store mutation as one unit of work: serialize per pod, hang a change
   * listener off the store connection, mutate inside a SNAPSHOT transaction, then — if anything
   * actually changed — commit, build a [PodChangeSet] from the captured delta and dispatch it.
   *
   * The captured statement delta is the single source for everything the old `discover`/`mutate`
   * bookkeeping used to track explicitly: the changed-resource set (derived from the delta's
   * subjects), the change events, and — as an exact undo log — the compensating rollback. So
   * [block] just mutates the store; it declares nothing.
   *
   * @return `true` if the store was modified (an empty net delta ⇒ no-op write, rolled back).
   */
  private fun doWork(block: (RepositoryConnection) -> Unit): Boolean {
    writeLock.withLock {
      rdfRepository.connection.use { conn ->
        // Hang a change listener off the store connection for the duration of the transaction.
        // TODO: this only captures writes that go through doWork. Once non-doWork write paths open
        //   up (SPARQL UPDATE via withConnection, bulk loads), drive the change dispatch from this
        //   capture for those too — they currently bypass it entirely, which also means neither the
        //   backup rows nor the pod's `resourceRowCount` see them: the store diverges from both
        //   while the two stay consistent with each other, so the recovery check cannot report it.
        val capture = SailChangeCapture()
        val sailConn = (conn as SailRepositoryConnection).sailConnection as NotifyingSailConnection
        sailConn.addConnectionListener(capture)
        try {
          conn.begin(IsolationLevels.SNAPSHOT)
          try {
            block(conn)
          } catch (e: Exception) {
            if (conn.isActive) conn.rollback()
            throw e
          }
          val added = capture.netAdded()
          val removed = capture.netRemoved()
          if (added.isEmpty() && removed.isEmpty()) {
            if (conn.isActive) conn.rollback()
            return false
          }
          conn.commit()

          val changeSet = buildChangeSet(conn, added, removed)

          // Listeners run synchronously post-commit. The compensating rollback below only undoes
          // the store, so it is sound exactly because the dispatcher enforces at most one CRITICAL
          // listener (whose failure propagates here) and swallows BEST_EFFORT failures. A second
          // critical sink would reintroduce a multi-sink saga — see PodChangeDispatcher.
          try {
            podChangeDispatcher.dispatch(changeSet)
          } catch (e: Exception) {
            compensate(conn, added, removed)
            throw IllegalStateException(
              "Pod change listener failed for pod '$podName'; store change rolled back", e,
            )
          }
          return true
        } finally {
          sailConn.removeConnectionListener(capture)
        }
      }
    }
  }

  /**
   * Assemble the [PodChangeSet] from the committed net delta. Blank nodes are forbidden, so every
   * statement's subject IS the owning resource IRI — grouping is a direct partition by subject, no
   * anchor climbing. Per resource: the post-image is read from the store, the delta is split by
   * subject, and the operation is derived from post-image and delta (pre-image = `after − added +
   * removed`).
   */
  private fun buildChangeSet(conn: RepositoryConnection, added: Model, removed: Model): PodChangeSet {
    val affected = (added.subjects() + removed.subjects()).filterIsInstance<IRI>().toSet()
    val resources = affected.map { resourceIri ->
      val after = readResource(conn, resourceIri)
      val addedR = LinkedHashModel(added.filter { it.subject == resourceIri })
      val removedR = LinkedHashModel(removed.filter { it.subject == resourceIri })
      ResourceChange(
        uri = URI(resourceIri.stringValue()),
        operation = classify(after, addedR, removedR),
        // The named graphs actually touched (changed), for context-scoped consumers. Writes carry
        // only IRI contexts (requireIriContexts), so this filter just narrows Resource → IRI.
        contexts = (addedR.contexts() + removedR.contexts()).filterIsInstance<IRI>().toSet(),
        after = after,
        added = addedR,
        removed = removedR,
      )
    }
    return PodChangeSet(podId = podId, podName = podName, resources = resources)
  }

  /** Classify a resource change from its post-image and delta (pre-image = `after − added + removed`). */
  private fun classify(after: Model, added: Model, removed: Model): ChangeOperation = when {
    after.isEmpty() -> ChangeOperation.DELETED
    removed.isEmpty() && LinkedHashModel(after).apply { removeAll(added) }.isEmpty() -> ChangeOperation.CREATED
    else -> ChangeOperation.UPDATED
  }

  /**
   * Compensating rollback via the captured delta as an undo log: remove what was [added], re-add
   * what was [removed]. Exact and cheaper than restoring whole-resource snapshots.
   */
  private fun compensate(conn: RepositoryConnection, added: Model, removed: Model) {
    try {
      conn.begin(IsolationLevels.SNAPSHOT)
      conn.remove(added)
      conn.add(removed)
      conn.commit()
    } catch (e: Exception) {
      if (conn.isActive) conn.rollback()
      logger.error(e) { "Failed to roll back store change for pod $podName after listener failure" }
    }
  }

  /** Replace a resource: drop its statements (all contexts), then add [model]. */
  private fun replaceResource(conn: RepositoryConnection, resourceIri: IRI, model: Model) {
    conn.remove(resourceIri, null, null)
    conn.add(model)
  }

  /**
   * Read a resource's model from the store: all statements with [subject] as subject. No blank-node
   * closure — blank nodes are forbidden, so a resource is exactly its own-subject statements.
   * Statements keep their context.
   */
  private fun readResource(conn: RepositoryConnection, subject: Resource): Model {
    val model = LinkedHashModel()
    conn.getStatements(subject, null, null, false).use { result -> result.forEach(model::add) }
    return model
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
