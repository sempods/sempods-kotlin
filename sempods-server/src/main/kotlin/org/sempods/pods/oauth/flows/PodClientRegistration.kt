package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.ClientMetadataUri
import org.sempods.auth.core.RedirectUri
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.ForwardedFor
import org.sempods.mcp.core.BearerChallenge
import org.sempods.pods.HostedPod
import org.sempods.pods.PodId
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.SERVICE_CLIENTS_INSTALL_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.grants.carriesPrivilegedFeature
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PodInstallationAuthorityStore
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
 * **With an installation authority.** The pod owner approved one registration at `/authorize`
 * (`service-clients:install`), and the bearer that carries it may create one confidential client here. The
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
  private val installationBudget: PodInstallationBudget,
) {

  internal fun register(pod: HostedPod, request: PodRegistrationRequest): PodRegistrationResult {
    val shape = shapeOf(request.raw)
    // A caller holding an authority for one named operation is not registering an ordinary client,
    // whatever the body says.
    if (shape == PodClientShape.PUBLIC) {
      return if (request.caller?.carriesPrivilegedFeature == true) wrongProfile() else registerDynamic(pod, request)
    }
    return registerService(pod, request, shape)
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

  // ─── The installation profile ─────────────────────────────────────────────

  /**
   * One registration per approved installation.
   *
   * Everything the body can be refused for is refused before the authority is spent, so a typo in
   * it costs the owner nothing. After that the order is fixed: consume, then ask who owns the pod
   * now, then create. The other order lets two calls arriving together create two clients from one
   * approval, which is the whole point of the authority being one-shot.
   *
   * **Ownership is answered from the row, so it is answered after the authority is gone.** The
   * comparison is the pod's *current* owner against the URIs the consent recognised the person by
   * ([PrivilegedAuthorityRows.Authority.subjectUris][org.sempods.pods.oauth.PrivilegedAuthorityRows.Authority.subjectUris]) — the same question the dialog asked,
   * asked again an hour later. The bearer cannot answer it: it carries one identity URI, and the
   * `also_known_as` link between a person's two WebIDs — sign in with Google, own the pod under
   * the email address — is sempods-auth's and unreachable from here. What the comparison still
   * catches is a pod that changed hands in the hour, and an approval from someone who has since
   * stopped owning the pod is nothing to leave spendable.
   *
   * What the fixed order costs, and what the owner is told to do about it:
   *
   * | What happens | What is left |
   * |---|---|
   * | Two calls arrive together | One client. The other call is answered like a second attempt |
   * | The server dies between consuming and creating | Neither. The owner installs again |
   * | The pod changed hands since the approval | Neither, and the spent authority with it |
   * | The answer is lost on the way back | A client whose secret nobody holds, and no grants. The retry is refused, because the secret exists only in the answer that was lost |
   */
  private fun registerService(
    pod: HostedPod,
    request: PodRegistrationRequest,
    shape: PodClientShape,
  ): PodRegistrationResult {
    val caller = request.caller
      ?: return refused(
        PodRegistrationError.INVALID_CLIENT_METADATA,
        "this endpoint issues no client secret to an unauthenticated caller",
      )
    // The scope this route wants, asked for by name. `carriesPrivilegedFeature` is the catch-all —
    // "an authority for some named operation" — and a second feature scope would pass it while
    // authorizing something else entirely.
    if (SERVICE_CLIENTS_INSTALL_SCOPE !in caller.oauthScopes) {
      return unauthorized(
        PodRegistrationRefusal.NOT_AUTHORIZED,
        "registering a service client needs an authorization carrying '$SERVICE_CLIENTS_INSTALL_SCOPE'",
      )
    }
    if (shape != PodClientShape.INSTALLATION) return wrongProfile()

    request.raw.keys.firstOrNull { it !in INSTALLATION_MEMBERS }?.let { member ->
      return refused(
        PodRegistrationError.INVALID_CLIENT_METADATA,
        "an installation carries no '$member': this pod assigns everything but the name",
      )
    }

    val label = request.client.clientName
      ?: return refused(
        PodRegistrationError.INVALID_CLIENT_METADATA,
        "client_name is required: it is what names this service in the consent that grants it contexts",
      )

    // The budget is charged only by an authority that could still register — unspent, and from
    // the pod's owner now — so a spent token or one a former owner kept cannot hold it empty. And
    // before the authority is spent, so a throttled installation keeps its approval.
    val pending = caller.tokenJti?.let { installationAuthorities.peek(pod.id, it) }
    if (pending != null && pending.isFromOwnerOf(pod) && !installationBudget.tryAcquire(pod.id)) {
      return PodRegistrationResult.RateLimited
    }

    val authority = caller.tokenJti?.let { installationAuthorities.consume(pod.id, it) }
      ?: return unauthorized(
        PodRegistrationRefusal.AUTHORITY_SPENT,
        "this authorization has already registered a service client",
      )

    // The pod's owner as it stands now, against the URIs the consent recognised the person by.
    if (!authority.isFromOwnerOf(pod)) {
      // `legacy` is the one case where this refusal is not about who the person is: a node from
      // before this release recorded no URI set, so an owner recognised through a profile-linked
      // alias cannot be reconstructed from the row — `docs/auth/oauth.md` §"Installing a service
      // client" on finishing the rollout first.
      logger.info {
        "[oauth/register] Installation refused: no URI this authority names owns pod " +
            "'${pod.name}' (legacy=${authority.disconnects == null})"
      }
      return unauthorized(PodRegistrationRefusal.NOT_AUTHORIZED, "this pod's owner installs its service clients")
    }

    val registered = try {
      serviceClients.registerInstallation(pod, label)
    } catch (e: Exception) {
      // The authority is gone and no client exists, which is the row the table above calls "the
      // server dies between consuming and creating" — reached here without the process dying. The
      // caller is told the one thing it can act on: this authorization is spent, install again.
      logger.error(e) { "[oauth/register] Installation failed after its authority was spent: pod='${pod.name}'" }
      return unauthorized(
        PodRegistrationRefusal.AUTHORITY_SPENT,
        "this authorization is spent and its registration did not complete; install again",
      )
    }

    logger.info {
      "[oauth/register] Service client installed: pod='${pod.name}', " +
          "clientId='${registered.registration.clientId}', label='${LogSafeText.of(label)}', " +
          "installer='${LogSafeText.of(caller.oauthClientId ?: "(unset)")}', " +
          "owner='${LogSafeText.of(authority.webId)}'"
    }

    return PodRegistrationResult.ServiceRegistered(
      clientId = registered.registration.clientId,
      clientName = label,
      issuedAt = registered.registration.createdAt,
      secret = registered.secret,
    )
  }

  private fun PrivilegedAuthorityRows.Authority.isFromOwnerOf(pod: HostedPod): Boolean =
    subjectUris.any { podGrantsFacade.isPodOwner(pod, it) }

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
        PodClientShape.INSTALLATION

      authMethod != null && authMethod != PUBLIC_AUTH_METHOD -> PodClientShape.OTHER
      grantTypes?.any { it !in PUBLIC_GRANT_TYPES } == true -> PodClientShape.OTHER
      else -> PodClientShape.PUBLIC
    }
  }

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
     * Everything an installation body may carry. Anything else is refused by name.
     *
     * An allowlist, because the rule is "the installer names nothing but the label" and a list of
     * forbidden members cannot say that — it would admit `client_id`, `scope`, `jwks`,
     * `redirect_uris` and every member the SDK learns next. What each of those would buy the
     * caller is the reason: an identity the owner already trusts, grants the second consent is
     * there to give, a browser flow a client authenticating with a secret does not have.
     */
    private val INSTALLATION_MEMBERS = setOf(
      "client_name",
      "grant_types",
      "token_endpoint_auth_method",
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
   * [client] is the stored registration rather than the submitted body, and its four
   * `ClientMetadataUri` members are already filtered: `null` means the client named none or named
   * one this pod will not hand back.
   */
  data class Registered(val clientId: String, val client: PodClientMetadata) : PodRegistrationResult

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

  /** The pod's [PodInstallationBudget] is spent. The authority is not: the caller retries later. */
  data object RateLimited : PodRegistrationResult
}

