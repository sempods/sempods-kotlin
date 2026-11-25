package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PodManagementAuthorityStore
import org.sempods.pods.oauth.PrivilegedAuthority
import org.sempods.pods.oauth.PrivilegedAuthorityRows
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceManagementAuthority

/**
 * Whether a bearer carries an owner's authority for one named operation.
 *
 * A bearer whose `sub` names the owner is still an application, and holds what was approved for
 * it. The owner's own authority over the pod reaches a program only through a privileged scope the
 * owner approved for exactly that operation — `service-clients:manage`, `contexts:manage`. This is
 * the check both run: the scope by name, then the authority recorded for the bearer's `jti`, then
 * the pod's *current* owner against the URIs the dialog recognised the person by. The bearer's one
 * URI may not name the owner where that set does.
 *
 * A service client's token is the other way in: the authority is the one its registration holds
 * ([ServiceManagementAuthority]), lasting until the registration goes, and reaches only
 * [PodScopeValidator.serviceManagementScopes]. Its owner check is the same.
 */
class PodOwnerAuthority @Inject internal constructor(
  private val authorities: PodManagementAuthorityStore,
  private val podGrantsFacade: PodGrantsFacade,
  private val serviceClients: PodServiceClientStore,
) {

  /**
   * The authority [caller] holds for [scope] on [pod], or why it holds none.
   *
   * @param consent the consent text the operation was first promised in. An authority approved
   *   under an earlier text holds the rest of its scope but not this.
   */
  internal fun check(
    pod: HostedPod,
    caller: SempodsCredentials,
    scope: String,
    consent: Int = PrivilegedAuthorityRows.FIRST_CONSENT,
  ): PodOwnerAuthorityCheck {
    if (scope !in caller.oauthScopes) {
      return PodOwnerAuthorityCheck.Refused(PodOwnerAuthorityRefusal.SCOPE_REQUIRED)
    }
    val authority = (if (caller.isServiceToken) serviceAuthority(pod, caller, scope) else null)
      ?: caller.tokenJti?.let { authorities.standing(pod.id, it) }
      ?: return PodOwnerAuthorityCheck.Refused(PodOwnerAuthorityRefusal.AUTHORITY_WITHDRAWN)
    if (authority.subjectUris.none { podGrantsFacade.isPodOwner(pod, it) }) {
      logger.info { "[oauth/owner-authority] refused: no URI this authority names owns pod '${pod.name}'" }
      return PodOwnerAuthorityCheck.Refused(PodOwnerAuthorityRefusal.NOT_OWNER)
    }
    if (authority.consent < consent) {
      return PodOwnerAuthorityCheck.Refused(PodOwnerAuthorityRefusal.CONSENT_OUTDATED)
    }
    return PodOwnerAuthorityCheck.Standing(authority)
  }

  /**
   * The authority a service client's registration holds for [scope], as the record every caller of
   * [check] reads: `null` where the registration is gone or holds none, or [scope] is not one it
   * covers. Under the current consent text, since it was granted under the text the page shows now.
   */
  private fun serviceAuthority(pod: HostedPod, caller: SempodsCredentials, scope: String): PrivilegedAuthority? {
    if (scope !in PodScopeValidator.serviceManagementScopes) return null
    val clientId = caller.oauthClientId ?: return null
    val management = serviceClients.find(pod.id, clientId)?.management ?: return null
    return PrivilegedAuthority(
      pod = pod.id,
      clientId = clientId,
      webId = management.grantedBy,
      disconnects = 0,
      subjectUris = management.subjectUris,
      consent = PrivilegedAuthorityRows.consentTextOf(scope),
    )
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/**
 * Whether these credentials are a service client's own token: the pod mints those with the client
 * as their subject, and a person's token names the person.
 */
internal val SempodsCredentials.isServiceToken: Boolean
  get() = tokenSub != null && tokenSub == oauthClientId

/** What [PodOwnerAuthority.check] answers. */
internal sealed interface PodOwnerAuthorityCheck {
  data class Standing(val authority: PrivilegedAuthority) : PodOwnerAuthorityCheck
  data class Refused(val reason: PodOwnerAuthorityRefusal) : PodOwnerAuthorityCheck
}

/** Why a bearer holds no owner authority. */
internal enum class PodOwnerAuthorityRefusal {

  /** The bearer does not carry the scope asked for. */
  SCOPE_REQUIRED,

  /** The authority recorded for this bearer is gone: expired, another pod's, or disconnected. */
  AUTHORITY_WITHDRAWN,

  /** No URI the dialog recognised the person by owns the pod now. */
  NOT_OWNER,

  /** The person approved this authority under a consent text that did not promise the operation. */
  CONSENT_OUTDATED,
}
