package org.sempods.pods.oauth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.PodFacade
import java.time.Instant

/**
 * A person signing out of a pod, and the question every credential issued to them there has to
 * answer afterwards.
 *
 * A sign-out is about the person. It ends every session cookie they hold on the pod, every
 * refresh-token family of every app, every authorization code not yet exchanged and every access
 * token already issued. The grants stay: signing in again finds them where they were. Ending one
 * app is the consent screen's disconnect, and that one takes the grants.
 *
 * It is not scoped to one sign-in. That form cannot reach the app connected yesterday: its session
 * expired after twelve hours, so the person signs in again to reach the button, and ending *that*
 * sign-in ends nothing they meant.
 *
 * A session that expires on its own ends nothing here. A connected app never calls `/authorize`
 * again, so a family tied to the session's clock would end twelve hours after its last authorization
 * however busy the person was. The family's own deadline ends it.
 *
 * The pod is passed by name, so this stays free of the HTTP and storage types of its two callers.
 */
class PodSignOut @Inject internal constructor(
  private val podFacade: PodFacade,
  private val signOutStore: PodSignOutStore,
  private val consentDecisionStore: PodConsentDecisionStore,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val webIdUriDeriver: WebIdUriDeriver,
) {

  /**
   * Ends everything [webIds] hold on [pod].
   *
   * Four writes, in an order that leaves a concurrent issuance nowhere to land unseen
   * (`SPS-AUTH-063`):
   *
   * 1. **The instant**, so every session and access token presented from here on is refused.
   * 2. **The consent generation**, for every app, so a code minted before it is refused at the
   *    exchange — which reads the generation again after seeding its family, and revokes that family
   *    when it moved.
   * 3. **The families**, every row of every app, rotated rows included: a rotation that inserts its
   *    successor after this sweep finds its predecessor revoked and ends the family.
   * 4. **The instant again.** An exchange signs its access token before its last check, so a token
   *    that passes the check was signed before 2 or 3 took effect — and therefore before this. The
   *    first write alone would miss a token signed between it and the sweep.
   *
   * Every URI the person is known by, twins included: a family or a code can sit under any of them
   * (`SPS-AUTH-061`), and a session under an alias is still theirs.
   */
  internal fun signOut(pod: String, webIds: Collection<String>) {
    val podId = podFacade.getPodId(pod) ?: return
    val person = webIds.flatMap(webIdUriDeriver::derivableAliases).toSet()
    if (person.isEmpty()) return

    signOutStore.record(podId, person)
    val decisions = consentDecisionStore.bumpGenerationForPerson(podId, person)
    val revokedRows = refreshTokenStore.revokeForPerson(podId, person)
    val signedOutAt = signOutStore.record(podId, person)

    logger.info {
      "Signed out: pod='$pod', webIds=${person.sorted()}, decisions=$decisions, " +
          "revokedRows=$revokedRows, signedOutAt=$signedOutAt"
    }
  }

  /**
   * Whether a session for [webIds], signed in at [authTime], still stands on [pod].
   *
   * Only the person's own URIs are read, because the sign-out wrote every URI it could derive: a
   * session under a twin of the one that signed out finds the row under its own name.
   */
  internal fun sessionStands(pod: String, webIds: Collection<String>, authTime: Instant): Boolean =
    issuedAfterSignOut(pod, webIds, authTime)

  /**
   * Whether an access token that verified still stands on [pod].
   *
   * A service client's token names no person and cannot be signed out. A person's token without an
   * `iat` is refused once the person has signed out, since nothing says it came afterwards; this
   * server writes one on every token it issues.
   */
  internal fun accessTokenStands(pod: String, token: PodAccessToken): Boolean {
    if (token.isServiceClient) return true
    val sub = token.sub ?: return true
    val stands = issuedAfterSignOut(pod, listOf(sub), token.issuedAt)
    if (!stands) {
      logger.info { "Access token issued before its person signed out: pod='$pod', clientId='${token.clientId}'" }
    }
    return stands
  }

  /**
   * Compared in whole seconds, because `auth_time` and `iat` are written in them — and a credential
   * from the very second of a sign-out counts as signed out. That refuses a sign-in completed within
   * the same second as a sign-out, which a round trip through the identity service does not manage.
   *
   * The sign-out and the credential can be dated by different replicas, so clock skew between them
   * moves the line by the skew.
   */
  private fun issuedAfterSignOut(pod: String, webIds: Collection<String>, issuedAt: Instant?): Boolean {
    val podId = podFacade.getPodId(pod) ?: return true
    val signedOutAt = signOutStore.signedOutAt(podId, webIds) ?: return true
    return issuedAt != null && issuedAt.epochSecond > signedOutAt.epochSecond
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
