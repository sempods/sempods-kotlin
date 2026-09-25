package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import org.sempods.SempodsCollections
import org.sempods.pods.PodId

/**
 * The authority a [org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE] or
 * [org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE] bearer carries — one store for both, since the
 * scope is the bearer's and the row is keyed by its `jti`. Read, not spent: one approval serves every
 * call its bearer makes in the hour. Each call compares the pod's current owner against the recorded
 * URIs, through [org.sempods.pods.oauth.flows.PodOwnerAuthority].
 */
class PodManagementAuthorityStore @Inject internal constructor(
  db: MongoDatabase,
  private val consentDecisions: PodConsentDecisionStore,
) : PrivilegedAuthorityRows(db, SempodsCollections.OAUTH_MANAGEMENT_AUTHORITIES) {

  init {
    // For [standsFor], which the consent dialog asks on every render and submission.
    db.getCollection(SempodsCollections.OAUTH_MANAGEMENT_AUTHORITIES)
      .createIndex(Indexes.ascending("podId", "clientId", "webId"))
  }

  /**
   * The authority behind [jti] if it still stands on [pod]; otherwise `null`, for every reason alike.
   * This store has no rows from before this release, so one without a disconnect count is refused.
   */
  internal fun standing(pod: PodId, jti: String): Authority? =
    rows.peek(jti)
      ?.takeIf { it.pod == pod }
      ?.takeIf { it.disconnects != null }
      ?.takeIf { (consentDecisions.find(pod, it.clientId, listOf(it.webId))?.disconnects ?: 0L) == it.disconnects }

  /**
   * Whether an authority [clientId] holds on [pod] from one of [webIds] still stands — something a
   * disconnect by that person would withdraw.
   *
   * Matched on [Authority.webId] alone, not on its recognised URIs: [standing] reads the disconnect
   * count under that URI, and a disconnect moves it only for the URIs it is made under. That count
   * is part of the filter, so the answer is one indexed read however many authorities were issued.
   */
  internal fun standsFor(pod: PodId, clientId: String, webIds: Collection<String>): Boolean {
    if (webIds.isEmpty()) return false
    val standingUnder = webIds.distinct().map { webId ->
      Filters.and(
        Filters.eq("webId", webId),
        Filters.eq("disconnects", consentDecisions.find(pod, clientId, listOf(webId))?.disconnects ?: 0L),
      )
    }
    return rows.findLive(
      Filters.and(Filters.eq("podId", pod.value), Filters.eq("clientId", clientId), Filters.or(standingUnder)),
    ) != null
  }
}
