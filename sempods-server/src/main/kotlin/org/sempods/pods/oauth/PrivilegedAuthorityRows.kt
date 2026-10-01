package org.sempods.pods.oauth

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putStrings
import org.sempods.pods.PodId
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import java.time.Duration

/**
 * One row per privileged bearer, keyed by its `jti` and living as long as it. It holds what the
 * bearer cannot: every URI the dialog recognised the person by, and whether the app has been
 * disconnected since. [PodManagementAuthorityStore] reads it for the hour.
 */
abstract class PrivilegedAuthorityRows internal constructor(
  db: MongoDatabase,
  collectionName: String,
  private val consentDecisions: PodConsentDecisionStore,
) {

  init {
    // For [standsFor], which the consent dialog asks on every render and submission.
    db.getCollection(collectionName).createIndex(Indexes.ascending("podId", "clientId", "webId"))
  }

  internal val rows = OneTimeStore<PrivilegedAuthority>(
    db = db,
    collectionName = collectionName,
    // The bearer's own hour, derived so the two cannot drift apart.
    ttl = Duration.ofSeconds(PodTokenIssuer.USER_TOKEN_TTL_SECONDS),
    write = {
      put("podId", it.pod.value)
      put("clientId", it.clientId)
      put("webId", it.webId)
      put("disconnects", it.disconnects)
      putStrings("subjectUris", it.subjectUris)
      put("consent", it.consent)
    },
    read = {
      val webId = getString("webId") ?: return@OneTimeStore null
      PrivilegedAuthority(
        pod = PodId(getString("podId") ?: return@OneTimeStore null),
        clientId = getString("clientId") ?: return@OneTimeStore null,
        webId = webId,
        // A row without a count stands for nothing, so it reads as no row.
        disconnects = get("disconnects", Number::class.java)?.toLong() ?: return@OneTimeStore null,
        subjectUris = getStringSet("subjectUris").ifEmpty { setOf(webId) },
        consent = get("consent", Number::class.java)?.toInt() ?: FIRST_CONSENT,
      )
    },
  )

  /** Records the authority a privileged bearer carries. Called once, where that bearer is signed. */
  internal fun record(
    pod: PodId,
    jti: String,
    clientId: String,
    webId: String,
    disconnects: Long,
    subjectUris: Set<String>,
    consent: Int,
  ) {
    require(webId in subjectUris) { "the URIs a person was recognised by include the one they are" }
    rows.create(jti, PrivilegedAuthority(pod, clientId, webId, disconnects, subjectUris, consent))
  }

  /** Whether this authority stands on [pod]: granted there, and not withdrawn by a disconnect since. */
  internal fun PrivilegedAuthority.standsOn(pod: PodId): Boolean =
    this.pod == pod && disconnectsUnder(pod, clientId, webId) == disconnects

  /**
   * Whether a live row [clientId] holds on [pod] from one of [webIds] is one a disconnect by that
   * person would withdraw. An expired row is gone and counts for nothing.
   *
   * Matched on [PrivilegedAuthority.webId] alone, not on its recognised URIs: [standsOn] reads the disconnect
   * count under that URI, and a disconnect moves it only for the URIs it is made under. That count
   * is part of the filter, so the answer is one indexed read however many authorities were issued.
   */
  internal fun standsFor(pod: PodId, clientId: String, webIds: Collection<String>): Boolean {
    if (webIds.isEmpty()) return false
    val standingUnder = webIds.distinct().map { webId ->
      Filters.and(
        Filters.eq("webId", webId),
        Filters.eq("disconnects", disconnectsUnder(pod, clientId, webId)),
      )
    }
    return rows.findLive(
      Filters.and(Filters.eq("podId", pod.value), Filters.eq("clientId", clientId), Filters.or(standingUnder)),
    ) != null
  }

  private fun disconnectsUnder(pod: PodId, clientId: String, webId: String): Long =
    consentDecisions.find(pod, clientId, listOf(webId))?.disconnects ?: 0L

  internal companion object {

    /** The consent text rows recorded before [PrivilegedAuthority.consent] existed were approved under. */
    const val FIRST_CONSENT = 1

    /**
     * The `service-clients:manage` text that says the authority registers services and gives them
     * access to the person's data. An authority approved under [FIRST_CONSENT] was told it could not,
     * and [org.sempods.pods.oauth.flows.PodOwnerAuthority] keeps it to what that text said.
     */
    const val SERVICE_CLIENTS_CONSENT = 2

    /** The version of the consent text this server shows for the privileged [scope]. */
    fun consentTextOf(scope: String): Int =
      if (scope == SERVICE_CLIENTS_MANAGE_SCOPE) SERVICE_CLIENTS_CONSENT else FIRST_CONSENT
  }
}
