package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.ContextHolderCounts
import org.sempods.pods.grants.ContextHolders
import org.sempods.pods.grants.GRANTS_MANAGE_SCOPE
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.GrantReplacement
import org.sempods.pods.grants.PodGrantHolders
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.ReachingGrant
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PrivilegedAuthority
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore

/**
 * The owner's view of who reaches each context, and the withdrawal of one direct grant on one
 * context. What each does on the wire is `sempods-server/docs/auth/service-clients.md`
 * §"Managing grants".
 *
 * Every operation needs [GRANTS_MANAGE_SCOPE], which only a service's management authority carries
 * ([PodOwnerAuthority]); the owner is checked on every call. Owner-only, because the listing shows
 * every person and app on a context.
 *
 * **Only a direct grant is withdrawn on a context.** One reaching it from an ancestor's `#manage`
 * is refused with that ancestor named ([PodGrantManagementRefusal.INHERITED]), since withdrawing it
 * here would mean withdrawing it on every context it covers.
 */
class PodGrantManagement @Inject internal constructor(
  private val ownerAuthority: PodOwnerAuthority,
  private val holders: PodGrantHolders,
  private val podGrantsFacade: PodGrantsFacade,
  private val serviceClients: PodServiceClientStore,
  private val webIdUriDeriver: WebIdUriDeriver,
) {

  /** How many people, owner's apps and services reach each registered context. */
  internal fun counts(pod: HostedPod, caller: SempodsCredentials): PodGrantManagementResult<List<ContextHolderCounts>> =
    authorized(pod, caller) { PodGrantManagementResult.Done(holders.snapshot(pod).counts()) }

  /** Who reaches [contextUri], grouped. */
  internal fun holders(pod: HostedPod, caller: SempodsCredentials, contextUri: String): PodGrantManagementResult<ContextHolders> =
    authorized(pod, caller) {
      holders.snapshot(pod).on(contextUri)?.let { PodGrantManagementResult.Done(it) }
        ?: PodGrantManagementResult.Refused(PodGrantManagementRefusal.UNKNOWN_CONTEXT)
    }

  /**
   * Withdraws [webId]'s direct grants on [contextUri], with the delegations they back
   * ([PodGrantsFacade.withdrawWebIdGrantsOn]). A person listed with delegations here and no grant
   * reaching here is withdrawn too: the cascade ends what no grant of theirs backs, which for a
   * person holding no grant at all — a former owner — is every delegation they made.
   */
  internal fun withdrawPerson(
    pod: HostedPod,
    caller: SempodsCredentials,
    contextUri: String,
    webId: String,
  ): PodGrantManagementResult<Unit> = authorized(pod, caller) { authority ->
    val context = holders.snapshot(pod).on(contextUri)
      ?: return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.UNKNOWN_CONTEXT)
    val spellings = webIdUriDeriver.derivableAliases(webId) + webId
    val person = context.people.firstOrNull { it.webId in spellings }
      ?: return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.NOT_HELD)
    directOrRefused(person.grants, withdrawable = person.grants.isEmpty()) {
      val result = podGrantsFacade.withdrawWebIdGrantsOn(pod, person.webId, contextUri)
      logger.info {
        "[grants/withdraw] A person's grants withdrawn: pod='${pod.name}', webId='${LogSafeText.of(person.webId)}', " +
            "context='$contextUri', by='${authority.webId}', deletedAppGrants=${result.deletedAppGrants}"
      }
    }
  }

  /** Withdraws what the owner delegated to [clientId] on [contextUri]. */
  internal fun withdrawOwnerApp(
    pod: HostedPod,
    caller: SempodsCredentials,
    contextUri: String,
    clientId: String,
  ): PodGrantManagementResult<Unit> = authorized(pod, caller) {
    val context = holders.snapshot(pod).on(contextUri)
      ?: return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.UNKNOWN_CONTEXT)
    val app = context.ownerApps.firstOrNull { it.clientId == clientId }
      ?: return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.NOT_HELD)
    directOrRefused(app.grants) { podGrantsFacade.withdrawOwnerDelegationOn(pod, clientId, contextUri) }
  }

  /**
   * Withdraws [clientId]'s direct grants on [contextUri], if its grants are still at
   * [expectedVersion]: the same conditional write as replacing them
   * ([PodServiceClientManagement.replaceGrants]), with the rest of its grants kept.
   */
  internal fun withdrawService(
    pod: HostedPod,
    caller: SempodsCredentials,
    contextUri: String,
    clientId: String,
    expectedVersion: Long,
  ): PodGrantManagementResult<Unit> = authorized(pod, caller) { authority ->
    val context = holders.snapshot(pod).on(contextUri)
      ?: return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.UNKNOWN_CONTEXT)
    val registration = serviceClients.find(pod.id, clientId)
      ?: return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.NOT_HELD)
    // The precondition first, as RFC 9110 §13.2.2 orders it: a caller holding a stale version
    // learns that before anything about what the grants are now.
    if (registration.grantsVersion != expectedVersion) {
      return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.VERSION_MISMATCH)
    }
    val service = context.services.firstOrNull { it.clientId == clientId }
      ?: return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.NOT_HELD)
    if (service.grants.none { it.inheritedFrom == null }) {
      return@authorized PodGrantManagementResult.Refused(PodGrantManagementRefusal.INHERITED, ancestors(service.grants))
    }
    val kept = registration.scopes - podGrantsFacade.anchoredAt(contextUri).toSet()
    val recipient = GrantRecipient.Service(registration.id, clientId, expectedVersion)
    when (podGrantsFacade.replaceGrants(pod, recipient, kept, grantedBy = authority.webId)) {
      is GrantReplacement.Replaced -> {
        logger.info {
          "[grants/withdraw] A service's grants withdrawn: pod='${pod.name}', clientId='${LogSafeText.of(clientId)}', " +
              "context='$contextUri', by='${authority.webId}'"
        }
        PodGrantManagementResult.Done(Unit)
      }
      GrantReplacement.Conflict -> PodGrantManagementResult.Refused(PodGrantManagementRefusal.VERSION_MISMATCH)
      GrantReplacement.NotFound -> PodGrantManagementResult.Refused(PodGrantManagementRefusal.NOT_HELD)
    }
  }

  /**
   * Runs [withdraw] where [grants] hold a direct one, or where [withdrawable] says so anyway;
   * otherwise refuses with the ancestors they come from.
   */
  private inline fun directOrRefused(
    grants: List<ReachingGrant>,
    withdrawable: Boolean = false,
    withdraw: () -> Unit,
  ): PodGrantManagementResult<Unit> {
    if (withdrawable || grants.any { it.inheritedFrom == null }) {
      withdraw()
      return PodGrantManagementResult.Done(Unit)
    }
    return PodGrantManagementResult.Refused(PodGrantManagementRefusal.INHERITED, ancestors(grants))
  }

  private fun ancestors(grants: List<ReachingGrant>): List<String> =
    grants.mapNotNull { it.inheritedFrom }.distinct().sorted()

  /** [PodOwnerAuthority] for [GRANTS_MANAGE_SCOPE], asked by name; [operation] receives the authority that stood. */
  private inline fun <T> authorized(
    pod: HostedPod,
    caller: SempodsCredentials,
    operation: (PrivilegedAuthority) -> PodGrantManagementResult<T>,
  ): PodGrantManagementResult<T> =
    when (val check = ownerAuthority.check(pod, caller, GRANTS_MANAGE_SCOPE)) {
      is PodOwnerAuthorityCheck.Standing -> operation(check.authority)
      is PodOwnerAuthorityCheck.Refused -> PodGrantManagementResult.Unauthorized(check.reason)
    }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/** What a grant-management operation answers. */
internal sealed interface PodGrantManagementResult<out T> {
  data class Done<T>(val value: T) : PodGrantManagementResult<T>

  /** [inheritedFrom] names the ancestors of an [PodGrantManagementRefusal.INHERITED] grant. */
  data class Refused(
    val reason: PodGrantManagementRefusal,
    val inheritedFrom: List<String> = emptyList(),
  ) : PodGrantManagementResult<Nothing>

  /** The bearer holds no owner authority for [GRANTS_MANAGE_SCOPE]. */
  data class Unauthorized(val reason: PodOwnerAuthorityRefusal) : PodGrantManagementResult<Nothing>
}

/** Why a grant-management operation the bearer was authorized for did nothing. The adapter words each one. */
internal enum class PodGrantManagementRefusal {

  /** No context registered at that IRI on this pod. */
  UNKNOWN_CONTEXT,

  /** The holder named reaches nothing on that context, or is no holder of that kind. */
  NOT_HELD,

  /** The holder reaches the context only through an ancestor's `#manage` grant. */
  INHERITED,

  /** A service's grants are no longer at the version `If-Match` names. */
  VERSION_MISMATCH,
}
