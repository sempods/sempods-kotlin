package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.GrantReplacement
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PrivilegedAuthorityRows
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration

/**
 * An owner's reads, grant replacement, rotation and revocation of the service clients on their pod.
 * What each does on the wire is `docs/auth/service-clients.md` §"Managing service clients".
 *
 * Every operation needs [SERVICE_CLIENTS_MANAGE_SCOPE]. [replaceGrants] reaches every registration;
 * rotation and revocation only those this pod named (`svc:`), since an operator holds the secret
 * and the registration of its own.
 */
class PodServiceClientManagement @Inject internal constructor(
  private val serviceClients: PodServiceClientStore,
  private val ownerAuthority: PodOwnerAuthority,
  private val podGrantsFacade: PodGrantsFacade,
) {

  /** Every registration on [pod], with no secret. */
  internal fun list(pod: HostedPod, caller: SempodsCredentials): PodServiceClientManagementResult<List<ServiceClientRegistration>> =
    authorized(pod, caller) { PodServiceClientManagementResult.Done(serviceClients.list(pod.id)) }

  /** A new secret for [clientId], answered once. The identifier and the grants stay. */
  internal fun rotate(
    pod: HostedPod,
    caller: SempodsCredentials,
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
  internal fun revoke(pod: HostedPod, caller: SempodsCredentials, clientId: String): PodServiceClientManagementResult<Unit> =
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

  /** The registration [clientId] names, with no secret. */
  internal fun get(pod: HostedPod, caller: SempodsCredentials, clientId: String): PodServiceClientManagementResult<ServiceClientRegistration> =
    authorized(pod, caller) {
      serviceClients.find(pod.id, clientId)?.let { PodServiceClientManagementResult.Done(it) }
        ?: PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
    }

  /**
   * Makes [scopes] the grants of [clientId], if they are still at [expectedVersion], and answers the
   * registration afterwards. An empty set removes every grant and keeps the registration. A
   * provisional registration is activated: the owner's authority is the owner's consent.
   *
   * Any registration on the pod, an operator-provisioned one included: its grants are the owner's.
   * Every scope has to be a context grant the owner can give, on a context registered on the pod.
   * A scope that is not is refused whole, before anything is written.
   *
   * @param expectedVersion the grants version the caller read; `null` when it named none, which is
   *   refused, so two tools never overwrite each other unseen.
   */
  internal fun replaceGrants(
    pod: HostedPod,
    caller: SempodsCredentials,
    clientId: String,
    expectedVersion: Long?,
    scopes: Set<String>,
  ): PodServiceClientManagementResult<ServiceClientRegistration> =
    authorized(pod, caller, consent = PrivilegedAuthorityRows.CONSENT) { authority ->
      if (expectedVersion == null) {
        return@authorized PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.VERSION_REQUIRED)
      }
      val ungrantable = serviceClients.ungrantable(pod, scopes)
      if (ungrantable.isNotEmpty()) {
        return@authorized PodServiceClientManagementResult.Refused(
          PodServiceClientManagementRefusal.UNGRANTABLE,
          ungrantable.entries.sortedBy { it.key }.joinToString("; ") { (scope, reason) -> "'$scope': $reason" },
        )
      }
      val delegatable = podGrantsFacade.resolveUserGrants(pod, authority.subjectUris)
      val unknown = scopes.filterNot { it in delegatable }.sorted()
      if (unknown.isNotEmpty()) {
        return@authorized PodServiceClientManagementResult.Refused(
          PodServiceClientManagementRefusal.UNGRANTABLE,
          unknown.joinToString("; ") { "'$it': no context registered on this pod" },
        )
      }
      val registration = serviceClients.find(pod.id, clientId)
        ?: return@authorized PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
      val recipient = GrantRecipient.Service(registration.id, clientId, expectedVersion)
      when (podGrantsFacade.replaceGrants(pod, recipient, scopes, grantedBy = authority.webId)) {
        is GrantReplacement.Replaced -> {
          val replaced = serviceClients.find(pod.id, clientId)
            ?.takeIf { it.id == registration.id }
            ?: return@authorized PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
          logger.info {
            "[service-clients] Grants replaced: pod='${pod.name}', clientId='${LogSafeText.of(clientId)}', " +
                "webId='${authority.webId}', scopes='${LogSafeText.of(replaced.scopes.sorted().joinToString(" "))}'"
          }
          PodServiceClientManagementResult.Done(replaced)
        }
        GrantReplacement.Conflict -> PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.VERSION_MISMATCH)
        GrantReplacement.NotFound -> PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
      }
    }

  /**
   * The authority check every operation runs first: [PodOwnerAuthority] for
   * [SERVICE_CLIENTS_MANAGE_SCOPE], asked by name, since a `contexts:manage` bearer passes
   * `carriesPrivilegedFeature` too. [operation] receives the authority that stood.
   *
   * @param consent the consent text that first promised the operation; see [PodOwnerAuthority.check].
   */
  private inline fun <T> authorized(
    pod: HostedPod,
    caller: SempodsCredentials,
    consent: Int = PrivilegedAuthorityRows.FIRST_CONSENT,
    operation: (PrivilegedAuthorityRows.Authority) -> PodServiceClientManagementResult<T>,
  ): PodServiceClientManagementResult<T> =
    when (val check = ownerAuthority.check(pod, caller, SERVICE_CLIENTS_MANAGE_SCOPE, consent)) {
      is PodOwnerAuthorityCheck.Standing -> operation(check.authority)
      is PodOwnerAuthorityCheck.Refused -> PodServiceClientManagementResult.Unauthorized(check.reason)
    }

  /**
   * Runs [operation] where this pod named [clientId], which its prefix alone tells. An operator's
   * client keeps the secret and the registration the operator holds; its grants are the owner's.
   */
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
  /** [detail] says which input was refused, where the reason alone does not. */
  data class Refused(val reason: PodServiceClientManagementRefusal, val detail: String? = null) : PodServiceClientManagementResult<Nothing>

  /** The bearer holds no owner authority for [SERVICE_CLIENTS_MANAGE_SCOPE]. */
  data class Unauthorized(val reason: PodOwnerAuthorityRefusal) : PodServiceClientManagementResult<Nothing>
}

/** Why a management operation the bearer was authorized for did nothing. The adapter words each one. */
internal enum class PodServiceClientManagementRefusal {

  /** No registration by that identifier on this pod. */
  NOT_FOUND,

  /** An operator-provisioned client: its secret and its registration are the operator's. */
  PROVISIONED_BY_OPERATOR,

  /** Another change to the same registration landed in between. */
  CONFLICT,

  /** A replace that named no version to write at. */
  VERSION_REQUIRED,

  /** The grants are no longer at the version the replace named. */
  VERSION_MISMATCH,

  /** A scope a service client cannot hold, or one on no context registered on the pod. */
  UNGRANTABLE,
}
