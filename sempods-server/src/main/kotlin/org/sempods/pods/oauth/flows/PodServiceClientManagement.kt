package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration

/**
 * An owner's list, rotation, grant removal and revocation of the service clients on their pod.
 * What each does on the wire is `docs/auth/service-clients.md` §"Managing an installed service
 * client".
 *
 * Every operation needs [SERVICE_CLIENTS_MANAGE_SCOPE]; its KDoc says why that is not the
 * installer's scope. Nothing here widens a grant — that is [PodServiceClientGrantFlow]'s. Only
 * owner-installed registrations are changed; an operator-provisioned one is listed and refused.
 */
class PodServiceClientManagement @Inject internal constructor(
  private val serviceClients: PodServiceClientStore,
  private val ownerAuthority: PodOwnerAuthority,
) {

  /** Every registration on [pod], with no secret. */
  internal fun list(pod: HostedPod, caller: SempodsCredentials?): PodServiceClientManagementResult<List<ServiceClientRegistration>> =
    authorized(pod, caller) { PodServiceClientManagementResult.Done(serviceClients.list(pod.id)) }

  /** A new secret for [clientId], answered once. The identifier and the grants stay. */
  internal fun rotate(
    pod: HostedPod,
    caller: SempodsCredentials?,
    clientId: String,
  ): PodServiceClientManagementResult<PodServiceClientStore.SecretRotation.Rotated> = authorized(pod, caller) {
    changeable(clientId) {
      when (val rotation = serviceClients.rotateSecret(pod.id, clientId)) {
        is PodServiceClientStore.SecretRotation.Rotated -> {
          logger.info { "[service-clients] Secret rotated: pod='${pod.name}', clientId='${LogSafeText.of(clientId)}'" }
          PodServiceClientManagementResult.Done(rotation)
        }

        PodServiceClientStore.SecretRotation.NotFound -> PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
        PodServiceClientStore.SecretRotation.Conflict -> PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.CONFLICT)
      }
    }
  }

  /** Removes [clientId]. The contexts it wrote to stay. */
  internal fun revoke(pod: HostedPod, caller: SempodsCredentials?, clientId: String): PodServiceClientManagementResult<Unit> =
    authorized(pod, caller) {
      changeable(clientId) {
        val registration = serviceClients.find(pod.id, clientId)
          ?: return@changeable PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
        if (!serviceClients.remove(pod.id, clientId, registration.id)) {
          return@changeable PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.CONFLICT)
        }
        logger.info { "[service-clients] Revoked: pod='${pod.name}', clientId='${LogSafeText.of(clientId)}'" }
        PodServiceClientManagementResult.Done(Unit)
      }
    }

  /** Takes [scopes] away from [clientId] and answers what it holds afterwards, possibly nothing. */
  internal fun removeGrants(
    pod: HostedPod,
    caller: SempodsCredentials?,
    clientId: String,
    scopes: Set<String>,
  ): PodServiceClientManagementResult<ServiceClientRegistration> = authorized(pod, caller) {
    if (scopes.isEmpty()) {
      return@authorized PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NO_SCOPE)
    }
    changeable(clientId) {
      val remaining = serviceClients.removeScopes(pod.id, clientId, scopes)
        ?: return@changeable PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
      logger.info {
        "[service-clients] Grants removed: pod='${pod.name}', clientId='${LogSafeText.of(clientId)}', " +
            "removed='${LogSafeText.of(scopes.sorted().joinToString(" "))}', remaining=${remaining.scopes.size}"
      }
      PodServiceClientManagementResult.Done(remaining)
    }
  }

  /**
   * The authority check every operation runs first: [PodOwnerAuthority] for
   * [SERVICE_CLIENTS_MANAGE_SCOPE], asked by name, since an installer bearer passes
   * `carriesPrivilegedFeature` too.
   */
  private inline fun <T> authorized(
    pod: HostedPod,
    caller: SempodsCredentials?,
    operation: () -> PodServiceClientManagementResult<T>,
  ): PodServiceClientManagementResult<T> =
    when (val check = ownerAuthority.check(pod, caller, SERVICE_CLIENTS_MANAGE_SCOPE)) {
      is PodOwnerAuthorityCheck.Standing -> operation()
      is PodOwnerAuthorityCheck.Refused -> PodServiceClientManagementResult.Refused(
        when (check.reason) {
          PodOwnerAuthorityRefusal.SCOPE_REQUIRED -> PodServiceClientManagementRefusal.SCOPE_REQUIRED
          PodOwnerAuthorityRefusal.AUTHORITY_WITHDRAWN -> PodServiceClientManagementRefusal.AUTHORITY_WITHDRAWN
          PodOwnerAuthorityRefusal.NOT_OWNER -> PodServiceClientManagementRefusal.NOT_OWNER
        },
      )
    }

  /** Runs [operation] where [clientId] is owner-installed, which its prefix alone tells. */
  private inline fun <T> changeable(
    clientId: String,
    operation: () -> PodServiceClientManagementResult<T>,
  ): PodServiceClientManagementResult<T> =
    if (clientId.startsWith(PodServiceClientStore.SERVICE_CLIENT_PREFIX)) operation()
    else PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.PROVISIONED_BY_OPERATOR)

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/** What a management operation answers. */
internal sealed interface PodServiceClientManagementResult<out T> {
  data class Done<T>(val value: T) : PodServiceClientManagementResult<T>
  data class Refused(val reason: PodServiceClientManagementRefusal) : PodServiceClientManagementResult<Nothing>
}

/** Why a management operation did nothing. The adapter words each one. */
internal enum class PodServiceClientManagementRefusal {

  /** No bearer, or one that does not carry [SERVICE_CLIENTS_MANAGE_SCOPE]. */
  SCOPE_REQUIRED,

  /** The authority recorded for this bearer is gone: expired, another pod's, or disconnected. */
  AUTHORITY_WITHDRAWN,

  /** No URI the dialog recognised the person by owns the pod now. */
  NOT_OWNER,

  /** No registration by that identifier on this pod. */
  NOT_FOUND,

  /** An operator-provisioned client: listed, and not the owner's to change. */
  PROVISIONED_BY_OPERATOR,

  /** Another change to the same registration landed in between. */
  CONFLICT,

  /** A grant removal that named no scope. */
  NO_SCOPE,
}
