package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putStrings
import org.sempods.pods.PodId
import java.time.Duration

/**
 * The right to register one service client, spent the moment it is used.
 *
 * An access token carrying `service-clients` is a bearer like any other: it can be replayed, and
 * the feature scope it carries is read from the token rather than resolved per request, so removing
 * a stored row cannot stop a second registration. What stops it is this — one row per issued
 * installer token, removed atomically by whoever gets there first. A second call, a concurrent
 * call and a call with a token from another pod all come back with nothing.
 *
 * Keyed by the token's `jti`, which every access token already carries. A claim of its own would
 * have been a change to the token shape `context7.json` pins.
 *
 * **One answer, not three.** Unknown, already spent, expired and withdrawn are the same `null`: for
 * the caller they are the same refusal, and telling them apart would mean keeping a tombstone for
 * every installer token ever issued — a retention design, on rows whose whole point is to
 * disappear.
 *
 * #126 binds this to a successful registration and owns what a crash between the two costs.
 */
class PodInstallationAuthorityStore @Inject internal constructor(
  db: MongoDatabase,
  private val consentDecisions: PodConsentDecisionStore,
) {

  /**
   * @param pod the pod the authority was granted on. Compared on consumption, so a token minted
   *   for one pod cannot register a client on another.
   * @param clientId the app the person authorized — the installer program, not the service it is
   *   about to create.
   * @param webId the person who granted it.
   * @param generation the consent generation this authority was granted under. Compared on
   *   consumption, so that disconnecting the app takes the authority with it. `null` on a row a
   *   node wrote before the field existed — see [consume] for what that costs.
   * @param subjectUris every identity URI that person was recognised by at the dialog, [webId]
   *   among them. What `PodClientRegistration` asks ownership of when the authority is spent; an
   *   empty set recognises nobody.
   */
  internal data class Authority(
    val pod: PodId,
    val clientId: String,
    val webId: String,
    val generation: Long?,
    val subjectUris: Set<String>,
  )

  private val authorities = OneTimeStore(
    db = db,
    collectionName = SempodsCollections.OAUTH_INSTALLATION_AUTHORITIES,
    // The token's own hour. Derived from it rather than written again, so a row cannot outlive the
    // bearer it belongs to or die under one that is still valid.
    ttl = Duration.ofSeconds(PodTokenIssuer.USER_TOKEN_TTL_SECONDS),
    write = {
      put("podId", it.pod.value)
      put("clientId", it.clientId)
      put("webId", it.webId)
      put("generation", it.generation)
      putStrings("subjectUris", it.subjectUris)
    },
    read = {
      val webId = getString("webId") ?: return@OneTimeStore null
      Authority(
        pod = PodId(getString("podId") ?: return@OneTimeStore null),
        clientId = getString("clientId") ?: return@OneTimeStore null,
        webId = webId,
        // Both absent on a row written before this release, which a rolling deploy puts in front
        // of a node that reads it. The row lives an hour, so this reaches no further than that.
        generation = get("generation", Number::class.java)?.toLong(),
        subjectUris = getStringSet("subjectUris").ifEmpty { setOf(webId) },
      )
    },
  )

  /** Records the authority an installer token carries. Called once, where that token is signed. */
  internal fun record(
    pod: PodId,
    jti: String,
    clientId: String,
    webId: String,
    generation: Long,
    subjectUris: Set<String>,
  ) {
    require(webId in subjectUris) { "the URIs a person was recognised by include the one they are" }
    authorities.create(
      jti,
      Authority(
        pod = pod,
        clientId = clientId,
        webId = webId,
        generation = generation,
        subjectUris = subjectUris,
      ),
    )
  }

  /**
   * The authority behind [jti], spent in the same operation.
   *
   * Returns `null` where there is none to spend — see the note on this class about why that is one
   * answer. A row belonging to another pod, or to a consent the person has since answered again,
   * is consumed and refused: it was presented at the wrong door, and leaving it to be presented
   * again at a right one would be worse.
   *
   * **The consent generation is read here and not at issuance.** Disconnecting an app bumps it,
   * and an installer token outlives that by up to its hour — so an authority checked only when it
   * was written would let an app the owner has just disconnected mint a service credential
   * afterwards.
   *
   * **A row from before that field existed carries no generation and is accepted without the
   * comparison.** Refusing it instead would spend an authority an owner is holding for a flow the
   * old node could not serve anyway, and the row's own hour bounds how long any of them survive a
   * deploy. What such an authority can create is a service client holding no grants, which reaches
   * nothing until the owner approves the second consent.
   */
  internal fun consume(pod: PodId, jti: String): Authority? =
    authorities.consume(jti)
      ?.takeIf { it.pod == pod }
      ?.takeIf { it.generation == null || consentDecisions.find(pod, it.clientId, listOf(it.webId))?.generation == it.generation }
}
