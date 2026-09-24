package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.pods.PodId

/**
 * The authority a [org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE] bearer carries. Read, not
 * spent: one approval serves every call its bearer makes in the hour. Each call compares the pod's
 * current owner against the recorded URIs.
 */
class PodManagementAuthorityStore @Inject internal constructor(
  db: MongoDatabase,
  private val consentDecisions: PodConsentDecisionStore,
) : PrivilegedAuthorityRows(db, SempodsCollections.OAUTH_MANAGEMENT_AUTHORITIES) {

  /**
   * The authority behind [jti] if it still stands on [pod]; otherwise `null`, for every reason alike.
   * This store has no rows from before this release, so one without a disconnect count is refused.
   */
  internal fun standing(pod: PodId, jti: String): Authority? =
    rows.peek(jti)
      ?.takeIf { it.pod == pod }
      ?.takeIf { it.disconnects != null }
      ?.takeIf { (consentDecisions.find(pod, it.clientId, listOf(it.webId))?.disconnects ?: 0L) == it.disconnects }
}
