package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.DidWeb
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.oauth.didweb.DidWebClientAuthentication
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientAlreadyRegistered
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration
import org.sempods.pods.oauth.serviceclients.ServiceClientSecretRotation
import org.sempods.pods.oauth.serviceclients.ServiceManagementAuthority
import java.time.Instant

/**
 * Giving a service client its credentials, idempotently. Provisioning creates; it never writes the
 * grants of a registration that exists, because those are the pod owner's
 * (`sempods-server/docs/auth/service-clients.md` §"Managing service clients").
 *
 * A service client authenticates with a secret and no person behind it, so provisioning it twice
 * must not quietly mint a second secret the caller then races its own health check against. The
 * caller asserts which registration it believes it holds, and this answers one of three things: the
 * registration still stands, a secret was issued for it, or somebody changed it in between.
 *
 * Who may ask is the adapter's question, and so is where the scopes come from. This decides what
 * happens to the registration.
 */
class PodServiceClientProvisioning @Inject internal constructor(
  private val serviceClients: PodServiceClientStore,
  private val didWebAuthentication: DidWebClientAuthentication,
) {

  /**
   * Whether [clientId] can be provisioned as a management client: a `did:web` identifier the pod's
   * routes can name, so the owner can remove it ([DidWebClientAuthentication.isAddressable]).
   */
  internal fun acceptsManagementClient(clientId: String): Boolean =
    DidWeb.targetOf(clientId) != null && didWebAuthentication.isAddressable(clientId)

  /**
   * Registers the `did:web` service [clientId] on a pod just created for [owner], active and holding
   * the authority to manage it on that owner's behalf ([ServiceManagementAuthority]). The operator
   * names the service; no software statement is asked for, because the admin surface is trusted.
   * A registration under [clientId] that exists is left as it is.
   *
   * @param ownerUris every URI the owner is known by, [owner] among them.
   */
  internal fun provisionManagement(pod: HostedPod, clientId: String, label: String, owner: String, ownerUris: Set<String>) {
    if (serviceClients.find(pod.id, clientId) != null) return
    try {
      serviceClients.registerDidWeb(
        pod, clientId, label, redirectUris = emptyList(), provisional = false,
        management = ServiceManagementAuthority(grantedBy = owner, subjectUris = ownerUris + owner, grantedAt = Instant.now()),
      )
    } catch (_: ServiceClientAlreadyRegistered) {
      return
    }
    logger.info { "Pod '${pod.name}': management service '${LogSafeText.of(clientId)}' provisioned for owner '$owner'" }
  }

  /**
   * @param beforeCreating runs only when there is no registration yet, before one is created: what
   *   the adapter sets up once for a new client, and never again for one that exists.
   */
  internal fun provision(
    pod: HostedPod,
    request: PodServiceClientRequest,
    beforeCreating: () -> Unit = {},
  ): PodServiceClientResult {
    val existing = serviceClients.find(pod.id, request.clientId)
      ?: return create(pod, request, beforeCreating)

    // The registration stays whatever the caller holds: its grants may be the owner's by now. The
    // caller that names it and its current secret holds a working one; any other gets a new secret
    // for the same registration.
    if (existing.id.value == request.expectedRegistrationId && existing.secretId == request.expectedSecretId) {
      return PodServiceClientResult.AlreadyProvisioned(existing)
    }
    logger.warn {
      "Pod '${pod.name}': issuing a new secret for service client '${request.clientId}' (expectedRegistrationId=" +
          "${LogSafeText.of(request.expectedRegistrationId ?: "(none)")}, current=${existing.id}, " +
          "expectedSecretId=${LogSafeText.of(request.expectedSecretId ?: "(none)")}, currentSecretId=${existing.secretId})"
    }
    return when (val rotation = serviceClients.rotateSecret(pod.id, request.clientId, expectedSecretId = existing.secretId)) {
      is ServiceClientSecretRotation.Rotated -> PodServiceClientResult.Provisioned(rotation.registration, rotation.secret)
      // Another rotation landed in between, or the registration went: a secret answered now would
      // not be the one that works.
      ServiceClientSecretRotation.Conflict,
      ServiceClientSecretRotation.NotFound -> PodServiceClientResult.Refused(PodServiceClientRefusal.MODIFIED_CONCURRENTLY)
      // An operator's identifier carries no `:`, so it never names a `did:web` client.
      ServiceClientSecretRotation.HoldsNoSecret -> error("an operator-provisioned client holds a secret: '${request.clientId}'")
    }
  }

  private fun create(pod: HostedPod, request: PodServiceClientRequest, beforeCreating: () -> Unit): PodServiceClientResult {
    beforeCreating()
    return try {
      val registered = serviceClients.register(pod, request.clientId, request.scopes, request.label)
      PodServiceClientResult.Provisioned(registered.registration, registered.secret)
    } catch (_: ServiceClientAlreadyRegistered) {
      // Somebody inserted since the read above. The caller has to re-read rather than receive a
      // secret for a registration it did not make.
      PodServiceClientResult.Refused(PodServiceClientRefusal.PROVISIONED_CONCURRENTLY)
    }
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/**
 * What a caller wants a service client to be.
 *
 * @param scopes the grants a new registration starts with. An existing one keeps its own.
 * @param expectedRegistrationId the registration the caller believes it holds a secret for, or
 *   `null` to assert nothing.
 * @param expectedSecretId the `secretId` of the secret the caller holds, or `null`. Where a
 *   registration exists and the two do not both name it and its current secret, the caller gets a new
 *   secret for it, so a caller whose stored credential drifted heals on its next run.
 */
internal data class PodServiceClientRequest(
  val clientId: String,
  val scopes: Set<String>,
  val label: String?,
  val expectedRegistrationId: String?,
  val expectedSecretId: String?,
)

/** What provisioning answers. */
internal sealed interface PodServiceClientResult {

  /** A new secret exists, for a new registration or the one there, and this is the only moment it can be read. */
  data class Provisioned(
    val registration: ServiceClientRegistration,
    val secret: String,
  ) : PodServiceClientResult

  /** The registration the caller named still stands, secret included. Nothing was written. */
  data class AlreadyProvisioned(val registration: ServiceClientRegistration) : PodServiceClientResult

  /** Somebody else changed the registration in between — see [PodServiceClientRefusal]. */
  data class Refused(val reason: PodServiceClientRefusal) : PodServiceClientResult
}

/** The two ways a concurrent caller takes the registration away. */
internal enum class PodServiceClientRefusal {

  /** Another secret was issued for the registration in between, or it was removed. */
  MODIFIED_CONCURRENTLY,

  /** The insert lost to one that arrived first. */
  PROVISIONED_CONCURRENTLY,
}
