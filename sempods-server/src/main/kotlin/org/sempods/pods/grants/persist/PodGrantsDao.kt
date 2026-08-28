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
import org.sempods.commons.mongo.getStringList
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Store for **app-delegated** grants ([PodGrantDbo], `grants`) — what an app may do on
 * behalf of a person, keyed `(podId, appId, webId, scope)`.
 *
 * Naming: the `scope` column holds *grant strings* (`<context-iri>#read|write|manage`), which are
 * not OAuth scopes — they never travel in an access token and are resolved server-side per request
 * (`SPS-GRANT-001`…`003` (sempods-spec)). The
 * one genuine OAuth scope that also lives here is the feature scope `public-read`, persisted so
 * `prompt=none` auto-grant honours the user's choice. Any consumer that filters by
 * "context grants only" must route through `PodScopeValidator`, not string shape.
 *
 * Writes funnel through [org.sempods.pods.grants.PodGrantsFacade] so the owner-level layer
 * ([PodWebIdGrantsDao]) and this one cannot drift apart.
 *
 * **On the MongoDB driver, mapped by hand** — see `sempods-commons-mongo/docs/document-contract.md`. No document
 * mapper writes here: every row is created by the upsert in [addGrants], and the inserted document
 * is composed by Mongo out of the filter's equality fields plus `$setOnInsert`, in an order it
 * picks. The field *set* is what holds, and `PodGrantsDaoTest` asserts it.
 */
