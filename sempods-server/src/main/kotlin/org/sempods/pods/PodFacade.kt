package org.sempods.pods

import com.google.inject.Inject
import org.bson.types.ObjectId
import org.sempods.commons.jaxrs.errors.ApiErrors
import org.sempods.SempodsFacade
import org.sempods.SempodsUriBuilder
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.rdf.toIri
import java.net.URI
import java.time.Instant
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Business logic orchestration for pod resource operations.
 *
 * The pod server's own way in: endpoints call this directly, with no service abstraction in front
 * of it. A consumer outside the process arrives over HTTP instead.
 *
 * See docs/architecture/module-layering.md.
 */
class PodFacade @Inject constructor(
  private val sempodsFacade: SempodsFacade,
  private val podRepositoryCache: PodRepositoryCache,
  private val podDao: PodDao,
  private val podContextsDao: PodContextsDao,
  private val podGrantsFacade: PodGrantsFacade,
  private val podMediaDao: PodMediaDao,
  private val sempodsUriBuilder: SempodsUriBuilder,
) {

  // -- repository access --

  private fun getRepository(podName: String): PodRepository {
    return podRepositoryCache.get(podName)
      ?: throw ApiErrors.throwNotFoundError("pod", podName)
  }

  // -- resource operations --

  internal fun getResource(podName: String, resourceUri: URI): Model? {
    return getRepository(podName).getResource(resourceUri)
  }

  internal fun getResourceInContext(podName: String, resourceUri: URI, contextUri: URI): Model? {
    return getRepository(podName).getResource(resourceUri, contextUri)
  }

  internal fun putResourceModel(
    podName: String,
    resourceUri: URI,
    model: Model,
  ): Boolean {
    val changed = getRepository(podName).putResource(resourceUri, model)
    if (changed) {
      podDao.updateLastModifiedAt(podName)
    }
    return changed
  }

  internal fun deleteFromContext(
    podName: String,
    resourceUri: URI,
    contextUri: URI,
  ): Boolean {
    val changed = getRepository(podName).removeFromContext(resourceUri, contextUri)
    if (changed) {
      podDao.updateLastModifiedAt(podName)
    }
    return changed
  }

  /**
   * Cascade-deletes a context: revokes refresh tokens that carry a scope on this context,
   * drops the matching `<contextUri>#read|write|manage` grants, pulls the context out of every
   * media assignment naming it, strips the context from every RDF resource (resources with no
   * remaining statements are deleted), and removes the [PodContextDbo] registry row.
   *
   * The media half is a registry write and nothing more: the bytes stay, and a media left with no
   * assignment at all becomes a candidate for the sweep after its grace period
   * ([org.sempods.pods.media.PodMediaFacade.sweepUnreferenced]). Deleting them here would make an
   * accidental context deletion unrecoverable.
   *
   * Order matters for security: tokens and grants are killed *before* the data so a stale
   * session cannot snipe in between (the revocation half lives in
   * [PodGrantsFacade.revokeContextGrants], which documents that ordering). The refresh-token
   * revoke covers the re-create-with-same-URI window — access tokens are self-contained and
   * expire naturally; the live context-existence check at the write endpoint blocks them
   * meanwhile.
   *
   * Does NOT cascade into sub-contexts. `R/sub` is a separate context with its own
   * grants and resources; deleting `R` leaves them intact. Subtree *authority* however cannot
   * live on past its root: deleting `R` removes an `R#manage` grant, and
   * [PodGrantsFacade.revokeContextGrants] then re-derives the app level so delegations that were
   * only backed by that manage root — including ones naming a surviving descendant — go with it.
   */
  internal fun removeContext(podName: String, context: URI) {
    logger.info { "Removing context $context from pod $podName" }

    val podDbo = podDao.fetchByName(podName)
      ?: throw ApiErrors.throwNotFoundError("pod", podName)
    val podId = checkNotNull(podDbo.id)
    val contextUri = context.toString()

    podGrantsFacade.revokeContextGrants(podDbo = podDbo, contextUri = contextUri)
    // Media are not RDF and do not go with the statements: pull the context out of every assignment
    // that names it, which stamps the ones it leaves with no context at all as sweep candidates.
    // The DAO directly rather than `PodMediaFacade` — this touches no byte, and the facade exists
    // only on a deployment that configured a store (`docs/media.md` §"The seam").
    val mediaUnassigned = podMediaDao.removeContextFromAll(podId = podId, context = contextUri, now = Instant.now())
    if (mediaUnassigned > 0) {
      logger.info { "Removed context $context from $mediaUnassigned media assignment(s) of pod $podName" }
    }
    val resourcesChanged = getRepository(podName).removeContext(context)
    podContextsDao.delete(podId = podId, contextUri = contextUri)

    if (resourcesChanged) {
      podDao.updateLastModifiedAt(podName)
    }
    // TODO: the data side of this removal is evented via the change dispatch (stripped statements
    //   arrive as `removed` with their graph), but the context lifecycle itself is not: the registry
    //   delete above plus the grant/refresh-token/service-client revocations, and likewise
    //   `createContext` / `setContextPublic`, emit no change event. When audit (vision V2.3) or a
    //   context-scoped ChangeStream (V4.1) needs it, emit a `ContextChange` event from here — see the
    //   note on `PodChangeSet`.
  }

  /**
   * This implementation's storage key for [podName], or null if there is no such pod.
   *
   * Here so that a caller holding a [org.sempods.spec.PodRef] — which deliberately carries no
   * storage key — can still reach the DAOs, without every endpoint injecting `SempodsFacade` for
   * one lookup. Backed by that facade's process-local cache, so this is not a round trip per
   * request.
   */
  internal fun getPodId(podName: String): ObjectId? = sempodsFacade.getPodId(podName)

  /**
   * Single source of truth for a pod's public contexts: the [isPublic][org.sempods.pods.contexts.persist.PodContextDbo.isPublic]
   * flagged rows in the context registry. Replaces a former hardcoded allowlist — anonymous reads,
   * `public-read` consent and the metadata counts all derive from this. Unknown pod → empty (no
   * throw; callers default to "no public contexts").
   */
  internal fun getPublicContexts(podName: String): Set<URI> {
    val podId = sempodsFacade.getPodId(podName) ?: return emptySet()
    return podContextsDao.fetchByPod(podId, public = true)
      .map { URI(it.contextUri) }
      .toSet()
  }

  /**
   * All registered contexts of a pod (public and private), straight from the context
   * registry. Authoritative source for "where does a pod's data actually sit" — used by
   * an application's `<app-root>` discovery scan, which must see contexts regardless of any
   * app-side pointer. Unknown pod → empty (no throw).
   */
  internal fun getContexts(podName: String): Set<URI> {
    val podId = sempodsFacade.getPodId(podName) ?: return emptySet()
    return podContextsDao.fetchByPod(podId)
      .map { URI(it.contextUri) }
      .toSet()
  }

  /**
   * Backs the pod's context-registration route (`SempodsPodClient.createContext` from outside): registers [contextUri]
   * as a context row for [podName]. Idempotent — returns `false` if the row
   * already exists. `createdBy` is left null to denote a system-driven creation
   * (account provisioning, programmatic seeds); the HTTP endpoint variant
   * populates it with the owner's WebID instead.
   */
  internal fun createContext(
    podName: String,
    contextUri: URI,
    public: Boolean,
    label: String?,
    description: String?,
  ): Boolean {
    val podId = sempodsFacade.getPodId(podName)
      ?: throw ApiErrors.throwNotFoundError("pod", podName)
    requireInPodNamespace(podName = podName, contextUri = contextUri)
    return podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = label,
      description = description,
      createdBy = null,
      isPublic = public,
    ) != null
  }

  /**
   * Backs the admin provisioning path in `AdminPodsEndpoint`, its only caller: flips the public flag of
   * an existing context row. Returns `false` when pod or row is unknown.
   */
  internal fun setContextPublic(podName: String, contextUri: URI, public: Boolean): Boolean {
    val podId = sempodsFacade.getPodId(podName) ?: return false
    return podContextsDao.setPublic(podId = podId, contextUri = contextUri.toString(), isPublic = public)
  }

  /**
   * Defense-in-depth for write paths that persist a context URI: rejects URIs
   * outside the pod's namespace or carrying a fragment. The HTTP endpoint
   * runs the same checks during input normalisation; this guards programmatic
   * callers so a bug or future code path cannot register an out-of-pod URI as
   * (public) context — which would silently widen anonymous-read scope.
   */
  private fun requireInPodNamespace(podName: String, contextUri: URI) {
    val podBaseUrl = sempodsUriBuilder.buildResourceUri(podName, "").toString()
    if (!contextUri.toString().startsWith(podBaseUrl)) {
      throw ApiErrors.throwInvalidParameterError(
        parameterName = "contextUri",
        message = "context must be inside pod namespace ($podBaseUrl)",
      )
    }
    if (contextUri.fragment != null) {
      throw ApiErrors.throwInvalidParameterError(
        parameterName = "contextUri",
        message = "context URI must not contain fragment",
      )
    }
  }

  internal fun existsResource(
    podName: String,
    resourceUri: URI,
    types: Collection<URI>?,
    contexts: Collection<URI>?,
  ): Boolean {
    return getRepository(podName).existsResource(resourceUri, types, contexts)
  }

  internal fun findReferencingResources(podName: String, contextUri: URI, objectUri: URI): Set<URI> {
    return getRepository(podName).findReferencingResources(contextUri, objectUri)
  }

  // -- endpoint-facing operations --

  /**
   * Replace all outgoing statements of a resource within a specific context.
   * Statements in other contexts are preserved.
   * The [replacementModel] contains the complete desired state for the context.
   */
  internal fun patchResource(
    podName: String,
    resourceUri: URI,
    contextUri: URI,
    replacementModel: Model,
  ): Boolean {
    val repo = getRepository(podName)
    val resourceIri = resourceUri.toIri()
    val contextIri = contextUri.toIri()

    // Build merged model: existing (all contexts) minus target context, plus replacement
    val mergedModel = repo.getResource(resourceUri) ?: LinkedHashModel()
    mergedModel.remove(resourceIri, null, null, contextIri)

    replacementModel.asSequence()
      .filter { it.subject == resourceIri }
      .forEach { stmt ->
        mergedModel.add(stmt.subject, stmt.predicate, stmt.`object`, contextIri)
      }

    return putResourceModel(
      podName = podName,
      resourceUri = resourceUri,
      model = mergedModel,
    )
  }

  /**
   * Load the statements of a resource within a specific context.
   * Returns an empty model if the resource does not exist.
   */
  internal fun loadResourceStatementsInContext(
    podName: String,
    resourceUri: URI,
    contextUri: URI,
  ): Model {
    return getResourceInContext(podName, resourceUri, contextUri) ?: LinkedHashModel()
  }

  // -- LOD-CRUD System layer: slot-granular operations --

  /**
   * Outcome of [addSlotValue] — distinguishes the two RDF set-semantics cases the
   * System-layer HTTP `POST` needs to translate to `201 Created` vs. `200 OK`.
   */
  enum class SlotAddOutcome { CREATED, ALREADY_PRESENT }

  /**
   * Return the slot model — all statements `(subject, predicate, *)` filtered by [contexts]
   * if provided, otherwise across every context the resource exists in. Caller-side context
   * filtering (readable contexts intersect with `?context=` parameter) happens upstream.
   *
   * Returns an empty model if the resource does not exist or has no matching statements.
   */
  internal fun getSlot(
    podName: String,
    subjectUri: URI,
    predicateUri: URI,
    contexts: Collection<URI>?,
  ): Model {
    val repo = getRepository(podName)
    val resourceModel = repo.getResource(subjectUri) ?: return LinkedHashModel()
    val subjectIri = subjectUri.toIri()
    val predicateIri = predicateUri.toIri()
    val contextIris = contexts?.map { it.toIri() }?.toSet()
    val result = LinkedHashModel()
    resourceModel.getStatements(subjectIri, predicateIri, null)
      .asSequence()
      .filter { stmt -> contextIris == null || contextIris.contains(stmt.context) }
      .forEach { result.add(it) }
    return result
  }

  /**
   * Cardinality check for one slot in one context: are there zero statements
   * `(subject, predicate, *)` in [contextUri]? Used by the System-layer endpoint to drive
   * `If-None-Match: *` (slot-as-resource semantics — empty slot ≙ "does not exist") before
   * an upstream conditional write.
   */
  internal fun isSlotEmpty(
    podName: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
  ): Boolean {
    val repo = getRepository(podName)
    val mergedModel = repo.getResource(subjectUri) ?: return true
    val subjectIri = subjectUri.toIri()
    val predicateIri = predicateUri.toIri()
    val contextIri = contextUri.toIri()
    return !mergedModel.getStatements(subjectIri, predicateIri, null, contextIri).any()
  }

  /**
   * Replace all statements `(subject, predicate, *)` in [contextUri] with [newSlotStatements].
   * Other predicates of the subject in this context AND every statement of the subject in
   * other contexts remain untouched. Returns `true` if the store was modified.
   */
  internal fun replaceSlot(
    podName: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    newSlotStatements: Collection<Value>,
  ): Boolean {
    val repo = getRepository(podName)
    val subjectIri = subjectUri.toIri()
    val predicateIri = predicateUri.toIri()
    val contextIri = contextUri.toIri()

    val mergedModel = repo.getResource(subjectUri) ?: LinkedHashModel()
    mergedModel.remove(subjectIri, predicateIri, null, contextIri)
    newSlotStatements.forEach { value ->
      mergedModel.add(subjectIri, predicateIri, value, contextIri)
    }
    return persistSlotMutation(podName, subjectUri, mergedModel)
  }

  /**
   * Add one value to the slot `(subject, predicate)` in [contextUri]. Idempotent: if the
   * exact statement already exists, returns [SlotAddOutcome.ALREADY_PRESENT] and does not
   * touch the store (RDF set semantics; see sempods-spec `spec/core/lod-crud.md` §5 §"Acknowledged deviations" 3).
   */
  internal fun addSlotValue(
    podName: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    value: Value,
  ): SlotAddOutcome {
    val repo = getRepository(podName)
    val subjectIri = subjectUri.toIri()
    val predicateIri = predicateUri.toIri()
    val contextIri = contextUri.toIri()

    val mergedModel = repo.getResource(subjectUri) ?: LinkedHashModel()
    if (mergedModel.contains(subjectIri, predicateIri, value, contextIri)) {
      return SlotAddOutcome.ALREADY_PRESENT
    }
    mergedModel.add(subjectIri, predicateIri, value, contextIri)
    persistSlotMutation(podName, subjectUri, mergedModel)
    return SlotAddOutcome.CREATED
  }

  /**
   * Remove exactly the triple `(subject, predicate, target)` in [contextUri]. Only IRI
   * targets are addressable from the System layer's edge URL. Returns `true` if the edge
   * existed and was removed; `false` if it was already absent. Both outcomes are success
   * for the HTTP and MCP callers — they map to `outcome=removed|already_absent` (HTTP:
   * `200 OK` with that outcome in the JSON body per RFC 9110 §9.3.5; MCP: the tool
   * result's `outcome` field), never `404`.
   */
  internal fun removeSlotEdge(
    podName: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    targetIri: IRI,
  ): Boolean {
    val repo = getRepository(podName)
    val subjectRdfIri = subjectUri.toIri()
    val predicateRdfIri = predicateUri.toIri()
    val contextRdfIri = contextUri.toIri()

    val mergedModel = repo.getResource(subjectUri) ?: return false
    if (!mergedModel.contains(subjectRdfIri, predicateRdfIri, targetIri, contextRdfIri)) {
      return false
    }
    mergedModel.remove(subjectRdfIri, predicateRdfIri, targetIri, contextRdfIri)
    return persistSlotMutation(podName, subjectUri, mergedModel)
  }

  /**
   * Remove every statement `(subject, predicate, *)` in [contextUri]. Other predicates of the
   * subject and statements in other contexts remain untouched. Returns `true` if anything was
   * removed; `false` if the slot was already empty. Both outcomes are success for the HTTP
   * and MCP callers — they map to `204` / `outcome=cleared|already_empty`, never `404`.
   */
  internal fun clearSlot(
    podName: String,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
  ): Boolean {
    val repo = getRepository(podName)
    val subjectIri = subjectUri.toIri()
    val predicateIri = predicateUri.toIri()
    val contextIri = contextUri.toIri()

    val mergedModel = repo.getResource(subjectUri) ?: return false
    val existed = mergedModel.getStatements(subjectIri, predicateIri, null, contextIri).any()
    if (!existed) return false
    mergedModel.remove(subjectIri, predicateIri, null, contextIri)
    return persistSlotMutation(podName, subjectUri, mergedModel)
  }

  /**
   * Persist the result of a slot mutation. If the mutation drained the last statement of
   * the resource across all contexts, the resource itself is deleted — [PodRepository.putResource]
   * rejects a model without the resource's own statements, so we cannot just
   * `putResourceModel(empty)` and expect "fully cleared" semantics.
   *
   * `dateModified` is bumped via the same `podDao.updateLastModifiedAt(podName)` call that
   * `putResourceModel` runs on a successful write, so both paths produce the same pod-level
   * change signal.
   */
  private fun persistSlotMutation(podName: String, subjectUri: URI, mergedModel: Model): Boolean {
    return if (mergedModel.isEmpty()) {
      val changed = getRepository(podName).deleteResource(subjectUri)
      if (changed) {
        podDao.updateLastModifiedAt(podName)
      }
      changed
    } else {
      putResourceModel(podName, subjectUri, mergedModel)
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
