package org.sempods.pods.oauth

import com.mongodb.client.MongoDatabase
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
 */
abstract class PrivilegedAuthorityRows internal constructor(db: MongoDatabase, collectionName: String) {

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
}
