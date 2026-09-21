package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.PersonIdentity
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.ClientMetadataUri
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Pkce
import org.sempods.auth.core.Redirectable
import org.sempods.auth.core.Secrets
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.OFFLINE_ACCESS_SCOPE
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.PodSignOut
import org.sempods.pods.oauth.PodTokenIssuer
import java.net.URI
import java.util.UUID

/**
 * What a pod does with `GET /authorize`: decide whether this client may be answered at all, who is
 * asking, and whether the answer is a code, the consent dialog, a trip to the id-server or a
 * refusal.
 *
 * **Every decision here is the pod's, and none of them is HTTP.** The result says which of the five
 * answers it is and what goes in it; nothing in this class knows about status codes, cookies,
 * templates or redirects. The endpoint binds the request and renders the answer.
 *
 * **Entered twice for one sign-in**: once by the client's browser with no identity, and once by the
 * login callback with the one the id-server asserted. Everything is re-validated on the second
 * entry rather than trusted from the first — the request was parked for up to fifteen minutes, and
 * the pod's clients, grants and public contexts can have changed in that time. That is also why the
 * session arrives as a parameter instead of being read here: the second entry has no cookie yet,
 * only the identity it has just established.
 */
class PodAuthorizeFlow @Inject internal constructor(
  private val authorizationCodeStore: AuthorizationCodeStore,
  private val podFacade: PodFacade,
  private val podGrantsFacade: PodGrantsFacade,
  private val dynamicClientStore: DynamicClientStore,
  private val consentDecisionStore: PodConsentDecisionStore,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val consentTransactionStore: ConsentTransactionStore,
  private val identityProvider: PodIdentityProvider,
  private val loginStateStore: PodLoginStateStore,
  private val podSignOut: PodSignOut,
) {

  internal fun authorize(
    pod: HostedPod,
    request: PodAuthorizeRequest,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodAuthorizeResult {
    val sessionIdentity = session?.let { PersonIdentity(webId = it.webId, alsoKnownAs = it.alsoKnownAs) }

    // R6: audit-log every authorize entry so cross-client spikes can replay the
    // exact request shape per MCP client. One line per request, kept short — the
    // outcome is logged separately by the matching error/issue path.
    // Ahead of `PodClientDirectory.identify`, which is the point of an audit line: these are raw
    // query parameters.
    logger.info {
      "[oauth/authorize-audit] outcome=start pod='${pod.name}' " +
          "client_id='${LogSafeText.of(request.clientId ?: "(none)")}' " +
          "redirect_uri='${LogSafeText.of(request.redirectUri ?: "(none)")}' " +
          "prompt='${LogSafeText.of(request.prompt ?: "(unset)")}' " +
          "scope='${LogSafeText.of(request.scope ?: "(unset)")}' " +
          "signed_in=${session != null}"
    }

    // ── Validate required params ──────────────────────────────────────────
    // Order is load-bearing: address first, client second, and only then a [Redirectable]. Until
    // the redirect_uri is known to belong to the client that named it, nothing may be *delivered*
    // by redirecting there, not even an error — [OAuthErrorDelivery] says what that costs.
    val normalizedRedirectUri = request.redirectUri?.trim()?.takeIf { it.isNotBlank() }
      ?: return PodAuthorizeResult.Refused(PodAuthorizeRefusal.MISSING_REDIRECT_URI)

    // Both refusals below stay direct answers for that reason. What differs between them is only
    // which of the two is said.
    val clients = PodClientDirectory.of(pod.id, dynamicClientStore)
    val normalizedClientId = when (val client = clients.identify(request.clientId)) {
      is PodClientIdentity.Known -> client.clientId
      PodClientIdentity.Unregistered -> {
        logger.info {
          "[oauth/authorize-audit] outcome=error error=invalid_client " +
              "error_description=\"client_id is not registered at this pod\" " +
              "pod='${pod.name}' client_id='${request.clientId?.trim()}' state=${request.state ?: "(none)"}"
        }
        return PodAuthorizeResult.Refused(PodAuthorizeRefusal.UNREGISTERED_CLIENT)
      }

      PodClientIdentity.Malformed -> return PodAuthorizeResult.Refused(PodAuthorizeRefusal.MALFORMED_CLIENT_ID)
    }

    // Holding this is the proof the rule above asks for, and there is no other way to reach a
    // redirected error from here.
    val redirectTarget = OAuthErrors.redirectTargetFor(clients, normalizedClientId, normalizedRedirectUri)
      ?: return PodAuthorizeResult.Refused(PodAuthorizeRefusal.REDIRECT_URI_NOT_ALLOWED)

    // The AS metadata advertises `response_types_supported: ["code"]`, and this is the flow that
    // has to make that true. The parameter was bound and never read, so anything at all —
    // including `token`, the implicit grant this project does not implement — reached the code
    // path for `code` and got an authorization code back. Now that the redirect address is
    // validated, the error can travel the way RFC 6749 §4.1.2.1 asks for.
    val requestedResponseType = request.responseType?.trim().orEmpty()
    if (requestedResponseType != "code") {
      return failed(
        redirectTarget, OAuthErrorCode.UNSUPPORTED_RESPONSE_TYPE,
        "response_type must be 'code'", request.state,
      )
    }

    // PKCE is mandatory for dynamic (public) clients. RFC 7591 dynamic clients always register
    // with `token_endpoint_auth_method=none`, so without PKCE an intercepted auth code can be
    // redeemed by anyone. Reject early before issuing a code.
    val trimmedCodeChallenge = request.codeChallenge?.trim()?.takeIf { it.isNotBlank() }
    val trimmedCodeChallengeMethod = request.codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() }
    if (normalizedClientId.startsWith(PodClientDirectory.DYNAMIC_PREFIX) && trimmedCodeChallenge == null) {
      return failed(
        redirectTarget, OAuthErrorCode.INVALID_REQUEST,
        "code_challenge is required for dynamic clients (PKCE)", request.state,
      )
    }
    // A challenge with a method this server cannot verify is refused here rather than at the
    // exchange. `S256` is case-sensitive (RFC 7636 §4.3) and it is the only method OAuth 2.1
    // allows, so `plain`, `s256` and an absent method are all unusable — and used to be found out
    // only at `/token`, after a code had been minted and the browser was gone. The client can act
    // on it here.
    if (trimmedCodeChallenge != null && !Pkce.isSupportedMethod(trimmedCodeChallengeMethod)) {
      return failed(
        redirectTarget, OAuthErrorCode.INVALID_REQUEST,
        "code_challenge_method must be ${Pkce.METHOD_S256}", request.state,
      )
    }

    // ── Parse `prompt` (multi-valued, space-separated per OIDC Core 1.0 §3.1.2.1) ──
    val promptValues = OAuthSyntax.parsePrompt(request.prompt)
    if (OAuthSyntax.isContradictoryPrompt(promptValues)) {
      // Spec: `none` is exclusive — if combined with anything else it's a request error.
      return failed(
        redirectTarget, OAuthErrorCode.INVALID_REQUEST,
        "prompt=none cannot be combined with other prompt values", request.state,
      )
    }

    // ── Parse requested scope (validation deferred) ───────────────────────
    // Parsing is cheap and infallible. Validation comes after JWT resolution
    // so the precedence is: invalid JWT > invalid scope > missing JWT. That
    // way `scope=public-read` cannot mask a manipulated token, and a
    // malformed `scope` on an unauthenticated request still yields
    // `invalid_scope` instead of `login_required`.
    // TODO: an unknown scope is dropped in silence, so a typo (`offline-access`) is answered with
    // a working token and no explanation. RFC 6749 §4.1.2.1 would have this be `invalid_scope`;
    // what it costs is a refusal for clients that send scope names from their own world, and which
    // of the clients in `docs/mcp/clients.md` those are is what the `[oauth/authorize]` log line
    // accumulates.
    val requestedScopes = OAuthSyntax.parseScope(request.scope)

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
      val publicContexts = podFacade.getPublicContexts(podName = pod.name)
      if (publicContexts.isEmpty()) {
        return failed(
          redirectTarget, OAuthErrorCode.CONSENT_REQUIRED,
          "pod has no public-read contexts", request.state,
        )
      }
      val anonymousPublicReadWebId =
        if (identity == null && requestedScopes == setOf(PUBLIC_READ_SCOPE) && "none" in promptValues) {
          "urn:sempods:anon:${UUID.randomUUID()}"
        } else null
      if (anonymousPublicReadWebId != null) {
        logger.info {
          "[oauth/authorize] public-read code issued: pod='${pod.name}', " +
              "clientId='$normalizedClientId', webId='$anonymousPublicReadWebId', anonymous=true"
        }
        return issueCode(
          pod = pod,
          clientId = normalizedClientId,
          webId = anonymousPublicReadWebId,
          scopes = setOf(PUBLIC_READ_SCOPE),
          target = redirectTarget,
          state = request.state,
          codeChallenge = trimmedCodeChallenge,
          codeChallengeMethod = trimmedCodeChallengeMethod,
          via = PodCodeIssuance.ANONYMOUS_PUBLIC_READ,
          session = null,
        )
      }
    }

    // ── Missing JWT → login flow (after scope is known to be well-formed) ─
    if (identity == null) {
      // prompt=none is `login_required` per spec: without a session — none yet, one expired, or one
      // its person has signed out of since — this server cannot answer without the interaction the
      // parameter forbids. With prompt=none combined with login/select_account we already errored
      // out above as `invalid_request`, so the prompt set is consistent here.
      if ("none" in promptValues) {
        return failed(redirectTarget, OAuthErrorCode.LOGIN_REQUIRED, "user is not authenticated", request.state)
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
        identityProvider.relyingParty(pod.name)
      } catch (e: Exception) {
        logger.warn(e) { "[oauth/authorize] identity provider discovery failed: pod='${pod.name}'" }
        return PodAuthorizeResult.Refused(PodAuthorizeRefusal.IDENTITY_PROVIDER_UNAVAILABLE)
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
          pod = pod.name,
          clientId = normalizedClientId,
          redirectUri = normalizedRedirectUri,
          clientState = request.state,
          scope = request.scope,
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
        "[oauth/authorize] Redirecting to login: pod='${pod.name}', " +
            "clientId='$normalizedClientId', forceReauth=$forceReauth, forwardedPrompt=${forwardedPrompt ?: "(none)"}"
      }
      logger.info {
        "[oauth/authorize-audit] outcome=login_redirect pod='${pod.name}' " +
            "client_id='$normalizedClientId' force_reauth=$forceReauth " +
            "forwarded_prompt='${forwardedPrompt ?: "(none)"}'"
      }
      return PodAuthorizeResult.Login(
        authorizationUrl = started.authorizationUrl,
        state = started.state,
        browserPin = browserPin,
      )
    }

    logger.info {
      "[oauth/authorize] JWT verified: pod='${pod.name}', clientId='$normalizedClientId', " +
          "webId='${identity.webId}', prompt=${promptValues.sorted().joinToString(" ").ifEmpty { "(unset)" }}, " +
          "scope=${LogSafeText.of(requestedScopes.sorted().joinToString(" ")).ifEmpty { "(unset)" }}"
    }

    // ── Resolve user's available contexts and existing grants ────────────
    val isOwner = podGrantsFacade.isPodOwner(pod, identity.allUris)
    val userGrants = podGrantsFacade.resolveUserGrants(pod, identity.allUris)

    // Deliberately the subject's own rows, not the person's. Auto-grant issues a code for this
    // WebID and does not re-key what it finds, while `resolveFromGrants` and the refresh path both
    // query the token's subject — so counting an alias's rows here would auto-grant a token with no
    // context permissions whose first refresh fails. Whether an app holds anything *at all* is a
    // different question, and the dialog's disconnect offer is where it is asked.
    val existingGrants = podGrantsFacade.appGrants(pod.id, normalizedClientId, listOf(identity.webId))

    logger.info {
      "[oauth/authorize] Grants pre-check: pod='${pod.name}', clientId='$normalizedClientId', " +
          "webId='${identity.webId}', isOwner=$isOwner, ownerExpected='${pod.owner}', " +
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
    val isDynamicClient = normalizedClientId.startsWith(PodClientDirectory.DYNAMIC_PREFIX)
    // An authorization that predates the lifetime control has no decision recorded, and this branch
    // renders nothing — so it could never acquire one. Once, therefore, it falls through to the
    // dialog instead, which is where it picks one up. Only where there is a dialog to fall through
    // to: `prompt=none` has none, so it keeps its silent code and the redirect looks unchanged.
    // What that code buys is nothing — carrying no generation, it is refused at the exchange — and
    // answering `consent_required` here instead is not worth changing a live contract for a state
    // the deployment step removes (`docs/auth/oauth.md` §"Refresh token rotation").
    val decisionRecorded =
      consentDecisionStore.find(pod.id, normalizedClientId, listOf(identity.webId)) != null
    val mayAutoGrant = decisionRecorded || "none" in promptValues
    if ("consent" !in promptValues && !isDynamicClient && existingGrants.isNotEmpty()) {
      // Re-issue auth-code when the user still has a grant for this app. Per-context grants
      // stay in the durable store and are resolved server-side per request; public-read is an
      // additive persisted grant, still valid as long as the pod has public contexts.
      val effectiveContextGrants = existingGrants.intersect(userGrants)
      val effectivePublicReadScope = if (
        PUBLIC_READ_SCOPE in existingGrants &&
        podFacade.getPublicContexts(podName = pod.name).isNotEmpty()
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
          pod = pod,
          appId = normalizedClientId,
          webId = identity.webId,
          subjectUris = identity.allUris,
          grants = persisted,
          grantedBy = identity.webId,
        )
        logger.info {
          "[oauth/auto-grant] Narrowed stale grants: pod='${pod.name}', clientId='$normalizedClientId', " +
              "webId='${identity.webId}', before=${existingGrants.size}, after=${persisted.size}"
        }
      }
      // Auto-grant if anything is still granted and the person has answered once; the slim token
      // carries only feature scopes. Falls through to the consent UI when nothing survived, rather
      // than handing out a token that authorizes nothing — and when nothing has been answered,
      // which is the dialog this authorization needs. The repair above happens either way: it is
      // what a failed cascade is owed, and it has nothing to do with which of the two follows.
      if (persisted.isNotEmpty() && mayAutoGrant) {
        return issueCode(
          pod = pod,
          clientId = normalizedClientId,
          webId = identity.webId,
          scopes = effectivePublicReadScope,
          target = redirectTarget,
          state = request.state,
          codeChallenge = trimmedCodeChallenge,
          codeChallengeMethod = trimmedCodeChallengeMethod,
          via = PodCodeIssuance.AUTO_GRANT,
          session = session,
          // The subject's own document, because that is the one redemption will read: the newest
          // across the person's URIs is what the dialog wants, and binding to it would refuse a
          // code the moment an alias carried a higher count.
          consentGeneration = consentDecisionStore
            .find(pod.id, normalizedClientId, listOf(identity.webId))?.generation,
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
        val publicContexts = podFacade.getPublicContexts(podName = pod.name)
        val desc = if (publicContexts.isNotEmpty()) {
          "no app-specific scopes; re-authorize with scope=$PUBLIC_READ_SCOPE for read-only access"
        } else {
          "no app-specific scopes available for this user"
        }
        return failed(redirectTarget, OAuthErrorCode.CONSENT_REQUIRED, desc, request.state)
      }
      return failed(
        redirectTarget, OAuthErrorCode.CONSENT_REQUIRED, "user has not granted access to this app", request.state,
      )
    }

    // Non-owner with no scopes: soft-fail. When the pod has public contexts,
    // render the consent UI with public-read pre-selected — that's the
    // interactive read-only path for a stranger reaching a pod for the first
    // time. Without public contexts there's nothing they could possibly
    // consent to; keep the error.
    if (!isOwner && userGrants.isEmpty()) {
      val publicContexts = podFacade.getPublicContexts(podName = pod.name)
      if (publicContexts.isEmpty()) {
        return failed(
          redirectTarget, OAuthErrorCode.CONSENT_REQUIRED,
          "no app-specific scopes available for this user", request.state,
        )
      }
      return consentScreen(
        pod = pod,
        identity = identity,
        normalizedClientId = normalizedClientId,
        normalizedRedirectUri = normalizedRedirectUri,
        state = request.state,
        codeChallenge = trimmedCodeChallenge,
        codeChallengeMethod = trimmedCodeChallengeMethod,
        publicContexts = publicContexts.map { it.toString() }.sorted(),
        publicReadPreselected = true,
        durableRequested = OFFLINE_ACCESS_SCOPE in requestedScopes,
        userGrants = emptySet(),
        existingGrants = emptySet(),
        isOwner = false,
      )
    }

    // ── Render consent page ──────────────────────────────────────────────
    val publicContextsForUi = podFacade.getPublicContexts(podName = pod.name).map { it.toString() }.sorted()
    // Public-Read default: ticked unless the caller already has explicit
    // grants and `public-read` is not among them (i.e. the user previously
    // unticked it). For first-time consent (no existing grants), default
    // ticked — that's the additive-model expectation.
    val publicReadPreselected = if (existingGrants.isEmpty()) true
    else PUBLIC_READ_SCOPE in existingGrants
    return consentScreen(
      pod = pod,
      identity = identity,
      normalizedClientId = normalizedClientId,
      normalizedRedirectUri = normalizedRedirectUri,
      state = request.state,
      codeChallenge = trimmedCodeChallenge,
      codeChallengeMethod = trimmedCodeChallengeMethod,
      publicContexts = publicContextsForUi,
      publicReadPreselected = publicReadPreselected,
      durableRequested = OFFLINE_ACCESS_SCOPE in requestedScopes,
      userGrants = userGrants,
      existingGrants = existingGrants,
      isOwner = isOwner,
    )
  }

  /**
   * Mint an authorization code for [webId] and say where to send it — a [PodAuthorizeResult.Code],
   * or a [PodAuthorizeResult.Error] where one of the two checks below refuses.
   *
   * Reached from the two branches above and from the consent submission, which is why the caller
   * says which ([PodCodeIssuance]).
   *
   * @param session the sign-in this code is being issued under, or `null` for the anonymous
   *   public-read code, which has none. Re-checked here rather than only at the entrance — see the
   *   note at the check itself.
   */
  internal fun issueCode(
    pod: HostedPod,
    clientId: String,
    webId: String,
    scopes: Set<String>,
    target: Redirectable,
    state: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    via: PodCodeIssuance,
    consentGeneration: Long? = null,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodAuthorizeResult {
    // Defense-in-depth: even if a code path reaches here without /authorize's PKCE check,
    // never mint an auth code for a dynamic (public) client without PKCE.
    if (clientId.startsWith(PodClientDirectory.DYNAMIC_PREFIX)) {
      if (codeChallenge.isNullOrBlank() || !Pkce.isSupportedMethod(codeChallengeMethod)) {
        return failed(
          target, OAuthErrorCode.INVALID_REQUEST,
          "PKCE (S256) is required for dynamic clients", state,
        )
      }
    }
    // Asked again, now that [consentGeneration] has been read. A sign-out landing between the session
    // read and that one moves the generation first, and the code would carry the moved generation and
    // redeem. The sign-out writes its instant before it moves the generation, so a code that could
    // carry the moved one finds the instant here.
    if (session != null && !podSignOut.sessionStands(pod.id, session)) {
      return failed(target, OAuthErrorCode.ACCESS_DENIED, "signed out", state)
    }
    val code = authorizationCodeStore.issue(
      realm = pod.name,
      clientId = clientId,
      subject = webId,
      scopes = scopes,
      redirectUri = target.uri,
      codeChallenge = codeChallenge,
      codeChallengeMethod = codeChallengeMethod,
      consentGeneration = consentGeneration,
    )

    logger.info {
      "[${via.tag}] Authorization code issued: pod='${pod.name}', clientId='$clientId', " +
          "webId='$webId', scopes=${scopes.size}"
    }
    // R6: terminal audit line for the success path. Pairs with the `outcome=start`
    // entry at the top of authorize() (and with consent-submission requests, which
    // also funnel through this helper).
    logger.info {
      "[oauth/authorize-audit] outcome=issued_code pod='${pod.name}' " +
          "client_id='$clientId' web_id='$webId' scopes=${scopes.size} " +
          "via='${via.tag}'"
    }
    return PodAuthorizeResult.Code(code = code, target = target, state = state)
  }

  /**
   * What the consent dialog shows. Reached by the ordinary authorize path (per-context scope
   * checkboxes plus an optional public-read toggle) and by the public-read path
   * (`scope=public-read&prompt=consent`, where [publicReadPreselected] is true).
   *
   * For the public-read path, [userGrants] / [existingGrants] are not relevant and are empty — the
   * template only shows the public-read section.
   */
  private fun consentScreen(
    pod: HostedPod,
    identity: PersonIdentity,
    normalizedClientId: String,
    normalizedRedirectUri: String,
    state: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    publicContexts: List<String>,
    publicReadPreselected: Boolean,
    durableRequested: Boolean,
    userGrants: Set<String>,
    existingGrants: Set<String>,
    isOwner: Boolean,
  ): PodAuthorizeResult {
    val contexts = consentContexts(userGrants, existingGrants)
    val registration = if (normalizedClientId.startsWith(PodClientDirectory.DYNAMIC_PREFIX)) {
      dynamicClientStore.lookup(pod.id, normalizedClientId)
    } else null
    val displayName = registration?.clientName?.takeIf { it.isNotBlank() } ?: normalizedClientId

    // What the person decided last time outranks what the client asked for this time: a request
    // cannot quietly re-tick a box somebody cleared. With nothing recorded the request decides,
    // which is all `offline_access` does — it preselects, it does not grant.
    val recordedDurable = consentDecisionStore
      .find(pod.id, normalizedClientId, identity.allUris)
      ?.durable
    val durablePreselected = recordedDurable ?: durableRequested

    logger.info {
      "[oauth/authorize] Showing consent UI: pod='${pod.name}', clientId='$normalizedClientId', " +
          "clientName='${registration?.clientName ?: "(unset)"}', " +
          "webId='${identity.webId}', availableContexts=${contexts.size}, " +
          "publicContexts=${publicContexts.size}, publicReadPreselected=$publicReadPreselected, " +
          "durablePreselected=$durablePreselected"
    }
    logger.info {
      "[oauth/authorize-audit] outcome=consent_ui pod='${pod.name}' " +
          "client_id='$normalizedClientId' web_id='${identity.webId}' " +
          "available_contexts=${contexts.size} existing_grants=${existingGrants.size} " +
          "public_read_preselected=$publicReadPreselected durable_preselected=$durablePreselected"
    }
    return PodAuthorizeResult.Consent(
      PodConsentScreen(
        podName = pod.name,
        podBaseUrl = pod.baseUrl,
        clientId = normalizedClientId,
        clientName = displayName,
        // The consumer [ClientMetadataUri] exists for: whatever a template does with these, the
        // value reached it through that check.
        clientUri = registration?.clientUri?.takeIf(ClientMetadataUri::isValid),
        logoUri = registration?.logoUri?.takeIf(ClientMetadataUri::isValid),
        redirectUri = normalizedRedirectUri,
        state = state?.trim()?.takeIf { it.isNotBlank() },
        codeChallenge = codeChallenge,
        codeChallengeMethod = codeChallengeMethod,
        // One screen, once — see [ConsentTransactionStore]. Not a credential on its own: spending
        // it also requires the session cookie it was rendered beside.
        // Bound to the subject's own document, which is what the submission will be compared
        // against — the newest across the person's URIs is what the control above wants.
        csrfToken = consentTransactionStore.issue(
          pod.name,
          identity.webId,
          consentDecisionStore.find(pod.id, normalizedClientId, listOf(identity.webId))?.generation,
        ),
        webId = identity.webId,
        contexts = contexts,
        isOwner = isOwner,
        publicContexts = publicContexts,
        publicReadPreselected = publicReadPreselected,
        durablePreselected = durablePreselected,
        sessionTerms = refreshTokenStore.termsOf(PodRefreshTokenStore.Lifetime.SESSION),
        durableTerms = refreshTokenStore.termsOf(PodRefreshTokenStore.Lifetime.DURABLE),
        // Removing an app's access is only on offer where there is something to remove. Saying it
        // happened on a first authorization would be the same lie as saying nothing happened on a
        // later one. Asked over the person rather than over this URI, because that is what the
        // action itself clears.
        disconnectAvailable =
          podGrantsFacade.appGrants(pod.id, normalizedClientId, identity.allUris).isNotEmpty(),
      ),
    )
  }

  /**
   * The contexts the dialog lists, one row of read/write/manage per context the person can reach.
   *
   * Pre-ticked from the grants a previous authorization of this app left. No app-suggested scopes —
   * the person always decides their own data topology.
   */
  private fun consentContexts(
    userGrants: Set<String>,
    existingGrants: Set<String>,
  ): List<PodConsentContext> {
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
      PodConsentContext(
        uri = uri,
        relativePath = relativePath,
        label = label,
        readGranted = existingGrants.contains("$uri#read"),
        writeGranted = existingGrants.contains("$uri#write"),
        manageGranted = existingGrants.contains("$uri#manage"),
      )
    }
  }

  /** An error that may travel to the client's own address, because [target] is the proof it may. */
  private fun failed(
    target: Redirectable,
    error: OAuthErrorCode,
    description: String,
    state: String?,
  ): PodAuthorizeResult =
    PodAuthorizeResult.Error(OAuthErrorDelivery.Redirect(target, error, description, state))

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/**
 * `/authorize`'s parameters as the browser sent them — untrimmed, unvalidated, any of them absent.
 *
 * Raw on purpose: what counts as blank, what a missing `response_type` means and which of them may
 * carry whitespace are decisions, and they are [PodAuthorizeFlow]'s. A binding that trimmed on the
 * way in would be making the first of them where nobody would look for it.
 */
internal data class PodAuthorizeRequest(
  val responseType: String?,
  val clientId: String?,
  val redirectUri: String?,
  val state: String?,
  val codeChallenge: String?,
  val codeChallengeMethod: String?,
  val prompt: String?,
  val scope: String?,
)

/**
 * What an authorization answers.
 *
 * Five answers. [Error] carries a [Redirectable] and [Refused] does not, which is
 * [OAuthErrorDelivery]'s rule in the shape of a type: the cases that reach [Refused] have no proof
 * yet that the address belongs to the client that named it.
 */
internal sealed interface PodAuthorizeResult {

  /**
   * A code was minted. [target] is where it goes and [state] is what travels beside it — the
   * address is not assembled here because appending a parameter and overwriting one are different
   * answers, and that difference is the adapter's (`PodOAuthErrorResponses`).
   */
  data class Code(val code: String, val target: Redirectable, val state: String?) : PodAuthorizeResult

  /** The dialog, with everything it has to show already decided. */
  data class Consent(val screen: PodConsentScreen) : PodAuthorizeResult

  /**
   * The person is not signed in, so the request is parked and the browser goes to the id-server.
   *
   * [browserPin] is the second factor that ties the callback to *this* browser — see where it is
   * minted. The adapter is what puts it somewhere the browser will send back.
   */
  data class Login(val authorizationUrl: String, val state: String, val browserPin: String) : PodAuthorizeResult

  /** An OAuth error, delivered the way [OAuthErrorDelivery] says it may be. */
  data class Error(val delivery: OAuthErrorDelivery) : PodAuthorizeResult

  /** A refusal that is not an OAuth error document — see [PodAuthorizeRefusal]. */
  data class Refused(val reason: PodAuthorizeRefusal) : PodAuthorizeResult
}

/**
 * Why an authorization was refused without an OAuth error document.
 *
 * Four of these are the ordering rule from the top of [PodAuthorizeFlow.authorize]: until the
 * `redirect_uri` is known to belong to the client that named it, nothing may travel there, not even
 * an error. The fifth is the id-server being unreachable, which is this deployment's fault and not
 * the request's.
 *
 * The reason is named and the sentence is not. `/authorize` answers a malformed `client_id` with
 * "client_id must be a did:web or dyn: identity" and the consent submission with "invalid
 * client_id"; each route keeps its own wording, and neither is a decision this layer makes.
 */
internal enum class PodAuthorizeRefusal {
  MISSING_REDIRECT_URI,
  UNREGISTERED_CLIENT,
  MALFORMED_CLIENT_ID,
  REDIRECT_URI_NOT_ALLOWED,
  IDENTITY_PROVIDER_UNAVAILABLE,
}

/**
 * Which of the three ways to an authorization code was taken, as both log lines name it.
 *
 * A closed set because `via=` is an operator-facing audit field: grouping a spike by it only works
 * while nobody can spell a fourth value.
 */
internal enum class PodCodeIssuance(val tag: String) {

  /** `scope=public-read&prompt=none` with nobody signed in — the one code with no person behind it. */
  ANONYMOUS_PUBLIC_READ("oauth/public-read/anon"),

  /** Grants stood and the person had answered once, so no dialog was shown. */
  AUTO_GRANT("oauth/auto-grant"),

  /** The dialog was submitted. */
  CONSENT("oauth/consent"),
}
