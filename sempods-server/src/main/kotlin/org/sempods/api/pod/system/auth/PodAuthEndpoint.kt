package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.io.IOException
import java.net.URI
import java.time.Instant
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.PersonIdentity
import org.sempods.auth.PodBrowserCookies
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.ClientMetadataUri
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.RedirectUri
import org.sempods.auth.core.Redirectable
import org.sempods.auth.core.Secrets
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.BasicAuth
import org.sempods.commons.net.ForwardedFor
import org.sempods.pods.PodFacade
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.contexts.ContextUriResolution
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.flows.PodAuthorizeFlow
import org.sempods.pods.oauth.flows.PodAuthorizeRequest
import org.sempods.pods.oauth.flows.PodAuthorizeResult
import org.sempods.pods.oauth.flows.PodClientDirectory
import org.sempods.pods.oauth.flows.PodClientIdentity
import org.sempods.pods.oauth.flows.PodCodeIssuance
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.PodSignOut
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.pods.oauth.flows.PodTokenExchange
import org.sempods.pods.oauth.flows.PodTokenResult
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore

@Path("{pod}/_system/auth")
class PodAuthEndpoint @Inject constructor(
  private val podAuthorizeFlow: PodAuthorizeFlow,
  private val podTokenExchange: PodTokenExchange,
  private val podGrantsFacade: PodGrantsFacade,
  private val dynamicClientStore: DynamicClientStore,
  private val templateRenderer: TemplateRenderer,
  private val podTokenIssuer: PodTokenIssuer,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val consentDecisionStore: PodConsentDecisionStore,
  private val podSignOut: PodSignOut,
  private val tokenRateLimiter: PodTokenRateLimiter,
  private val podContextsDao: PodContextsDao,
  private val podServiceClientStore: PodServiceClientStore,
  private val identityProvider: PodIdentityProvider,
  private val loginStateStore: PodLoginStateStore,
  private val consentTransactionStore: ConsentTransactionStore,
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
    // `PodClientDirectory.permits` would refuse is a registration no login can honour.
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
      registeredForPod = podDbo.podId(),
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
    // A fingerprint hit returns the *stored* row and discards the body just validated, so none of
    // these is the value those checks saw. Same reason [ClientMetadataUri] is asked again on read.
    logger.info {
      "[oauth/register] $action: pod='$pod', clientId='${registration.clientId}', " +
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
  ): Response {
    // Who the pod already knows, from a cookie on its own origin. Never from a parameter a browser
    // carried — that was the arrangement the OIDC cutover removed. A session saves the round trip
    // to the id-server and is what makes `prompt=none` answerable at all.
    val podDbo = fetchPodOrThrow(pod)
    val session = readSession(podDbo, sessionCookie)
    val answer = render(
      podDbo.name,
      podAuthorizeFlow.authorize(
        pod = podDbo.hosted,
        request = PodAuthorizeRequest(
          responseType = responseType,
          clientId = clientId,
          redirectUri = redirectUri,
          state = state,
          codeChallenge = codeChallenge,
          codeChallengeMethod = codeChallengeMethod,
          prompt = prompt,
          scope = scope,
        ),
        session = session,
      ),
    )
    return withRenewedSession(pod, session, answer)
  }

  /**
   * Extends the sign-in this request arrived with, on whatever the request answered.
   *
   * **This endpoint alone, and that is enough for what this buys.** Every consent screen and every
   * authorization arrives here, so connecting a second app, reconnecting one or passing a dialog
   * resets the clock, and the form submission that follows a dialog needs no renewal of its own.
   * Ordinary work does not reach here: a connection holding a refresh token renews on that one and
   * calls `/authorize` never again, so somebody who authorizes nothing is still forgotten twelve
   * hours after their last. Nothing else on the pod reads the cookie.
   *
   * Attached the way [oidcCallback] attaches the original, and for the same reason:
   * [PodAuthorizeFlow] has a dozen exits and threading a cookie through each is how one gets
   * missed. An error is renewed alongside a code, because what the client asked for does not
   * change whether the person is here.
   *
   * [pod] is safe to build a cookie path from precisely where there is a session to renew:
   * [PodTokenIssuer.readSession] compares the issuer against this string, so a principal exists
   * only for a pod name that matched one this server minted.
   */
  private fun withRenewedSession(
    pod: String,
    session: PodTokenIssuer.SessionPrincipal?,
    answer: Response,
  ): Response {
    val renewed = session?.let { podTokenIssuer.renewSession(pod, it) } ?: return answer
    // `Max-Age` from the renewal, for the reason on [PodTokenIssuer.RenewedSession.ttlSeconds]:
    // near the absolute deadline it is shorter than the idle window.
    return Response.fromResponse(answer)
      .cookie(cookies.session(pod, renewed.token, renewed.ttlSeconds.toInt()))
      .build()
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
    val clients = PodClientDirectory.of(podDbo.podId(), dynamicClientStore)
    val normalizedClientId = when (val client = clients.identify(clientId)) {
      is PodClientIdentity.Known -> client.clientId
      PodClientIdentity.Unregistered -> return Response.status(400)
        .entity(PodAuthorizeResponses.UNREGISTERED_CLIENT_MESSAGE).type("text/plain").build()

      PodClientIdentity.Malformed -> return Response.status(400)
        .entity("invalid client_id").type("text/plain").build()
    }

    val normalizedRedirectUri = redirectUri?.trim()?.takeIf { it.isNotBlank() }
      ?: return Response.status(400).entity("missing redirect_uri").type("text/plain").build()

    val redirectTarget = OAuthErrors.redirectTargetFor(clients, normalizedClientId, normalizedRedirectUri)
      ?: return Response.status(400).entity("redirect_uri not allowed for this client_id").type("text/plain").build()

    // Two questions, two answers. The session says *who* is submitting; the transaction says
    // *which screen* this is, and that it has not been submitted before. Neither alone is enough:
    // a session-derived token would be the same on every screen the session outlives (so a stale
    // page could be replayed over a narrower consent), and a transaction alone could be lifted out of a
    // page and spent from another browser.
    val session = readSession(podDbo, sessionCookie)
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

    // Ahead of the check below, which refuses a page rendered before this app was disconnected. That
    // check keeps an old page from writing grants back; a sign-out writes none, and refusing it would
    // leave the person signed in with no way out on the page in front of them.
    if (action?.trim() == SIGN_OUT_ACTION) {
      podSignOut.signOut(podDbo.podId(), podDbo.name, identity.allUris)
      return Response.fromResponse(
        oauthError(redirectTarget, OAuthErrorCode.ACCESS_DENIED, "signed out", state),
      ).cookie(cookies.clearSession(podDbo.name)).build()
    }

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
      .find(podDbo.podId(), normalizedClientId, listOf(identity.webId))
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
        disconnectApp(podDbo, normalizedClientId, identity, redirectTarget, state)
      } else {
        oauthError(redirectTarget, OAuthErrorCode.ACCESS_DENIED, "no scopes selected", state)
      }
    }

    val podBaseUrl = "${config.apiBaseUrl}${podDbo.name}/"
    val hostedPod = podDbo.hosted
    val isOwner = podGrantsFacade.isPodOwner(hostedPod, identity.allUris)

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
        disconnectApp(podDbo, normalizedClientId, identity, redirectTarget, state)
      } else {
        oauthError(redirectTarget, OAuthErrorCode.ACCESS_DENIED, "no scopes selected", state)
      }
    }

    if (publicReadRequested) {
      // public-read needs at least one public context to be meaningful — drop
      // it from the grant set if the pod has no public contexts (rather than
      // erroring; the user may still want the per-context grants).
      val publicContexts = podFacade.getPublicContexts(podName = podDbo.name)
      if (publicContexts.isEmpty() && perContextSubmitted.isEmpty() && newContextsRequested.isEmpty()) {
        return oauthError(
          redirectTarget, OAuthErrorCode.CONSENT_REQUIRED,
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
    val userGrants = podGrantsFacade.resolveUserGrants(hostedPod, identity.allUris)
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
        disconnectApp(podDbo, normalizedClientId, identity, redirectTarget, state)
      } else {
        oauthError(redirectTarget, OAuthErrorCode.ACCESS_DENIED, "no scopes selected", state)
      }
    }

    // Persist the user's grant selection for this app (replace — the checkbox submission is the
    // authoritative new state, so any previously granted scope the user unchecked must be revoked).
    // The facade re-derives after writing, so an owner-level revocation that landed between
    // `resolveUserGrants` above and this write cannot leave an unbacked grant behind. What comes
    // back is what actually survived.
    val persistedScopes = podGrantsFacade.replaceAppGrants(
      pod = hostedPod,
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
        redirectTarget, OAuthErrorCode.CONSENT_REQUIRED,
        "granted access changed while consenting; please re-authorize", state,
      )
    }

    // **The answer is written after the grants, and a run dying between them leaves the safe
    // half.** What survives is the selection the person just made, under the answer that stood
    // before it — so a narrowing takes effect, and the durability question keeps its previous
    // answer rather than acquiring one nobody gave. Writing the answer first inverts exactly that:
    // the old, wider grants would stand under a *new* generation, the narrowing silently lost and
    // the credentials it was meant to end still rotating.
    //
    // The pair this order can leave — grants with no answer beside them, on a first consent — is
    // harmless since a code carrying no generation is refused at the exchange: nothing redeems,
    // auto-grant needs a decision it does not have, and the next visit renders this dialog again.
    val durableGranted = durable != null
    val decision = recordDecision(podDbo, normalizedClientId, identity, durable = durableGranted)
    if (!durableGranted) {
      // Withholding is not merely declining to extend: the families this authorization already has
      // would otherwise keep rotating, and the person would have changed nothing they can observe.
      val revoked = refreshTokenStore.revokeForUser(
        pod = podDbo.podId(),
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

    return render(
      podDbo.name,
      podAuthorizeFlow.issueCode(
        pod = hostedPod,
        clientId = normalizedClientId,
        webId = identity.webId,
        scopes = tokenFeatureScopes,
        target = redirectTarget,
        state = state,
        codeChallenge = codeChallenge?.trim()?.takeIf { it.isNotBlank() },
        codeChallengeMethod = codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() },
        via = PodCodeIssuance.CONSENT,
        consentGeneration = decision.generation,
        session = session,
      ),
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
    val pod = podDbo.podId()
    val forSubject = consentDecisionStore.record(pod, clientId, identity.webId, durable)
    identity.allUris.filterNot { it == identity.webId }.forEach { alias ->
      consentDecisionStore.record(pod, clientId, alias, durable)
    }
    return forSubject
  }

  /**
   * Whether this app holds anything for this person — the question that decides both whether the
   * way out is offered and whether taking it means anything. Asked over every URI that names the
   * person: an authorization stored under an alias is one they can still end.
   */
  private fun holdsAnything(podDbo: PodDbo, clientId: String, identity: PersonIdentity): Boolean =
    podGrantsFacade.appGrants(podDbo.podId(), clientId, identity.allUris).isNotEmpty()

  private fun disconnectApp(
    podDbo: PodDbo,
    clientId: String,
    identity: PersonIdentity,
    target: Redirectable,
    state: String?,
  ): Response {
    // Once per URI that names the person, because that is how the rows are keyed: an authorization
    // made under an alias is one this person can end, and `holdsAnything` already counted it.
    val hostedPod = podDbo.hosted
    identity.allUris.forEach { uri ->
      podGrantsFacade.replaceAppGrants(
        pod = hostedPod,
        appId = clientId,
        webId = uri,
        subjectUris = identity.allUris,
        grants = emptySet(),
        grantedBy = identity.webId,
      )
    }
    val decision = recordDecision(podDbo, clientId, identity, durable = false)
    val revoked = refreshTokenStore.revokeForUser(hostedPod.id, clientId, identity.allUris)
    logger.info {
      "[oauth/consent] App disconnected: pod='${podDbo.name}', clientId='$clientId', " +
          "webId='${identity.webId}', revokedRows=$revoked, generation=${decision.generation}"
    }
    return oauthError(target, OAuthErrorCode.ACCESS_DENIED, "app disconnected", state)
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
      return PodTokenResponses.rateLimited()
    }

    val podDbo = fetchPodOrThrow(pod)

    return when (grantType) {
      "authorization_code" -> podTokenExchange.redeemCode(
        pod = podDbo.podId(),
        podName = podDbo.name,
        code = code,
        redirectUri = redirectUri,
        clientId = clientId,
        codeVerifier = codeVerifier,
      ).asResponse()

      "refresh_token" -> podTokenExchange.refresh(
        pod = podDbo.podId(),
        podName = podDbo.name,
        refreshToken = refreshToken,
        clientId = clientId,
        requestedScope = scope,
      ).asResponse()

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
   * The exchange's answer on the wire.
   *
   * `scope` is omitted where the bearer carries no feature scope — the rule and its reason are
   * [PodTokenResponses.tokens]'.
   */
  private fun PodTokenResult.asResponse(): Response = when (this) {
    is PodTokenResult.Issued -> PodTokenResponses.tokens(
      accessToken = accessToken,
      expiresInSeconds = expiresInSeconds,
      scope = OAuthSyntax.formatScope(scopes).takeIf { scopes.isNotEmpty() },
      refreshToken = refreshToken,
    )

    is PodTokenResult.Refused -> PodTokenResponses.error(code, description)
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
      ?: return PodTokenResponses.clientAuthenticationRequired(
        realm = podDbo.name,
        description = "HTTP Basic authentication required",
      )

    val podId = checkNotNull(podDbo.id)
    val client = podServiceClientStore.authenticate(podId, basic.username, basic.password)
    if (client == null) {
      logger.info {
        "[oauth/token] client_credentials auth failed: pod='${podDbo.name}', " +
            "clientId='${LogSafeText.of(basic.username)}'"
      }
      return PodTokenResponses.clientAuthenticationRequired(
        realm = podDbo.name,
        description = "unknown client_id or invalid secret",
      )
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

    // `scope` is stated even when the set is empty, which is the ordinary shape today — see
    // [PodTokenResponses.tokens] for why this answer differs from the user token's there.
    return PodTokenResponses.tokens(
      accessToken = accessToken,
      expiresInSeconds = PodTokenIssuer.SERVICE_TOKEN_TTL_SECONDS,
      scope = OAuthSyntax.formatScope(tokenFeatureScopes),
    )
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
      return oauthErrorToParked(pending, upstreamClass, describedAs)
    }
    // Neither an error nor a code: nobody refused anything, the callback is malformed. `server_error`
    // rather than `access_denied`, so a client does not record a decision that was never made.
    val authorizationCode = code?.trim()?.takeIf { it.isNotBlank() }
      ?: return oauthErrorToParked(pending, OAuthErrorCode.SERVER_ERROR, "no authorization code")

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
      return oauthErrorToParked(pending, failureClass, "login failed")
    }

    logger.info {
      "[oauth/authorize] login completed: pod='${podDbo.name}', clientId='${pending.clientId}', " +
          "webId='${verified.webId}'"
    }
    // Remember the sign-in on this pod's own origin, so the next authorization needs no round trip
    // and `prompt=none` has something to answer with. Scoped to this pod: pods are isolated
    // tenants, and on a path-scoped deployment they share a host.
    // One instant for both: the cookie's `auth_time` and the principal this request runs under
    // describe the same sign-in, and two `Instant.now()` calls would date it twice.
    val authTime = Instant.now()
    val sessionToken =
      podTokenIssuer.issueSession(
        podDbo.name, verified.webId, verified.alsoKnownAs, authTime, PodTokenIssuer.SESSION_TTL_SECONDS,
      )
    val answer = render(
      podDbo.name,
      podAuthorizeFlow.authorize(
        pod = podDbo.hosted,
        request = PodAuthorizeRequest(
          responseType = "code",
          clientId = pending.clientId,
          redirectUri = pending.redirectUri,
          state = pending.clientState,
          codeChallenge = pending.codeChallenge,
          codeChallengeMethod = pending.codeChallengeMethod,
          prompt = pending.prompt,
          scope = pending.scope,
        ),
        session = PodTokenIssuer.SessionPrincipal(verified.webId, verified.alsoKnownAs, authTime),
      ),
    )
    // Attached once to whatever the flow answered — consent page, auto-granted code, or an error.
    // [PodAuthorizeFlow] has a dozen exits and threading a cookie through each is how one gets
    // missed.
    return Response.fromResponse(answer)
      .cookie(cookies.session(podDbo.name, sessionToken, PodTokenIssuer.SESSION_TTL_SECONDS.toInt()))
      .build()
  }

  @GET
  @Path("jwks.json")
  fun jwks(): Response {
    return Response.ok(podTokenIssuer.jwksJson, MediaType.APPLICATION_JSON).build()
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  /** Kept as a name because two dozen refusals read better for it; the answer is [PodTokenResponses]'. */
  private fun tokenError(error: OAuthErrorCode, description: String): Response =
    PodTokenResponses.error(error, description)


  /**
   * The person this browser already proved itself as on this pod, or null — also where they have
   * signed out since, which reads exactly like a session that expired.
   */
  private fun readSession(podDbo: PodDbo, cookieValue: String?): PodTokenIssuer.SessionPrincipal? =
    podTokenIssuer.readSession(podDbo.name, cookieValue)
      // The pod as the row this request read, because [PodSignOut] takes the id on it — see its
      // KDoc for what resolving the name again would cost.
      ?.takeIf { podSignOut.sessionStands(podDbo.podId(), it) }

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

  /** Whatever the authorization decided, on the wire — see [PodAuthorizeResponses]. */
  private fun render(podName: String, result: PodAuthorizeResult): Response =
    PodAuthorizeResponses.render(result, podName, cookies, templateRenderer, config)

  /**
   * Reports [error] at the client's own address, which [target] is the proof of.
   *
   * There is no way to reach this without that proof, which is the open-redirector rule stated as
   * a type: an error may only travel to an address once it is known to belong to the client that
   * named it.
   */
  private fun oauthError(
    target: Redirectable,
    error: OAuthErrorCode,
    errorDescription: String,
    state: String?,
  ): Response =
    PodOAuthErrorResponses.render(OAuthErrorDelivery.Redirect(target, error, errorDescription, state), config)

  /**
   * The same, for a request `oidc/callback` is resuming.
   *
   * See [PodOAuthErrorResponses.renderToParked]: the parked record is the proof, and it is the
   * only thing that opens this door.
   */
  private fun oauthErrorToParked(
    pending: PodLoginStateStore.Pending,
    error: OAuthErrorCode,
    errorDescription: String,
  ): Response =
    PodOAuthErrorResponses.renderToParked(pending, error, errorDescription, config)

  companion object {
    private val logger = KotlinLogging.logger {}

    /** The consent form's named way out, as the submit button sends it. */
    private const val DISCONNECT_ACTION = "disconnect"

    /** The consent form's sign-out: it ends everything the person holds on the pod. */
    private const val SIGN_OUT_ACTION = "signout"
  }
}
