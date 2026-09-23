package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.ClientMetadataUri
import org.sempods.auth.core.RedirectUri
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.ForwardedFor
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.SERVICE_CLIENTS_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.grants.carriesPrivilegedFeature
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PodInstallationAuthorityStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import java.time.Instant

/**
 * Registering a client at `POST {pod}/_system/auth/register`, in either of the two profiles that
 * endpoint serves.
 *
 * **Unauthenticated (RFC 7591).** MCP clients self-register, and many keep no client state at all
 * — they register again on every reconnect. `DynamicClientStore` deduplicates by fingerprint, so a
 * repeat returns the stored row instead of a fresh `dyn:` identity nobody has consented to.
 * `token_endpoint_auth_method` is always `none`: these clients hold no secret, and PKCE is what
 * binds a code to the caller that asked for it.
 *
 * **With an installation authority.** The pod owner approved one registration at `/authorize`
 * (`service-clients`), and the bearer that carries it may create one confidential client here. The
 * server names it, it is stored where Client Credentials reads its clients, and it starts with no
 * grants at all — the contexts are the owner's to approve in a second consent, against a service
 * that by then exists.
 *
 * Which profile a body asks for is read from the body as it arrived. The SDK fills in what RFC 7591
 * says a field defaults to, and a default must not be able to turn a request nobody made into a
 * profile this pod serves.
 *
 * The [`dyn:` prefix](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-008)
 * and the [grant types a registration response may advertise](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-011)
 * are bound to this endpoint by the specification, so the second profile is an experimental
 * extension with known deviations — sempods-spec#69 carries them. What holds either way: an
 * unauthenticated registration gains no service credentials.
 */
