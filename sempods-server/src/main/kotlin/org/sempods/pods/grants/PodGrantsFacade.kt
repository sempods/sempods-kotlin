package org.sempods.pods.grants

import com.google.inject.Inject
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.SempodsUriBuilder
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantDbo
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantDbo
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.types.ObjectId
import java.net.URI

/**
 * Single entry point for reading and mutating grants on a pod.
 *
 * ## The two levels, and why they can drift
 *
 * `SPS-GRANT-013` (sempods-spec) defines authorization as a
 * two-level intersection:
 *
 * ```
 * granted = requested ∩ user_grants
 * ```
 *
 * The *user level* ([PodWebIdGrantsDao]) says what a person may do on this pod. The *app level*
 * ([PodGrantsDao]) says what an app may do on their behalf, and is **derived** from the user level
 * once, at consent time. The request path
 * ([PodContextPermissionResolver.resolveFromGrants]) then reads the app level alone.
 *
 * That derivation is why every owner-level write has to funnel through here. Narrowing or removing
 * a user-level grant does not by itself reach an app that already consented — its derived rows
 * would keep authorizing the revoked access indefinitely. This facade owns the cascade that
 * re-derives them.
 *
 * ## Why revocation recomputes instead of the request path intersecting
 *
 * Re-intersecting on every request would be the obvious fix and is the wrong one. A request
 * carries a single identity URI (the token's `sub`), while a user-level grant may have been
 * written under an equivalent one, so the intersection would drop legitimate access. It would also
 * put a second grant-store round-trip on the hot path of every authenticated request.
 *
 * Revocation is rare and knows the full picture, so the intersection is re-applied there instead:
 * [cascadeToAppGrants] recomputes [resolveUserGrants] and deletes the app-level rows it no longer
 * covers. A plain string match against the revoked grant would not do — [resolveUserGrants]
 * expands `<root>#manage` into its registered descendants, so revoking `<root>#manage` has to
 * sweep derived rows like `<root>/child#write` whose text matches nothing.
 *
 * The recompute is complete rather than approximate because
 * [PodContextPermissionResolver.expandManageCascade] only ever expands into contexts that exist in
 * the registry: a context created *after* consent is never materialized at the app level (the
 * request path expands it live), so the cascade only has to reason about what was materialized.
 */
