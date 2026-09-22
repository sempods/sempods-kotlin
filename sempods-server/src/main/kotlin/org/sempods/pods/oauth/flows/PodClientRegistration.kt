package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.ClientMetadataUri
import org.sempods.auth.core.RedirectUri
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.ForwardedFor
import org.sempods.pods.HostedPod
import org.sempods.pods.oauth.DynamicClientStore

/**
 * Registering a client that arrives with no identity of its own (RFC 7591).
 *
 * MCP clients self-register, and many keep no client state at all — they register again on every
 * reconnect. `DynamicClientStore` deduplicates by fingerprint, so a repeat returns the stored row
 * instead of a fresh `dyn:` identity nobody has consented to.
 *
 * `token_endpoint_auth_method` is always `none`: these clients hold no secret, and PKCE is what
 * binds a code to the caller that asked for it.
 */
class PodClientRegistration @Inject internal constructor(
  private val dynamicClientStore: DynamicClientStore,
) {

  internal fun register(pod: HostedPod, request: PodRegistrationRequest): PodRegistrationResult {
    val metadata = request.metadata
    val redirectUris = (metadata?.get("redirect_uris") as? List<*>)
      ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotBlank() } }
      ?.toSet()
      ?: emptySet()

    if (redirectUris.isEmpty()) {
      return refused(PodRegistrationError.INVALID_REDIRECT_URI, "at least one redirect_uri is required")
    }

    // The rule `/authorize` applies, through the same method: an address stored here that
    // `PodClientDirectory.permits` would refuse is a registration no login can honour.
    redirectUris.forEach { uri ->
      if (!RedirectUri.isValid(uri)) {
        return refused(
          PodRegistrationError.INVALID_REDIRECT_URI,
          "redirect_uri must be https, or http on a loopback host, with no fragment " +
              "and no code/response/state in the query: $uri",
        )
      }
    }

    val clientName = (metadata?.get("client_name") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val clientUri = (metadata?.get("client_uri") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val logoUri = (metadata?.get("logo_uri") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val softwareId = (metadata?.get("software_id") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val softwareVersion = (metadata?.get("software_version") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val tosUri = (metadata?.get("tos_uri") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val policyUri = (metadata?.get("policy_uri") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val contacts = (metadata?.get("contacts") as? List<*>)
      ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotBlank() } }
      ?: emptyList()

    // The four members [ClientMetadataUri] is about.
    listOf(
      "client_uri" to clientUri,
      "logo_uri" to logoUri,
      "tos_uri" to tosUri,
      "policy_uri" to policyUri,
    ).forEach { (field, value) ->
      if (value != null && !ClientMetadataUri.isValid(value)) {
        return refused(
          PodRegistrationError.INVALID_CLIENT_METADATA,
          "$field must be https, or http on a loopback host: $value",
        )
      }
    }

    val registration = dynamicClientStore.register(
      registeredForPod = pod.id,
      registeredForPodName = pod.name,
      redirectUris = redirectUris,
      clientName = clientName,
      clientUri = clientUri,
      logoUri = logoUri,
      softwareId = softwareId,
      softwareVersion = softwareVersion,
      contacts = contacts,
      tosUri = tosUri,
      policyUri = policyUri,
      rawRequest = metadata ?: emptyMap(),
      remoteAddr = ForwardedFor.clientIp(request.forwardedFor),
      userAgent = request.userAgent?.trim()?.takeIf { it.isNotBlank() },
    )

    announce(pod, registration)

    return PodRegistrationResult.Registered(
      clientId = registration.clientId,
      redirectUris = registration.redirectUris,
      clientName = registration.clientName,
      // Filtered on the way out as well — see [ClientMetadataUri], which says why the check above
      // does not cover the row this may be reading.
      clientUri = registration.clientUri?.takeIf(ClientMetadataUri::isValid),
      logoUri = registration.logoUri?.takeIf(ClientMetadataUri::isValid),
      softwareId = registration.softwareId,
      softwareVersion = registration.softwareVersion,
      contacts = registration.contacts,
      tosUri = registration.tosUri?.takeIf(ClientMetadataUri::isValid),
      policyUri = registration.policyUri?.takeIf(ClientMetadataUri::isValid),
    )
  }

  // TODO: full DCR profile on INFO while Stage 1 observes real agents; drop back to FINE once
  //  Stage 2 pins the per-agent identity model. The second line below logs the whole submitted
  //  body, which is caller-controlled text on an unauthenticated endpoint — the log volume is
  //  theirs to choose, not this server's.
  private fun announce(pod: HostedPod, registration: DynamicClientStore.Registration) {
    val action = if (registration.deduplicatedFromRegisteredAt != null) {
      "Dynamic client dedup hit (reused existing registration from ${registration.deduplicatedFromRegisteredAt})"
    } else {
      "Dynamic client registered"
    }
    // A fingerprint hit returns the *stored* row and discards the body just validated, so none of
    // these is the value those checks saw. Same reason [ClientMetadataUri] is asked again on read.
    logger.info {
      "[oauth/register] $action: pod='${pod.name}', clientId='${registration.clientId}', " +
          "clientName='${LogSafeText.of(registration.clientName ?: "(unset)")}', " +
          "softwareId='${LogSafeText.of(registration.softwareId ?: "(unset)")}', " +
          "softwareVersion='${LogSafeText.of(registration.softwareVersion ?: "(unset)")}', " +
          "clientUri='${LogSafeText.of(registration.clientUri ?: "(unset)")}', " +
          "logoUri='${LogSafeText.of(registration.logoUri ?: "(unset)")}', " +
          "tosUri='${LogSafeText.of(registration.tosUri ?: "(unset)")}', " +
          "policyUri='${LogSafeText.of(registration.policyUri ?: "(unset)")}', " +
          "redirectUris=${LogSafeText.of(registration.redirectUris.toList().toString())}, " +
          "contacts=${LogSafeText.of(registration.contacts.toString())}, " +
          "rawRequestKeys=${LogSafeText.of(registration.rawRequest.keys.sorted().toString())}"
    }
    logger.info { "[oauth/register] full request body: ${LogSafeText.of(registration.rawRequest.toString())}" }
  }

  private fun refused(error: PodRegistrationError, description: String): PodRegistrationResult =
    PodRegistrationResult.Refused(error, description)

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/**
 * A registration as the caller sent it — untrimmed, untyped, any of it absent.
 *
 * [metadata] is the JSON body as a map. Nothing is a declared field: the body is stored verbatim,
 * RFC 7591 lets a client send members this server does not read, and a wrong-typed optional member
 * is read as absent.
 */
internal data class PodRegistrationRequest(
  val metadata: Map<String, Any?>?,
  val userAgent: String?,
  val forwardedFor: String?,
)

/** What a registration answers. */
internal sealed interface PodRegistrationResult {

  /**
   * The client, as the answer describes it — a fresh identity and a dedup hit look the same here.
   *
   * The four `ClientMetadataUri` members are already filtered: `null` means the client named none
   * or named one this pod will not hand back.
   */
  data class Registered(
    val clientId: String,
    val redirectUris: Set<String>,
    val clientName: String?,
    val clientUri: String?,
    val logoUri: String?,
    val softwareId: String?,
    val softwareVersion: String?,
    val contacts: List<String>,
    val tosUri: String?,
    val policyUri: String?,
  ) : PodRegistrationResult

  /**
   * @param description the wire's `error_description`, decided here because two of the three name
   *   the value that was refused.
   */
  data class Refused(val error: PodRegistrationError, val description: String) : PodRegistrationResult
}

/**
 * RFC 7591 §3.2.2's registration errors.
 *
 * Its own set, and not [OAuthErrorCode][org.sempods.auth.core.OAuthErrorCode], which is scoped to
 * authorize and token responses.
 */
internal enum class PodRegistrationError(val code: String) {
  INVALID_REDIRECT_URI("invalid_redirect_uri"),
  INVALID_CLIENT_METADATA("invalid_client_metadata"),
}