class PodClientRegistration @Inject internal constructor(
  private val dynamicClientStore: DynamicClientStore,
  private val serviceClients: PodServiceClientStore,
  private val installationAuthorities: PodInstallationAuthorityStore,
  private val podGrantsFacade: PodGrantsFacade,
  private val webIdUriDeriver: WebIdUriDeriver,
) {

  internal fun register(pod: HostedPod, request: PodRegistrationRequest): PodRegistrationResult =
    if (asksForASecret(request.raw)) registerService(pod, request) else registerDynamic(pod, request)

  // ─── The unauthenticated profile ──────────────────────────────────────────

  private fun registerDynamic(pod: HostedPod, request: PodRegistrationRequest): PodRegistrationResult {
    if (request.caller?.carriesPrivilegedFeature == true) return wrongProfile()

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

  // ─── The installation profile ─────────────────────────────────────────────

  /**
   * One registration per approved installation.
   *
   * Everything that can refuse refuses before the authority is spent, so a body with a typo in it
   * costs the owner nothing. After that the order is fixed: consume, then create. The other order
   * lets two calls arriving together create two clients from one approval, which is the whole
   * point of the authority being one-shot.
   *
   * What the fixed order costs, and what the owner is told to do about it:
   *
   * | What happens | What is left |
   * |---|---|
   * | Two calls arrive together | One client. The other call is answered like a second attempt |
   * | The server dies between consuming and creating | Neither. The owner installs again |
   * | The answer is lost on the way back | A client whose secret nobody holds, and no grants. The retry is refused, because the secret exists only in the answer that was lost |
   */
  private fun registerService(pod: HostedPod, request: PodRegistrationRequest): PodRegistrationResult {
    val caller = request.caller
      ?: return refused(
        PodRegistrationError.INVALID_CLIENT_METADATA,
        "this endpoint issues no client secret to an unauthenticated caller",
      )
    if (!caller.carriesPrivilegedFeature) {
      return unauthorized(
        PodRegistrationRefusal.NOT_AUTHORIZED,
        "registering a service client needs an authorization carrying '$SERVICE_CLIENTS_SCOPE'",
      )
    }
    if (!isInstallationProfile(request.raw)) return wrongProfile()

    SERVER_ASSIGNED.firstOrNull { it in request.raw }?.let { member ->
      return refused(
        PodRegistrationError.INVALID_CLIENT_METADATA,
        "$member is this pod's to decide, and an installation may not carry it",
      )
    }

    val label = request.client.clientName
      ?: return refused(
        PodRegistrationError.INVALID_CLIENT_METADATA,
        "client_name is required: it is what names this service in the consent that grants it contexts",
      )

    val subject = caller.tokenSub?.trim()?.takeIf { it.isNotBlank() }
    // Alias-aware, over the pair of spellings one address has ([WebIdUriDeriver.derivableAliases])
    // — the set a request carries, and the same one `SempodsBaseEndpoint.resolvePodOwnerPrincipal`
    // recognises an owner by. The consent that issued this authority asked the richer question,
    // against the session's own `also_known_as`; ownership can have moved since, so it is asked
    // again here rather than taken from a token.
    if (subject == null || !podGrantsFacade.isPodOwner(pod, webIdUriDeriver.derivableAliases(subject))) {
      return unauthorized(PodRegistrationRefusal.NOT_AUTHORIZED, "this pod's owner installs its service clients")
    }

    val tokenId = caller.tokenJti
    if (tokenId == null || installationAuthorities.consume(pod.id, tokenId) == null) {
      return unauthorized(
        PodRegistrationRefusal.AUTHORITY_SPENT,
        "this authorization has already registered a service client",
      )
    }

    val registered = serviceClients.registerInstallation(pod, label)

    logger.info {
      "[oauth/register] Service client installed: pod='${pod.name}', " +
          "clientId='${registered.registration.clientId}', label='${LogSafeText.of(label)}', " +
          "installer='${LogSafeText.of(caller.oauthClientId ?: "(unset)")}', owner='${LogSafeText.of(subject)}'"
    }

    return PodRegistrationResult.ServiceRegistered(
      clientId = registered.registration.clientId,
      clientName = label,
      issuedAt = registered.registration.createdAt,
      secret = registered.secret,
    )
  }

  /**
   * Whether the body asks for a client that authenticates with a secret.
   *
   * Read widely on purpose: any `token_endpoint_auth_method` other than `none`, and any grant type
   * outside the browser flow. A body asking for `client_secret_post` or `private_key_jwt` is a
   * confidential registration this pod does not serve, and answering it as a public one would hand
   * back a `dyn:` client that silently does something else.
   */
  private fun asksForASecret(raw: Map<String, Any?>): Boolean {
    val authMethod = (raw["token_endpoint_auth_method"] as? String)?.trim()
    if (authMethod != null && authMethod != PUBLIC_AUTH_METHOD) return true
    return strings(raw["grant_types"])?.any { it !in PUBLIC_GRANT_TYPES } == true
  }

  /** The one confidential shape this pod serves, spelled exactly. */
  private fun isInstallationProfile(raw: Map<String, Any?>): Boolean =
    (raw["token_endpoint_auth_method"] as? String)?.trim() == CONFIDENTIAL_AUTH_METHOD &&
        strings(raw["grant_types"]) == listOf(CLIENT_CREDENTIALS_GRANT)

  private fun strings(value: Any?): List<String>? =
    (value as? List<*>)?.mapNotNull { (it as? String)?.trim() }

  private fun wrongProfile(): PodRegistrationResult = refused(
    PodRegistrationError.INVALID_CLIENT_METADATA,
    "an installation registers a confidential client: grant_types [\"$CLIENT_CREDENTIALS_GRANT\"] " +
        "and token_endpoint_auth_method \"$CONFIDENTIAL_AUTH_METHOD\"",
  )

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

  private fun unauthorized(reason: PodRegistrationRefusal, description: String): PodRegistrationResult =
    PodRegistrationResult.Unauthorized(reason, description)

  private companion object {
    private val logger = KotlinLogging.logger {}

    private const val PUBLIC_AUTH_METHOD = "none"
    private const val CONFIDENTIAL_AUTH_METHOD = "client_secret_basic"
    private const val CLIENT_CREDENTIALS_GRANT = "client_credentials"
    private val PUBLIC_GRANT_TYPES = setOf("authorization_code", "refresh_token")

    /**
     * Members an installation may not carry, whichever spelling it reaches for.
     *
     * The identity and the place are the pod's to assign. A caller that could name the
     * `client_id` could install itself under an identity the owner already trusts, and one that
     * could name a root could point the service at a context the owner is already filling.
     * `scope` is the same request in RFC 7591's own spelling: grants come from the consent that
     * follows a registration, never from the registration. `redirect_uris` and `response_types`
     * describe a browser flow a client authenticating with a secret does not have.
     */
    private val SERVER_ASSIGNED = listOf(
      "client_id",
      "clientId",
      "contextRoot",
      "context_root",
      "scope",
      "scopes",
      "redirect_uris",
      "response_types",
    )
  }
}

/**
 * A registration, as the protocol adapter read it.
 *
 * @param client the members this pod reads, already typed. Syntax is the adapter's question and
 *   was answered before this exists.
 * @param raw the JSON body as a map, verbatim. Kept beside [client] because it is what the
 *   registration row stores, because RFC 7591 lets a client send members this server does not
 *   read, and because which profile a body asks for is read from what arrived rather than from
 *   what the SDK filled in.
 * @param caller the bearer the request carried, `null` where it carried none. A verified one that
 *   this pod could not place never reaches here — that is a 401 at the adapter.
 */
internal data class PodRegistrationRequest(
  val client: PodClientMetadata,
  val raw: Map<String, Any?>,
  val userAgent: String?,
  val forwardedFor: String?,
  val caller: SempodsCredentials? = null,
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
   * A service client, and the one moment its secret can be read.
   *
   * [issuedAt] and [clientId] are what the caller opens the grant consent with. Both are the pod's
   * own: [clientName] is whatever the installer typed, so an owner shown only that has no way to
   * tell an expected installation from a crafted one.
   */
  data class ServiceRegistered(
    val clientId: String,
    val clientName: String,
    val issuedAt: Instant,
    val secret: String,
  ) : PodRegistrationResult

  /**
   * @param description the wire's `error_description`, decided here because two of the three name
   *   the value that was refused.
   */
  data class Refused(val error: PodRegistrationError, val description: String) : PodRegistrationResult

  /** The caller's own credential is what this answer is about — see [PodRegistrationRefusal]. */
  data class Unauthorized(val reason: PodRegistrationRefusal, val description: String) : PodRegistrationResult
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

/** RFC 6750 §3.1's two answers about a bearer that carries the wrong authority. */
internal enum class PodRegistrationRefusal {

  /**
   * The authorization registered its one service client already, or never carried the right to.
   *
   * `invalid_token`, which RFC 6750 defines as expired, revoked, malformed **or invalid for other
   * reasons** — a spent one-shot authority is the last of those. A 401 rather than a 403 because
   * the way out is a new authorization, which is what a 401 tells a client to go and get.
   */
  AUTHORITY_SPENT,

  /** The credential is good and does not cover this: `insufficient_scope`, 403. */
  NOT_AUTHORIZED,
}