class PodGrantsFacade @Inject constructor(
  private val podWebIdGrantsDao: PodWebIdGrantsDao,
  private val podGrantsDao: PodGrantsDao,
  private val podContextsDao: PodContextsDao,
  private val podServiceClientDao: PodServiceClientDao,
  private val podScopeValidator: PodScopeValidator,
  private val podContextPermissionResolver: PodContextPermissionResolver,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val sempodsUriBuilder: SempodsUriBuilder,
  private val webIdUriDeriver: WebIdUriDeriver,
) {

  // ── user level: what a person may do on this pod ────────────────────────────

  /**
   * The effective grant set for a person on [podDbo] — the `user_grants` side of the two-level
   * intersection.
   *
   * - Pod owner: implicit `read | write | manage` on every registered context.
   * - Everyone else: their owner-level grants from `webIdGrants`, with `<root>#manage`
   *   expanded via the slash-delimited cascade so a manage grant lets the person delegate every
   *   registered descendant — the same rule the resource layer enforces. Any grant on a context
   *   implies read (the resource layer already makes a `#write`/`#manage` context readable), so a
   *   write-authority person can still delegate read-only access at consent, which exact-matches
   *   against this set.
   *
   * Pass **every** URI the person is known by (`identity.allUris` at consent time; the union of
   * `webId` and [PodGrantDbo.subjectUris] during revocation) — a grant made under one identity URI
   * must still resolve when they appear under an equivalent one.
   */
  internal fun resolveUserGrants(
    podDbo: PodDbo,
    webIds: Collection<String>,
    podBaseUrl: String,
  ): Set<String> {
    val podId = checkNotNull(podDbo.id)
    if (isPodOwner(podDbo, webIds)) {
      return podContextsDao.fetchByPod(podId).flatMap { ctx ->
        ScopePermission.entries.map { permission -> "${ctx.contextUri}#${permission.value}" }
      }.toSet()
    }

    // Keep only well-formed context grants before expanding — a malformed or foreign string in
    // the store must not surface into the person's delegatable set.
    val contextGrants = podWebIdGrantsDao.fetchGrantStrings(podId, webIds)
      .filter { podScopeValidator.validate(it, podBaseUrl) is ScopeValidationResult.Context }
      .toSet()
    if (contextGrants.isEmpty()) return emptySet()

    val expanded = podContextPermissionResolver.expandManageCascade(contextGrants, podId, podBaseUrl)
    val impliedReads = expanded
      .mapNotNull { grant ->
        (podScopeValidator.validate(grant, podBaseUrl) as? ScopeValidationResult.Context)
          ?.takeIf { it.permission != ScopePermission.read }
          ?.contextUri
      }
      .map { contextUri -> "$contextUri#read" }
      .toSet()
    return expanded + impliedReads
  }

  /**
   * Whether any of [webIds] is the pod owner. The single source of this check — the owner branch
   * of [resolveUserGrants] and every caller-side owner decision must agree, or a cascade would
   * strip grants the owner implicitly still holds.
   */
  internal fun isPodOwner(podDbo: PodDbo, webIds: Collection<String>): Boolean =
    podDbo.owner in webIds

  /** Owner-level grants held by any of [webIds], with `grantedBy`/`grantedAt` provenance. */
  internal fun fetchWebIdGrants(podDbo: PodDbo, webIds: Collection<String>): List<PodWebIdGrantDbo> =
    podWebIdGrantsDao.fetchGrants(checkNotNull(podDbo.id), webIds)

  /**
   * Grants [grants] to [webId] on top of what they already hold.
   *
   * Deliberately does **not** cascade: widening a person's authority never widens an app
   * retroactively. The app level is `requested ∩ user_grants` fixed at consent, so a newly granted
   * context reaches an app only when the user delegates it in a fresh consent. Adding a cascade
   * here would silently hand apps access the user never delegated.
   */
  internal fun addWebIdGrants(
    podDbo: PodDbo,
    webId: String,
    grants: Collection<String>,
    grantedBy: String?,
  ) {
    podWebIdGrantsDao.addGrants(
      podId = checkNotNull(podDbo.id),
      webId = webId,
      grants = grants,
      grantedBy = grantedBy,
    )
  }

  /**
   * Makes [grants] the authoritative owner-level set for [webId], cascading what it removes.
   *
   * Writes against exactly [webId] — a replace states "this URI now holds this set", so the caller
   * picks the key. The cascade that follows still spans the derivable aliases.
   */
  internal fun replaceWebIdGrants(
    podDbo: PodDbo,
    webId: String,
    grants: Collection<String>,
    grantedBy: String?,
  ): GrantCascadeResult = mutateWebIdGrants(podDbo, webId) { _ ->
    podWebIdGrantsDao.replaceGrants(
      podId = checkNotNull(podDbo.id),
      webId = webId,
      grants = grants,
      grantedBy = grantedBy,
    )
  }

  /**
   * Removes exactly [grants] from [webId]'s owner-level set, cascading into derived app grants.
   *
   * Sweeps every derivable alias, not just the URI as given: in both identity namespaces the
   * dereferenceable WebID and its URN twin (`{idBaseUrl}/e/<hash>` ↔ `urn:sempods:e:<hash>`,
   * likewise for `oidc`) provably denote the same subject, and a revocation that missed the twin
   * would leave the access it was meant to remove in place.
   */
  internal fun revokeWebIdGrants(
    podDbo: PodDbo,
    webId: String,
    grants: Collection<String>,
  ): GrantCascadeResult {
    val podId = checkNotNull(podDbo.id)
    return mutateWebIdGrants(podDbo, webId) { aliases ->
      aliases.sumOf { alias -> podWebIdGrantsDao.deleteGrants(podId, alias, grants) }
    }
  }

  /** Removes every owner-level grant [webId] holds, across derivable aliases, and cascades. */
  internal fun revokeAllWebIdGrants(podDbo: PodDbo, webId: String): GrantCascadeResult {
    val podId = checkNotNull(podDbo.id)
    return mutateWebIdGrants(podDbo, webId) { aliases ->
      aliases.sumOf { alias -> podWebIdGrantsDao.deleteByWebId(podId, alias) }
    }
  }

  // ── app level ───────────────────────────────────────────────────────────────

  /**
   * Persists what a user delegated to an app — the authoritative new state, so anything not in
   * [grants] is revoked. Called from the OAuth consent submission and from the auto-grant branch
   * that re-narrows a stale set. **Returns the set that actually survived**, which is not always
   * [grants] — see below.
   *
   * [subjectUris] must be the consenting identity's full URI set (`identity.allUris`); it is what
   * lets a later owner-level revocation find these rows when the owner-level grant was written
   * under an alias. See [PodGrantDbo.subjectUris].
   *
   * ## Why this re-checks after writing
   *
   * Callers compute [grants] by intersecting a request against the user level, and time passes
   * between that read and this write. An owner-level revocation that lands inside that window is
   * invisible to both sides: the caller's intersection is already stale, and the revocation's own
   * cascade runs before this row exists, so it finds nothing to sweep. The result would be an
   * app grant no authority backs — permanently, since the request path consults the app level
   * alone.
   *
   * There is no transaction to lean on (standalone Mongo). Instead both sides write before they
   * check: a revocation deletes the owner-level row and *then* cascades, and this method persists
   * and *then* re-derives. That makes the two unable to miss each other. If the revocation's
   * cascade did not see this row, its delete had already landed, so the re-derivation here must
   * observe it and sweeps the row again; and if the delete lands later, its own cascade sees the
   * row. The check is a no-op whenever nothing raced, which is the overwhelmingly common case.
   */
  internal fun replaceAppGrants(
    podDbo: PodDbo,
    appId: String,
    webId: String,
    subjectUris: Collection<String>,
    grants: Collection<String>,
    grantedBy: String?,
  ): Set<String> {
    val podId = checkNotNull(podDbo.id)
    podGrantsDao.replaceGrants(
      podId = podId,
      appId = appId,
      webId = webId,
      grants = grants,
      subjectUris = subjectUris,
      grantedBy = grantedBy,
    )

    val cascade = cascadeToAppGrants(
      podDbo,
      subjectUris.toSet() + webIdUriDeriver.derivableAliases(webId),
    )
    if (cascade.deletedAppGrants == 0L) return grants.toSet()

    // Only reachable when an owner-level change raced this write. Re-read rather than subtract:
    // the cascade reports counts, and the caller needs the exact surviving set to decide whether
    // issuing a token still makes sense.
    val surviving = podGrantsDao.fetchGrantStrings(podId, appId, listOf(webId))
    logger.warn {
      "[grants/consent] Owner-level change raced this delegation — dropped unbacked grants: " +
          "pod='${podDbo.name}', clientId='$appId', webId='$webId', " +
          "requested=${grants.size}, surviving=${surviving.size}"
    }
    return surviving
  }

  // ── context lifecycle ───────────────────────────────────────────────────────

  /**
   * Revokes everything anchored at [contextUri] when the context itself is being deleted:
   * app-delegated grants, owner-level WebID grants, and static service-client registrations.
   *
   * **A refresh family is never revoked for naming the context, only for being left with nothing.**
   * A refresh row carries feature scopes only — both insert paths intersect against
   * [PodScopeValidator.featureScopes] — and the refresh exchange intersects again before issuing,
   * so a family holds no authority over a context to lose. What closes the
   * re-create-with-same-URI window is the grant deletion below: permissions are resolved per
   * request from these rows, so a context recreated under the same URI inherits nothing. Sweeping
   * families by the scope string would instead end the sessions of apps that still hold other
   * grants, and only those families old enough to carry a context scope at all.
   *
   * What does end a family here is the same condition [sweepUnbackedAppGrants] applies — the app
   * is left holding nothing — and it needs a pass of its own, because a pair whose *last* grant
   * named the deleted context never reaches that one: its rows are gone before the recompute
   * reads them. Keyed on the family's own `webId`, exactly as the refresh exchange keys its
   * `currentGrants` check, so the proactive answer and the lazy one cannot disagree.
   *
   * **That pass is the one step here that reads before the deletes, and the one that may be lost
   * to a retry.** Its candidates are the pairs holding a grant anchored at [contextUri], captured
   * ahead of the delete that removes them — the only moment they are visible. Deriving them
   * afterwards would mean scanning the pod's live families instead, and `replaceGrants` is a
   * delete followed by inserts, so such a scan would sooner or later catch an unrelated
   * re-consent mid-replacement and irreversibly revoke a connection it was in the middle of
   * renewing. Narrowing to this deletion's own subjects is what keeps that impossible; the price
   * is that a run dying between the delete and the revocation leaves a family standing. That
   * price is payable only here, and only because this is the one step with a backstop: the
   * refresh exchange refuses a token whose app has no grants and ends the family itself.
   *
   * The exact-string sweep alone is **not** sufficient, and this is easy to get wrong. Context
   * deletion does not cascade into sub-contexts — `R/sub` survives with its own data — but
   * deleting `R` does remove an `R#manage` grant, and with it the person's authority over that
   * whole subtree. App-delegated rows derived from that manage root name the *descendants*
   * (`R/sub#write`), so no string match reaches them and they would keep authorizing writes to a
   * context that still exists. Hence the same recompute as [revokeWebIdGrants] runs afterwards,
   * for every subject whose owner-level authority this deletion touched.
   *
   * The pod owner is unaffected by that recompute: their authority is implicit over every
   * *registered* context, and `R/sub` stays registered, so their delegations survive.
   *
   * **Retryable**, with the one exception named above. Every other step derives what it needs from
   * `(podDbo, contextUri)` and the rows that are still in the store, and every step is a no-op once
   * it has run — so an attempt that dies part-way (a failing service-client update, a process kill)
   * is repaired by calling this again with the same arguments. Nothing else is carried in memory
   * between the deletes and the sweep; that would be exactly the state a retry could no longer
   * reconstruct. The sweep runs
   * before [org.sempods.pods.PodFacade.removeContext] touches data or the registry row, so a
   * failure here also leaves the caller's own retry path intact.
   */
  internal fun revokeContextGrants(podDbo: PodDbo, contextUri: String): GrantCascadeResult {
    val podId = checkNotNull(podDbo.id)
    // Read first, because the delete below is what makes these unfindable — see the note above on
    // why the candidates are this deletion's own subjects and not the pod's live connections.
    val delegatedHere = podGrantsDao.fetchByContext(podId = podId, contextUri = contextUri)
      .map { it.appId to it.webId }
      .toSet()
    val deletedAppGrants = podGrantsDao.deleteByContext(podId = podId, contextUri = contextUri)
    // Owner-level, app-independent WebID grants cascade the same way as the app-delegated
    // grants above — a manage/write/read grant must not outlive its context.
    podWebIdGrantsDao.deleteByContext(podId = podId, contextUri = contextUri)
    // Static service clients are revoked like grants: scopes anchored at the deleted context are
    // stripped, grant-less registrations removed — otherwise the client secret could keep minting
    // tokens for the deleted root (manage descendants, recreate the root). Unlike a refresh row, a
    // registration's context scopes *are* the authority the resolver reads.
    val revokedClients = podServiceClientDao.revokeByContextScope(podId = podId, contextUri = contextUri)
    if (revokedClients > 0) {
      logger.info { "Revoked $revokedClients service-client registration(s) anchored at deleted context $contextUri" }
    }

    // Second pass: sweep the app grants that were *derived* from an authority this deletion just
    // removed but that name a surviving descendant. Recomputed from scratch, never from state
    // captured before the deletes — see the retry note above.
    val derived = sweepSubtreeAppGrants(podDbo, contextUri)

    // Third pass, and the reason it is a pass rather than a line inside the second: the pairs it
    // is for have no rows left for the recompute to group.
    val orphaned = revokeEmptiedFamilies(podId, delegatedHere)

    return GrantCascadeResult(
      deletedAppGrants = deletedAppGrants + derived.deletedAppGrants,
      revokedRefreshTokens = derived.revokedRefreshTokens + orphaned.revokedRefreshTokens,
      affectedApps = derived.affectedApps + orphaned.affectedApps,
    )
  }

  /**
   * Revokes the families of those [candidates] that now hold no grant at all.
   *
   * [candidates] are `(appId, webId)` pairs that held a grant on the context being deleted, read
   * before it was removed. Both halves are load-bearing: the first keeps every connection this
   * deletion did not touch out of reach, and the second is asked afterwards, so a pair that lost
   * one grant and kept others is left alone. Idempotent — a second run finds the same pairs
   * holding the same nothing and re-revokes rows that are already revoked, which is a no-op.
   *
   * **Named before the emptiness is asked, and revoked by name — one row at a time.** Reading
   * grants and then revoking *everything* the pair holds is two moments with a gap, and a
   * re-consent for one of these very pairs can complete inside it. Naming the rows first closes it,
   * and the rows are named rather than their families because a family id keeps selecting: a refresh
   * succeeding in the gap rotates a successor into a family this pass listed, and reaching it would
   * mean revoking a token already handed to a client — the failure this whole shape exists to
   * avoid. Whether that successor may live is not this pass's question, and it is not answered by
   * the rotation having succeeded: that refresh read its grants before this deletion wrote. It is
   * answered where it can be, by the refresh exchange asking the grants once more after inserting
   * the successor. What is left is narrow and benign: a re-consent restoring grants without any
   * rotation loses the rows it was replacing, which is what its own exchange would have done.
   *
   * What it revokes was functionally dead already: the refresh exchange refuses a token whose app
   * has no grants, so nothing could have been minted from it. This is about the two cascades
   * giving one answer, not about closing a hole.
   */
  private fun revokeEmptiedFamilies(
    podId: ObjectId,
    candidates: Set<Pair<String, String>>,
  ): GrantCascadeResult {
    var revokedRefreshTokens = 0L
    val affectedApps = mutableSetOf<String>()
    candidates.forEach { (appId, webId) ->
      val standing = refreshTokenStore.liveTokens(podId, appId, listOf(webId))
      if (standing.isEmpty()) return@forEach
      if (podGrantsDao.fetchGrantStrings(podId, appId, listOf(webId)).isNotEmpty()) return@forEach
      revokedRefreshTokens += refreshTokenStore.revokeTokens(standing)
      affectedApps += appId
    }
    if (revokedRefreshTokens > 0) {
      logger.info {
        "[grants/revoke] Families the context deletion left without any grant revoked: podId='$podId', " +
            "revokedRefreshTokens=$revokedRefreshTokens, apps=${affectedApps.sorted()}"
      }
    }
    return GrantCascadeResult(
      deletedAppGrants = 0,
      revokedRefreshTokens = revokedRefreshTokens,
      affectedApps = affectedApps,
    )
  }

  // ── internals ───────────────────────────────────────────────────────────────

  /**
   * Applies an owner-level mutation and then re-derives the app level.
   *
   * The ordering is not incidental. The user-level write happens **first** so the recompute reads
   * the state the caller just established — recomputing beforehand would let a concurrent second
   * revocation resurrect grants into the result. There are no Mongo transactions here (standalone
   * node), so a crash between the two steps leaves the app level stale, which is precisely the
   * pre-existing situation and is repaired by simply running the mutation again.
   */
  private fun mutateWebIdGrants(
    podDbo: PodDbo,
    webId: String,
    mutation: (aliases: Set<String>) -> Long,
  ): GrantCascadeResult {
    val aliases = webIdUriDeriver.derivableAliases(webId)
    val revokedUserGrants = mutation(aliases)
    val result = cascadeToAppGrants(podDbo, aliases)
    if (revokedUserGrants > 0 || result.deletedAppGrants > 0) {
      logger.info {
        "[grants/revoke] pod='${podDbo.name}', webId='$webId', revokedUserGrants=$revokedUserGrants, " +
            "deletedAppGrants=${result.deletedAppGrants}, apps=${result.affectedApps.sorted()}, " +
            "revokedRefreshTokens=${result.revokedRefreshTokens}"
      }
    }
    return result
  }

  /**
   * Deletes every app-level grant of the subject identified by [targetUris] that the recomputed
   * user level no longer covers.
   *
   * Only *context* grants are candidates. `grants` also holds the genuine OAuth feature
   * scope `public-read`, which never appears in [resolveUserGrants]; sweeping by "not in the
   * recomputed set" alone would delete it, and if it were the app's last remaining row the refresh
   * exchange would read that as a full revocation and force a re-consent. Narrowing one context
   * grant must not end a session.
   *
   * After the deletes the gap is already closed: access tokens carry no context permissions, so
   * the very next request resolves the reduced set. The refresh-token revocation that follows is
   * for determinism in the full-revocation case only — a surviving family can never re-hydrate
   * context grants (the stored `scopes` hold feature scopes), it would merely keep issuing
   * access tokens that no longer authorize anything. "Full" is measured over *all* remaining
   * grants, feature scopes included: an app left holding only `public-read` keeps its session.
   */
  private fun cascadeToAppGrants(podDbo: PodDbo, targetUris: Set<String>): GrantCascadeResult {
    val rows = podGrantsDao.fetchGrantsForSubject(checkNotNull(podDbo.id), targetUris)
    if (rows.isEmpty()) return GrantCascadeResult.empty

    // The subject's full URI set: what the revocation started from, plus every URI the app rows
    // recorded at consent time. Legacy rows carry no set and contribute their primary WebID only.
    val subjectUris = buildSet {
      addAll(targetUris)
      rows.forEach { row ->
        add(row.webId)
        row.subjectUris?.let(::addAll)
      }
    }
    return sweepUnbackedAppGrants(podDbo, rows, subjectUris)
  }

  /**
   * Re-derives the app level for every subject holding grants inside [root]'s subtree, deleting
   * what the current user level no longer backs.
   *
   * This is the context-deletion counterpart of [cascadeToAppGrants], and it deliberately takes
   * **no** caller-supplied state: everything it needs is recomputed from `(pod, root)` plus rows
   * that are still in the store. That is what makes [revokeContextGrants] retryable — an earlier
   * attempt that died after removing the owner-level rows but before the sweep leaves no
   * bookkeeping behind, so a repair pass has to be able to find the work on its own.
   *
   * Candidates are restricted to [root] and its slash-delimited descendants, via the same
   * [PodContextPermissionResolver] rule the resource layer enforces — deleting `R` can only ever
   * have removed authority over `R`'s subtree, so nothing outside it may be touched. Rows in the
   * subtree that are still backed (a direct descendant grant, or the pod owner's implicit
   * authority over contexts that remain registered) survive.
   */
  private fun sweepSubtreeAppGrants(podDbo: PodDbo, root: String): GrantCascadeResult {
    val podId = checkNotNull(podDbo.id)
    val podBaseUrl = podBaseUrl(podDbo)
    val rows = podGrantsDao.fetchGrantsByPod(podId)
    if (rows.isEmpty()) return GrantCascadeResult.empty

    return rows.groupBy { it.webId }.entries.fold(GrantCascadeResult.empty) { acc, (webId, subjectRows) ->
      val subjectUris = buildSet {
        add(webId)
        addAll(webIdUriDeriver.derivableAliases(webId))
        subjectRows.forEach { row -> row.subjectUris?.let(::addAll) }
      }
      acc + sweepUnbackedAppGrants(podDbo, subjectRows, subjectUris) { contextUri ->
        podContextPermissionResolver.isCoveredByManageScope(
          scopes = setOf("$root#${ScopePermission.manage.value}"),
          podBaseUrl = podBaseUrl,
          contextUri = URI(contextUri),
        )
      }
    }
  }

  /**
   * Shared core: recompute [subjectUris]' user level and delete the context grants among [rows]
   * that it no longer covers, optionally narrowed by [isCandidateContext].
   *
   * Only *context* grants are candidates. `grants` also holds the genuine OAuth feature
   * scope `public-read`, which never appears in [resolveUserGrants]; sweeping by "not in the
   * recomputed set" alone would delete it, and if it were the app's last remaining row the refresh
   * exchange would read that as a full revocation and force a re-consent. Narrowing one context
   * grant must not end a session.
   *
   * After the deletes the gap is already closed: access tokens carry no context permissions, so
   * the very next request resolves the reduced set. The refresh-token revocation that follows is
   * for determinism in the full-revocation case only — a surviving family can never re-hydrate
   * context grants (the stored `scopes` hold feature scopes), it would merely keep issuing
   * access tokens that no longer authorize anything.
   */
  private fun sweepUnbackedAppGrants(
    podDbo: PodDbo,
    rows: List<PodGrantDbo>,
    subjectUris: Set<String>,
    isCandidateContext: (contextUri: String) -> Boolean = { true },
  ): GrantCascadeResult {
    val podId = checkNotNull(podDbo.id)
    val podBaseUrl = podBaseUrl(podDbo)
    val stillGranted = resolveUserGrants(podDbo, subjectUris, podBaseUrl)

    var deletedAppGrants = 0L
    var revokedRefreshTokens = 0L
    val affectedApps = mutableSetOf<String>()

    rows.groupBy { it.appId to it.webId }.forEach { (key, appRows) ->
      val (appId, appWebId) = key
      val contextGrants = appRows.mapNotNull { row ->
        (podScopeValidator.validate(row.scope, podBaseUrl) as? ScopeValidationResult.Context)
          ?.let { row.scope to it.contextUri }
      }
      val unbacked = contextGrants
        .filter { (scope, contextUri) -> scope !in stillGranted && isCandidateContext(contextUri) }
        .map { (scope, _) -> scope }
        .toSet()
      if (unbacked.isEmpty()) return@forEach

      deletedAppGrants += podGrantsDao.deleteGrants(
        podId = podId,
        appId = appId,
        webId = appWebId,
        grants = unbacked,
      )
      affectedApps += appId

      // Kill the session only when *nothing at all* is left for this app — including feature
      // scopes like `public-read`, which are not context grants and were never sweep candidates.
      // Deciding on the context grants alone would revoke the family of an app that still holds
      // public-read, reintroducing through the token path exactly the session-killer the
      // context-only sweep above avoids. The condition mirrors `PodAuthEndpoint`'s refresh
      // exchange, which forces re-consent on `currentGrants.isEmpty()` over the same full set, so
      // the proactive revocation and the lazy one cannot disagree.
      val remainingGrants = appRows.map { it.scope }.toSet() - unbacked
      if (remainingGrants.isEmpty()) {
        revokedRefreshTokens += refreshTokenStore.revokeForUser(
          podId = podId,
          clientId = appId,
          webId = appWebId,
        )
      }
    }

    return GrantCascadeResult(
      deletedAppGrants = deletedAppGrants,
      revokedRefreshTokens = revokedRefreshTokens,
      affectedApps = affectedApps,
    )
  }

  /**
   * The pod's grant namespace, i.e. what [PodScopeValidator] measures context URIs against.
   * Byte-identical to the endpoint layer's `"${config.apiBaseUrl}${podName}/"`.
   */
  private fun podBaseUrl(podDbo: PodDbo): String =
    sempodsUriBuilder.buildResourceUri(podName = podDbo.name, resourcePath = "").toString()

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/** What a grant mutation revoked downstream. Reported for logging and owner-facing feedback. */
internal data class GrantCascadeResult(
  val deletedAppGrants: Long,
  val revokedRefreshTokens: Long,
  /** Client IDs that lost at least one grant. */
  val affectedApps: Set<String>,
) {

  operator fun plus(other: GrantCascadeResult): GrantCascadeResult = GrantCascadeResult(
    deletedAppGrants = deletedAppGrants + other.deletedAppGrants,
    revokedRefreshTokens = revokedRefreshTokens + other.revokedRefreshTokens,
    affectedApps = affectedApps + other.affectedApps,
  )

  companion object {
    val empty = GrantCascadeResult(
      deletedAppGrants = 0L,
      revokedRefreshTokens = 0L,
      affectedApps = emptySet(),
    )
  }
}
