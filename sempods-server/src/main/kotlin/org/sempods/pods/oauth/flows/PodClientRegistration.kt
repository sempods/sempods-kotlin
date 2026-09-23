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
    val client = request.client

    if (client.redirectUris.isEmpty()) {
      return refused(PodRegistrationError.INVALID_REDIRECT_URI, "at least one redirect_uri is required")
    }

    // The rule `/authorize` applies, through the same method: an address stored here that
    // `PodClientDirectory.permits` would refuse is a registration no login can honour.
    client.redirectUris.forEach { uri ->
      if (!RedirectUri.isValid(uri)) {
        return refused(
          PodRegistrationError.INVALID_REDIRECT_URI,
          "redirect_uri must be https, or http on a loopback host, with no fragment " +
              "and no code/response/state in the query: $uri",
        )
      }
    }

    // The four members [ClientMetadataUri] is about. Asked here and not at the parser: the SDK
    // checks three of them and measurably not `logo_uri` — see [ClientMetadataUri].
    listOf(
      "client_uri" to client.clientUri,
      "logo_uri" to client.logoUri,
      "tos_uri" to client.tosUri,
      "policy_uri" to client.policyUri,
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
      redirectUris = client.redirectUris,
      clientName = client.clientName,
      clientUri = client.clientUri,
      logoUri = client.logoUri,
      softwareId = client.softwareId,
      softwareVersion = client.softwareVersion,
      contacts = client.contacts,
      tosUri = client.tosUri,
      policyUri = client.policyUri,
      rawRequest = request.raw,
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
 * A registration, as the protocol adapter read it.
 *
 * @param client the members this pod reads, already typed. Syntax is the adapter's question and
 *   was answered before this exists.
 * @param raw the JSON body as a map, verbatim. Kept beside [client] because it is what the
 *   registration row stores, and because RFC 7591 lets a client send members this server does not
 *   read — a member absent from [client] may still be present here.
 */
internal data class PodRegistrationRequest(
  val client: PodClientMetadata,
  val raw: Map<String, Any?>,
  val userAgent: String?,
  val forwardedFor: String?,
)

/**
 * The RFC 7591 members this pod reads, trimmed, with a blank read as absent.
 *
 * Every default is "the client said nothing", so a body that carries none of these is expressible
 * — which is what an empty registration body is.
 */
internal data class PodClientMetadata(
  val redirectUris: Set<String> = emptySet(),
  val clientName: String? = null,
  val clientUri: String? = null,
  val logoUri: String? = null,
  val softwareId: String? = null,
  val softwareVersion: String? = null,
  val contacts: List<String> = emptyList(),
  val tosUri: String? = null,
  val policyUri: String? = null,
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
