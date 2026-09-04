package org.sempods.api.pod.system.auth

import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.ClientId
import org.sempods.auth.core.ClientMetadataUri
import org.sempods.auth.core.DidWeb
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Pkce
import org.sempods.auth.core.RedirectUri
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.auth.core.Secrets
import com.google.inject.Inject
import com.google.inject.name.Named
import org.sempods.commons.config.Env
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.BasicAuth
import org.sempods.commons.net.ForwardedFor
import org.sempods.commons.net.UrlUtil
import org.sempods.SempodsUriBuilder
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.PersonIdentity
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.PodBrowserCookies
import org.sempods.pods.PodFacade
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.contexts.ContextUriResolution
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.OFFLINE_ACCESS_SCOPE
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.io.IOException
import java.net.URI
import io.github.oshai.kotlinlogging.KotlinLogging

@Path("{pod}/_system/auth")
class PodAuthEndpoint @Inject constructor(
  private val authorizationCodeStore: AuthorizationCodeStore,
  private val podGrantsDao: PodGrantsDao,
  private val podGrantsFacade: PodGrantsFacade,
  private val dynamicClientStore: DynamicClientStore,
  private val templateRenderer: TemplateRenderer,
  private val podTokenIssuer: PodTokenIssuer,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val consentDecisionStore: PodConsentDecisionStore,
  private val tokenRateLimiter: PodTokenRateLimiter,
  private val podContextsDao: PodContextsDao,
  private val podServiceClientStore: PodServiceClientStore,
  private val identityProvider: PodIdentityProvider,
  private val loginStateStore: PodLoginStateStore,
  private val consentTransactionStore: ConsentTransactionStore,
  private val webIdUriDeriver: WebIdUriDeriver,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  // ─── OAuth discovery (RFC 8414) ──────────────────────────────────────────
  // Lives here (not on PodOAuthMetadataEndpoint) because JAX-RS routes sub-paths of
  // `{pod}/_system/auth/*` exclusively to this class. The body is shared with the
  // RFC-strict sibling endpoint (see buildAuthorizationServerMetadata).

  @GET
  @Path(".well-known/oauth-authorization-server")
  @Produces(MediaType.APPLICATION_JSON)
  fun authorizationServerMetadata(@PathParam("pod") pod: String): Response =
    buildAuthorizationServerMetadata(fetchPodOrThrow(pod), config.apiBaseUrl)

  // ─── OAuth Dynamic Client Registration (RFC 7591) ────────────────────────
  // MCP 2025-06-18 requires clients to be able to self-register. We issue an opaque
  // `dyn:<random>` client_id and persist the submitted metadata (fingerprint-deduped:
  // identical fingerprint inputs return the existing clientId rather than minting a
  // new one) so re-registrations from the same logical client stay anchored to one
  // row. The historical record stays available for Stage-2 agent-identity derivation.
  // token_endpoint_auth_method is always "none" — we rely on PKCE, not client secrets.

  // One route: a pod has one registration endpoint, which is what `registration_endpoint`
  // in AS-metadata points at.

  @POST
  @Path("register")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  fun register(
    @PathParam("pod") pod: String,
    @HeaderParam("User-Agent") userAgent: String?,
    @HeaderParam("X-Forwarded-For") forwardedFor: String?,
    request: Map<String, Any?>?,
  ): Response = doRegister(pod, userAgent, forwardedFor, request)

  private fun doRegister(
    pod: String,
    userAgent: String?,
    forwardedFor: String?,
    request: Map<String, Any?>?,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)

    val redirectUris = (request?.get("redirect_uris") as? List<*>)
      ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotBlank() } }
      ?.toSet()
      ?: emptySet()

    if (redirectUris.isEmpty()) {
      return Response.status(400)
        .entity(
          mapOf(
            "error" to "invalid_redirect_uri",
            "error_description" to "at least one redirect_uri is required",
          )
        )
        .type(MediaType.APPLICATION_JSON)
        .build()
    }

    // The rule `/authorize` applies, through the same method: an address stored here that
    // `isAllowedRedirectUri` would refuse is a registration no login can honour.
    redirectUris.forEach { uri ->
      if (!RedirectUri.isValid(uri)) {
        return Response.status(400)
          .entity(
            mapOf(
              "error" to "invalid_redirect_uri",
              "error_description" to
                  "redirect_uri must be https, or http on a loopback host, with no fragment " +
                  "and no code/response/state in the query: $uri",
            )
          )
          .type(MediaType.APPLICATION_JSON)
          .build()
      }
    }

    val clientName = (request?.get("client_name") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val clientUri = (request?.get("client_uri") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val logoUri = (request?.get("logo_uri") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val softwareId = (request?.get("software_id") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val softwareVersion = (request?.get("software_version") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val tosUri = (request?.get("tos_uri") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val policyUri = (request?.get("policy_uri") as? String)?.trim()?.takeIf { it.isNotBlank() }
    val contacts = (request?.get("contacts") as? List<*>)
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
        return Response.status(400)
          .entity(
            mapOf(
              // A literal like the `invalid_redirect_uri` above: RFC 7591's registration errors are
              // their own set, and `OAuthErrorCode` is scoped to authorize and token responses.
              "error" to "invalid_client_metadata",
              "error_description" to
                  "$field must be https, or http on a loopback host: $value",
            )
          )
          .type(MediaType.APPLICATION_JSON)
          .build()
      }
    }

    val registration = dynamicClientStore.register(
      registeredForPodId = checkNotNull(podDbo.id),
      registeredForPodName = podDbo.name,
      redirectUris = redirectUris,
      clientName = clientName,
      clientUri = clientUri,
      logoUri = logoUri,
      softwareId = softwareId,
      softwareVersion = softwareVersion,
      contacts = contacts,
      tosUri = tosUri,
      policyUri = policyUri,
      rawRequest = request ?: emptyMap(),
      remoteAddr = ForwardedFor.clientIp(forwardedFor),
      userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() },
    )

    // TODO: full DCR profile on INFO while Stage 1 observes real agents; drop back to FINE once
    //  Stage 2 pins the per-agent identity model. The second line below logs the whole submitted
    //  body, which is caller-controlled text on an unauthenticated endpoint — the log volume is
    //  theirs to choose, not this server's.
    val action = if (registration.deduplicatedFromRegisteredAt != null) {
      "Dynamic client dedup hit (reused existing registration from ${registration.deduplicatedFromRegisteredAt})"
    } else {
      "Dynamic client registered"
    }
    // Escaped where nothing has vouched for the characters. The four `*Uri` members went through
    // `ClientMetadataUri.isValid` and the redirect URIs through `RedirectUri.isValid`, both of
    // which parse a `java.net.URI` — which admits no control character and no U+2028. The free-text
    // members and the submitted keys have no such check behind them, and the body has none at all.
    logger.info {
      "[oauth/register] $action: pod='$pod', clientId='${registration.clientId}', " +
          "clientName='${LogSafeText.of(registration.clientName ?: "(unset)")}', " +
          "softwareId='${LogSafeText.of(registration.softwareId ?: "(unset)")}', " +
          "softwareVersion='${LogSafeText.of(registration.softwareVersion ?: "(unset)")}', " +
          "clientUri='${registration.clientUri ?: "(unset)"}', " +
          "logoUri='${registration.logoUri ?: "(unset)"}', " +
          "tosUri='${registration.tosUri ?: "(unset)"}', " +
          "policyUri='${registration.policyUri ?: "(unset)"}', " +
          "redirectUris=${registration.redirectUris.toList()}, " +
          "contacts=${LogSafeText.of(registration.contacts.toString())}, " +
          "rawRequestKeys=${LogSafeText.of(registration.rawRequest.keys.sorted().toString())}"
    }
    logger.info { "[oauth/register] full request body: ${LogSafeText.of(registration.rawRequest.toString())}" }

    val body = linkedMapOf<String, Any?>(
      "client_id" to registration.clientId,
      "redirect_uris" to registration.redirectUris.toList(),
      "token_endpoint_auth_method" to "none",
      "grant_types" to listOf("authorization_code", "refresh_token"),
      "response_types" to listOf("code"),
    )
    if (registration.clientName != null) body["client_name"] = registration.clientName
    // Filtered on the way out as well — see [ClientMetadataUri], which says why the check at
    // registration does not cover the row this may be reading.
    registration.clientUri?.takeIf(ClientMetadataUri::isValid)?.let { body["client_uri"] = it }
    registration.logoUri?.takeIf(ClientMetadataUri::isValid)?.let { body["logo_uri"] = it }
    if (registration.softwareId != null) body["software_id"] = registration.softwareId
    if (registration.softwareVersion != null) body["software_version"] = registration.softwareVersion
    if (registration.contacts.isNotEmpty()) body["contacts"] = registration.contacts
    registration.tosUri?.takeIf(ClientMetadataUri::isValid)?.let { body["tos_uri"] = it }
    registration.policyUri?.takeIf(ClientMetadataUri::isValid)?.let { body["policy_uri"] = it }

    return Response.status(201).entity(body).type(MediaType.APPLICATION_JSON).build()
  }

  // ─── OAuth authorize ──────────────────────────────────────────────────────

  @GET
  @Path("authorize")
  fun authorize(
    @PathParam("pod") pod: String,
    @QueryParam("response_type") responseType: String?,
    @QueryParam("client_id") clientId: String?,
    @QueryParam("redirect_uri") redirectUri: String?,
    @QueryParam("state") state: String?,
    @QueryParam("code_challenge") codeChallenge: String?,
    @QueryParam("code_challenge_method") codeChallengeMethod: String?,
    @QueryParam("prompt") prompt: String?,
    @QueryParam("scope") scope: String?,
    @CookieParam(PodBrowserCookies.SESSION) sessionCookie: String?,
  ): Response = runAuthorize(
    pod = pod,
    responseType = responseType,
    clientId = clientId,
    redirectUri = redirectUri,
    state = state,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    prompt = prompt,
    scope = scope,
    // Who the pod already knows, from a cookie on its own origin. Never from a parameter a browser
    // carried — that was the arrangement the OIDC cutover removed. A session saves the round trip
    // to the id-server and is what makes `prompt=none` answerable at all.
    session = readSession(pod, sessionCookie),
  )

  /**
   * The authorization flow, entered twice for one sign-in: once by the client's browser with no
   * identity, and once by [oidcCallback] with the one the id-server asserted.
   *
   * Everything is re-validated on the second entry rather than trusted from the first. The request
   * was parked for up to fifteen minutes, and the pod's clients, grants and public contexts can
   * have changed in that time.
   */
  private fun runAuthorize(
    pod: String,
    responseType: String?,
    clientId: String?,
    redirectUri: String?,
    state: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    prompt: String?,
    scope: String?,
    session: PodTokenIssuer.SessionPrincipal?,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)
    val sessionIdentity = session?.let { PersonIdentity(webId = it.webId, alsoKnownAs = it.alsoKnownAs) }

    // R6: audit-log every authorize entry so cross-client spikes can replay the
    // exact request shape per MCP client. One line per request, kept short — the
    // outcome is logged separately by the matching error/issue path.
    // Ahead of every check below, which is the point of an audit line — and therefore ahead of
    // `readClientId`, so all four of these are raw query parameters here.
    logger.info {
      "[oauth/authorize-audit] outcome=start pod='${podDbo.name}' " +
          "client_id='${LogSafeText.of(clientId ?: "(none)")}' " +
          "redirect_uri='${LogSafeText.of(redirectUri ?: "(none)")}' " +
          "prompt='${LogSafeText.of(prompt ?: "(unset)")}' " +
          "scope='${LogSafeText.of(scope ?: "(unset)")}' " +
          "signed_in=${session != null}"
    }

    // ── Validate required params ──────────────────────────────────────────
    // Order is load-bearing. Until the redirect_uri is known to belong to the client that named
    // it, nothing may be *delivered* by redirecting there — not even an error. Reporting an
    // unknown client_id by redirecting to the address the same request supplied would make this
    // endpoint an open redirector on the pod's own origin, usable to launder a link through a
    // host a user trusts. So: address first, client second, and only then may `oauthError` be
    // reached at all. `sempods-auth-core`'s error model encodes this rule in its types; this
    // endpoint moves onto it when it moves onto the shared authorize path.
    val normalizedRedirectUri = redirectUri?.trim()?.takeIf { it.isNotBlank() }
      ?: return Response.status(400).entity("missing redirect_uri").type("text/plain").build()

    // Both refusals stay *direct* responses, for the reason stated above: the client is not yet
    // known to own the address it named, so neither may be delivered by redirecting there — an
    // unregistered client cannot be reported through `oauthError`. What changes is only which of
    // them is said.
    val normalizedClientId = when (val client = readClientId(podDbo, clientId)) {
      is ClientIdentity.Known -> client.clientId
      ClientIdentity.Unregistered -> {
        logger.info {
          "[oauth/authorize-audit] outcome=error error=invalid_client " +
              "error_description=\"client_id is not registered at this pod\" " +
              "pod='${podDbo.name}' client_id='${clientId?.trim()}' state=${state ?: "(none)"}"
        }
        return Response.status(400).entity(UNREGISTERED_CLIENT_MESSAGE).type("text/plain").build()
      }

      ClientIdentity.Malformed -> return Response.status(400)
        .entity("client_id must be a did:web or dyn: identity").type("text/plain").build()
    }

    if (!isAllowedRedirectUri(podDbo, normalizedClientId, normalizedRedirectUri)) {
      return Response.status(400).entity("redirect_uri not allowed for this client_id").type("text/plain").build()
    }

    // The AS metadata advertises `response_types_supported: ["code"]`, and this is the endpoint
    // that has to make that true. The parameter was bound and never read, so anything at all —
    // including `token`, the implicit grant this project does not implement — reached the code
    // path for `code` and got an authorization code back. Now that the redirect address is
    // validated, the error can travel the way RFC 6749 §4.1.2.1 asks for.
    val requestedResponseType = responseType?.trim().orEmpty()
    if (requestedResponseType != "code") {
      return oauthError(
        normalizedRedirectUri, OAuthErrorCode.UNSUPPORTED_RESPONSE_TYPE,
        "response_type must be 'code'", state,
      )
    }

    // PKCE is mandatory for dynamic (public) clients. RFC 7591 dynamic clients always register
    // with `token_endpoint_auth_method=none`, so without PKCE an intercepted auth code can be
    // redeemed by anyone. Reject early before issuing a code.
    val trimmedCodeChallenge = codeChallenge?.trim()?.takeIf { it.isNotBlank() }
    val trimmedCodeChallengeMethod = codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() }
    if (normalizedClientId.startsWith("dyn:") && trimmedCodeChallenge == null) {
      return oauthError(
        normalizedRedirectUri, OAuthErrorCode.INVALID_REQUEST,
        "code_challenge is required for dynamic clients (PKCE)", state,
      )
    }
    // A challenge with a method this server cannot verify is refused here rather than at the
    // exchange. `S256` is case-sensitive (RFC 7636 §4.3) and it is the only method OAuth 2.1
    // allows, so `plain`, `s256` and an absent method are all unusable — and used to be found out
    // only at `/token`, after a code had been minted and the browser was gone. The client can act
    // on it here.
    if (trimmedCodeChallenge != null && !Pkce.isSupportedMethod(trimmedCodeChallengeMethod)) {
      return oauthError(
        normalizedRedirectUri, OAuthErrorCode.INVALID_REQUEST,
        "code_challenge_method must be ${Pkce.METHOD_S256}", state,
      )
    }

    // ── Parse `prompt` (multi-valued, space-separated per OIDC Core 1.0 §3.1.2.1) ──
    val promptValues = OAuthSyntax.parsePrompt(prompt)
    if (OAuthSyntax.isContradictoryPrompt(promptValues)) {
      // Spec: `none` is exclusive — if combined with anything else it's a request error.
      return oauthError(
        normalizedRedirectUri, OAuthErrorCode.INVALID_REQUEST,
        "prompt=none cannot be combined with other prompt values", state,
      )
    }

    // ── Parse requested scope (validation deferred) ───────────────────────
    // Parsing is cheap and infallible. Validation comes after JWT resolution
    // so the precedence is: invalid JWT > invalid scope > missing JWT. That
    // way `scope=public-read` cannot mask a manipulated token, and a
    // malformed `scope` on an unauthenticated request still yields
    // `invalid_scope` instead of `login_required`.
    val requestedScopes = OAuthSyntax.parseScope(scope)

    // ── R1: forced re-authentication ──────────────────────────────────────
    // OIDC Core 1.0 §3.1.2.1 — `prompt=login` and `prompt=select_account` ask the upstream
    // provider to re-prompt. Honoured by discarding whatever identity is in hand and falling
    // through into the login branch below, which forwards the value to the id-server. The
    // re-entry after that login carries the prompt set with those values already removed, so it
    // takes the authenticated path instead of looping.
    // `prompt=login` must not be satisfied by a session either: the person asked to prove
    // themselves again, and a cookie is exactly what they are asking to bypass.
    val forceReauth = "login" in promptValues || "select_account" in promptValues
    val identity = if (forceReauth) null else sessionIdentity

    // ── Public-read request validation / anonymous shortcut ────────────────
    // `public-read` is an additive scope and may be combined with per-context
    // grants. The only remaining special case is anonymous
    // `scope=public-read&prompt=none`, which has no user identity to persist
    // consent against and therefore receives a short-lived public-read token.
    if (PUBLIC_READ_SCOPE in requestedScopes) {
      val publicContexts = podFacade.getPublicContexts(podName = podDbo.name)
      if (publicContexts.isEmpty()) {
        return oauthError(
          normalizedRedirectUri, OAuthErrorCode.CONSENT_REQUIRED,
          "pod has no public-read contexts", state,
        )
      }
      val anonymousPublicReadWebId =
        if (identity == null && requestedScopes == setOf(PUBLIC_READ_SCOPE) && "none" in promptValues) {
          "urn:sempods:anon:${java.util.UUID.randomUUID()}"
        } else null
      if (anonymousPublicReadWebId != null) {
        logger.info {
          "[oauth/authorize] public-read code issued: pod='${podDbo.name}', " +
              "clientId='$normalizedClientId', webId='$anonymousPublicReadWebId', anonymous=true"
        }
        return issueAuthCodeAndRedirect(
          podDbo = podDbo,
          clientId = normalizedClientId,
          webId = anonymousPublicReadWebId,
          scopes = setOf(PUBLIC_READ_SCOPE),
          redirectUri = normalizedRedirectUri,
          state = state,
          codeChallenge = trimmedCodeChallenge,
          codeChallengeMethod = trimmedCodeChallengeMethod,
          logPrefix = "[oauth/public-read/anon]",
        )
      }
    }

    // ── Missing JWT → login flow (after scope is known to be well-formed) ─
    if (identity == null) {
      // prompt=none is `login_required` per spec: this server holds no session, so it cannot
      // answer without the interaction the parameter forbids. With prompt=none combined with
      // login/select_account we already errored out above as `invalid_request`, so the prompt set
      // is consistent here.
      //
      // TODO: silent re-authorization used to appear to work by reading an identity token out of
      //   the request URL — the same parameter that let the id-server hand that token to anyone.
      //   Bringing the outcome back properly means a pod-side session established by the callback
      //   below (an HttpOnly cookie on the pod origin), which the consent POST would then also
      //   ride instead of the one-time ticket. That needs CSRF protection on the form, so it is
      //   its own change rather than a rider on this one.
      if ("none" in promptValues) {
        return oauthError(normalizedRedirectUri, OAuthErrorCode.LOGIN_REQUIRED, "user is not authenticated", state)
      }
      // Federate the login to the id-server as an ordinary OIDC relying party. The whole request
      // stays here, under a `state` this server minted; what comes back through the browser is a
      // single-use code, and the identity is fetched over a back channel with a verifier that
      // never left this process.
      //
      // It used to put this request's own URI into a `return_to` parameter and let the id-server
      // append an identity token to it — an address the id-server accepted from anyone, which is
      // what made that token collectable by whoever asked.
      val relyingParty = try {
        identityProvider.relyingParty(podDbo.name)
      } catch (e: Exception) {
        logger.warn(e) { "[oauth/authorize] identity provider discovery failed: pod='${podDbo.name}'" }
        return Response.status(503).entity("identity provider unavailable").type("text/plain").build()
      }
      // Forward `prompt=login` / `prompt=select_account` so the upstream provider re-prompts (OIDC
      // Core 1.0 §3.1.2.1). If both are set, prefer `select_account`: it is the more specific
      // signal and implies login as well. Apple does not document `prompt`, so forced
      // re-authentication cannot currently be guaranteed for an Apple login; see the TODO on
      // `AppleOidcClient.authorizeUrl`.
      val forwardedPrompt = when {
        "select_account" in promptValues -> "select_account"
        "login" in promptValues -> "login"
        else -> null
      }
      val loginState = loginStateStore.newState()
      val started = relyingParty.beginAuthorization(prompt = forwardedPrompt, state = loginState)
      // The `state` ties the callback to this request; it does not tie it to this *browser*, and
      // it is a bearer — a login URL captured by one party would otherwise complete in somebody
      // else's browser and hand them a session for the wrong identity. This is that second factor.
      val browserPin = Secrets.newSecret()
      loginStateStore.create(
        started.state,
        PodLoginStateStore.Pending(
          pod = podDbo.name,
          clientId = normalizedClientId,
          redirectUri = normalizedRedirectUri,
          clientState = state,
          scope = scope,
          // The force-reauth values are satisfied by the login now beginning, and carrying them
          // back would send the user straight into another one. `consent` and the rest survive,
          // because a login does not satisfy them.
          prompt = promptValues.minus(OAuthSyntax.FORCE_REAUTH_PROMPTS).sorted().joinToString(" ").takeIf { it.isNotEmpty() },
          codeChallenge = trimmedCodeChallenge,
          codeChallengeMethod = trimmedCodeChallengeMethod,
          codeVerifier = started.codeVerifier,
          nonce = started.nonce,
          browserPin = browserPin,
        ),
      )
      logger.info {
        "[oauth/authorize] Redirecting to login: pod='${podDbo.name}', " +
            "clientId='$normalizedClientId', forceReauth=$forceReauth, forwardedPrompt=${forwardedPrompt ?: "(none)"}"
      }
      logger.info {
        "[oauth/authorize-audit] outcome=login_redirect pod='${podDbo.name}' " +
            "client_id='$normalizedClientId' force_reauth=$forceReauth " +
            "forwarded_prompt='${forwardedPrompt ?: "(none)"}'"
      }
      return Response.temporaryRedirect(URI(started.authorizationUrl))
        .cookie(cookies.loginPin(podDbo.name, started.state, browserPin, LOGIN_PIN_TTL_SECONDS))
        .build()
    }

    logger.info {
      "[oauth/authorize] JWT verified: pod='${podDbo.name}', clientId='$normalizedClientId', " +
          "webId='${identity.webId}', prompt=${promptValues.sorted().joinToString(" ").ifEmpty { "(unset)" }}, " +
          "scope=${LogSafeText.of(requestedScopes.sorted().joinToString(" ")).ifEmpty { "(unset)" }}"
    }

    // ── Resolve user's available contexts and existing grants ────────────
    val podBaseUrl = "${config.apiBaseUrl}${podDbo.name}/"
    val isOwner = podGrantsFacade.isPodOwner(podDbo, identity.allUris)
    val userGrants = podGrantsFacade.resolveUserGrants(podDbo, identity.allUris, podBaseUrl)

    val podId = checkNotNull(podDbo.id)
    // Deliberately the subject's own rows, not the person's. Auto-grant issues a code for this
    // WebID and does not re-key what it finds, while `resolveFromGrants` and the refresh path both
    // query the token's subject — so counting an alias's rows here would auto-grant a token with no
    // context permissions whose first refresh fails. Whether an app holds anything *at all* is a
    // different question, and `holdsAnything` is where it is asked.
    val existingGrants = podGrantsDao.fetchGrantStrings(podId, normalizedClientId, listOf(identity.webId))

    logger.info {
      "[oauth/authorize] Grants pre-check: pod='${podDbo.name}', clientId='$normalizedClientId', " +
          "webId='${identity.webId}', isOwner=$isOwner, ownerExpected='${podDbo.owner}', " +
          "alsoKnownAs=${identity.alsoKnownAs}, userGrants=${userGrants.size}, " +
          "existingGrants=${existingGrants.size}"
    }

    // ── Auto-grant when existing grants cover the request ────────────────
    // prompt=none or prompt unset: skip consent UI if grants exist.
    // prompt=consent: always show consent UI (user explicitly wants to review).
    // Dynamic clients (`dyn:` — RFC 7591): ALWAYS render consent on /authorize.
    // Rationale: MCP clients only hit /authorize when the user just triggered an
    // OAuth flow (reconnect, explicit re-auth), so the user is already attentive
    // and benefits from seeing+adjusting grants every time. Existing grants are
    // pre-checked in the dialog, so repeat flows are one-click. /token exchanges
    // are unaffected (no dialog there), so in-session token refreshes stay silent.
    val isDynamicClient = normalizedClientId.startsWith("dyn:")
    // An authorization that predates the lifetime control has no decision recorded, and this branch
    // renders nothing — so it could never acquire one: it would keep working, short-lived, for ever,
    // without anybody being asked. Once, therefore, it falls through to the dialog instead. Only
    // where there is a dialog to fall through to: `prompt=none` has none, and answering it with
    // `consent_required` would retire a silent re-authorization that works today, so it keeps its
    // code and receives what an absent decision means anyway — an access token and nothing else.
    val decisionRecorded =
      consentDecisionStore.find(podId, normalizedClientId, listOf(identity.webId)) != null
    val mayAutoGrant = decisionRecorded || "none" in promptValues
    if ("consent" !in promptValues && !isDynamicClient && existingGrants.isNotEmpty()) {
      // Re-issue auth-code when the user still has a grant for this app. Per-context grants
      // stay in the durable store and are resolved server-side per request; public-read is an
      // additive persisted grant, still valid as long as the pod has public contexts.
      val effectiveContextGrants = existingGrants.intersect(userGrants)
      val effectivePublicReadScope = if (
        PUBLIC_READ_SCOPE in existingGrants &&
        podFacade.getPublicContexts(podName = podDbo.name).isNotEmpty()
      ) {
        setOf(PUBLIC_READ_SCOPE)
      } else {
        emptySet()
      }
      // Persist the narrowed set. `PodGrantsFacade` cascades an owner-level revocation into these
      // rows already, so this is a repair path rather than the primary enforcement: it is the
      // idempotent second chance for a cascade write that never landed (no Mongo transactions
      // here), and it keeps the store from carrying grants this branch has just decided are stale.
      // The facade re-derives after writing, so `persisted` may be narrower still if an
      // owner-level change raced us.
      var persisted = effectiveContextGrants + effectivePublicReadScope
      if (persisted.size != existingGrants.size) {
        persisted = podGrantsFacade.replaceAppGrants(
          podDbo = podDbo,
          appId = normalizedClientId,
          webId = identity.webId,
          subjectUris = identity.allUris,
          grants = persisted,
          grantedBy = identity.webId,
        )
        logger.info {
          "[oauth/auto-grant] Narrowed stale grants: pod='${podDbo.name}', clientId='$normalizedClientId', " +
              "webId='${identity.webId}', before=${existingGrants.size}, after=${persisted.size}"
        }
      }
      // Auto-grant if anything is still granted and the person has answered once; the slim token
      // carries only feature scopes. Falls through to the consent UI when nothing survived, rather
      // than handing out a token that authorizes nothing — and when nothing has been answered,
      // which is the dialog this authorization needs. The repair above happens either way: it is
      // what a failed cascade is owed, and it has nothing to do with which of the two follows.
      if (persisted.isNotEmpty() && mayAutoGrant) {
        return issueAuthCodeAndRedirect(
          podDbo = podDbo,
          clientId = normalizedClientId,
          webId = identity.webId,
          scopes = effectivePublicReadScope,
          redirectUri = normalizedRedirectUri,
          state = state,
          codeChallenge = codeChallenge?.trim()?.takeIf { it.isNotBlank() },
          codeChallengeMethod = codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() },
          logPrefix = "[oauth/auto-grant]",
          // The subject's own document, because that is the one redemption will read: the newest
          // across the person's URIs is what the dialog wants, and binding to it would refuse a
          // code the moment an alias carried a higher count.
          consentGeneration = consentDecisionStore
            .find(podId, normalizedClientId, listOf(identity.webId))?.generation,
        )
      }
    }

    // prompt=none but no (valid) grants → error.
    if ("none" in promptValues) {
      // Soft-fail: "no app-specific scopes" is always recoverable — the caller
      // can re-authorize interactively or with scope=public-read once the pod
      // publishes public contexts. Hard `access_denied` is reserved for signals
      // that mean "no, never" (invalid JWT, explicit user-side refusal at the
      // consent UI), not for "currently nothing matches".
      if (userGrants.isEmpty()) {
        val publicContexts = podFacade.getPublicContexts(podName = podDbo.name)
        val desc = if (publicContexts.isNotEmpty()) {
          "no app-specific scopes; re-authorize with scope=$PUBLIC_READ_SCOPE for read-only access"
        } else {
          "no app-specific scopes available for this user"
        }
        return oauthError(normalizedRedirectUri, OAuthErrorCode.CONSENT_REQUIRED, desc, state)
      }
      return oauthError(
        normalizedRedirectUri, OAuthErrorCode.CONSENT_REQUIRED, "user has not granted access to this app", state,
      )
    }

    // Non-owner with no scopes: soft-fail. When the pod has public contexts,
    // render the consent UI with public-read pre-selected — that's the
    // interactive read-only path for a stranger reaching a pod for the first
    // time. Without public contexts there's nothing they could possibly
    // consent to; keep the error.
    if (!isOwner && userGrants.isEmpty()) {
      val publicContexts = podFacade.getPublicContexts(podName = podDbo.name)
      if (publicContexts.isEmpty()) {
        return oauthError(
          normalizedRedirectUri, OAuthErrorCode.CONSENT_REQUIRED,
          "no app-specific scopes available for this user", state,
        )
      }
      return renderConsentUi(
        podDbo = podDbo,
        identity = identity,
        normalizedClientId = normalizedClientId,
        normalizedRedirectUri = normalizedRedirectUri,
        state = state,
        codeChallenge = codeChallenge?.trim()?.takeIf { it.isNotBlank() },
        codeChallengeMethod = codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() },
        publicContexts = publicContexts.map { it.toString() }.sorted(),
        publicReadPreselected = true,
        durableRequested = OFFLINE_ACCESS_SCOPE in requestedScopes,
        userGrants = emptySet(),
        existingGrants = emptySet(),
        isOwner = false,
      )
    }

    // ── Render consent page ──────────────────────────────────────────────
    val publicContextsForUi = podFacade.getPublicContexts(podName = podDbo.name).map { it.toString() }.sorted()
    // Public-Read default: ticked unless the caller already has explicit
    // grants and `public-read` is not among them (i.e. the user previously
    // unticked it). For first-time consent (no existing grants), default
    // ticked — that's the additive-model expectation.
    val publicReadPreselected = if (existingGrants.isEmpty()) true
    else PUBLIC_READ_SCOPE in existingGrants
    return renderConsentUi(
      podDbo = podDbo,
      identity = identity,
      normalizedClientId = normalizedClientId,
      normalizedRedirectUri = normalizedRedirectUri,
      state = state,
      codeChallenge = codeChallenge?.trim()?.takeIf { it.isNotBlank() },
      codeChallengeMethod = codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() },
      publicContexts = publicContextsForUi,
      publicReadPreselected = publicReadPreselected,
      durableRequested = OFFLINE_ACCESS_SCOPE in requestedScopes,
      userGrants = userGrants,
      existingGrants = existingGrants,
      isOwner = isOwner,
    )
  }

  /**
   * Renders the consent HTML. Used by the normal authorize flow (per-context
   * scope checkboxes + optional public-read toggle) and by the public-read
   * path (`scope=public-read&prompt=consent`, where `publicReadPreselected=true`).
   *
   * For the public-read path, [userGrants] / [existingGrants] are not relevant
   * and default to empty — the template only shows the public-read section.
   */
  private fun renderConsentUi(
    podDbo: PodDbo,
    identity: PersonIdentity,
    normalizedClientId: String,
    normalizedRedirectUri: String,
    state: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    publicContexts: List<String>,
    publicReadPreselected: Boolean,
    durableRequested: Boolean = false,
    userGrants: Set<String> = emptySet(),
    existingGrants: Set<String> = emptySet(),
    isOwner: Boolean = false,
  ): Response {
    val contexts = buildConsentContexts(userGrants, existingGrants)
    val registration = if (normalizedClientId.startsWith("dyn:")) {
      dynamicClientStore.lookup(checkNotNull(podDbo.id), normalizedClientId)
    } else null
    val displayName = registration?.clientName?.takeIf { it.isNotBlank() } ?: normalizedClientId
    val podBaseUrl = "${config.apiBaseUrl}${podDbo.name}/"

    // What the person decided last time outranks what the client asked for this time: a request
    // cannot quietly re-tick a box somebody cleared. With nothing recorded the request decides,
    // which is all `offline_access` does — it preselects, it does not grant.
    val recordedDurable = consentDecisionStore
      .find(checkNotNull(podDbo.id), normalizedClientId, identity.allUris)
      ?.durable
    val durablePreselected = recordedDurable ?: durableRequested

    logger.info {
      "[oauth/authorize] Showing consent UI: pod='${podDbo.name}', clientId='$normalizedClientId', " +
          "clientName='${registration?.clientName ?: "(unset)"}', " +
          "webId='${identity.webId}', availableContexts=${contexts.size}, " +
          "publicContexts=${publicContexts.size}, publicReadPreselected=$publicReadPreselected, " +
          "durablePreselected=$durablePreselected"
    }
    logger.info {
      "[oauth/authorize-audit] outcome=consent_ui pod='${podDbo.name}' " +
          "client_id='$normalizedClientId' web_id='${identity.webId}' " +
          "available_contexts=${contexts.size} existing_grants=${existingGrants.size} " +
          "public_read_preselected=$publicReadPreselected durable_preselected=$durablePreselected"
    }
    // Removing an app's access is only on offer where there is something to remove. Saying it
    // happened on a first authorization would be the same lie as saying nothing happened on a
    // later one. Asked over the person rather than over this URI, because that is what the action
    // itself clears.
    val disconnectAvailable = holdsAnything(podDbo, normalizedClientId, identity)

    val consentAction = "${config.apiBaseUrl}${podDbo.name}/_system/auth/authorize/consent"
    val html = templateRenderer.render(
      "consent", mapOf(
        "consentAction" to consentAction,
        "clientId" to normalizedClientId,
        "clientName" to displayName,
        // The consumer [ClientMetadataUri] exists for: whatever a template does with these, the
        // value reached it through that check.
        "clientUri" to (registration?.clientUri?.takeIf(ClientMetadataUri::isValid) ?: ""),
        "logoUri" to (registration?.logoUri?.takeIf(ClientMetadataUri::isValid) ?: ""),
        "redirectUri" to normalizedRedirectUri,
        "state" to (state?.trim()?.takeIf { it.isNotBlank() } ?: ""),
        "codeChallenge" to (codeChallenge ?: ""),
        "codeChallengeMethod" to (codeChallengeMethod ?: ""),
        // One screen, once — see [ConsentTransactionStore]. Not a credential on its own: spending
        // it also requires the session cookie it was rendered beside.
        // Bound to the subject's own document, which is what the submission will be compared
        // against — the newest across the person's URIs is what the control above wants.
        "csrfToken" to consentTransactionStore.issue(
          podDbo.name,
          identity.webId,
          consentDecisionStore.find(checkNotNull(podDbo.id), normalizedClientId, listOf(identity.webId))
            ?.generation,
        ),
        "webId" to identity.webId,
        "contexts" to contexts,
        "podBaseUrl" to podBaseUrl,
        // For the preview the form shows while a context is being typed. The posted value is the
        // relative path — [consent] builds the IRI, here and nowhere else.
        "contextPathPrefix" to SempodsUriBuilder.CONTEXT_PATH_PREFIX,
        // The reserved names, so the form can say *why* a name is refused instead of the server
        // silently dropping it from the grant list. Passed as data rather than hardcoded in the
        // template: a change to [ContextPathRules] then reaches the dialog without anyone
        // remembering to edit it. The server stays the authority either way.
        "reservedSegment" to ContextPathRules.RESERVED_SEGMENT,
        "delegationTypes" to ContextPathRules.DELEGATION_TYPES.joinToString(","),
        "implementedTypes" to ContextPathRules.IMPLEMENTED_TYPES.joinToString(","),
        "isOwner" to isOwner,
        "publicContexts" to publicContexts,
        "publicReadAvailable" to publicContexts.isNotEmpty(),
        "publicReadPreselected" to publicReadPreselected,
        "publicReadScope" to PUBLIC_READ_SCOPE,
        "durablePreselected" to durablePreselected,
        "disconnectAvailable" to disconnectAvailable,
      ))
    return Response.ok(html, MediaType.TEXT_HTML).build()
  }


  // ─── OAuth consent (form submit) ──────────────────────────────────────────

  @POST
  @Path("authorize/consent")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  fun consent(
    @PathParam("pod") pod: String,
    @FormParam("client_id") clientId: String?,
    @FormParam("redirect_uri") redirectUri: String?,
    @FormParam("state") state: String?,
    @FormParam("code_challenge") codeChallenge: String?,
    @FormParam("code_challenge_method") codeChallengeMethod: String?,
    @FormParam("csrf") csrf: String?,
    @CookieParam(PodBrowserCookies.SESSION) sessionCookie: String?,
    @FormParam("scope") scopes: List<String>?,
    @FormParam("new_context") newContexts: List<String>?,
    @FormParam("new_context_scope") newContextScopes: List<String>?,
    @FormParam("durable") durable: String?,
    @FormParam("action") action: String?,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)

    // Same split as `/authorize`: a consent form submitted after the registration was cleared is
    // not a malformed `client_id`, and telling the person it is sends them looking for a typo.
    val normalizedClientId = when (val client = readClientId(podDbo, clientId)) {
      is ClientIdentity.Known -> client.clientId
      ClientIdentity.Unregistered -> return Response.status(400)
        .entity(UNREGISTERED_CLIENT_MESSAGE).type("text/plain").build()

      ClientIdentity.Malformed -> return Response.status(400)
        .entity("invalid client_id").type("text/plain").build()
    }

    val normalizedRedirectUri = redirectUri?.trim()?.takeIf { it.isNotBlank() }
      ?: return Response.status(400).entity("missing redirect_uri").type("text/plain").build()

    if (!isAllowedRedirectUri(podDbo, normalizedClientId, normalizedRedirectUri)) {
      return Response.status(400).entity("redirect_uri not allowed for this client_id").type("text/plain").build()
    }

    // Two questions, two answers. The session says *who* is submitting; the transaction says
    // *which screen* this is, and that it has not been submitted before. Neither alone is enough:
    // a session-derived token would be the same on every screen for twelve hours (so a stale page
    // could be replayed over a narrower consent), and a transaction alone could be lifted out of a
    // page and spent from another browser.
    val session = readSession(pod, sessionCookie)
      ?: return Response.status(401).entity("session expired — please re-authorize").type("text/plain").build()
    val transaction = csrf?.trim()?.takeIf { it.isNotBlank() }?.let { consentTransactionStore.consume(it) }
    if (transaction == null || transaction.pod != podDbo.name || transaction.webId != session.webId) {
      logger.warn {
        "[oauth/consent] rejected: consent token ${if (csrf == null) "absent" else "unknown, spent or not this session's"} " +
            "(pod='${podDbo.name}')"
      }
      return Response.status(403)
        .entity("this form is no longer valid — please re-authorize")
        .type("text/plain")
        .build()
    }
    val identity = PersonIdentity(webId = session.webId, alsoKnownAs = session.alsoKnownAs)

    // Single-use stops this page being posted twice; it says nothing about a *second* page opened
    // before the app was disconnected, which would submit its own older selection as the
    // authoritative new state and hand back everything the person just removed. So a page carries
    // what stood when it was rendered, and one from before a disconnect is refused.
    //
    // Only that case. Screens are allowed to coexist on purpose — `ConsentTransactionStore` says
    // why, and `two sign-ins running at once in one browser both complete` pins it — so a page
    // that is merely older than the current answer, on an authorization that still holds
    // something, submits as it always did. A page that would resurrect a disconnected app does
    // not.
    val standing = consentDecisionStore
      .find(checkNotNull(podDbo.id), normalizedClientId, listOf(identity.webId))
      ?.generation
    if (transaction.consentGeneration != standing && !holdsAnything(podDbo, normalizedClientId, identity)) {
      logger.info {
        "[oauth/consent] rejected: page rendered before the app was disconnected (pod='${podDbo.name}', " +
            "clientId='$normalizedClientId', rendered=${transaction.consentGeneration ?: "(none)"}, " +
            "standing=${standing ?: "(none)"})"
      }
      return Response.status(403)
        .entity("this form is no longer valid — please re-authorize")
        .type("text/plain")
        .build()
    }

    // Before anything is created. The form can carry a context the person typed, and choosing to
    // remove an app's access is not the moment to build one for it — they asked for the opposite of
    // an authorization. The empty-submission route to the same place is further down, because it
    // can only be recognised once the selection has been resolved.
    if (action?.trim() == DISCONNECT_ACTION) {
      return if (holdsAnything(podDbo, normalizedClientId, identity)) {
        disconnectApp(podDbo, normalizedClientId, identity, normalizedRedirectUri, state)
      } else {
        oauthError(normalizedRedirectUri, OAuthErrorCode.ACCESS_DENIED, "no scopes selected", state)
      }
    }

    val podBaseUrl = "${config.apiBaseUrl}${podDbo.name}/"
    val isOwner = podGrantsFacade.isPodOwner(podDbo, identity.allUris)

    // `public-read` is an additive scope. It can be combined with per-context
    // scopes — no mutual-exclusivity check. Persisted as a grant so
    // prompt=none auto-grant works on subsequent /authorize calls.
    val rawSubmitted = scopes
      ?.map { it.trim() }
      ?.filter { it.isNotBlank() }
      ?.toSet()
      ?: emptySet()
    val publicReadRequested = PUBLIC_READ_SCOPE in rawSubmitted
    val perContextSubmitted = rawSubmitted - PUBLIC_READ_SCOPE
    val newContextsRequested = newContexts
      ?.map { ContextPathRules.normalize(it) }
      ?.filter { it.isNotBlank() }
      ?: emptyList()
    // `<relative-path>#<permission>` — a context that does not exist yet has no IRI to name, so
    // the form cannot post one. It used to post `podBaseUrl + path + '#' + perm`, which stopped
    // matching the moment the server started prefixing, and the grants silently vanished.
    val newContextScopesRequested = newContextScopes
      ?.map { it.trim() }
      ?.filter { it.isNotBlank() }
      ?: emptyList()

    // Nothing ticked anywhere is the other way to ask for the way out, and it is answerable here:
    // with no scope submitted and no permission on a pending context, no selection can survive the
    // creation below, so creating one would build a context for an authorization that is not
    // happening. The backstop after the resolution stays, for a selection that empties there.
    if (rawSubmitted.isEmpty() && newContextScopesRequested.isEmpty()) {
      return if (holdsAnything(podDbo, normalizedClientId, identity)) {
        disconnectApp(podDbo, normalizedClientId, identity, normalizedRedirectUri, state)
      } else {
        oauthError(normalizedRedirectUri, OAuthErrorCode.ACCESS_DENIED, "no scopes selected", state)
      }
    }

    if (publicReadRequested) {
      // public-read needs at least one public context to be meaningful — drop
      // it from the grant set if the pod has no public contexts (rather than
      // erroring; the user may still want the per-context grants).
      val publicContexts = podFacade.getPublicContexts(podName = podDbo.name)
      if (publicContexts.isEmpty() && perContextSubmitted.isEmpty() && newContextsRequested.isEmpty()) {
        return oauthError(
          normalizedRedirectUri, OAuthErrorCode.CONSENT_REQUIRED,
          "pod has no public-read contexts and no per-context scopes were selected", state,
        )
      }
    }

    // Create new contexts submitted from the consent UI (owner only).
    //
    // The path is free user input, so it goes through the same structure rules as the management
    // route ([ContextPathRules]) and gets the same namespace prefix. Until this iteration it did
    // neither: contexts landed directly under the pod root, in the freely writable resource
    // namespace, and no rule the other producer enforced applied here at all.
    //
    // A rejected path is skipped rather than failing the authorization: the user is mid-consent,
    // and losing the whole flow over a mistyped context name would be the worse outcome. The
    // grant set below is computed from what actually exists, so a skipped context simply is not
    // granted.
    //
    // The IRI is built here and nowhere else. The form posts the relative path, both for the
    // context and for its permission checkboxes — a second builder in the template is what made
    // every grant on a newly created context vanish the moment this one started prefixing.
    //
    // TODO: Schnitt 2 — surface a `public` checkbox here so consent-created
    //   contexts can be made anonymously readable; defaults to private for now.
    // TODO: a rejected path is still only a log line. The form validates first, which covers what a
    //   person actually types, but it is a second implementation of these rules and a second
    //   implementation eventually disagrees — a character class already did. The complete answer is
    //   to re-render the consent page with the reason instead of skipping: the owner stays in the
    //   flow and sees it. What that needs is carrying the pending contexts and their ticked
    //   permissions back into the template, so the re-render does not discard the work.
    val createdContexts = mutableMapOf<String, String>()
    if (isOwner) {
      val podId = checkNotNull(podDbo.id)
      newContextsRequested.forEach { relativePath ->
        fun reject(reason: String) = logger.warn { "[oauth/consent] Context rejected: pod='${podDbo.name}', path='$relativePath' — $reason" }
        ContextPathRules.rejectionReason(relativePath)?.let { return@forEach reject(it) }
        // Same builder as the management route, so the two cannot disagree about what a path maps
        // to — and so a form value carrying `#` or `?` is refused here as well. Concatenating the
        // string instead would have persisted `<pod>/_system/contexts/foo#bar`: unaddressable
        // through `_system/contexts/{path}`, and ambiguous against the `<iri>#<permission>` scope
        // grammar.
        val resolution = ContextPathRules.resolve(podBaseUrl, relativePath)
        if (resolution is ContextUriResolution.Rejected) {
          return@forEach reject(resolution.reason)
        }
        val contextUri = (resolution as ContextUriResolution.Resolved).uri.toString()
        podContextsDao.create(
          podId = podId,
          contextUri = contextUri,
          label = null,
          description = null,
          createdBy = identity.webId,
        )
        createdContexts[relativePath] = contextUri
        logger.info { "[oauth/consent] Context created: pod='${podDbo.name}', context='$contextUri'" }
      }
    }

    // The checkboxes of a just-created context, resolved against the IRI it actually got. A scope
    // whose path was rejected above resolves to nothing and is dropped with its context — which is
    // the intended outcome, and the reason this map is keyed by what was created rather than by
    // what was requested.
    val newContextScopesResolved = newContextScopesRequested.mapNotNull { raw ->
      val relativePath = ContextPathRules.normalize(raw.substringBeforeLast('#', missingDelimiterValue = ""))
      val permission = raw.substringAfterLast('#', missingDelimiterValue = "")
      if (permission !in PodGrantsDao.CONTEXT_PERMISSIONS) {
        return@mapNotNull null
      }
      createdContexts[relativePath]?.let { "$it#$permission" }
    }

    // Re-resolve the user's grants after potential context creation.
    val userGrants = podGrantsFacade.resolveUserGrants(podDbo, identity.allUris, podBaseUrl)
    val selectedPerContext = (perContextSubmitted + newContextScopesResolved)
      .filter { userGrants.contains(it) }
      .toSet()

    // Combined grant set: per-context scopes plus the public-read scope if
    // the toggle was ticked (additive model). Public-read is persisted so
    // prompt=none auto-grant honours the choice on later /authorize calls.
    val selectedScopes = if (publicReadRequested) {
      selectedPerContext + PUBLIC_READ_SCOPE
    } else {
      selectedPerContext
    }

    // The submission is the authoritative new state, and clearing it is the extreme case of that
    // rather than an exception to it. Two ways to arrive here mean the same thing — the named
    // action, and a submission with nothing ticked — and both end the authorization where there is
    // one. Where there is none the answer stays the plain denial it always was: reporting a
    // disconnect of nothing is the same lie as reporting nothing when something ended.
    if (selectedScopes.isEmpty()) {
      return if (holdsAnything(podDbo, normalizedClientId, identity)) {
        disconnectApp(podDbo, normalizedClientId, identity, normalizedRedirectUri, state)
      } else {
        oauthError(normalizedRedirectUri, OAuthErrorCode.ACCESS_DENIED, "no scopes selected", state)
      }
    }

    // Persist the user's grant selection for this app (replace — the checkbox submission is the
    // authoritative new state, so any previously granted scope the user unchecked must be revoked).
    // The facade re-derives after writing, so an owner-level revocation that landed between
    // `resolveUserGrants` above and this write cannot leave an unbacked grant behind. What comes
    // back is what actually survived.
    val persistedScopes = podGrantsFacade.replaceAppGrants(
      podDbo = podDbo,
      appId = normalizedClientId,
      webId = identity.webId,
      subjectUris = identity.allUris,
      grants = selectedScopes,
      grantedBy = identity.webId,
    )

    if (persistedScopes.isEmpty()) {
      // Recoverable: the person's authority changed while they were deciding. `consent_required`
      // rather than `access_denied` — nobody refused anything, the basis simply moved.
      logger.warn {
        "[oauth/consent] Selection void — owner-level access changed during consent: " +
            "pod='${podDbo.name}', clientId='$normalizedClientId', webId='${identity.webId}'"
      }
      return oauthError(
        normalizedRedirectUri, OAuthErrorCode.CONSENT_REQUIRED,
        "granted access changed while consenting; please re-authorize", state,
      )
    }

    // Recorded whichever way it was answered, because a refusal has to be tellable from a silence:
    // an authorization that predates the control has nothing written and keeps what it holds.
    val durableGranted = durable != null
    val decision = recordDecision(podDbo, normalizedClientId, identity, durable = durableGranted)
    if (!durableGranted) {
      // Withholding is not merely declining to extend: the families this authorization already has
      // would otherwise keep rotating, and the person would have changed nothing they can observe.
      val revoked = refreshTokenStore.revokeForUser(
        podId = checkNotNull(podDbo.id),
        clientId = normalizedClientId,
        webIds = identity.allUris,
      )
      if (revoked > 0) {
        logger.info {
          "[oauth/consent] Durable connection withheld — refresh tokens revoked: " +
              "pod='${podDbo.name}', clientId='$normalizedClientId', webId='${identity.webId}', " +
              "revokedRows=$revoked"
        }
      }
    }

    logger.info {
      "[oauth/consent] Grants saved: pod='${podDbo.name}', clientId='$normalizedClientId', " +
          "webId='${identity.webId}', scopes=${persistedScopes.size}, public_read=$publicReadRequested, " +
          "durable=$durableGranted, generation=${decision.generation}"
    }

    // Slim access token: context permissions are resolved server-side from the grant just
    // persisted, so only feature scopes (e.g. `public-read`) travel in the token.
    val tokenFeatureScopes = if (publicReadRequested) setOf(PUBLIC_READ_SCOPE) else emptySet()

    return issueAuthCodeAndRedirect(
      podDbo = podDbo,
      clientId = normalizedClientId,
      webId = identity.webId,
      scopes = tokenFeatureScopes,
      redirectUri = normalizedRedirectUri,
      state = state,
      codeChallenge = codeChallenge?.trim()?.takeIf { it.isNotBlank() },
      codeChallengeMethod = codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() },
      logPrefix = "[oauth/consent]",
      consentGeneration = decision.generation,
    )
  }

  /**
   * End what this app holds for this person.
   *
   * The grants go, the decision is written as a refusal — a silence would read as an authorization
   * that predates the control and be left alone — and the refresh families are revoked, because
   * withholding that is merely declining to extend would leave the person's most emphatic gesture
   * with nothing to show for it. The client is still told `access_denied`: the request really was
   * denied, and what changed is that the denial now has an effect.
   */
  /**
   * Write the answer under every URI that names this person, and hand back the one for the URI they
   * are signed in as.
   *
   * One document per URI rather than one per person, because that is how the rows this sits beside
   * are keyed — and because the alternative is worse than the duplication: a code issued while an
   * alias was the session identity carries that alias's generation, and only a document of its own
   * can move when the person answers again under their canonical WebID. Without that, the older
   * code would still compare equal and redeem against a consent that has been replaced.
   */
  private fun recordDecision(
    podDbo: PodDbo,
    clientId: String,
    identity: PersonIdentity,
    durable: Boolean,
  ): PodConsentDecisionStore.Decision {
    val podId = checkNotNull(podDbo.id)
    val forSubject = consentDecisionStore.record(podId, clientId, identity.webId, durable)
    identity.allUris.filterNot { it == identity.webId }.forEach { alias ->
      consentDecisionStore.record(podId, clientId, alias, durable)
    }
    return forSubject
  }

  /**
   * Whether the person has refused this app a durable connection.
   *
   * Not the negation of granted: an authorization with nothing recorded predates the control and is
   * left alone, which is why this asks for a recorded refusal rather than for the absence of a
   * grant.
   */
  private fun refusedDurability(podDbo: PodDbo, clientId: String, webId: String): Boolean =
    consentDecisionStore.find(checkNotNull(podDbo.id), clientId, listOf(webId))?.durable == false

  /**
   * Whether this app holds anything for this person — the question that decides both whether the
   * way out is offered and whether taking it means anything. Asked over every URI that names the
   * person: an authorization stored under an alias is one they can still end.
   */
  private fun holdsAnything(podDbo: PodDbo, clientId: String, identity: PersonIdentity): Boolean =
    podGrantsDao
      .fetchGrantStrings(checkNotNull(podDbo.id), clientId, identity.allUris.toList())
      .isNotEmpty()

  private fun disconnectApp(
    podDbo: PodDbo,
    clientId: String,
    identity: PersonIdentity,
    redirectUri: String,
    state: String?,
  ): Response {
    val podId = checkNotNull(podDbo.id)
    // Once per URI that names the person, because that is how the rows are keyed: an authorization
    // made under an alias is one this person can end, and `holdsAnything` already counted it.
    identity.allUris.forEach { uri ->
      podGrantsFacade.replaceAppGrants(
        podDbo = podDbo,
        appId = clientId,
        webId = uri,
        subjectUris = identity.allUris,
        grants = emptySet(),
        grantedBy = identity.webId,
      )
    }
    val decision = recordDecision(podDbo, clientId, identity, durable = false)
    val revoked = refreshTokenStore.revokeForUser(podId, clientId, identity.allUris)
    logger.info {
      "[oauth/consent] App disconnected: pod='${podDbo.name}', clientId='$clientId', " +
          "webId='${identity.webId}', revokedRows=$revoked, generation=${decision.generation}"
    }
    return oauthError(redirectUri, OAuthErrorCode.ACCESS_DENIED, "app disconnected", state)
  }

  // ─── OAuth token ──────────────────────────────────────────────────────────

  @POST
  @Path("token")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  fun token(
    @PathParam("pod") pod: String,
    @HeaderParam("Authorization") authorizationHeader: String?,
    @HeaderParam("X-Forwarded-For") forwardedFor: String?,
    @FormParam("grant_type") grantType: String?,
    @FormParam("code") code: String?,
    @FormParam("redirect_uri") redirectUri: String?,
    @FormParam("client_id") clientId: String?,
    @FormParam("code_verifier") codeVerifier: String?,
    @FormParam("refresh_token") refreshToken: String?,
    @FormParam("scope") scope: String?,
  ): Response {
    // Ahead of the pod row on purpose: `fetchPodOrThrow` reads it uncached, so a refused request
    // costs no query at all. That is most of what the budget buys — see [PodTokenRateLimiter].
    if (!tokenRateLimiter.tryAcquire(forwardedFor, grantType, clientId, authorizationHeader)) {
      return rateLimitedError()
    }

    val podDbo = fetchPodOrThrow(pod)

    return when (grantType) {
      "authorization_code" -> exchangeAuthorizationCode(
        podDbo = podDbo,
        code = code,
        redirectUri = redirectUri,
        clientId = clientId,
        codeVerifier = codeVerifier,
      )

      "refresh_token" -> exchangeRefreshToken(
        podDbo = podDbo,
        refreshToken = refreshToken,
        clientId = clientId,
        requestedScope = scope,
      )

      "client_credentials" -> exchangeClientCredentials(
        podDbo = podDbo,
        authorizationHeader = authorizationHeader,
        requestedScope = scope,
      )

      else -> tokenError(
        OAuthErrorCode.UNSUPPORTED_GRANT_TYPE,
        "only authorization_code, refresh_token and client_credentials are supported",
      )
    }
  }

  /**
   * OAuth 2-leg flow (RFC 6749 §4.4). Trusted service clients (statically
   * registered via [PodServiceClientStore]) authenticate with HTTP Basic and
   * receive a short-lived access token bound to their own clientId.
   *
   * The AS metadata at `_system/auth/.well-known/oauth-authorization-server`
   * advertises this grant and `client_secret_basic` so RFC 8414 §2 honest
   * disclosure holds. DCR clients (MCP) cannot use this grant — service
   * clients are statically registered out-of-band and never appear via
   * `/register` — but advertising it is correct, not enabling: knowledge of
   * the grant alone does not help an unregistered client mint a token.
   */
  private fun exchangeClientCredentials(
    podDbo: PodDbo,
    authorizationHeader: String?,
    requestedScope: String?,
  ): Response {
    val basic = BasicAuth.parse(authorizationHeader)
      ?: return Response.status(401)
        .header("WWW-Authenticate", "Basic realm=\"${podDbo.name}\"")
        .entity("""{"error":"invalid_client","error_description":"HTTP Basic authentication required"}""")
        .type(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store")
        .header("Pragma", "no-cache")
        .build()

    val podId = checkNotNull(podDbo.id)
    val client = podServiceClientStore.authenticate(podId, basic.username, basic.password)
    if (client == null) {
      // The submitted username, escaped — the same case `PodTokenRateLimiter` has one endpoint
      // over: naming what was sent is the whole point of the line, so it cannot be narrowed away.
      logger.info {
        "[oauth/token] client_credentials auth failed: pod='${podDbo.name}', " +
            "clientId='${LogSafeText.of(basic.username)}'"
      }
      return Response.status(401)
        .header("WWW-Authenticate", "Basic realm=\"${podDbo.name}\"")
        .entity("""{"error":"invalid_client","error_description":"unknown client_id or invalid secret"}""")
        .type(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store")
        .header("Pragma", "no-cache")
        .build()
    }

    // Down-scoping is NOT supported on client_credentials. A slim service token carries no
    // per-token state to express a subset, and the resolver always grants the client's full
    // registered set from `PodServiceClientDao` at request time. Accepting `scope=` and
    // silently granting more than requested would be a confused-deputy footgun, so reject it
    // outright until per-token service down-scope state exists.
    // TODO: support per-token service down-scoping (carry the requested subset as a signed,
    //   resolver-honored claim) — then this rejection can relax to the subset path.
    if (!requestedScope.isNullOrBlank()) {
      return tokenError(
        OAuthErrorCode.INVALID_SCOPE,
        "scope down-scoping is not supported on client_credentials; the token grants the " +
            "client's full registered scope set",
      )
    }

    if (client.scopes.isEmpty()) {
      return tokenError(OAuthErrorCode.INVALID_SCOPE, "no scopes registered for this client")
    }

    // Only feature scopes travel in a slim service token. Service clients register context
    // scopes only (feature scopes are rejected at registration), so this is empty today.
    val tokenFeatureScopes = client.scopes.intersect(PodScopeValidator.featureScopes)

    val accessToken = podTokenIssuer.issueServiceToken(
      pod = podDbo.name,
      clientId = client.clientId,
      scopes = tokenFeatureScopes,
    )
    podServiceClientStore.touchLastUsed(podId, client.clientId)

    logger.info {
      "[oauth/token] Service token issued (client_credentials): pod='${podDbo.name}', " +
          "clientId='${client.clientId}', registeredScopes=${client.scopes.size}, " +
          "tokenFeatureScopes=${tokenFeatureScopes.size}, label='${client.label ?: "(unset)"}'"
    }

    val body = linkedMapOf<String, Any>(
      "access_token" to accessToken,
      "token_type" to "Bearer",
      "expires_in" to PodTokenIssuer.SERVICE_TOKEN_TTL_SECONDS,
      "scope" to tokenFeatureScopes.joinToString(" "),
    )
    return Response.ok(body)
      .type(MediaType.APPLICATION_JSON)
      .header("Cache-Control", "no-store")
      .header("Pragma", "no-cache")
      .build()
  }

  private fun exchangeAuthorizationCode(
    podDbo: PodDbo,
    code: String?,
    redirectUri: String?,
    clientId: String?,
    codeVerifier: String?,
  ): Response {
    val normalizedCode = code?.trim()?.takeIf { it.isNotBlank() }
      ?: return tokenError(OAuthErrorCode.INVALID_REQUEST, "missing code")
    val normalizedRedirectUri = redirectUri?.trim()?.takeIf { it.isNotBlank() }
      ?: return tokenError(OAuthErrorCode.INVALID_REQUEST, "missing redirect_uri")
    val normalizedClientId = clientId?.trim()?.takeIf { it.isNotBlank() }
      ?: return tokenError(OAuthErrorCode.INVALID_REQUEST, "missing client_id")

    // Consume the authorization code (one-time use).
    val entry = authorizationCodeStore.consume(normalizedCode)
      ?: return tokenError(OAuthErrorCode.INVALID_GRANT, "invalid or expired authorization code")

    // Validate that redirect_uri and client_id match the original authorize request.
    if (entry.redirectUri != normalizedRedirectUri) {
      return tokenError(OAuthErrorCode.INVALID_GRANT, "redirect_uri mismatch")
    }
    if (entry.clientId != normalizedClientId) {
      return tokenError(OAuthErrorCode.INVALID_GRANT, "client_id mismatch")
    }
    if (entry.realm != podDbo.name) {
      return tokenError(OAuthErrorCode.INVALID_GRANT, "pod mismatch")
    }

    // PKCE verification. The challenge is bound to a local because the store lives in another
    // module now, where Kotlin will not smart-cast a public property across the boundary.
    val issuedChallenge = entry.codeChallenge
    if (issuedChallenge != null) {
      // Not trimmed, unlike every other form value here. RFC 7636 §4.1's alphabet has no
      // whitespace, so trimming would grant a leniency the rule does not — and grant it at two of
      // this repository's three token endpoints, since the third passes the value as sent.
      val verifier = codeVerifier?.takeIf { it.isNotBlank() }
        ?: return tokenError(OAuthErrorCode.INVALID_REQUEST, "missing code_verifier")
      // `Pkce` rather than a local comparison: it compares in constant time. A byte-by-byte
      // early exit leaks the stored challenge one character per request, and the challenge is
      // what stands between an intercepted authorization code and a token. The method is
      // re-checked because a stored row is the only thing that says which one was agreed —
      // `/authorize` refuses anything else, so this is the integrity check, not the gate.
      if (!Pkce.isSupportedMethod(entry.codeChallengeMethod) ||
        !Pkce.verifyS256(verifier, issuedChallenge)
      ) {
        return tokenError(OAuthErrorCode.INVALID_GRANT, "PKCE verification failed")
      }
    }

    // Anonymous public-read remains a short-lived special token because there
    // is no user grant row to bind refresh-token rotation against. Authenticated
    // public-read, including public-read-only consent, is a normal additive
    // scope and continues through the standard refresh-token path below.
    if (entry.scopes == setOf(PUBLIC_READ_SCOPE) && entry.subject.startsWith("urn:sempods:anon:")) {
      logger.info {
        "[oauth/token] public-read token issued: pod='${podDbo.name}', " +
            "clientId='${entry.clientId}', webId='${entry.subject}'"
      }
      dynamicClientStore.touchLastAuthorized(checkNotNull(podDbo.id), entry.clientId)
      return buildPublicReadTokenResponse(
        podName = podDbo.name,
        clientId = entry.clientId,
        webId = entry.subject,
      )
    }

    // A code is a request, not an authority: it must not pick up a consent given after it. The
    // generation it carries is the one that produced it, so a disconnect — or any later answer —
    // makes it stale, and its scopes are stale with it. Compared in both directions, because the
    // first answer an authorization ever gets supersedes the codes issued before it just as surely
    // as the second: a code from an authorization that had none carries none, and matches only for
    // as long as none is recorded.
    val decision = consentDecisionStore.find(checkNotNull(podDbo.id), entry.clientId, listOf(entry.subject))
    val issuedUnder = entry.consentGeneration
    if (decision?.generation != issuedUnder) {
      logger.info {
        "[oauth/token] authorization code superseded by a later consent: pod='${podDbo.name}', " +
            "clientId='${entry.clientId}', webId='${entry.subject}', " +
            "codeGeneration=$issuedUnder, current=${decision?.generation ?: "(none)"}"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "authorization code superseded by a later consent")
    }

    // Hard guarantee the access token is slim: keep only feature scopes, whatever the
    // authorization-code entry happens to carry. Context permissions are resolved per request
    // from the grant store, never echoed into the token. This also bounds the refresh row.
    val featureScopes = entry.scopes.intersect(PodScopeValidator.featureScopes)

    // Read from the stored consent, not from the code: a code carries what was asked for, never the
    // authority. An absent decision is not a grant — it leaves an already-rotating family alone,
    // which the refresh grant still honours, and mints no new one here.
    val durable = decision?.durable == true

    // What this exchange supersedes, named *before* the successor exists — see
    // `PodRefreshTokenStore.liveFamilies` for why the order is the whole argument. Across the
    // person's derivable URIs, because the superseded family may have been minted under the twin
    // of the URI this code carries.
    val superseded = if (durable) {
      refreshTokenStore.liveFamilies(
        podId = checkNotNull(podDbo.id),
        clientId = entry.clientId,
        webIds = webIdUriDeriver.derivableAliases(entry.subject),
      )
    } else {
      emptySet()
    }

    val issuedRefresh = if (durable) {
      refreshTokenStore.issueNewFamily(
        podId = checkNotNull(podDbo.id),
        podName = podDbo.name,
        clientId = entry.clientId,
        webId = entry.subject,
        scopes = featureScopes,
      )
    } else {
      null
    }

    // The same two moments on this path: the decision was read above and the family is inserted
    // here, so a withdrawal in between would revoke what it saw and leave this one standing.
    if (issuedRefresh != null && refusedDurability(podDbo, entry.clientId, entry.subject)) {
      val revoked = refreshTokenStore.revokeFamily(issuedRefresh.token.familyId)
      logger.info {
        "[oauth/token] durable connection withdrawn mid-exchange — family revoked: " +
            "pod='${podDbo.name}', clientId='${entry.clientId}', webId='${entry.subject}', " +
            "revokedRows=$revoked"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "the durable connection was withdrawn")
    }

    // I9 again, and this time about the sweep rather than the mint. The generation was compared
    // before any of this existed, and what follows it is destructive: an answer landing in between
    // is a *later* one than this code's, so retiring what its exchange produced would let the older
    // code win — the supersession running backwards. Asked once more for the same reason the
    // refusal above is, and answered the same way: this exchange's own family goes and the client
    // is told to come back through consent. Only the sequential case has a test (`a code cannot
    // pick up a consent granted after it`); this window is between two statements, where none can
    // reach.
    if (issuedRefresh != null &&
      consentDecisionStore.find(checkNotNull(podDbo.id), entry.clientId, listOf(entry.subject))
        ?.generation != issuedUnder
    ) {
      val revoked = refreshTokenStore.revokeFamily(issuedRefresh.token.familyId)
      logger.info {
        "[oauth/token] consent moved mid-exchange — family revoked before the sweep: " +
            "pod='${podDbo.name}', clientId='${entry.clientId}', webId='${entry.subject}', " +
            "codeGeneration=$issuedUnder, revokedRows=$revoked"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "authorization code superseded by a later consent")
    }

    // A reconnect replaces the connection it supersedes rather than adding to it — the same answer
    // the withholding path gives from the other end, so that reconnecting twice does not leave two
    // ninety-day families behind, each renewing its own TTL on every rotation. Swept only once the
    // successor exists, so answering "yes" never leaves the person holding nothing.
    if (superseded.isNotEmpty()) {
      val retired = refreshTokenStore.revokeFamilies(superseded)
      if (retired > 0) {
        logger.info {
          "[oauth/token] reconnect retired what it supersedes: pod='${podDbo.name}', " +
              "clientId='${entry.clientId}', webId='${entry.subject}', " +
              "retiredFamilies=${superseded.size}, retiredRows=$retired"
        }
      }
    }

    logger.info {
      "[oauth/token] Tokens issued (authorization_code): pod='${podDbo.name}', clientId='${entry.clientId}', " +
          "webId='${entry.subject}', scopes=${featureScopes.size}, durable=$durable, " +
          "familyId='${issuedRefresh?.token?.familyId ?: "(none)"}'"
    }

    // Liveness touch on the DCR row. Every completed flow reaches one of the three call sites —
    // this one, the anonymous public-read branch above and the rotation below — so a connection
    // the person kept short-lived stays as live as a durable one; it just says so by
    // re-authorizing rather than by refreshing. Best-effort: did:web clients have no DCR row and
    // return false here, which is fine.
    dynamicClientStore.touchLastAuthorized(checkNotNull(podDbo.id), entry.clientId)

    return buildTokenResponse(
      podName = podDbo.name,
      clientId = entry.clientId,
      webId = entry.subject,
      scopes = featureScopes,
      refreshToken = issuedRefresh?.plaintext,
      grantedDurable = durable,
    )
  }

  private fun exchangeRefreshToken(
    podDbo: PodDbo,
    refreshToken: String?,
    clientId: String?,
    requestedScope: String?,
  ): Response {
    val normalizedToken = refreshToken?.trim()?.takeIf { it.isNotBlank() }
      ?: return tokenError(OAuthErrorCode.INVALID_REQUEST, "missing refresh_token")
    val normalizedClientId = clientId?.trim()?.takeIf { it.isNotBlank() }
      ?: return tokenError(OAuthErrorCode.INVALID_REQUEST, "missing client_id")

    val lookup = refreshTokenStore.lookup(normalizedToken)
    val token = lookup.token
    when (lookup.state) {
      // TODO: the line names the pod, the client and the token — and still cannot say *who* tried.
      //  On a miss the store returns no token, so there is no family id and no WebID, and the
      //  submitted `client_id` names an app rather than an installation. Attributing a failed
      //  attempt to a person would mean keeping durable tombstones for tokens that no longer
      //  exist — a retention design that has to answer what is worth keeping about a credential
      //  that failed, not a log line. The policy question is tracked in the maintainer's auth
      //  roadmap; what the store *can* say is in `docs/auth/oauth.md` §"Refresh token
      //  rotation".
      RefreshTokenStore.LookupState.NOT_FOUND -> {
        // "unknown or expired", because the two are the same row-absence here: an expired token
        // reports EXPIRED only until the TTL index reaps it, and NOT_FOUND ever after. Reading
        // this line as "forged" would be wrong for the commoner of the two cases.
        logger.warn {
          "[oauth/token] refresh_token not recognized — unknown, or expired and already reaped: " +
              "pod='${podDbo.name}', clientId='$normalizedClientId', tokenFp='${lookup.fingerprint}'"
        }
        return tokenError(OAuthErrorCode.INVALID_GRANT, "refresh token not recognized")
      }

      RefreshTokenStore.LookupState.EXPIRED -> {
        logger.info {
          "[oauth/token] refresh_token expired: pod='${podDbo.name}', clientId='$normalizedClientId', " +
              "familyId='${token!!.familyId}'"
        }
        return tokenError(OAuthErrorCode.INVALID_GRANT, "refresh token expired")
      }

      RefreshTokenStore.LookupState.REVOKED -> {
        logger.warn {
          "[oauth/token] refresh_token revoked: pod='${podDbo.name}', clientId='$normalizedClientId', " +
              "familyId='${token!!.familyId}'"
        }
        return tokenError(OAuthErrorCode.INVALID_GRANT, "refresh token revoked")
      }

      RefreshTokenStore.LookupState.REUSED -> {
        // OAuth 2.1 reuse-detection: this token was already exchanged once. A correctly
        // behaving client keeps only the successor — whatever presented it again is stale
        // state or a thief. Kill the whole family to pull the plug on any in-flight child
        // token the attacker might already be holding.
        val revoked = refreshTokenStore.revokeFamily(token!!.familyId)
        logger.warn {
          "[oauth/token] refresh_token reuse detected — revoking family: pod='${podDbo.name}', " +
              "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
        }
        return tokenError(OAuthErrorCode.INVALID_GRANT, "refresh token reuse detected")
      }

      RefreshTokenStore.LookupState.ACTIVE -> Unit
    }
    token!!

    // Bind the token to its original pod + client. Prevents a client from taking a refresh
    // token issued for another pod/client and presenting it here.
    if (token.owner.podId != podDbo.id) {
      logger.warn {
        "[oauth/token] refresh_token pod mismatch: tokenPod='${token.owner.podName}', requestPod='${podDbo.name}', " +
            "clientId='$normalizedClientId'"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "refresh token does not belong to this pod")
    }
    if (token.owner.clientId != normalizedClientId) {
      logger.warn {
        "[oauth/token] refresh_token client mismatch: tokenClient='${token.owner.clientId}', " +
            "requestClient='$normalizedClientId', pod='${podDbo.name}'"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "refresh token does not belong to this client")
    }

    // Context permissions are resolved per request from the durable grant store, so a partial
    // revocation needs no token change. The session is dead only when the user has revoked
    // EVERY grant for this app (context grants and feature scopes both live in the store) —
    // then force re-consent and revoke the family so a stray rotation cannot re-hydrate it.
    val currentGrants = podGrantsDao.fetchGrantStrings(
      podId = checkNotNull(podDbo.id),
      appId = token.owner.clientId,
      webIds = listOf(token.owner.webId),
    )
    if (currentGrants.isEmpty()) {
      refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] refresh_token grants revoked since issue — forcing re-consent: " +
            "pod='${podDbo.name}', clientId='$normalizedClientId', webId='${token.owner.webId}', " +
            "familyId='${token.familyId}'"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "all previously granted scopes have been revoked")
    }

    // The slim token carries only feature scopes; keep the ones the user still grants
    // (e.g. drop public-read if it was revoked). The final `.intersect(featureScopes)` is a
    // hard guarantee against context scopes leaking from a legacy/seeded refresh row —
    // context permissions are resolved per request, never echoed into the token.
    val effectiveFeatureScopes = (token.scopes intersect currentGrants)
      .intersect(PodScopeValidator.featureScopes)

    // Optional down-scoping of the feature scopes. Unknown scopes are rejected per RFC 6749
    // §6 ("The requested scope […] MUST NOT include any scope not originally granted").
    //
    // `offline_access` is taken out of that comparison first, because the response says it was
    // granted and a client that does the standard thing — echo the granted scope back on the next
    // refresh — would otherwise be told the scope it was just handed is not covered. It is never a
    // feature scope, so it cannot be down-scoped *to*; what it names is the connection this request
    // is already proving it holds, and a refusal on record has ended the family further up.
    val requested = OAuthSyntax.parseScope(requestedScope)
    val durableEchoed = OFFLINE_ACCESS_SCOPE in requested
    val finalScopes = if (requestedScope.isNullOrBlank()) {
      effectiveFeatureScopes
    } else {
      val requestedFeatures = requested - OFFLINE_ACCESS_SCOPE
      val unknown = requestedFeatures - effectiveFeatureScopes
      if (unknown.isNotEmpty()) {
        return tokenError(OAuthErrorCode.INVALID_SCOPE, "requested scopes not covered by this refresh token")
      }
      requestedFeatures
    }

    // A refusal ends the family, whether or not the withdrawal's own revocation reached it: that
    // sweep sees the rows that exist at the moment it runs, and rotation inserts one after it.
    if (refusedDurability(podDbo, token.owner.clientId, token.owner.webId)) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] refresh refused — the durable connection was withdrawn: pod='${podDbo.name}', " +
            "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "the durable connection was withdrawn")
    }

    // Rotate atomically. If another caller slipped in between our lookup and rotation,
    // markRotated() returns false — that's an observed reuse event (could be a race too,
    // but treating it as reuse is the safe default per OAuth 2.1).
    if (!refreshTokenStore.markRotated(token.tokenHash)) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.warn {
        "[oauth/token] refresh_token rotation race — treating as reuse: pod='${podDbo.name}', " +
            "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "refresh token reuse detected")
    }

    val issuedRefresh = refreshTokenStore.issueInFamily(previous = token, scopes = finalScopes)

    // A retirement landing between the rotation and that insert revoked the rows it found, and this
    // successor appeared after it — alive, in the family a reconnect had just replaced. `markRotated`
    // answers for a retirement arriving earlier, since it refuses a revoked row; this answers for
    // one arriving in between, and the two together leave it nowhere to land unseen. The window is
    // between two statements and has no test; `a family the retirement swept cannot be refreshed
    // back to life` covers the ordinary path and says so.
    if (refreshTokenStore.noLongerStands(token.tokenHash)) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] family retired mid-rotation — successor revoked: pod='${podDbo.name}', " +
            "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "refresh token revoked")
    }

    // The third of the three, and the one the grant cascade needs. `currentGrants` was read before
    // any of this, and a context deletion writes in between: it removes the app's last grant, names
    // this family's live row, then finds it rotated and leaves it alone — deliberately, so that
    // replaying the spent row still ends the family. What that leaves behind is a successor for an
    // app holding nothing, and this is the last moment it can be answered for. Untestable for the
    // same reason as its two neighbours; the check before the insert covers the ordinary case.
    if (podGrantsDao.fetchGrantStrings(
        podId = checkNotNull(podDbo.id),
        appId = token.owner.clientId,
        webIds = listOf(token.owner.webId),
      ).isEmpty()
    ) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] grants revoked mid-rotation — successor revoked: pod='${podDbo.name}', " +
            "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "all previously granted scopes have been revoked")
    }

    // Asked again, because the check above and this insert are two moments: a withdrawal landing
    // between them revokes what it can see and misses the row about to appear. Whoever arrives
    // second undoes the other's work rather than leaving a live successor behind.
    if (refusedDurability(podDbo, token.owner.clientId, token.owner.webId)) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] durable connection withdrawn mid-rotation — successor revoked: " +
            "pod='${podDbo.name}', clientId='$normalizedClientId', familyId='${token.familyId}', " +
            "revokedRows=$revoked"
      }
      return tokenError(OAuthErrorCode.INVALID_GRANT, "the durable connection was withdrawn")
    }

    logger.info {
      "[oauth/token] Tokens issued (refresh_token): pod='${podDbo.name}', clientId='${token.owner.clientId}', " +
          "webId='${token.owner.webId}', scopes=${finalScopes.size}, familyId='${token.familyId}'"
    }

    dynamicClientStore.touchLastAuthorized(checkNotNull(podDbo.id), token.owner.clientId)

    return buildTokenResponse(
      podName = podDbo.name,
      clientId = token.owner.clientId,
      webId = token.owner.webId,
      scopes = finalScopes,
      refreshToken = issuedRefresh.plaintext,
      // A rotation hands back a successor, so the durable connection is what this client holds, and
      // a client refreshing its view of the granted scope from the newest response must not watch
      // it disappear at the first rotation. Except where the client narrowed the request itself and
      // left it out: RFC 6749 §6 down-scoping asks for a particular set, and answering with more
      // than was asked for reads as ignoring the narrowing — `refresh_token down-scope to a granted
      // feature subset succeeds` pins that. A client that echoed it back is asking, and is told.
      grantedDurable = requestedScope.isNullOrBlank() || durableEchoed,
    )
  }

  /**
   * Builds a token response for `scope=public-read`. Mirrors [buildTokenResponse]
   * minus the refresh_token — public-read is unprivileged, so a long-lived family
   * with reuse-detection adds no security value and only persistence cost. Clients
   * re-authorize when the token expires.
   */
  private fun buildPublicReadTokenResponse(
    podName: String,
    clientId: String,
    webId: String,
  ): Response {
    val accessToken = podTokenIssuer.issue(
      pod = podName,
      webId = webId,
      clientId = clientId,
      scopes = setOf(PUBLIC_READ_SCOPE),
    )
    val body = linkedMapOf<String, Any>(
      "access_token" to accessToken,
      "token_type" to "Bearer",
      "expires_in" to 3600,
      "scope" to PUBLIC_READ_SCOPE,
    )
    return Response.ok(body)
      .type(MediaType.APPLICATION_JSON)
      .header("Cache-Control", "no-store")
      .header("Pragma", "no-cache")
      .build()
  }

  private fun buildTokenResponse(
    podName: String,
    clientId: String,
    webId: String,
    scopes: Set<String>,
    refreshToken: String?,
    grantedDurable: Boolean = false,
  ): Response {
    val accessToken = podTokenIssuer.issue(
      pod = podName,
      webId = webId,
      clientId = clientId,
      scopes = scopes,
    )
    val body = linkedMapOf<String, Any>(
      "access_token" to accessToken,
      "token_type" to "Bearer",
      "expires_in" to 3600,
      // RFC 6749 §3.3: where what was granted differs from what was asked for, the response says
      // so. A durable connection is granted by the person and not by the request, so it can differ
      // in either direction — the client asked and was refused, or never asked and was granted —
      // and either way the `scope` member is where a client finds out. The access token's own
      // claim stays slim: this is what was granted, not what the bearer carries.
      "scope" to (if (grantedDurable) scopes + OFFLINE_ACCESS_SCOPE else scopes).joinToString(" "),
    )
    // Absent rather than null when no durable connection was granted: RFC 6749 §5.1 makes the
    // member optional, and a client reading `"refresh_token": null` as a token is a bug this
    // response should not be able to provoke.
    refreshToken?.let { body["refresh_token"] = it }
    // RFC 6749 §5.1 — token responses MUST carry Cache-Control: no-store + Pragma: no-cache.
    // Strict OAuth clients (observed: GitHub Copilot CLI) silently drop tokens received without
    // these headers, which manifests as "consent completed, tokens issued, but no follow-up
    // request ever carries a Bearer".
    return Response.ok(body)
      .type(MediaType.APPLICATION_JSON)
      .header("Cache-Control", "no-store")
      .header("Pragma", "no-cache")
      .build()
  }

  // ─── JWKS ─────────────────────────────────────────────────────────────────

  /**
   * Where the id-server sends the browser back after a sign-in this server started.
   *
   * Everything of substance is server-side: `state` names a request parked by [runAuthorize], and
   * the identity is fetched from the id-server's token endpoint with a verifier that never
   * travelled through the browser and checked against the nonce that flow sent. What arrived here
   * is a code, which is worth nothing without both.
   *
   * The parked request is then re-entered, and re-validated from scratch — it may have waited
   * fifteen minutes, and the pod's clients and grants can have moved in that time.
   */
  @GET
  @Path("oidc/callback")
  fun oidcCallback(
    @PathParam("pod") pod: String,
    @QueryParam("state") state: String?,
    @QueryParam("code") code: String?,
    @QueryParam("error") error: String?,
    @QueryParam("error_description") errorDescription: String?,
    @Context httpHeaders: HttpHeaders,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)
    // Whatever this callback answers, the pin that guarded it is spent. Cookie names now carry the
    // flow's `state`, so a pin left behind is not overwritten by the next attempt — it lingers for
    // its full fifteen minutes, and a handful of cancelled sign-ins would pile up on the callback
    // path until the browser starts evicting cookies, the session among them. Attached here rather
    // than at each `return`, because there are six of them and one will be missed.
    return withPinCleared(podDbo.name, state, completeLogin(podDbo, state, code, error, errorDescription, httpHeaders))
  }

  /**
   * Attaches the withdrawal of this flow's pin — when the flow can be named at all.
   *
   * The `state` came from a stranger and is about to become part of a cookie *name*, which has its
   * own grammar. JAX-RS happens to tolerate more than Ktor does here, so the pod server never
   * produced the 500 its sibling did; that is luck rather than design, and a malformed name in a
   * `Set-Cookie` is not something to emit on purpose. A value that cannot be one of ours could
   * never have matched a parked request either, so there is nothing to withdraw.
   */
  private fun withPinCleared(pod: String, state: String?, response: Response): Response {
    if (!Secrets.isWellFormed(state)) return response
    return Response.fromResponse(response).cookie(cookies.clearLoginPin(pod, checkNotNull(state))).build()
  }

  private fun completeLogin(
    podDbo: PodDbo,
    state: String?,
    code: String?,
    error: String?,
    errorDescription: String?,
    httpHeaders: HttpHeaders,
  ): Response {
    // Consumed first and unconditionally: a replayed callback must find nothing, whether it
    // carries a code, an error, or neither.
    val pending = state?.trim()?.takeIf { it.isNotBlank() }?.let { loginStateStore.consume(it) }
    if (pending == null || pending.pod != podDbo.name) {
      return Response.status(400).entity("invalid or expired login state").type("text/plain").build()
    }

    // Login-CSRF / session fixation: this callback must complete in the SAME browser that started
    // the sign-in, because it is about to establish a session here. Without it an attacker starts
    // their own login, gets the callback URL opened in somebody else's browser, and that browser
    // comes away signed in as the attacker. Checked before the code is exchanged — a callback
    // opened in the wrong browser must cost nothing.
    val presentedPin = httpHeaders.cookies[cookies.loginPinName(checkNotNull(state))]?.value
    if (!Secrets.matches(presentedPin, pending.browserPin)) {
      logger.warn {
        "[oauth/authorize] login callback rejected: browser pin ${if (presentedPin == null) "absent" else "mismatch"} " +
            "(pod='${podDbo.name}', clientId='${pending.clientId}')"
      }
      return Response.status(400)
        .entity("this sign-in was not started in this browser — please start it again")
        .type("text/plain")
        .build()
    }

    if (error != null) {
      logger.info {
        "[oauth/authorize-audit] outcome=login_failed pod='${podDbo.name}' " +
            "client_id='${pending.clientId}' error='$error'"
      }
      // The upstream provider's own verdict, translated rather than passed through: this pod's
      // client learns what happened *to it*, and the codes do not mean the same thing one leg up.
      //
      // `access_denied` is a claim about a person, so only an actual refusal earns it. The default
      // is deliberately the other way round from the obvious one: an unrecognised code is not
      // evidence that anybody declined, and getting it wrong there makes a client record a decision
      // that was never made — worse than offering a retry that fails again.
      //
      // What lands in `server_error` is broader than it looks. Besides the provider's own
      // `server_error`, RFC 6749 §4.1.2.1's `invalid_request`, `unauthorized_client`,
      // `invalid_scope` and `unsupported_response_type` all mean *this pod* sent a bad
      // authorization request as relying party — a configuration fault its client can neither fix
      // nor be blamed for.
      val upstreamClass = when (error) {
        // The refusal, in the two spellings this tree sees: RFC 6749's, and Apple's.
        "access_denied", "user_cancelled_authorize" -> OAuthErrorCode.ACCESS_DENIED
        "temporarily_unavailable" -> OAuthErrorCode.TEMPORARILY_UNAVAILABLE
        else -> OAuthErrorCode.SERVER_ERROR
      }
      // The upstream code survives in the description even when the class above is not it, so a
      // reclassification never costs the one detail an operator needs to find the cause.
      val describedAs = errorDescription?.takeIf { it.isNotBlank() }
        ?.let { if (it == error) it else "$error: $it" }
        ?: error
      return oauthError(pending.redirectUri, upstreamClass, describedAs, pending.clientState)
    }
    // Neither an error nor a code: nobody refused anything, the callback is malformed. `server_error`
    // rather than `access_denied`, so a client does not record a decision that was never made.
    val authorizationCode = code?.trim()?.takeIf { it.isNotBlank() }
      ?: return oauthError(pending.redirectUri, OAuthErrorCode.SERVER_ERROR, "no authorization code", pending.clientState)

    val verified = try {
      identityProvider.relyingParty(podDbo.name)
        .completeAuthorization(authorizationCode, pending.codeVerifier, pending.nonce)
    } catch (e: Exception) {
      // Transient by evidence rather than by guess: an `IOException` anywhere in the cause chain is
      // the transport saying it could not reach the identity service — a connect or read failure,
      // not a verdict. That is `temporarily_unavailable`, which a client may retry. Anything else
      // reaching here is this server's own fault and says so.
      val unreachable = generateSequence(e as Throwable?) { it.cause }.any { it is IOException }
      val failureClass =
        if (unreachable) OAuthErrorCode.TEMPORARILY_UNAVAILABLE else OAuthErrorCode.SERVER_ERROR
      logger.warn(e) {
        "[oauth/authorize] id-server token exchange failed: pod='${podDbo.name}', " +
            "clientId='${pending.clientId}', answered='${failureClass.code}'"
      }
      return oauthError(pending.redirectUri, failureClass, "login failed", pending.clientState)
    }

    logger.info {
      "[oauth/authorize] login completed: pod='${podDbo.name}', clientId='${pending.clientId}', " +
          "webId='${verified.webId}'"
    }
    // Remember the sign-in on this pod's own origin, so the next authorization needs no round trip
    // and `prompt=none` has something to answer with. Scoped to this pod: pods are isolated
    // tenants, and on a path-scoped deployment they share a host.
    val sessionToken = podTokenIssuer.issueSession(podDbo.name, verified.webId, verified.alsoKnownAs)
    val answer = runAuthorize(
      pod = podDbo.name,
      responseType = "code",
      clientId = pending.clientId,
      redirectUri = pending.redirectUri,
      state = pending.clientState,
      codeChallenge = pending.codeChallenge,
      codeChallengeMethod = pending.codeChallengeMethod,
      prompt = pending.prompt,
      scope = pending.scope,
      session = PodTokenIssuer.SessionPrincipal(verified.webId, verified.alsoKnownAs),
    )
    // Attached once to whatever the flow answered — consent page, auto-granted code, or an error.
    // `runAuthorize` has a dozen exits and threading a cookie through each is how one gets missed.
    return Response.fromResponse(answer)
      .cookie(cookies.session(podDbo.name, sessionToken, PodTokenIssuer.SESSION_TTL_SECONDS.toInt()))
      .build()
  }

  @GET
  @Path("jwks.json")
  fun jwks(): Response {
    return Response.ok(podTokenIssuer.jwksJson, MediaType.APPLICATION_JSON).build()
  }

  // ─── Shared auth-code issuance ─────────────────────────────────────────────

  private fun issueAuthCodeAndRedirect(
    podDbo: PodDbo,
    clientId: String,
    webId: String,
    scopes: Set<String>,
    redirectUri: String,
    state: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    logPrefix: String,
    consentGeneration: Long? = null,
  ): Response {
    // Defense-in-depth: even if a code path reaches here without /authorize's PKCE check,
    // never mint an auth code for a dynamic (public) client without PKCE.
    if (clientId.startsWith("dyn:")) {
      if (codeChallenge.isNullOrBlank() || !Pkce.isSupportedMethod(codeChallengeMethod)) {
        return oauthError(
          redirectUri, OAuthErrorCode.INVALID_REQUEST,
          "PKCE (S256) is required for dynamic clients", state,
        )
      }
    }
    val code = authorizationCodeStore.issue(
      realm = podDbo.name,
      clientId = clientId,
      subject = webId,
      scopes = scopes,
      redirectUri = redirectUri,
      codeChallenge = codeChallenge,
      codeChallengeMethod = codeChallengeMethod,
      consentGeneration = consentGeneration,
    )

    logger.info {
      "$logPrefix Authorization code issued: pod='${podDbo.name}', clientId='$clientId', " +
          "webId='$webId', scopes=${scopes.size}"
    }
    // R6: terminal audit line for the success path. Pairs with the `outcome=start`
    // entry at the top of authorize() (and with consent-submission requests, which
    // also funnel through this helper).
    logger.info {
      "[oauth/authorize-audit] outcome=issued_code pod='${podDbo.name}' " +
          "client_id='$clientId' web_id='$webId' scopes=${scopes.size} " +
          "via='${logPrefix.trim('[', ']')}'"
    }

    var callbackUri = UrlUtil.addOrUpdateQueryParameter(URI(redirectUri), "code", code)
    state?.trim()?.takeIf { it.isNotBlank() }?.let {
      callbackUri = UrlUtil.addOrUpdateQueryParameter(callbackUri, "state", it)
    }
    return Response.seeOther(callbackUri).build()
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private fun tokenError(error: OAuthErrorCode, description: String): Response {
    val body = """{"error":"${error.code}","error_description":"$description"}"""
    // RFC 6749 §5.2 — error responses from the token endpoint follow the same cache rules
    // as successful ones.
    return Response.status(400)
      .entity(body)
      .type(MediaType.APPLICATION_JSON)
      .header("Cache-Control", "no-store")
      .header("Pragma", "no-cache")
      .build()
  }

  /**
   * The refusal when a caller has spent its budget at this endpoint.
   *
   * `slow_down` rather than an invented code: RFC 8628 registered it for the token endpoint, and
   * it says exactly this — you are asking too often, keep going more slowly. The status is 429
   * rather than the 400 [tokenError] uses, because nothing about the request itself is wrong.
   *
   * `Retry-After` is stated in whole seconds and deliberately as one flat number: the bucket
   * refills continuously, so any single value is a hint rather than a deadline, and the hint worth
   * giving is the window the budget itself is stated in.
   */
  private fun rateLimitedError(): Response {
    val body = """{"error":"slow_down","error_description":"too many token requests — retry later"}"""
    return Response.status(429)
      .entity(body)
      .type(MediaType.APPLICATION_JSON)
      .header("Retry-After", RETRY_AFTER_SECONDS)
      // RFC 6749 §5.2 — the token endpoint's cache rules hold for every answer it gives.
      .header("Cache-Control", "no-store")
      .header("Pragma", "no-cache")
      .build()
  }

  /** The person this browser already proved itself as on this pod, or null. */
  private fun readSession(pod: String, cookieValue: String?): PodTokenIssuer.SessionPrincipal? =
    podTokenIssuer.readSession(pod, cookieValue)

  /**
   * Whether cookies may be marked `Secure`.
   *
   * Read from the configured base URL rather than from the request: behind a TLS-terminating proxy
   * every request arrives as http, so trusting the request would drop `Secure` on exactly the
   * deployment that needs it.
   */
  private val isSecureDeployment: Boolean get() = config.apiBaseUrl.startsWith("https://")

  /**
   * Cookie paths come from the same base URL the routes do — see [PodBrowserCookies]. Building
   * them from anything else is how a prefixed deployment ends up setting cookies the browser will
   * never send back.
   */
  private val cookies: PodBrowserCookies get() = PodBrowserCookies(config.apiBaseUrl, isSecureDeployment)

  private fun oauthError(
    redirectUri: String?,
    error: OAuthErrorCode,
    errorDescription: String,
    state: String?,
  ): Response {
    // R6: emit a single structured audit-log line per authorize-error so spike runs
    // can grep `[oauth/authorize-audit]` to reconstruct what each MCP client triggered.
    // `state` is opaque client text by definition and nothing ever looks at it; `redirectUri` is
    // the proven address on most paths into here but not demonstrably on all of them.
    // `errorDescription` is this endpoint's own, and stays plain.
    logger.info {
      "[oauth/authorize-audit] outcome=error error=${error.code} error_description=\"$errorDescription\" " +
          "state=${LogSafeText.of(state ?: "(none)")} " +
          "redirect_uri=${LogSafeText.of(redirectUri ?: "(none)")}"
    }
    if (redirectUri.isNullOrBlank()) {
      return Response.status(400).entity("${error.code}: $errorDescription").type("text/plain").build()
    }
    var uri = UrlUtil.addOrUpdateQueryParameter(URI(redirectUri), "error", error.code)
    uri = UrlUtil.addOrUpdateQueryParameter(uri, "error_description", errorDescription)
    // R6: an OAuth error may carry an `error_uri` pointing at a documentation page that
    // describes the recovery procedure (re-authorize with `prompt=login`, drop the connection,
    // etc.), the fragment anchoring one heading per error code — `docs/auth/oauth-errors.md`
    // has one for every code this method can emit.
    //
    // Only when a deployment has said where that page is served
    // (`SEMPODS_OAUTH_ERROR_DOC_BASE`). It used to be a literal pointing at sempods.org, which
    // is both somebody else's domain for a self-hoster and, today, a page nothing serves. The
    // parameter is optional (RFC 6749 §4.1.2.1); sending a person to a 404 is worse than
    // sending them nowhere.
    // Removed rather than skipped when there is nothing to point at. A registered `redirect_uri`
    // may carry a query of its own — `RedirectUri` keeps it — so a client registered as
    // `…/cb?error_uri=…` would otherwise receive its own value back looking exactly like one this
    // server chose, on a deployment that turned the parameter off. Overwrite or delete; never
    // leave somebody else's.
    val errorUri = config.oauthErrorUri(error.code)
    uri = if (errorUri != null) {
      UrlUtil.addOrUpdateQueryParameter(uri, "error_uri", errorUri)
    } else {
      UrlUtil.removeQueryParameter(uri, "error_uri")
    }
    state?.trim()?.takeIf { it.isNotBlank() }?.let {
      uri = UrlUtil.addOrUpdateQueryParameter(uri, "state", it)
    }
    return Response.temporaryRedirect(uri).build()
  }

  /**
   * Takes the string and parses it here — a value that is no URI at all is then a 400 like any
   * other bad parameter, rather than a `URISyntaxException` raised at the call site.
   */
  private fun isAllowedRedirectUri(podDbo: PodDbo, appId: String, redirectUriString: String): Boolean {
    // What an address may look like at all is `RedirectUri`'s question, asked through the same
    // method by `DidWebRedirectPolicy` and `OpenIdProviderEndpoint`. Load-bearing here because
    // `DidWeb.Target.covers` below matches host, port and path and says nothing about the scheme:
    // it alone would answer `did:web:example.org%3A8443` at `http://example.org:8443/cb`, and a
    // `did:web:` client sends no `code_challenge` to protect a code that travels there.
    if (!RedirectUri.isValid(redirectUriString)) return false
    val redirectUri = runCatching { URI(redirectUriString) }.getOrNull() ?: return false

    // Dynamic clients (RFC 7591): redirect_uri must match one of the values submitted at
    // registration time. Lookup is pod-scoped — a clientId from a different pod is rejected.
    // Loopback URIs (localhost / 127.0.0.1 / ::1 / 0.0.0.0) match with port stripped, per
    // RFC 8252 §7.3 — native clients legitimately bind an ephemeral port per invocation,
    // and the same rule already governs the `/register` fingerprint dedup.
    if (appId.startsWith("dyn:")) {
      val registration = dynamicClientStore.lookup(checkNotNull(podDbo.id), appId) ?: return false
      val requestedCanonical = RedirectUri.canonicalize(redirectUriString)
      return registration.redirectUris.any { registered ->
        RedirectUri.canonicalize(registered) == requestedCanonical
      }
    }

    val didTarget = DidWeb.targetOf(appId) ?: return false

    // Ahead of `covers`, which would otherwise answer for `did:web:localhost%3A5173` on its own
    // origin and never reach the development gate. `DidWebRedirectPolicy` carries the same
    // refusal, and its test asserts the production side: `Env.isDevelopment` is process-wide, so
    // this module's suite only ever runs as development.
    if (!Env.isDevelopment && RedirectUri.isLoopback(didTarget.host)) return false

    // `covers` matches on path segments, not on a string prefix. This endpoint used to compare
    // host and port only, so `did:web:example.org:mcp` would have been answered at
    // `https://example.org/other/cb` — a client scoped to one subtree receiving a code meant for
    // a sibling on the same host. The identity service asks the same question through the same
    // method, so the two cannot drift apart.
    if (didTarget.covers(redirectUri)) return true

    // Localhost redirect_uri only allowed in development — prevents authorization code
    // interception in production even though PKCE protects the token exchange.
    return Env.isDevelopment && RedirectUri.isLoopback(redirectUri.host?.trim()?.lowercase())
  }

  /**
   * What this pod makes of a submitted `client_id`.
   *
   * Three-valued because the two failures are statements about different things, and one `null`
   * for both made the endpoint say the wrong one. [Malformed] is about the **string**;
   * [Unregistered] is about this pod's **registration store**, and the string was fine. A client
   * whose `dyn:` registration had been cleared was told to fix a format that was never broken, and
   * the only way out was to disconnect and reconnect — found in production when a credential
   * revoke dropped the DCR rows.
   */
  private sealed interface ClientIdentity {
    /** A `did:web:` client, or a `dyn:` one this pod still holds a registration for. */
    data class Known(val clientId: String) : ClientIdentity

    /** Well-formed `dyn:<id>`, with no registration behind it here — cleared, expired, or another pod's. */
    data object Unregistered : ClientIdentity

    /** Absent, blank, outside RFC 6749's `*VSCHAR` ([ClientId]), or neither a `did:web:` nor a `dyn:` identity. */
    data object Malformed : ClientIdentity
  }

  private fun readClientId(podDbo: PodDbo, appId: String?): ClientIdentity {
    val normalized = appId?.trim()?.takeIf { it.isNotBlank() } ?: return ClientIdentity.Malformed
    if (!ClientId.isValid(normalized)) return ClientIdentity.Malformed
    return when {
      normalized.startsWith("did:web:") -> ClientIdentity.Known(normalized)
      normalized.startsWith("dyn:") ->
        if (dynamicClientStore.lookup(checkNotNull(podDbo.id), normalized) != null) {
          ClientIdentity.Known(normalized)
        } else {
          ClientIdentity.Unregistered
        }

      else -> ClientIdentity.Malformed
    }
  }


  /**
   * Builds the list of contexts for the consent UI.
   *
   * Each context shows read/write/manage checkboxes.
   * Pre-selected: existing grants from a previous authorization of this app.
   * No app-suggested scopes — the user always decides their own data topology.
   */
  private fun buildConsentContexts(
    userGrants: Set<String>,
    existingGrants: Set<String>,
  ): List<ConsentContext> {
    // Group user scopes by context URI
    val contextUris = userGrants
      .mapNotNull { scope ->
        val hashIndex = scope.lastIndexOf('#')
        if (hashIndex > 0) scope.substring(0, hashIndex) else null
      }
      .distinct()
      .sorted()

    return contextUris.map { uri ->
      val path = URI(uri).path?.trimStart('/') ?: uri
      // relativePath = everything after the pod name segment (e.g. "podname/public/tasks" → "public/tasks")
      val relativePath = path.substringAfter('/', path)
      val label = path.trimEnd('/').substringAfterLast('/')
      ConsentContext(
        uri = uri,
        relativePath = relativePath,
        label = label,
        readGranted = existingGrants.contains("$uri#read"),
        writeGranted = existingGrants.contains("$uri#write"),
        manageGranted = existingGrants.contains("$uri#manage"),
      )
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    /**
     * How long the browser keeps the login pin. Must outlive the parked request it guards
     * (`PodLoginStateStore`, 15 min) or the round trip times out at the shorter of the two.
     */
    private const val LOGIN_PIN_TTL_SECONDS = 15 * 60

    /** The consent form's named way out, as the submit button sends it. */
    private const val DISCONNECT_ACTION = "disconnect"

    /**
     * What a rate-limited caller is told to wait — the window the budget is stated in, which is
     * the only number that means anything about a bucket that refills continuously.
     */
    private const val RETRY_AFTER_SECONDS = 60

    /**
     * What a well-formed `dyn:` client_id with no registration behind it is answered with.
     *
     * It names the RFC 6749 code in prose because the response cannot be the error *document* that
     * would normally carry it: at this point in `/authorize` the redirect address is not yet known
     * to belong to the client, so nothing may travel by redirect (see the ordering note there).
     * Plain text going to whoever is holding the browser, then — and it says the one thing that
     * actually fixes it. Kept ASCII-only: the response declares no charset, so a typographic dash
     * would be the one part of it a client could garble.
     */
    private const val UNREGISTERED_CLIENT_MESSAGE =
      "invalid_client: this pod holds no registration for that client_id. It was removed, it " +
          "expired, or it belongs to a different pod. Register again at the registration_endpoint " +
          "and restart authorization."
  }
}

data class ConsentContext(
  val uri: String,
  val relativePath: String,
  val label: String,
  val readGranted: Boolean,
  val writeGranted: Boolean,
  val manageGranted: Boolean,
)
