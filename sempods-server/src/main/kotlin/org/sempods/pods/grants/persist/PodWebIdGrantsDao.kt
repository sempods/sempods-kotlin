package org.sempods.pods.grants.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Store for owner-level, app-independent WebID grants ([PodWebIdGrantDbo], `webIdGrants`).
 *
 * This is the *user level* of the two-level model in `docs/auth/authorization.md`
 * §"Authorization model" — what a person may do on this pod, independent of any app. The app level
 * ([PodGrantsDao]) is derived from it at consent time via `granted = requested ∩ user_grants`.
 *
 * Read side is on the hot path of the authorize/consent flow:
 * [org.sempods.pods.grants.PodGrantsFacade]'s `resolveUserGrants` calls [fetchGrantStrings] for
 * every non-owner authorize/consent so the grants a pod owner handed a WebID actually take effect.
 *
 * **All writes must go through [org.sempods.pods.grants.PodGrantsFacade], never this DAO
 * directly.** Narrowing or removing a grant here does not by itself reach an already-consented
 * app — the request path resolves permissions from [PodGrantsDao] alone. The facade owns the
 * cascade that re-derives the app level; bypassing it silently leaves revoked access in place.
 * The cascade deletes below mirror [PodGrantsDao] so a context/pod removal cannot leave dangling
 * user-level grants.
 *
 * **On the MongoDB driver, mapped by hand** — see `sempods-commons-mongo/docs/document-contract.md`. Like
 * [PodGrantsDao] and unlike every other collection here, its rows are written by an upsert rather
 * than by a document mapper, so the server picks their field order — see [addGrants].
 *
 * TODO: owner-facing grant CRUD (`{pod}/_system/grants`) is a follow-up — see the control-plane
 *   console roadmap (C2). It exposes the facade's grant/replace/revoke methods over HTTP,
 *   authorized by owner / `<root>#manage` like `PodContextsEndpoint`.
 */
