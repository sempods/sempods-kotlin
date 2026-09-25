package org.sempods.pods.oauth

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putStrings
import org.sempods.pods.PodId
import java.time.Duration

/**
 * One row per privileged bearer, keyed by its `jti` and living as long as it. It holds what the
 * bearer cannot: every URI the dialog recognised the person by, and whether the app has been
 * disconnected since. [PodInstallationAuthorityStore] spends the row; [PodManagementAuthorityStore]
 * reads it for the hour.
 *
 * @param uncountedStands whether a row carrying no disconnect count stands. Only a row from before
 *   the count existed carries none; each store says why it accepts or refuses one.
 */
abstract class PrivilegedAuthorityRows internal constructor(
  db: MongoDatabase,
  collectionName: String,
  private val consentDecisions: PodConsentDecisionStore,
  private val uncountedStands: Boolean,
) {

  init {
    // For [standsFor], which the consent dialog asks on every render and submission.
    db.getCollection(collectionName).createIndex(Indexes.ascending("podId", "clientId", "webId"))
  }

  /**
   * @param pod the pod the authority was granted on.
   * @param clientId the app the person authorized — the program holding the bearer, not a service
   *   client it creates or manages.
   * @param webId the person who granted it.
   * @param disconnects `PodConsentDecisionStore.Decision.disconnects` when the authority was
   *   granted; a disconnect since withdraws it. Not the consent generation, which every privileged
   *   consent moves — one authority would then withdraw the other. `null` on a row from before the
   *   field existed; see [PodInstallationAuthorityStore.consume].
   * @param subjectUris every identity URI the person was recognised by at the dialog, [webId] among
   *   them. An empty set recognises nobody.
   */
  internal data class Authority(
    val pod: PodId,
    val clientId: String,
    val webId: String,
    val disconnects: Long?,
    val subjectUris: Set<String>,
  )

  internal val rows = OneTimeStore<Authority>(
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
    },
    read = {
      val webId = getString("webId") ?: return@OneTimeStore null
      Authority(
        pod = PodId(getString("podId") ?: return@OneTimeStore null),
        clientId = getString("clientId") ?: return@OneTimeStore null,
        webId = webId,
        // Both absent on a row from before this release; it lives an hour at most.
        disconnects = get("disconnects", Number::class.java)?.toLong(),
        subjectUris = getStringSet("subjectUris").ifEmpty { setOf(webId) },
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
  ) {
    require(webId in subjectUris) { "the URIs a person was recognised by include the one they are" }
    rows.create(jti, Authority(pod, clientId, webId, disconnects, subjectUris))
  }

  /**
   * Whether this authority stands on [pod]: granted there, and not withdrawn by a disconnect since.
   * A row without a count stands where [uncountedStands] says so.
   */
  internal fun Authority.standsOn(pod: PodId): Boolean =
    this.pod == pod &&
      (if (disconnects == null) uncountedStands else disconnectsUnder(pod, clientId, webId) == disconnects)

  /**
   * Whether a live row [clientId] holds on [pod] from one of [webIds] stands, as [standsOn] reads
   * it — something a disconnect by that person would withdraw. A spent row is gone and counts for
   * nothing.
   *
   * Matched on [Authority.webId] alone, not on its recognised URIs: [standsOn] reads the disconnect
   * count under that URI, and a disconnect moves it only for the URIs it is made under. That count
   * is part of the filter, so the answer is one indexed read however many authorities were issued.
   */
  internal fun standsFor(pod: PodId, clientId: String, webIds: Collection<String>): Boolean {
    if (webIds.isEmpty()) return false
    val standingUnder = webIds.distinct().map { webId ->
      val counted = Filters.eq("disconnects", disconnectsUnder(pod, clientId, webId))
      Filters.and(
        Filters.eq("webId", webId),
        // `{disconnects: null}` also matches a row that never had the field, as [standsOn] reads it.
        if (uncountedStands) Filters.or(counted, Filters.eq("disconnects", null)) else counted,
      )
    }
    return rows.findLive(
      Filters.and(Filters.eq("podId", pod.value), Filters.eq("clientId", clientId), Filters.or(standingUnder)),
    ) != null
  }

  private fun disconnectsUnder(pod: PodId, clientId: String, webId: String): Long =
    consentDecisions.find(pod, clientId, listOf(webId))?.disconnects ?: 0L
}
