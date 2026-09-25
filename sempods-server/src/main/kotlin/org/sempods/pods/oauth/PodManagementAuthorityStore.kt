package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
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
  consentDecisions: PodConsentDecisionStore,
) : PrivilegedAuthorityRows(
  db = db,
  collectionName = SempodsCollections.OAUTH_MANAGEMENT_AUTHORITIES,
  consentDecisions = consentDecisions,
  // This store has no rows from before the count existed, so one without it is refused.
  uncountedStands = false,
) {

  /** The authority behind [jti] if it still stands on [pod]; otherwise `null`, for every reason alike. */
  internal fun standing(pod: PodId, jti: String): Authority? =
    rows.peek(jti)?.takeIf { it.standsOn(pod) }
}
