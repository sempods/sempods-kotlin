package org.sempods.pods.oauth

import org.sempods.auth.core.ClientId
import org.sempods.auth.core.ClientRedirectPolicy
import org.sempods.auth.core.DidWebRedirectPolicy
import org.sempods.auth.core.RedirectUri

/**
 * What one pod makes of a `client_id`: whether it knows the client at all, and where that client
 * may be answered.
 *
 * Two questions rather than one, and they are here together because they are one lookup. A
 * `did:web:` identity answers both from the identifier itself; a `dyn:` one answers both from the
 * registration this pod holds, and asking twice would mean two ways to spell the same split.
 *
 * @param allowLoopback development only. A loopback redirect address in production means an
 *   authorization code can be intercepted by anything running on the user's machine. Read once,
 *   because the process-wide setting it comes from cannot change while this pod is serving.
 * @param registrationOf the redirect addresses this pod registered for a `dyn:` client, or `null`
 *   where it holds no registration — cleared, expired, or another pod's. Never asked about a
 *   `did:web:` client, which is asserted rather than registered. A function rather than the store,
 *   so this can be exercised without one.
 */
internal class PodClientDirectory(
  allowLoopback: Boolean,
  private val registrationOf: (clientId: String) -> Set<String>?,
) : ClientRedirectPolicy {

  private val didWeb = DidWebRedirectPolicy(allowLoopback)

  /**
   * Three-valued because the two failures are statements about different things, and one `null`
   * for both made the endpoint say the wrong one. [PodClientIdentity.Malformed] is about the
   * **string**; [PodClientIdentity.Unregistered] is about this pod's registration store, and the
   * string was fine. A client whose `dyn:` registration had been cleared was told to fix a format
   * that was never broken, and the only way out was to disconnect and reconnect — found in
   * production when a credential revoke dropped the DCR rows.
   */
  fun identify(clientId: String?): PodClientIdentity {
    val normalized = clientId?.trim()?.takeIf { it.isNotBlank() } ?: return PodClientIdentity.Malformed
    if (!ClientId.isValid(normalized)) return PodClientIdentity.Malformed
    return when {
      normalized.startsWith(DID_WEB_PREFIX) -> PodClientIdentity.Known(normalized)
      normalized.startsWith(DYNAMIC_PREFIX) ->
        if (registrationOf(normalized) != null) PodClientIdentity.Known(normalized) else PodClientIdentity.Unregistered

      else -> PodClientIdentity.Malformed
    }
  }

  /**
   * A `dyn:` client is answered only at an address it registered, compared canonically: loopback
   * addresses match with the port stripped (RFC 8252 §7.3), because a native client legitimately
   * binds an ephemeral port per invocation, and the same rule governs the registration
   * fingerprint's dedup.
   *
   * A `did:web:` client is [DidWebRedirectPolicy]'s question, asked through it rather than beside
   * it: the identity service asks the same one, and two readings of "may this address answer for
   * this origin" eventually disagree.
   *
   * Anything that is neither is refused, which is what an identifier this pod cannot place means.
   */
  override fun permits(clientId: String, redirectUri: String): Boolean {
    // What an address may look like at all, ahead of either branch. Load-bearing for `did:web:`,
    // where `DidWeb.Target.covers` matches host, port and path and says nothing about the scheme.
    if (!RedirectUri.isValid(redirectUri)) return false
    if (!clientId.startsWith(DYNAMIC_PREFIX)) return didWeb.permits(clientId, redirectUri)
    val registered = registrationOf(clientId) ?: return false
    val requested = RedirectUri.canonicalize(redirectUri)
    return registered.any { RedirectUri.canonicalize(it) == requested }
  }

  companion object {
    /** RFC 7591 dynamic clients, as `DynamicClientStore` mints them. */
    const val DYNAMIC_PREFIX = "dyn:"

    const val DID_WEB_PREFIX = "did:web:"
  }
}

/** What [PodClientDirectory.identify] answers. */
internal sealed interface PodClientIdentity {

  /** A `did:web:` client, or a `dyn:` one this pod still holds a registration for. */
  data class Known(val clientId: String) : PodClientIdentity

  /** Well-formed `dyn:<id>`, with no registration behind it here. */
  data object Unregistered : PodClientIdentity

  /** Absent, blank, outside RFC 6749's `*VSCHAR` ([ClientId]), or neither of the two shapes. */
  data object Malformed : PodClientIdentity
}
