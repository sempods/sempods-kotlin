package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.GrantReplacement
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PrivilegedAuthority
import org.sempods.pods.oauth.PrivilegedAuthorityRows
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration
import java.net.URI
import org.sempods.pods.oauth.serviceclients.ServiceClientSecretRotation

/**
 * An owner's reads, grant replacement, rotation and revocation of the service clients on their pod.
 * What each does on the wire is `sempods-server/docs/auth/service-clients.md` §"Managing service clients".
 *
 * Every operation needs [SERVICE_CLIENTS_MANAGE_SCOPE]. [replaceGrants] reaches every registration;
 * rotation and revocation only those this pod named (`svc:`), since an operator holds the secret
 * and the registration of its own.
 */
class PodServiceClientManagement @Inject internal constructor(
  private val serviceClients: PodServiceClientStore,
  private val ownerAuthority: PodOwnerAuthority,
  private val podGrantsFacade: PodGrantsFacade,
  private val podFacade: PodFacade,
) {

  /** Every registration on [pod], with no secret. */
  internal fun list(pod: HostedPod, caller: SempodsCredentials): PodServiceClientManagementResult<List<ServiceClientRegistration>> =
    authorized(pod, caller) { PodServiceClientManagementResult.Done(serviceClients.list(pod.id)) }

  /** A new secret for [clientId], answered once. The identifier and the grants stay. */
  internal fun rotate(
    pod: HostedPod,
    caller: SempodsCredentials,
    clientId: String,
  ): PodServiceClientManagementResult<ServiceClientSecretRotation.Rotated> = authorized(pod, caller) {
    changeable(clientId) {
      when (val rotation = serviceClients.rotateSecret(pod.id, clientId)) {
        is ServiceClientSecretRotation.Rotated -> {
          logger.info { "[service-clients] Secret rotated: pod='${pod.name}', clientId='${LogSafeText.of(clientId)}'" }
          PodServiceClientManagementResult.Done(rotation)
        }

        ServiceClientSecretRotation.NotFound -> PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
        ServiceClientSecretRotation.Conflict -> PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.CONFLICT)
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
   * An authority approved under the first consent text, which promised only to take access away,
   * may narrow the grants of an active `svc:` registration and nothing more.
   *
   * @param expectedVersion the grants version the caller read. The write is conditional on it, so
   *   two tools never overwrite each other unseen.
   */
  internal fun replaceGrants(
    pod: HostedPod,
    caller: SempodsCredentials,
    clientId: String,
    expectedVersion: Long,
    scopes: Set<String>,
  ): PodServiceClientManagementResult<ServiceClientRegistration> =
    authorized(pod, caller) { authority ->
      val registration = serviceClients.find(pod.id, clientId)
        ?: return@authorized PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
      // What the first text allowed: taking access away from an active service this pod named.
      val narrowing = registration.registered && registration.pendingUntil == null && registration.scopes.containsAll(scopes)
      if (authority.consent < PrivilegedAuthorityRows.SERVICE_CLIENTS_CONSENT && !narrowing) {
        return@authorized PodServiceClientManagementResult.Unauthorized(PodOwnerAuthorityRefusal.CONSENT_OUTDATED)
      }
      // Early and cheap; the conditional write below is what decides.
      if (registration.grantsVersion != expectedVersion) {
        return@authorized PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.VERSION_MISMATCH)
      }
      // The authority is the owner's, who can give any registered context: asked per context rather
      // than by listing every context the pod has.
      val invalid = serviceClients.ungrantable(pod, scopes)
      val unregistered = scopes.filterNot { it in invalid }.map { it.substringBeforeLast('#') }.distinct()
        .filterNot { podFacade.contextExists(pod, URI(it)) }.toSet()
      val refused = scopes.sorted().mapNotNull { scope ->
        val reason = invalid[scope] ?: "no context registered on this pod".takeIf { scope.substringBeforeLast('#') in unregistered }
        reason?.let { "'$scope': $it" }
      }
      if (refused.isNotEmpty()) {
        return@authorized PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.UNGRANTABLE, refused.joinToString("; "))
      }
      val recipient = GrantRecipient.Service(registration.id, clientId, expectedVersion)
      when (val replacement = podGrantsFacade.replaceGrants(pod, recipient, scopes, grantedBy = authority.webId)) {
        is GrantReplacement.Replaced -> {
          // The write moved the version by one and activated the registration. Only where the check
          // after it dropped a grant did more change, and the answer reads it again.
          val replaced = if (replacement.granted == scopes) {
            registration.copy(scopes = scopes, grantsVersion = expectedVersion + 1, pendingUntil = null)
          } else {
            serviceClients.find(pod.id, clientId)?.takeIf { it.id == registration.id }
              ?: return@authorized PodServiceClientManagementResult.Refused(PodServiceClientManagementRefusal.NOT_FOUND)
          }
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
    operation: (PrivilegedAuthority) -> PodServiceClientManagementResult<T>,
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

  /** The grants are no longer at the version the replace named. */
  VERSION_MISMATCH,

  /** A scope a service client cannot hold, or one on no context registered on the pod. */
  UNGRANTABLE,
}