class PodGrantsDao internal constructor(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states.
   */
  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.GRANTS)

  private val grantRows = db.getCollection(collectionName)

  init {
    // The three indexes `@Indexes` declared, with the same options — measured against the running
    // database. The `partialFilter` on the unique one is redundant (every row has an `appId`) and
    // is reproduced anyway, exactly as the annotation did: `createIndex` throws
    // `IndexOptionsConflict` against an existing index whose options differ, and that failure lands
    // at boot rather than at the first query. The other two are the `$or` branches of the
    // owner-level revocation cascade, and carry no options at all.
    grantRows.createIndex(
      Indexes.ascending(
        PodGrantDboFields.appId,
        PodGrantDboFields.podId,
        PodGrantDboFields.webId,
        PodGrantDboFields.scope,
      ),
      IndexOptions()
        .unique(true)
        .partialFilterExpression(Filters.exists(PodGrantDboFields.appId, true)),
    )
    grantRows.createIndex(Indexes.ascending(PodGrantDboFields.podId, PodGrantDboFields.webId))
    grantRows.createIndex(Indexes.ascending(PodGrantDboFields.podId, PodGrantDboFields.subjectUris))
  }

  internal fun deleteByPod(podId: ObjectId): Long =
    grantRows.deleteMany(Filters.eq(PodGrantDboFields.podId, podId)).deletedCount

  /**
   * Removes every grant whose scope is `<contextUri>#read`, `<contextUri>#write`, or
   * `<contextUri>#manage`. Used by the context-cascade delete path. Uses an exact
   * `Filters.in` over the three permission strings rather than a `startsWith` regex so
   * that a sibling context with a prefix-overlapping URI (e.g. deleting `/tasks` would
   * otherwise sweep `/tasks-private#read`) is never touched.
   */
  internal fun deleteByContext(podId: ObjectId, contextUri: String): Long =
    grantRows.deleteMany(
      Filters.and(
        Filters.eq(PodGrantDboFields.podId, podId),
        Filters.`in`(PodGrantDboFields.scope, anchoredScopes(contextUri)),
      ),
    ).deletedCount

  /**
   * Upserts one row per entry of [grants]. Idempotent per grant string (unique index).
   *
   * [subjectUris] records every identity URI the person was known by at consent time so the
   * owner-level revocation cascade can find these rows even when the owner-level grant was
   * written under an alias — see [PodGrantDbo.subjectUris]. It is written with `setOnInsert`, so
   * an existing row keeps its recorded set; the consent path uses [replaceGrants], which deletes
   * first and therefore always records the current set.
   *
   * **The order of both the filter and the `$setOnInsert` document is the stored row's field
   * order**, because an upsert that inserts builds the new document out of exactly those two —
   * there is no encoder in this path to impose the entity's declaration order. Both are kept as
   * Morphia issued them, so a row this writes is laid out like the rows already on disk.
   */
  internal fun addGrants(
    podId: ObjectId,
    appId: String,
    webId: String,
    grants: Collection<String>,
    subjectUris: Collection<String>? = null,
    grantedBy: String?,
  ) {
    val now = Instant.now()
    val normalizedSubjectUris = subjectUris
      ?.map(String::trim)
      ?.filter(String::isNotBlank)
      ?.distinct()
      ?.takeIf { it.isNotEmpty() }
    grants
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .forEach { grant ->
        val onInsert = Document()
          .putNotNull(PodGrantDboFields.appId, appId)
          .putNotNull(PodGrantDboFields.podId, podId)
          .putNotNull(PodGrantDboFields.webId, webId)
          .putNotNull(PodGrantDboFields.scope, grant)
          .putInstant(PodGrantDboFields.grantedAt, now)
          .putStrings(PodGrantDboFields.subjectUris, normalizedSubjectUris)
          .putNotNull(PodGrantDboFields.grantedBy, grantedBy)
        grantRows.updateOne(
          grantKeyFilter(podId = podId, appId = appId, webId = webId, scope = grant),
          Updates.setOnInsert(onInsert),
          UpdateOptions().upsert(true),
        )
      }
  }

  /**
   * The grant strings this app may exercise for any of [webIds] on [podId].
   *
   * **Request hot path** — called by
   * [org.sempods.pods.grants.PodContextPermissionResolver.resolveFromGrants] on every
   * authenticated request. Fully covered by the `(appId, podId, webId, scope)` unique index.
   */
  internal fun fetchGrantStrings(
    podId: ObjectId,
    appId: String,
    webIds: List<String>,
  ): Set<String> =
    grantRows.find(
      Filters.and(
        Filters.eq(PodGrantDboFields.podId, podId),
        Filters.eq(PodGrantDboFields.appId, appId),
        Filters.`in`(PodGrantDboFields.webId, webIds),
      ),
    )
      .map { it.getString(PodGrantDboFields.scope) }
      .toSet()

  /**
   * Every row on [podId] belonging to a person identified by any of [webIds], **across all apps**.
   *
   * The owner-level revocation cascade needs this: it knows the person, not the apps they
   * consented to, so it cannot pin `appId` and a grant-string set would not tell it which app to
   * narrow. Matches either the primary [PodGrantDbo.webId] or the recorded
   * [PodGrantDbo.subjectUris] alias set — legacy rows have no alias set and are found via `webId`
   * alone, which is the pre-existing behaviour. Both `$or` branches are index-backed by the
   * additive `(podId, webId)` / `(podId, subjectUris)` indexes.
   */
  internal fun fetchGrantsForSubject(podId: ObjectId, webIds: Collection<String>): List<PodGrantDbo> {
    if (webIds.isEmpty()) return emptyList()
    val uris = webIds.toList()
    return find(
      Filters.and(
        Filters.eq(PodGrantDboFields.podId, podId),
        Filters.or(
          Filters.`in`(PodGrantDboFields.webId, uris),
          Filters.`in`(PodGrantDboFields.subjectUris, uris),
        ),
      ),
    )
  }

  /**
   * Every row on [podId], across all apps and subjects.
   *
   * Used by the context-deletion sweep, which cannot narrow by subject: the WebIDs whose
   * authority a context deletion removes are gone from the owner-level store by the time the
   * sweep runs, and re-deriving them from surviving state is what makes the operation retryable.
   * Backed by the `(podId, webId)` index prefix; a pod's grant set is small (one row per
   * consented grant per app per person).
   */
  internal fun fetchGrantsByPod(podId: ObjectId): List<PodGrantDbo> =
    find(Filters.eq(PodGrantDboFields.podId, podId))

  /**
   * Removes exactly the listed [grants] for one `(appId, webId)`. Fully covered by the unique
   * index.
   *
   * Deliberately scoped to a single app rather than offering a bulk `(podId, webIds, grants)`
   * form: that shape pins neither index prefix (`appId` leads the unique index, `scope` is absent
   * from the additive ones) and would be a collection scan. A person has a handful of apps per
   * pod, so per-app deletes are both cheaper and simpler to reason about.
   */
  internal fun deleteGrants(
    podId: ObjectId,
    appId: String,
    webId: String,
    grants: Collection<String>,
  ): Long {
    if (grants.isEmpty()) return 0L
    return grantRows.deleteMany(
      Filters.and(
        Filters.eq(PodGrantDboFields.podId, podId),
        Filters.eq(PodGrantDboFields.appId, appId),
        Filters.eq(PodGrantDboFields.webId, webId),
        Filters.`in`(PodGrantDboFields.scope, grants.toList()),
      ),
    ).deletedCount
  }

  /**
   * Replaces the full set of grants for `(podId, appId, webId)` with [grants].
   * Used by the OAuth consent flow where the user's checkbox submission is the
   * authoritative new state — grants not in the submitted set must be revoked.
   */
  internal fun replaceGrants(
    podId: ObjectId,
    appId: String,
    webId: String,
    grants: Collection<String>,
    subjectUris: Collection<String>? = null,
    grantedBy: String?,
  ) {
    grantRows.deleteMany(
      Filters.and(
        Filters.eq(PodGrantDboFields.podId, podId),
        Filters.eq(PodGrantDboFields.appId, appId),
        Filters.eq(PodGrantDboFields.webId, webId),
      ),
    )
    addGrants(podId, appId, webId, grants, subjectUris, grantedBy)
  }

  /**
   * Returns `true` if any grant row exists for [podId]. Used by cascade-deletion
   * tests to assert that pod-scoped grant rows survived or were swept.
   */
  internal fun anyForPod(podId: ObjectId): Boolean =
    grantRows.find(Filters.eq(PodGrantDboFields.podId, podId)).limit(1).first() != null

  private fun find(filter: Bson): List<PodGrantDbo> = grantRows.find(filter).map { it.toDbo() }.toList()

  internal companion object {
    /**
     * The permission suffixes a context grant string can carry. Mirrors
     * `org.sempods.pods.grants.ScopePermission`, kept as plain strings here so the persistence
     * layer does not reach up into the grants service package.
     */
    val CONTEXT_PERMISSIONS = listOf("read", "write", "manage")

    private fun anchoredScopes(contextUri: String): List<String> =
      CONTEXT_PERMISSIONS.map { "$contextUri#$it" }

    /**
     * The upsert's key filter.
     *
     * **This collection is the one place in the milestone where field order is not preserved,
     * because it was never stable to begin with.** On an insert the server composes the new
     * document out of the query's equality fields, and the order it picks varies from row to row
     * for the same command — measured in `PodGrantsDaoTest`, which upserts identical
     * values repeatedly and gets different layouts back. The rows Morphia wrote are therefore
     * already laid out inconsistently among themselves, and no spelling of this filter changes
     * that. What is stable, and what the test asserts instead, is the set of fields and their
     * values.
     */
    private fun grantKeyFilter(podId: ObjectId, appId: String, webId: String, scope: String): Bson =
      Filters.and(
        Filters.eq(PodGrantDboFields.appId, appId),
        Filters.eq(PodGrantDboFields.podId, podId),
        Filters.eq(PodGrantDboFields.webId, webId),
        Filters.eq(PodGrantDboFields.scope, scope),
      )

    private fun Document.toDbo(): PodGrantDbo = PodGrantDbo(
      id = getObjectId(PodGrantDboFields.id),
      podId = getObjectId(PodGrantDboFields.podId),
      appId = getString(PodGrantDboFields.appId),
      webId = getString(PodGrantDboFields.webId),
      scope = getString(PodGrantDboFields.scope),
      // Null rather than empty for a row written before the alias set existed — the revocation
      // cascade substitutes `listOf(webId)` for exactly that state, and an empty list would read
      // as "known by no URI at all".
      subjectUris = if (containsKey(PodGrantDboFields.subjectUris)) {
        getStringList(PodGrantDboFields.subjectUris)
      } else {
        null
      },
      grantedBy = getString(PodGrantDboFields.grantedBy),
      // Every row has one — it is non-null in the entity and has been since the collection existed.
      // Failing loudly beats defaulting to `now`, which would restamp a corrupt grant as freshly
      // consented.
      grantedAt = checkNotNull(getInstant(PodGrantDboFields.grantedAt)) {
        "grant without grantedAt: ${getString(PodGrantDboFields.scope)}"
      },
    )
  }
}
