package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientAlreadyRegistered
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration

/**
 * Giving a service client its credentials, idempotently.
 *
 * A service client authenticates with a secret and no person behind it, so provisioning it twice
 * must not quietly mint a second secret the caller then races its own health check against. The
 * caller asserts which registration it believes it holds, and this answers one of three things: the
 * registration still stands, a new one was minted, or somebody changed it in between.
 *
 * Who may ask is the adapter's question, and so is where the scopes come from. This decides what
 * happens to the registration.
 */
class PodServiceClientProvisioning @Inject internal constructor(
  private val serviceClients: PodServiceClientStore,
) {

  internal fun provision(
    pod: HostedPod,
    request: PodServiceClientRequest,
  ): PodServiceClientResult {
    val existing = serviceClients.find(pod.id, request.clientId)

    if (existing != null) {
      // Nothing to do only when the caller named the registration that is actually there *and* it
      // still carries the scopes being asked for. Scope drift takes the re-mint branch instead,
      // which is what makes a changed sandbox take effect.
      if (existing.id.value == request.expectedRegistrationId && existing.scopes == request.scopes) {
        return PodServiceClientResult.AlreadyProvisioned(existing)
      }
      logger.warn {
        "Pod '${pod.name}': re-minting service client '${request.clientId}' (expectedRegistrationId=" +
            "${LogSafeText.of(request.expectedRegistrationId ?: "(none)")}, current=${existing.id}, " +
            "scopes=${existing.scopes})"
      }
      if (!serviceClients.remove(pod.id, request.clientId, existing.id)) {
        return PodServiceClientResult.Refused(PodServiceClientRefusal.MODIFIED_CONCURRENTLY)
      }
    }

    return try {
      val registered = serviceClients.register(pod, request.clientId, request.scopes, request.label)
      PodServiceClientResult.Provisioned(registered.registration, registered.secret)
    } catch (_: ServiceClientAlreadyRegistered) {
      // Somebody inserted between the removal above and this one. The caller has to re-read rather
      // than receive a secret a competing registration already invalidated.
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
 * @param expectedRegistrationId the registration the caller believes it holds a secret for, or
 *   `null` to assert nothing — which always re-mints. A value that no longer matches re-mints too,
 *   so a caller whose stored credential drifted heals on its next run.
 */
internal data class PodServiceClientRequest(
  val clientId: String,
  val scopes: Set<String>,
  val label: String?,
  val expectedRegistrationId: String?,
)

/** What provisioning answers. */
internal sealed interface PodServiceClientResult {

  /** A new secret exists, and this is the only moment it can be read. */
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

  /** The conditional removal found the registration already replaced. */
  MODIFIED_CONCURRENTLY,

  /** The insert lost to one that arrived first. */
  PROVISIONED_CONCURRENTLY,
}
