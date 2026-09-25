package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.PersonIdentity
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.ClientMetadataUri
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Pkce
import org.sempods.auth.core.Redirectable
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.OFFLINE_ACCESS_SCOPE
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.grants.ScopeValidationResult
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.PodTokenIssuer
import java.net.URI
import java.util.UUID

/**
 * What a pod does with `GET /authorize`: decide whether this client may be answered at all, who is
 * asking, and whether the answer is a code, the consent dialog, a trip to the id-server or a
 * refusal.
 *
 * **Every decision here is the pod's, and none of them is HTTP.** The endpoint binds the request
 * and renders the [PodAuthorizeResult] this hands back.
 *
 * **Entered twice for one sign-in**: once by the client's browser with no identity, and once by the
 * login callback with the one the id-server asserted. Everything is re-validated on the second
 * entry rather than trusted from the first — the request was parked for up to fifteen minutes, and
 * the pod's clients, grants and public contexts can have changed in that time. That is also why the
 * session arrives as a parameter instead of being read here: the second entry has no cookie yet,
 * only the identity it has just established.
 */
class PodAuthorizeFlow @Inject internal constructor(
  private val codes: PodAuthorizationCodes,
  private val podFacade: PodFacade,
  private val podGrantsFacade: PodGrantsFacade,
  private val dynamicClientStore: DynamicClientStore,
  private val consentDecisionStore: PodConsentDecisionStore,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val consentTransactionStore: ConsentTransactionStore,
  private val signIn: PodSignIn,
  private val podScopeValidator: PodScopeValidator,
  private val appHoldings: PodAppHoldings,
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

    val clientState = suppliedState(request.state)

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
              "pod='${pod.name}' client_id='${LogSafeText.of(request.clientId?.trim() ?: "(none)")}' " +
              "state=${LogSafeText.of(request.state ?: "(none)")}"
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
        "response_type must be 'code'", clientState,
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
        "code_challenge is required for dynamic clients (PKCE)", clientState,
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
        "code_challenge_method must be ${Pkce.METHOD_S256}", clientState,
      )
    }

    // ── Parse `prompt` (multi-valued, space-separated per OIDC Core 1.0 §3.1.2.1) ──
    val promptValues = OAuthSyntax.parsePrompt(request.prompt)
    if (OAuthSyntax.isContradictoryPrompt(promptValues)) {
      // Spec: `none` is exclusive — if combined with anything else it's a request error.
      return failed(
        redirectTarget, OAuthErrorCode.INVALID_REQUEST,
        "prompt=none cannot be combined with other prompt values", clientState,
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

    // ── A privileged feature scope stands alone ───────────────────────────
    // An authorization that arranges a service client never holds that service's rights: the
    // grants come from a second consent, rendered for the identity once it exists. A request for
    // both is a request for an installer that could write to the owner's data itself. Refused
    // rather than trimmed — a trim hands back a narrower token than was asked for and gives the
    // client nothing to notice it by.
    val privilegedRequested = requestedScopes.intersect(PodScopeValidator.privilegedFeatureScopes)
    if (privilegedRequested.isNotEmpty()) {
      val asked = privilegedRequested.sorted().joinToString(" ")
      val reachesData = requestedScopes.any {
        it == PUBLIC_READ_SCOPE || podScopeValidator.validate(it, pod.baseUrl) is ScopeValidationResult.Context
      }
      if (reachesData) {
        return failed(
          redirectTarget, OAuthErrorCode.INVALID_SCOPE,
          "'$asked' cannot be combined with $PUBLIC_READ_SCOPE or a context scope", clientState,
        )
      }
      // One per authorization: a bearer holding both would be an installer that reaches every
      // service's secret.
      if (privilegedRequested.size > 1) {
        return failed(
          redirectTarget, OAuthErrorCode.INVALID_SCOPE, "'$asked' are granted one at a time", clientState,
        )
      }
    }

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
          "pod has no public-read contexts", clientState,
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
        return codes.issue(
          pod = pod,
          clientId = normalizedClientId,
          webId = anonymousPublicReadWebId,
          scopes = setOf(PUBLIC_READ_SCOPE),
          target = redirectTarget,
          state = clientState,
          codeChallenge = trimmedCodeChallenge,
          codeChallengeMethod = trimmedCodeChallengeMethod,
          via = PodCodeIssuance.ANONYMOUS_PUBLIC_READ,
          session = null,
        ).asResult()
      }
    }

    // ── Missing JWT → login flow (after scope is known to be well-formed) ─
    if (identity == null) {
      // prompt=none is `login_required` per spec: without a session — none yet, one expired, or one
      // its person has signed out of since — this server cannot answer without the interaction the
      // parameter forbids. With prompt=none combined with login/select_account we already errored
      // out above as `invalid_request`, so the prompt set is consistent here.
      if ("none" in promptValues) {
        return failed(redirectTarget, OAuthErrorCode.LOGIN_REQUIRED, "user is not authenticated", clientState)
      }
      // Federate the login to the id-server as an ordinary OIDC relying party — [PodSignIn].
      //
      // It used to put this request's own URI into a `return_to` parameter and let the id-server
      // append an identity token to it — an address the id-server accepted from anyone, which is
      // what made that token collectable by whoever asked.
      //
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
      val started = signIn.park(pod, forwardedPrompt) { codeVerifier, nonce, browserPin ->
        PodLoginStateStore.Pending(
          pod = pod.name,
          clientId = normalizedClientId,
          redirectUri = normalizedRedirectUri,
          clientState = clientState,
          scope = request.scope,
          // The force-reauth values are satisfied by the login now beginning, and carrying them
          // back would send the user straight into another one. `consent` and the rest survive,
          // because a login does not satisfy them.
          prompt = promptValues.minus(OAuthSyntax.FORCE_REAUTH_PROMPTS).sorted().joinToString(" ").takeIf { it.isNotEmpty() },
          codeChallenge = trimmedCodeChallenge,
          codeChallengeMethod = trimmedCodeChallengeMethod,
          codeVerifier = codeVerifier,
          nonce = nonce,
          browserPin = browserPin,
        )
      } ?: return PodAuthorizeResult.Refused(PodAuthorizeRefusal.IDENTITY_PROVIDER_UNAVAILABLE)
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
        browserPin = started.browserPin,
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

    // ── An installation is always asked for ──────────────────────────────
    // Ahead of auto-grant, which is the branch that would otherwise answer a second installation
    // out of a standing consent. It never reaches it: the dialog is what a one-shot authority is
    // granted in, every time.
    if (privilegedRequested.isNotEmpty()) {
      val asked = privilegedRequested.sorted().joinToString(" ")
      if (!isOwner) {
        // Alias-aware, through `isOwner` above: the person may be signed in under any URI that
        // names them. Answered here rather than by leaving the item off the dialog, so a client
        // asking for an authority it cannot have learns that it cannot have it.
        return failed(
          redirectTarget, OAuthErrorCode.INVALID_SCOPE, "'$asked' is the pod owner's to grant", clientState,
        )
      }
      if ("none" in promptValues) {
        return failed(
          redirectTarget, OAuthErrorCode.CONSENT_REQUIRED, "'$asked' is granted at the dialog", clientState,
        )
      }
      return consentScreen(
        pod = pod,
        identity = identity,
        normalizedClientId = normalizedClientId,
        normalizedRedirectUri = normalizedRedirectUri,
        state = clientState,
        codeChallenge = trimmedCodeChallenge,
        codeChallengeMethod = trimmedCodeChallengeMethod,
        // The dialog shows the installation and nothing else. There is no data selection to make:
        // a request that carried one was refused above.
        publicContexts = emptyList(),
        publicReadPreselected = false,
        durableRequested = false,
        userGrants = emptySet(),
        existingGrants = emptySet(),
        isOwner = true,
        privilegedFeatures = privilegedRequested.sorted(),
      )
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
    // An authorization that predates the lifetime control has no lifetime answer on record, and this branch
    // renders nothing — so it could never acquire one. Once, therefore, it falls through to the
    // dialog instead, which is where it picks one up. Only where there is a dialog to fall through
    // to: `prompt=none` has none, so it keeps its silent code and the redirect looks unchanged.
    // What that code buys is nothing — the exchange refuses it for want of a generation or of an
    // answer — and
    // answering `consent_required` here instead is not worth changing a live contract for a state
    // the deployment step removes (`docs/auth/oauth.md` §"Refresh token rotation").
    val decisionRecorded = consentDecisionStore
      .find(pod.id, normalizedClientId, listOf(identity.webId))?.durable != null
    val mayAutoGrant = decisionRecorded || "none" in promptValues
    if ("consent" !in promptValues && !isDynamicClient && existingGrants.isNotEmpty()) {
      // Re-issue auth-code when the user still has a grant for this app. Per-context grants
      // stay in the durable store and are resolved server-side per request.
      val effectiveContextGrants = existingGrants.intersect(userGrants)
      // The feature scopes a row may still hand back without asking anyone. Privileged ones never
      // can: an installation authority is granted at a dialog, every time, and a stored grant that
      // named one would let an ordinary reconnect hand it back in silence. `public-read` keeps its
      // own condition — it means nothing on a pod with no public context.
      val effectiveFeatureScopes = existingGrants
        .intersect(PodScopeValidator.featureScopes)
        .minus(PodScopeValidator.privilegedFeatureScopes)
        .filterTo(mutableSetOf()) {
          it != PUBLIC_READ_SCOPE || podFacade.getPublicContexts(podName = pod.name).isNotEmpty()
        }
      // Persist the narrowed set. `PodGrantsFacade` cascades an owner-level revocation into these
      // rows already, so this is a repair path rather than the primary enforcement: it is the
      // idempotent second chance for a cascade write that never landed (no Mongo transactions
      // here), and it keeps the store from carrying grants this branch has just decided are stale.
      // The facade re-derives after writing, so `persisted` may be narrower still if an
      // owner-level change raced us.
      var persisted = effectiveContextGrants + effectiveFeatureScopes
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
        return codes.issue(
          pod = pod,
          clientId = normalizedClientId,
          webId = identity.webId,
          scopes = effectiveFeatureScopes,
          target = redirectTarget,
          state = clientState,
          codeChallenge = trimmedCodeChallenge,
          codeChallengeMethod = trimmedCodeChallengeMethod,
          via = PodCodeIssuance.AUTO_GRANT,
          session = session,
          // The subject's own document, because that is the one redemption will read: the newest
          // across the person's URIs is what the dialog wants, and binding to it would refuse a
          // code the moment an alias carried a higher count.
          consentGeneration = consentDecisionStore
            .find(pod.id, normalizedClientId, listOf(identity.webId))?.generation,
        ).asResult()
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
        return failed(redirectTarget, OAuthErrorCode.CONSENT_REQUIRED, desc, clientState)
      }
      return failed(
        redirectTarget, OAuthErrorCode.CONSENT_REQUIRED, "user has not granted access to this app", clientState,
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
          "no app-specific scopes available for this user", clientState,
        )
      }
      return consentScreen(
        pod = pod,
        identity = identity,
        normalizedClientId = normalizedClientId,
        normalizedRedirectUri = normalizedRedirectUri,
        state = clientState,
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
      state = clientState,
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
   * What the consent dialog shows. Reached by the ordinary authorize path (per-context scope
   * checkboxes plus an optional public-read toggle), by the public-read path
   * (`scope=public-read&prompt=consent`, where [publicReadPreselected] is true), and by the
   * installation path ([privilegedFeatures] non-empty).
   *
   * For the public-read path, [userGrants] / [existingGrants] are not relevant and are empty — the
   * template only shows the public-read section.
   *
   * @param privilegedFeatures the privileged feature scopes this request asked for. A dialog that
   *   carries one carries nothing else: no lifetime control, because a one-shot authority must not
   *   be turned into a renewable one by an ordinary tick, and no way out, because ending an
   *   authorization this screen is not about is not one click's worth of decision.
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
    privilegedFeatures: List<String> = emptyList(),
  ): PodAuthorizeResult {
    val contexts = consentContexts(userGrants, existingGrants)
    val registration = dynamicClientStore.registrationOf(pod.id, normalizedClientId)
    val displayName = clientDisplayName(registration, normalizedClientId)

    // What the person decided last time outranks what the client asked for this time: a request
    // cannot quietly re-tick a box somebody cleared. With nothing recorded the request decides,
    // which is all `offline_access` does — it preselects, it does not grant.
    // Two reads, over two identity sets, and the difference is load-bearing. The control is
    // pre-ticked from the newest answer across every URI that names the person. What the page is
    // *bound* to comes from the subject's own document, because that is the one the submission
    // will compare it against — read over the person here, a page rendered under a fresh alias
    // would carry a count the submission cannot see and be refused the moment it was posted.
    val subjectDecision = consentDecisionStore.find(pod.id, normalizedClientId, listOf(identity.webId))
    val recordedDurable = consentDecisionStore.find(pod.id, normalizedClientId, identity.allUris)?.durable
    val durablePreselected = recordedDurable ?: durableRequested

    logger.info {
      "[oauth/authorize] Showing consent UI: pod='${pod.name}', clientId='$normalizedClientId', " +
          "clientName='${registration?.clientName ?: "(unset)"}', " +
          "webId='${identity.webId}', availableContexts=${contexts.size}, " +
          "publicContexts=${publicContexts.size}, publicReadPreselected=$publicReadPreselected, " +
          "durablePreselected=$durablePreselected, " +
          "privilegedFeatures=${privilegedFeatures.joinToString(" ").ifEmpty { "(none)" }}"
    }
    logger.info {
      "[oauth/authorize-audit] outcome=consent_ui pod='${pod.name}' " +
          "client_id='$normalizedClientId' web_id='${identity.webId}' " +
          "available_contexts=${contexts.size} existing_grants=${existingGrants.size} " +
          "public_read_preselected=$publicReadPreselected durable_preselected=$durablePreselected " +
          "privileged_features='${privilegedFeatures.joinToString(" ")}'"
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
        state = state,
        codeChallenge = codeChallenge,
        codeChallengeMethod = codeChallengeMethod,
        // One screen, once — see [ConsentTransactionStore]. Not a credential on its own: spending
        // it also requires the session cookie it was rendered beside.
        csrfToken = consentTransactionStore.issue(
          pod.name,
          identity.webId,
          subjectDecision?.generation,
          // What this screen put to the person, so the submission can read its own kind from the
          // server rather than from a field the form carries.
          privilegedFeatures.toSet(),
          subjectDecision?.disconnects ?: 0L,
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
        disconnectAvailable = privilegedFeatures.isEmpty() &&
            appHoldings.holdsAnything(pod.id, normalizedClientId, identity.allUris),
        privilegedFeatures = privilegedFeatures,
        lifetimeAvailable = privilegedFeatures.isEmpty(),
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

  /** This route's answer to whatever minting a code said. */
  private fun PodCodeResult.asResult(): PodAuthorizeResult = when (this) {
    is PodCodeResult.Minted -> PodAuthorizeResult.Code(code, target, state)
    is PodCodeResult.Refused -> PodAuthorizeResult.Error(delivery)
  }

  /** An error at the client's own address — the rule [OAuthErrorDelivery] states. */
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
 * Raw on purpose: what counts as blank and which of them may carry whitespace are decisions, and a
 * binding that trimmed on the way in would be making the first of them where nobody would look.
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
 * [Error] carries a [Redirectable] and [Refused] does not — [OAuthErrorDelivery]'s rule as a type.
 */
internal sealed interface PodAuthorizeResult {

  /** A code was minted. [target] is where it goes; the adapter assembles the address. */
  data class Code(val code: String, val target: Redirectable, val state: String?) : PodAuthorizeResult

  /** The dialog, with everything it has to show already decided. */
  data class Consent(val screen: PodConsentScreen) : PodAuthorizeResult

  /**
   * The person is not signed in, so the request is parked and the browser goes to the id-server.
   *
   * [browserPin] ties the callback to *this* browser — see where it is minted. The adapter is what
   * puts it somewhere the browser will send back.
   */
  data class Login(val authorizationUrl: String, val state: String, val browserPin: String) : PodAuthorizeResult

  /** An OAuth error, delivered the way [OAuthErrorDelivery] says it may be. */
  data class Error(val delivery: OAuthErrorDelivery) : PodAuthorizeResult

  /** A refusal that is not an OAuth error document — see [PodAuthorizeRefusal]. */
  data class Refused(val reason: PodAuthorizeRefusal) : PodAuthorizeResult
}

/**
 * Why an authorization was refused without an OAuth error document: four by the ordering rule at
 * the top of [PodAuthorizeFlow.authorize], one because the id-server was unreachable.
 *
 * The reason is named and the sentence is not. `/authorize` answers a malformed `client_id` with
 * "client_id must be a did:web or dyn: identity" and the consent submission with "invalid
 * client_id"; each route keeps its own wording.
 */
internal enum class PodAuthorizeRefusal {
  MISSING_REDIRECT_URI,
  UNREGISTERED_CLIENT,
  MALFORMED_CLIENT_ID,
  REDIRECT_URI_NOT_ALLOWED,
  IDENTITY_PROVIDER_UNAVAILABLE,
}
