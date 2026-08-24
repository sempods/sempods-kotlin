package org.sempods.pods.changes

import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import java.net.URI

/**
 * Change-event surface for sempods writes: a change listener hangs off the store and feeds sinks,
 * and additional sinks just subscribe. A committed write produces one [PodChangeSet], grouped by
 * affected resource, which the [PodChangeDispatcher] hands to every registered
 * [PodChangeListener].
 *
 * Deliberately storage-agnostic: it lives next to (not inside) the repository implementation so
 * subscribers — the backup sink, media cleanup, and future ones like audit/ChangeStreams (vision
 * V4.1) or pod-local search indexing (V4.2) — can subscribe without touching the write path.
 */
interface PodChangeListener {

  /**
   * How a failure of this listener is handled (see [PodChangeDispatcher]).
   *
   * - [Durability.CRITICAL]: a failure propagates, the write path compensates (rolls the store
   *   change back) and the request fails. At most **one** critical listener may exist, because the
   *   compensation only undoes the store, not a critical sink's already-applied side effects.
   *   That one critical sink is [BackupSinkPodChangeListener] — the pod's only durable persistence,
   *   so a write it did not record must not be reported as successful.
   * - [Durability.BEST_EFFORT]: a failure is logged and swallowed; it never rolls the store back.
   *   For indexes / audit / external side effects where a lag is reconcilable.
   */
  val durability: Durability

  /**
   * Invoked synchronously after the store commit, before the HTTP response. For a
   * [Durability.CRITICAL] listener an exception propagates and the write is rolled back; for a
   * [Durability.BEST_EFFORT] listener it is caught and logged.
   */
  fun onChange(changeSet: PodChangeSet)
}

/** Failure-handling contract of a [PodChangeListener]. */
enum class Durability { CRITICAL, BEST_EFFORT }

/** What happened to a resource as a whole within one committed write. */
enum class ChangeOperation { CREATED, UPDATED, DELETED }

/**
 * The committed change of a single resource (all contexts): the full post-image plus the net
 * statement delta.
 *
 * Context (named graph) is a first-class concern in sempods — it carries authorization and many
 * operations are context-bound. The delta Models stay context-aware (every statement keeps its
 * graph), and [contexts] surfaces the touched graphs explicitly so context-scoped consumers can
 * filter cheaply. (Vision V4.1 ChangeStreams are "scoped by context"; the per-statement graph in
 * the Models supports a finer per-context projection downstream.)
 *
 * @property contexts the named graphs actually touched by this change — the contexts of [added] ∪
 *   [removed]. Note: this is the *changed* graphs, not necessarily every graph the resource still
 *   lives in (that is `after.contexts()`).
 * @property after full post-image (empty ⇒ [ChangeOperation.DELETED]); carried so a sink can
 *   persist it without a store connection. The pre-image, if a subscriber needs it, is
 *   `after − added + removed`.
 * @property added net statements gained (churn from replace-all already netted out).
 * @property removed net statements lost.
 */
data class ResourceChange(
  val uri: URI,
  val operation: ChangeOperation,
  val contexts: Set<IRI>,
  val after: Model,
  val added: Model,
  val removed: Model,
)

/**
 * All resource changes from one committed write against one pod.
 *
 * **Scope — resource *data* only.** This models the RDF statement changes, derived purely from the
 * store's committed delta. It deliberately does NOT model the **context lifecycle as an entity**:
 * creating a context, flipping its public flag, or deleting a context registry row (with the grant
 * and refresh-token revocations that go with it) happen in `PodFacade`/`PodContextsDao` outside the
 * dispatch. The data side of a context removal *is* visible here — the stripped statements arrive as
 * `removed` with their graph — but "context ctxA was deleted, 3 grants revoked" is not.
 *
 * The split is intentional: data changes are **delta-derived** (from the capture), context-lifecycle
 * changes are **intent-explicit** (only the operation knows them). Mixing operation intent into this
 * delta-derived set would break the capture-driven model.
 *
 * TODO: when a consumer needs it (audit dashboard — vision V2.3; context-scoped ChangeStreams /
 *   visibility-aware index — V4.1/V4.2), add a sibling `ContextChange` event type
 *   (`context, op: CREATED | VISIBILITY_CHANGED | REMOVED, public`, plus revoked grants/tokens for a
 *   faithful audit trail) emitted from `PodFacade.createContext` / `setContextPublic` /
 *   `removeContext`, and widen the listener SPI (e.g. a sealed change hierarchy) to carry both. Do
 *   not build the events before that consumer exists.
 *
 * @property resources one [ResourceChange] per affected resource (a single transaction —
 *   e.g. a context removal — may touch many).
 */
data class PodChangeSet(
  val podId: ObjectId,
  val podName: String,
  val resources: List<ResourceChange>,
  // TODO: carry the actor (WebID / OAuth-client id) for audit + ChangeStreams (vision V4.1).
  //   Intentionally omitted for now — the principal (SempodsCredentials) is request-local and does
  //   not reach the repository write path today; threading it here is a follow-up.
)
