package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.pods.PodId

/**
 * The right to register one service client, spent the moment it is used.
 *
 * An access token carrying `service-clients:install` is a bearer like any other: it can be replayed, and
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
) : PrivilegedAuthorityRows(db, SempodsCollections.OAUTH_INSTALLATION_AUTHORITIES) {

  /**
   * The authority behind [jti], spent in the same operation.
   *
   * Returns `null` where there is none to spend — see the note on this class about why that is one
   * answer. A row belonging to another pod, or to an app the person has since disconnected, is
   * consumed and refused: it was presented at the wrong door, and leaving it to be presented
   * again at a right one would be worse.
   *
   * **Whether the app was disconnected is read here and not at issuance.** An installer token
   * outlives a disconnect by up to its hour, so an authority checked only when it was written would
   * let an app the owner has just disconnected mint a service credential afterwards.
   *
   * **A row from before that field existed carries no count and is accepted without the
   * comparison.** Refusing it instead would spend an authority an owner is holding for a flow the
   * old node could not serve anyway, and the row's own hour bounds how long any of them survive a
   * deploy. What such an authority can create is a service client holding no grants, which reaches
   * nothing until the owner approves the second consent.
   */
  internal fun consume(pod: PodId, jti: String): Authority? =
    rows.consume(jti)?.takeIf { it.pod == pod && stands(it) }

  /**
   * The authority [consume] would hand over for [jti] now, without spending it.
   *
   * For a budget that should be charged only by a token that can still register: a spent or
   * withdrawn one is refused before any expensive work anyway. The answer can be stale by the time
   * [consume] runs, which is fine for that purpose — a race costs at most one charge.
   */
  internal fun peek(pod: PodId, jti: String): Authority? =
    rows.peek(jti)?.takeIf { it.pod == pod && stands(it) }

  override fun stands(authority: Authority): Boolean =
    authority.disconnects == null ||
      (consentDecisions.find(authority.pod, authority.clientId, listOf(authority.webId))?.disconnects ?: 0L) == authority.disconnects
}