/**
 * How fast installations on one pod may spend their authorities — each spend mints a secret at
 * bcrypt cost. A port, so the budget is decided where the authority is and kept by the adapter
 * that keeps the other registration budgets.
 */
fun interface PodInstallationBudget {
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

/**
 * RFC 6750 §3.1's two answers about a bearer that carries the wrong authority.
 *
 * Each carries the code and the status that section pairs it with, so nothing downstream has to
 * map one to the other.
 */
internal enum class PodRegistrationRefusal(val error: String, val status: Int) {

  /**
   * The authorization registered its one service client already, or never carried the right to.
   *
   * `invalid_token` covers "expired, revoked, malformed **or invalid for other reasons**", and a
   * spent one-shot authority is the last of those. A 401 rather than a 403 because the way out is
   * a new authorization, which is what a 401 tells a client to go and get.
   */
  AUTHORITY_SPENT(BearerChallenge.INVALID_TOKEN, 401),

  /** The credential is good and does not cover this. */
  NOT_AUTHORIZED(BearerChallenge.INSUFFICIENT_SCOPE, 403),
}

/** Which of the two clients `POST {pod}/_system/auth/register` serves a body is asking for. */
internal enum class PodClientShape {

  /** A client that holds no secret: RFC 7591's unauthenticated profile. */
  PUBLIC,

  /** The one confidential shape this pod serves, spelled exactly. */
  INSTALLATION,

  /** A client that holds a secret in some other shape, which this pod does not serve. */
  OTHER,
}