class PodWebIdGrantsDao internal constructor(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states.
   */
  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.WEB_ID_GRANTS)

  private val grantRows = db.getCollection(collectionName)

  init {
    // The one index `@Indexes` declared, with the same options — measured against the running
    // database, which carries `podId_1_webId_1_scope_1` (unique). This DAO is bound **eagerly** in
    // `SempodsModule` precisely so that this runs at boot: the store is seeded out-of-band, ahead
    // of the owner-facing HTTP API, so the collection and its unique index must exist before the
    // first request rather than after it.
    grantRows.createIndex(
      Indexes.ascending(
        PodWebIdGrantDboFields.podId,
        PodWebIdGrantDboFields.webId,
        PodWebIdGrantDboFields.scope,
      ),
      IndexOptions().unique(true),
    )
  }

  /**
   * Upsert owner-level grants for `(podId, webId)`. Idempotent per grant string (unique index).
   * Additive — grants not listed are left alone; use [replaceGrants] when the caller's set is
   * meant to be authoritative.
   *
   * **The key filter and the `$setOnInsert` document decide the stored row's field order**, because
   * an upsert that inserts composes the new document out of exactly those two — there is no encoder
   * in this path. Both are kept as Morphia issued them, down to the explicit `$and`: the driver's
   * `Filters.and(…)` flattens to a single document, and the server lays a row out differently for a
   * flat query than for an `$and` one. Measured in `PodGrantsDaoTest`, which is where the
   * sibling collection compares the two writers directly.
   */
  internal fun addGrants(
    podId: ObjectId,
    webId: String,
    grants: Collection<String>,
    grantedBy: String?,
  ) {
    val now = Instant.now()
    grants
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .forEach { grant ->
        val onInsert = Document()
          .putNotNull(PodWebIdGrantDboFields.podId, podId)
          .putNotNull(PodWebIdGrantDboFields.webId, webId)
          .putNotNull(PodWebIdGrantDboFields.scope, grant)
          .putInstant(PodWebIdGrantDboFields.grantedAt, now)
          .putNotNull(PodWebIdGrantDboFields.grantedBy, grantedBy)
        grantRows.updateOne(
          grantKeyFilter(podId = podId, webId = webId, scope = grant),
          Updates.setOnInsert(onInsert),
          UpdateOptions().upsert(true),
        )
      }
  }

  /**
   * Makes [grants] the authoritative set for `(podId, webId)`: entries not listed are revoked.
   *
   * Implemented as a **diff**, not as delete-all-then-reinsert like [PodGrantsDao.replaceGrants].
   * There are no Mongo transactions here (standalone node), so the failure mode matters: wiping
   * the whole set first means a crash mid-way leaves the person with *no* authority at all, which
   * the refresh-token path reads as "everything revoked" and answers with a forced re-consent. A
   * diff only touches what actually changed. Removals are applied before additions so a crash can
   * only ever under-grant, never over-grant.
   *
   * Returns the number of revoked rows — what the caller's cascade reports.
   */
  internal fun replaceGrants(
    podId: ObjectId,
    webId: String,
    grants: Collection<String>,
    grantedBy: String?,
  ): Long {
    val desired = grants.map(String::trim).filter(String::isNotBlank).toSet()
    val current = fetchGrantStrings(podId = podId, webIds = listOf(webId))
    val removed = current - desired
    val added = desired - current
    val revoked = if (removed.isEmpty()) 0L else deleteGrants(podId, webId, removed)
    if (added.isNotEmpty()) {
      addGrants(podId = podId, webId = webId, grants = added, grantedBy = grantedBy)
    }
    return revoked
  }

  /** Removes exactly the listed [grants] for one WebID. Covered by the unique index. */
  internal fun deleteGrants(podId: ObjectId, webId: String, grants: Collection<String>): Long {
    if (grants.isEmpty()) return 0L
    return grantRows.deleteMany(
      Filters.and(
        Filters.eq(PodWebIdGrantDboFields.podId, podId),
        Filters.eq(PodWebIdGrantDboFields.webId, webId),
        Filters.`in`(PodWebIdGrantDboFields.scope, grants.toList()),
      ),
    ).deletedCount
  }

  /** Removes every owner-level grant one WebID holds on this pod. */
  internal fun deleteByWebId(podId: ObjectId, webId: String): Long =
    grantRows.deleteMany(
      Filters.and(
        Filters.eq(PodWebIdGrantDboFields.podId, podId),
        Filters.eq(PodWebIdGrantDboFields.webId, webId),
      ),
    ).deletedCount

  /**
   * The app-independent grant strings (`<context-iri>#read|write|manage`) granted to any of
   * [webIds] on [podId]. Pass every URI the caller is known by (`identity.allUris` — the WebID
   * plus `also_known_as`) so a grant made under one identity URI still resolves when the person
   * authenticates under an equivalent one, mirroring the owner check and [PodGrantsDao]. Empty
   * when none of the WebIDs has an owner-level grant.
   */
  internal fun fetchGrantStrings(podId: ObjectId, webIds: Collection<String>): Set<String> =
    fetchGrants(podId = podId, webIds = webIds).map { it.scope }.toSet()

  /**
   * Full rows for any of [webIds] on [podId] — carries `grantedBy`/`grantedAt`, which
   * [fetchGrantStrings] drops. Used by the owner-facing listing and wherever provenance matters.
   */
  internal fun fetchGrants(podId: ObjectId, webIds: Collection<String>): List<PodWebIdGrantDbo> {
    if (webIds.isEmpty()) return emptyList()
    return grantRows.find(
      Filters.and(
        Filters.eq(PodWebIdGrantDboFields.podId, podId),
        Filters.`in`(PodWebIdGrantDboFields.webId, webIds.toList()),
      ),
    )
      .map { it.toDbo() }
      .toList()
  }

  /**
   * Drop every owner-level grant of a pod.
   *
   * `deleteMany`, because Morphia's `deleteAll()` is `DeleteOptions().multi(true)`: a `deleteOne`
   * carried over here would leave all but one of a deleted pod's grants behind.
   */
  internal fun deleteByPod(podId: ObjectId): Long =
    grantRows.deleteMany(Filters.eq(PodWebIdGrantDboFields.podId, podId)).deletedCount

  /**
   * Removes every grant whose scope is `<contextUri>#read`, `<contextUri>#write`, or
   * `<contextUri>#manage`. Used by the context-cascade delete path. Uses an exact
   * `Filters.in` over the three permission strings (not a `startsWith` regex) so a sibling
   * context sharing a URI prefix (e.g. deleting `/tasks` must not sweep `/tasks-private#read`)
   * is never touched — same rule as [PodGrantsDao.deleteByContext].
   */
  internal fun deleteByContext(podId: ObjectId, contextUri: String): Long =
    grantRows.deleteMany(
      Filters.and(
        Filters.eq(PodWebIdGrantDboFields.podId, podId),
        Filters.`in`(
          PodWebIdGrantDboFields.scope,
          PodGrantsDao.CONTEXT_PERMISSIONS.map { "$contextUri#$it" },
        ),
      ),
    ).deletedCount

  /**
   * Returns `true` if any grant row exists for [podId]. Used by cascade-deletion tests to
   * assert that pod-scoped grant rows survived or were swept.
   */
  internal fun anyForPod(podId: ObjectId): Boolean =
    grantRows.find(Filters.eq(PodWebIdGrantDboFields.podId, podId)).limit(1).first() != null

  private companion object {

    /**
     * The upsert's key filter. Field order of the row this inserts is the server's choice and is
     * not stable across rows, for the reason `PodGrantsDao`'s key filter states — the two grant
     * collections are the milestone's exception to the field-order rule, and were before this
     * change too.
     */
    private fun grantKeyFilter(podId: ObjectId, webId: String, scope: String): Bson =
      Filters.and(
        Filters.eq(PodWebIdGrantDboFields.podId, podId),
        Filters.eq(PodWebIdGrantDboFields.webId, webId),
        Filters.eq(PodWebIdGrantDboFields.scope, scope),
      )

    private fun Document.toDbo(): PodWebIdGrantDbo = PodWebIdGrantDbo(
      id = getObjectId(PodWebIdGrantDboFields.id),
      podId = getObjectId(PodWebIdGrantDboFields.podId),
      webId = getString(PodWebIdGrantDboFields.webId),
      scope = getString(PodWebIdGrantDboFields.scope),
      grantedBy = getString(PodWebIdGrantDboFields.grantedBy),
      // Every row has one — it is non-null in the entity and has been since the collection existed.
      // Failing loudly beats defaulting to `now`, which would restamp a corrupt grant as freshly
      // granted.
      grantedAt = checkNotNull(getInstant(PodWebIdGrantDboFields.grantedAt)) {
        "owner-level grant without grantedAt: ${getString(PodWebIdGrantDboFields.scope)}"
      },
    )
  }
}
