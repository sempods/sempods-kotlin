package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.ClientMetadataUri
import org.sempods.auth.core.RedirectUri
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.ForwardedFor
import org.sempods.pods.HostedPod
import org.sempods.pods.PodId
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.grants.carriesPrivilegedFeature
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PrivilegedAuthorityRows
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
 * **A service, registering itself.** A confidential client that authenticates with a secret and
 * uses Client Credentials. Nobody authorizes the call: the registration is provisional, holds no
 * data rights, and is removed after [PodServiceClientStore.ACTIVATION_WINDOW] unless the pod owner
 * activates it by granting it contexts. The deadline and the per-pod budget bound what an open
 * endpoint costs; neither confirms who registered. The server names the client `svc:…`.
 *
 * Which profile a body asks for is read from the body as it arrived. The SDK fills in what RFC 7591
 * says a field defaults to, and a default must not be able to turn a request nobody made into a
 * profile this pod serves.
 *
 * The [`dyn:` prefix](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-008),
 * the [grant types a registration response may advertise](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-011)
 * and [out-of-band service clients](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md#SPS-AUTH-012)
 * are bound by the specification, so the second profile is an experimental extension with known
 * deviations — sempods-spec#122 carries them.
 */
class PodClientRegistration @Inject internal constructor(
  private val dynamicClientStore: DynamicClientStore,
  private val serviceClients: PodServiceClientStore,
  private val serviceBudget: PodServiceRegistrationBudget,
  private val ownerAuthority: PodOwnerAuthority,
) {

  internal fun register(pod: HostedPod, request: PodRegistrationRequest): PodRegistrationResult {
    return when (shapeOf(request.raw)) {
      // A caller holding an authority for one named operation is not registering an ordinary
      // client, whatever the body says.
      PodClientShape.PUBLIC ->
        if (request.caller?.carriesPrivilegedFeature == true) wrongProfile() else registerDynamic(pod, request)
      PodClientShape.SERVICE -> registerService(pod, request)
      PodClientShape.OTHER -> wrongProfile()
    }
  }

  // ─── The unauthenticated profile ──────────────────────────────────────────

  private fun registerDynamic(pod: HostedPod, request: PodRegistrationRequest): PodRegistrationResult {
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
      client = PodClientMetadata(
        // Filtered on the way out, all five of them: a fingerprint hit answers with the stored
        // row, so a value written before the rule that refuses it would otherwise reach the
        // answer. The address matters most, because building the answer *parses* it: a stored
        // fragment or `?state=` would throw where every other value is merely dropped.
        redirectUris = registration.redirectUris.filter(RedirectUri::isValid).toSet(),
        clientName = registration.clientName,
        clientUri = registration.clientUri?.takeIf(ClientMetadataUri::isValid),
        logoUri = registration.logoUri?.takeIf(ClientMetadataUri::isValid),
        softwareId = registration.softwareId,
        softwareVersion = registration.softwareVersion,
        contacts = registration.contacts,
        tosUri = registration.tosUri?.takeIf(ClientMetadataUri::isValid),
        policyUri = registration.policyUri?.takeIf(ClientMetadataUri::isValid),
      ),
    )
  }

  // ─── The service profile ──────────────────────────────────────────────────

  /**
   * A service client: its identifier and its secret. What happens to each member the body carries
   * is `docs/auth/oauth.md` §"Registering a service client"; [REFUSED_MEMBERS] are the ones refused,
   * and anything else this pod does not read is neither stored nor echoed.
   *
   * Without a bearer the registration is provisional, with a deadline for the owner's consent. A
   * bearer is the owner's initial access token (RFC 7591 §3.1): its standing
   * [SERVICE_CLIENTS_MANAGE_SCOPE] authority, approved under the consent that promises registration,
   * makes the registration active. Any other bearer is refused, so a caller that
   * meant to present authority never walks away with a provisional registration it did not expect.
   * The pod's budget is charged only for a provisional registration whose body would be accepted.
   */
  private fun registerService(pod: HostedPod, request: PodRegistrationRequest): PodRegistrationResult {
    val owner = request.caller?.let { caller ->
      when (val check = ownerAuthority.check(pod, caller, SERVICE_CLIENTS_MANAGE_SCOPE, PrivilegedAuthorityRows.SERVICE_CLIENTS_CONSENT)) {
        is PodOwnerAuthorityCheck.Standing -> check.authority
        is PodOwnerAuthorityCheck.Refused -> return PodRegistrationResult.Unauthorized(check.reason)
      }
    }

    REFUSED_MEMBERS.firstOrNull { it in request.raw && isGiven(request.raw[it]) }?.let { member ->
      return refused(
        PodRegistrationError.INVALID_CLIENT_METADATA,
        "a service registration carries no '$member': it authenticates with its secret and is granted contexts by the owner",
      )
    }

    val label = request.client.clientName
      ?: return refused(
        PodRegistrationError.INVALID_CLIENT_METADATA,
        "client_name is required: it is what names this service in the consent that activates it",
      )

    // The rule `/authorize` applies to a public client's addresses, through the same method.
    request.client.redirectUris.firstOrNull { !RedirectUri.isValid(it) }?.let { uri ->
      return refused(
        PodRegistrationError.INVALID_REDIRECT_URI,
        "redirect_uri must be https, or http on a loopback host, with no fragment " +
            "and no code/response/state in the query: $uri",
      )
    }

    // Each registration costs a bcrypt run, and without a bearer nothing authenticates the caller.
    // The owner's own registration is not counted, so anonymous ones cannot hold it up; the
    // protected address budget and the authority bound it.
    if (owner == null && !serviceBudget.tryAcquire(pod.id)) return PodRegistrationResult.RateLimited

    val registered = serviceClients.registerService(pod, label, request.client.redirectUris.toList(), provisional = owner == null)
    val registration = registered.registration

    logger.info {
      "[oauth/register] Service client registered: pod='${pod.name}', clientId='${registration.clientId}', " +
          "label='${LogSafeText.of(label)}', redirectUris=${LogSafeText.of(registration.redirectUris.toString())}, " +
          "activationExpiresAt=${registration.pendingUntil}, by='${owner?.webId}'"
    }

    return PodRegistrationResult.ServiceRegistered(
      clientId = registration.clientId,
      clientName = label,
      issuedAt = registration.createdAt,
      secret = registered.secret,
      redirectUris = registration.redirectUris,
      activationExpiresAt = registration.pendingUntil,
    )
  }

  /** Whether a member carries a value: `null`, an empty string and an empty list say nothing. */
  private fun isGiven(value: Any?): Boolean = when (value) {
    null -> false
    is String -> value.isNotBlank()
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    else -> true
  }

  /**
   * Which client the body asks for, read from the two members that decide it.
   *
   * Read from what arrived rather than from the parsed metadata: the SDK fills in what RFC 7591
   * says a member defaults to, and a default must not turn a request nobody made into a profile
   * this pod serves.
   *
   * [PodClientShape.OTHER] is deliberately wide — `client_secret_post`, `private_key_jwt`, a grant
   * type this pod has never heard of. Each is a request for a client that holds a secret, and
   * answering one as a public registration would hand back a `dyn:` client that silently does
   * something else.
   */
  private fun shapeOf(raw: Map<String, Any?>): PodClientShape {
    val authMethod = (raw["token_endpoint_auth_method"] as? String)?.trim()
    val grantTypes = (raw["grant_types"] as? List<*>)?.mapNotNull { (it as? String)?.trim() }
    return when {
      authMethod == CONFIDENTIAL_AUTH_METHOD && grantTypes == listOf(CLIENT_CREDENTIALS_GRANT) ->
        PodClientShape.SERVICE

      authMethod != null && authMethod != PUBLIC_AUTH_METHOD -> PodClientShape.OTHER
      grantTypes?.any { it !in PUBLIC_GRANT_TYPES } == true -> PodClientShape.OTHER
      else -> PodClientShape.PUBLIC
    }
  }

  private fun wrongProfile(): PodRegistrationResult = refused(
    PodRegistrationError.INVALID_CLIENT_METADATA,
    "a service registers as a confidential client: grant_types [\"$CLIENT_CREDENTIALS_GRANT\"] " +
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

  private companion object {
    private val logger = KotlinLogging.logger {}

    private const val PUBLIC_AUTH_METHOD = "none"
    private const val CONFIDENTIAL_AUTH_METHOD = "client_secret_basic"
    private const val CLIENT_CREDENTIALS_GRANT = "client_credentials"
    private val PUBLIC_GRANT_TYPES = setOf("authorization_code", "refresh_token")

    /**
     * Members a service registration may not carry: a key of its own (`jwks`, `jwks_uri`), a scope
     * it would ask for itself, or a response type for a browser flow it does not have. Contexts are
     * the owner's to grant, and the secret is how it authenticates.
     */
    private val REFUSED_MEMBERS = listOf("jwks", "jwks_uri", "scope", "response_types")
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
   * [client] is the stored registration rather than the submitted body, and its four
   * `ClientMetadataUri` members are already filtered: `null` means the client named none or named
   * one this pod will not hand back.
   */
  data class Registered(val clientId: String, val client: PodClientMetadata) : PodRegistrationResult

  /**
   * A service client, and the one moment its secret can be read.
   *
   * [issuedAt] and [clientId] are the pod's own: [clientName] is whatever the service typed, so an
   * owner shown only that has no way to tell an expected service from a crafted one.
   *
   * @param activationExpiresAt when the registration is removed unless the owner activates it;
   *   `null` for one the owner's authority registered, which is active.
   */
  data class ServiceRegistered(
    val clientId: String,
    val clientName: String,
    val issuedAt: Instant,
    val secret: String,
    val redirectUris: List<String>,
    val activationExpiresAt: Instant?,
  ) : PodRegistrationResult

  /** A bearer came with a service registration and holds no owner authority to register one. */
  data class Unauthorized(val reason: PodOwnerAuthorityRefusal) : PodRegistrationResult

  /**
   * @param description the wire's `error_description`, decided here because two of the three name
   *   the value that was refused.
   */
  data class Refused(val error: PodRegistrationError, val description: String) : PodRegistrationResult

  /** The pod's [PodServiceRegistrationBudget] is spent: the caller retries later. */
  data object RateLimited : PodRegistrationResult
}

/**
 * How fast services may register themselves on one pod — each registration mints a secret at
 * bcrypt cost, and nothing authenticates the caller. The owner's own registrations are not counted.
 * A port, so the budget is decided here and kept by the adapter that keeps the other registration
 * budgets.
 */
fun interface PodServiceRegistrationBudget {
  fun tryAcquire(pod: PodId): Boolean
}

/**
 * RFC 7591 §3.2.2's registration errors.
 *
 * Its own set, and not [OAuthErrorCode][org.sempods.auth.core.OAuthErrorCode], which is scoped to
 * authorize and token responses. The wire spelling is the protocol adapter's — this names which
 * of the two a decision reached.
 */
internal enum class PodRegistrationError {
  INVALID_REDIRECT_URI,
  INVALID_CLIENT_METADATA,
}

/** Which of the two clients `POST {pod}/_system/auth/register` serves a body is asking for. */
internal enum class PodClientShape {

  /** A client that holds no secret: RFC 7591's unauthenticated profile. */
  PUBLIC,

  /** The one confidential shape this pod serves, spelled exactly: a service registering itself. */
  SERVICE,

  /** A client that holds a secret in some other shape, which this pod does not serve. */
  OTHER,
}
