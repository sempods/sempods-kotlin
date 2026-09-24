package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PodManagementAuthorityStore
import org.sempods.pods.oauth.PrivilegedAuthorityRows

/**
 * Whether a bearer carries an owner's authority for one named operation.
 *
 * A bearer whose `sub` names the owner is still an application, and holds what was approved for
 * it. The owner's own authority over the pod reaches a program only through a privileged scope the
 * owner approved for exactly that operation — `service-clients:manage`, `contexts:manage`. This is
 * the check both run: the scope by name, then the authority recorded for the bearer's `jti`, then
 * the pod's *current* owner against the URIs the dialog recognised the person by. The bearer's one
 * URI may not name the owner where that set does.
 */
class PodOwnerAuthority @Inject internal constructor(
  private val authorities: PodManagementAuthorityStore,
  private val podGrantsFacade: PodGrantsFacade,
) {

  /** The authority [caller] holds for [scope] on [pod], or why it holds none. */
  internal fun check(pod: HostedPod, caller: SempodsCredentials?, scope: String): PodOwnerAuthorityCheck {
    if (caller == null || scope !in caller.oauthScopes) {
      return PodOwnerAuthorityCheck.Refused(PodOwnerAuthorityRefusal.SCOPE_REQUIRED)
    }
    val authority = caller.tokenJti?.let { authorities.standing(pod.id, it) }
      ?: return PodOwnerAuthorityCheck.Refused(PodOwnerAuthorityRefusal.AUTHORITY_WITHDRAWN)
    if (authority.subjectUris.none { podGrantsFacade.isPodOwner(pod, it) }) {
      logger.info { "[oauth/owner-authority] refused: no URI this authority names owns pod '${pod.name}'" }
      return PodOwnerAuthorityCheck.Refused(PodOwnerAuthorityRefusal.NOT_OWNER)
    }
    return PodOwnerAuthorityCheck.Standing(authority)
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/** What [PodOwnerAuthority.check] answers. */
internal sealed interface PodOwnerAuthorityCheck {
  data class Standing(val authority: PrivilegedAuthorityRows.Authority) : PodOwnerAuthorityCheck
  data class Refused(val reason: PodOwnerAuthorityRefusal) : PodOwnerAuthorityCheck
}

/** Why a bearer holds no owner authority. */
internal enum class PodOwnerAuthorityRefusal {

  /** No bearer, or one that does not carry the scope asked for. */
  SCOPE_REQUIRED,

  /** The authority recorded for this bearer is gone: expired, another pod's, or disconnected. */
  AUTHORITY_WITHDRAWN,

  /** No URI the dialog recognised the person by owns the pod now. */
  NOT_OWNER,
}
