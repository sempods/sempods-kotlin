package org.sempods.mcp.api.oauth

import org.sempods.mcp.oauth.IdentityProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.forLog
import org.sempods.mcp.api.resolveProfileOr404
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.LoginCsrfPin
import org.sempods.mcp.auth.WebSession
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.mcp.oauth.ConsentTransactionStore
import org.sempods.auth.core.DynamicClientFingerprint
import org.sempods.mcp.oauth.LoginStateStore
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationSuccessResponse
import com.nimbusds.oauth2.sdk.ResponseMode
import com.nimbusds.oauth2.sdk.client.RegistrationError
import com.nimbusds.oauth2.sdk.id.State
import org.sempods.auth.core.ClientRedirectPolicy
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.Pkce
import org.sempods.auth.core.RedirectUri
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.mcp.oauth.McpRefreshTokenStore
import org.sempods.mcp.oauth.TokenIssuer
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.PodConnection
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.ProfileDao
import org.sempods.mcp.persist.ProfileKey
import org.sempods.mcp.persist.ProfilePath
import org.sempods.mcp.persist.TokenVaultDao
import org.sempods.mcp.persist.oauth.DcrClient
import org.sempods.mcp.persist.oauth.DcrClientDao
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.http.Parameters
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.request.userAgent
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Date
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Secrets
import org.sempods.commons.utils.appendEscapedHtml

/**
 * The service's own OAuth authorization server (the AI-client → service layer): DCR
 * `/register`, `/authorize` + `/authorize/consent`, `/token`, `/jwks.json`, and the OIDC
 * relying-party `/oidc/callback` that federates user login to id.sempods.org.
 *
 * The WebID JWT from id.sempods.org is verified **server-side** at `/oidc/callback` and never
 * travels through the browser/consent form: the consent page carries only an opaque,
 * one-time [ConsentTransactionStore] id. This prevents a leaked short-lived WebID JWT from
 * being replayed into a service authorization code / long-lived refresh token.
 *
 * For M1 everything runs under the **default profile**; the `(user, profile, pod)` key is
 * already threaded through so named profiles (M5) are additive.
 */
private val logger = KotlinLogging.logger("org.sempods.mcp.api.oauth")

fun Application.authEndpoint(
  config: SempodsMcpConfig,
  dcrClientDao: DcrClientDao,
  authorizationCodeStore: AuthorizationCodeStore,
  loginStateStore: LoginStateStore,
  consentTransactionStore: ConsentTransactionStore,
  refreshTokenStore: McpRefreshTokenStore,
  tokenIssuer: TokenIssuer,
  profileDao: ProfileDao,
  webSession: WebSession,
  connectionRegistryDao: ConnectionRegistryDao,
  tokenVaultDao: TokenVaultDao,
  objectMapper: ObjectMapper,
  auditLog: AuditLog,
  identityProvider: IdentityProvider,
) {
  val base = config.mcpBaseUrl
  val loginBaseUrl = config.authIssuers.firstOrNull()

  /** `prompt` values worth forwarding: the rest are not this service's to interpret. */
  val forwardedPrompts = setOf("login", "select_account")

  /** Where the id-server returns; also this client's registered redirect address. */
  val OIDC_CALLBACK_PATH = "/oidc/callback"


  // Login-CSRF pin: set on the `/authorize` → id-server redirect, required back at
  // `/oidc/callback`. See [LoginCsrfPin] for why `state` alone is not enough.
  val loginPin = LoginCsrfPin(
    cookieNamePrefix = LOGIN_NONCE_COOKIE_PREFIX,
    cookiePath = LOGIN_NONCE_COOKIE_PATH,
    secure = config.isSecure,
  )

  // --- RFC 7591 Dynamic Client Registration (profile from the URL path; default = root) ---
  suspend fun doRegister(call: ApplicationCall, profile: String) {
    val body = runCatching {
      @Suppress("UNCHECKED_CAST")
      objectMapper.readValue(call.receiveText(), Map::class.java) as Map<String, Any?>
    }.getOrElse {
      return call.respondJson(
        HttpStatusCode.BadRequest, objectMapper,
        RegistrationError.INVALID_CLIENT_METADATA.setDescription("malformed JSON body").toJSONObject(),
      )
    }

    val redirectUris = (body["redirect_uris"] as? List<*>)?.mapNotNull { it?.toString() }?.toSet().orEmpty()
    if (redirectUris.isEmpty() || redirectUris.any { !RedirectUri.isValid(it) }) {
      return call.respondJson(
        HttpStatusCode.BadRequest, objectMapper,
        RegistrationError.INVALID_REDIRECT_URI
          .setDescription(
            "redirect_uris must be https, or http on a loopback host, with no fragment and no " +
                "code/response/state in the query"
          )
          .toJSONObject(),
      )
    }

    val clientName = body["client_name"]?.toString()
    val userAgent = call.request.userAgent()
    val fingerprint = DynamicClientFingerprint.compute(clientName, userAgent, profile, redirectUris)

    val existing = dcrClientDao.findByFingerprint(profile, fingerprint)
    val client = existing ?: DcrClient(
      clientId = "dyn:" + newOpaqueId(),
      profile = profile,
      redirectUris = redirectUris,
      clientName = clientName,
      softwareId = body["software_id"]?.toString(),
      softwareVersion = body["software_version"]?.toString(),
      fingerprint = fingerprint,
      userAgent = userAgent,
      registeredAt = Date(),
    ).also { dcrClientDao.create(it) }

    if (existing != null) logger.info { "DCR dedup hit: reusing client_id=${client.clientId} (profile=$profile)" }
    else logger.info { "DCR registered new client_id=${client.clientId} name='${forLog(clientName)}' (profile=$profile)" }

    // Echo the **current request's** redirect_uris, not the stored ones. On a loopback
    // dedup hit the stored row holds the first-seen ephemeral port; a client that treats
    // the RFC 7591 response as authoritative would otherwise drive /authorize against the
    // old, now-dead callback port. The stored row stays the immutable dedup anchor —
    // /authorize validates redirect_uri loopback-canonically (port-insensitive), so the
    // new port is accepted regardless of what is stored.
    call.respondJson(
      HttpStatusCode.Created, objectMapper,
      linkedMapOf<String, Any?>(
        "client_id" to client.clientId,
        "redirect_uris" to redirectUris.toList(),
        "token_endpoint_auth_method" to "none",
        "grant_types" to listOf("authorization_code", "refresh_token"),
        "response_types" to listOf("code"),
        "client_name" to client.clientName,
      ),
    )
  }

  // --- Authorization endpoint (profile from the URL path; default = root) ---
  // The WebID is established only via the id.sempods.org round-trip; this endpoint never
  // accepts a WebID token directly (it is purely an internal service↔id-server credential).
  suspend fun doAuthorize(call: ApplicationCall, profile: String) {
    val q = call.request.queryParameters
    val responseType = q["response_type"]
    // Trimmed at the door, because the check below trims too: `OAuthErrors.redirectTargetFor`
    // validates the trimmed value, and parking the raw one meant a padded `client_id` passed the
    // check and then matched no row at the callback — a flow started that could not finish, after
    // costing the user an id-server round trip. Blank collapses to absent for the same reason it
    // does for `state`: a value made of whitespace names nothing.
    val clientId = q["client_id"]?.trim()?.takeIf { it.isNotBlank() }
    val redirectUri = q["redirect_uri"]?.trim()?.takeIf { it.isNotBlank() }
    val clientState = q["state"]
    val codeChallenge = q["code_challenge"]
    val codeChallengeMethod = q["code_challenge_method"]
    val scopes = OAuthSyntax.parseScope(q["scope"])
    val promptValues = OAuthSyntax.parsePrompt(q["prompt"])

    val policy = redirectPolicyFor(dcrClientDao, profile)
    if (clientId == null || redirectUri == null) {
      return call.respondOAuthError(OAuthErrorDelivery.Direct(OAuthErrorCode.INVALID_REQUEST, "client_id and redirect_uri required"))
    }
    // The one check that decides whether anything at all may be sent to this address — an error
    // included. `redirectTargetFor` answers null both for a client this server does not know and
    // for an address that client may not use, and the two must stay indistinguishable to the
    // caller: telling them apart is a client-enumeration oracle. The server log keeps them apart.
    val target = OAuthErrors.redirectTargetFor(policy, clientId, redirectUri)
      ?: return call.respondOAuthError(OAuthErrorDelivery.Direct(OAuthErrorCode.INVALID_CLIENT, "unknown client_id or unregistered redirect_uri"))
        .also {
          val known = dcrClientDao.findByClientId(profile, clientId) != null
          logger.info {
            "authorize refused (profile=$profile, client=${forLog(clientId)}): " +
              if (known) "redirect_uri not registered for this client" else "unknown client_id"
          }
        }
    // Ordered after the address is proven, not before: RFC 6749 §4.1.2.1 places this code at the
    // redirect_uri, and a client that asked for the wrong response type is still a client that can
    // be told so where it is listening. Checking it first — as this did — answered a well-behaved
    // client's typo with an opaque 400 it had no way to correlate.
    if (responseType != "code") {
      return call.respondOAuthError(
        OAuthErrorDelivery.Redirect(target, OAuthErrorCode.UNSUPPORTED_RESPONSE_TYPE, "only 'code' is supported", clientState),
      )
    }
    // PKCE mandatory for public (dynamic) clients.
    if (codeChallenge.isNullOrBlank() || codeChallengeMethod != Pkce.METHOD_S256) {
      return call.respondOAuthError(
        OAuthErrorDelivery.Redirect(target, OAuthErrorCode.INVALID_REQUEST, "PKCE S256 required", clientState),
      )
    }
    // No service session yet → cannot satisfy prompt=none silently (refresh tokens cover
    // headless re-auth).
    // TODO: persist a per-(user, profile, client) service grant on consent + a service
    //  session cookie, so a returning browser with prompt=none can auto-grant without a
    //  fresh id-server round-trip. Purely a UX smoothing — refresh tokens already cover
    //  headless re-auth.
    if ("none" in promptValues) {
      return call.respondOAuthError(
        OAuthErrorDelivery.Redirect(target, OAuthErrorCode.LOGIN_REQUIRED, "interactive login required", clientState),
      )
    }
    if (loginBaseUrl == null) {
      return call.respondText("no auth issuer configured (SEMPODS_AUTH_ISSUERS)", status = HttpStatusCode.ServiceUnavailable)
    }

    // Federate login to the id-server as a standard OIDC relying party: what comes back through
    // the browser is a single-use code, and the token is fetched over a back channel using a
    // verifier that never left this process. The previous flow had the id-server append the token
    // to a `return_to` of this service's choosing — which it accepted from anyone.
    val relyingParty = try {
      identityProvider.relyingPartyFor(OIDC_CALLBACK_PATH)
    } catch (e: Exception) {
      logger.warn(e) { "identity provider discovery failed" }
      return call.respondText("identity provider unavailable", status = HttpStatusCode.ServiceUnavailable)
    }
    // The id-server's `state` is also the key this flow is stored under: one value tying the
    // callback to this request, rather than two that could disagree about which it belongs to.
    val started = relyingParty.beginAuthorization(
      prompt = promptValues.intersect(forwardedPrompts).firstOrNull(),
      state = loginStateStore.newState(),
    )
    // The browserNonce pins the callback to *this* browser (login-CSRF defense) — the `state` is
    // bearer on its own, and a login URL captured by one party must not complete in another's
    // browser. Set as a cookie below.
    val browserNonce = loginPin.mint()
    loginStateStore.create(
      started.state,
      LoginStateStore.PendingAuthorize(
        profile = profile,
        clientId = clientId,
        redirectUri = redirectUri,
        clientState = clientState,
        scopes = scopes,
        codeChallenge = codeChallenge,
        codeChallengeMethod = codeChallengeMethod,
        browserNonce = browserNonce,
        oidcCodeVerifier = started.codeVerifier,
        oidcNonce = started.nonce,
      ),
    )
    loginPin.set(call, started.state, browserNonce)
    call.respondRedirect(started.authorizationUrl)
  }

  routing {

    // --- JWKS for the service's own access tokens (service-wide; never per-profile) ---
    get("/jwks.json") {
      call.respondText(tokenIssuer.jwksJson, ContentType.Application.Json)
    }

    // DCR + authorize, default (root) and named-profile (`/{profile}/…`) variants. A reserved or
    // malformed profile segment is rejected with 404 rather than silently treated as the default.
    post("/register") { doRegister(call, PodKey.DEFAULT_PROFILE) }
    post("/{profile}/register") {
      call.resolveProfileOr404()?.let { doRegister(call, it) }
    }
    get("/authorize") { doAuthorize(call, PodKey.DEFAULT_PROFILE) }
    get("/{profile}/authorize") {
      call.resolveProfileOr404()?.let { doAuthorize(call, it) }
    }

    // --- OIDC relying-party callback from id.sempods.org ---
    get("/oidc/callback") {
      val q = call.request.queryParameters
      val state = q["state"] ?: return@get call.respondText("missing state", status = HttpStatusCode.BadRequest)
      val code = q["code"]
      val error = q["error"]
      // The state reaches a cookie *name* below, and it arrived from a stranger. A value that
      // cannot be one is refused here rather than further down: it could never match a parked
      // request anyway, and rendering `Set-Cookie` with it throws — turning this 400 into a 500.
      if (!Secrets.isWellFormed(state)) {
        return@get call.respondText("invalid or expired login state", status = HttpStatusCode.BadRequest)
      }
      // Withdrawn as soon as the flow it belongs to is named, so *every* answer below expires it
      // — including "unknown state". Cookie names carry the state now, so a pin nobody withdraws
      // is not overwritten by the next attempt; it lingers its full fifteen minutes. Reading the
      // pin is a request concern and clearing it a response one, so the comparison still works.
      loginPin.clear(call, state)
      val pending = loginStateStore.consume(state)
        ?: return@get call.respondText("invalid or expired login state", status = HttpStatusCode.BadRequest)

      // Login-CSRF / session-fixation: the callback must be completed in the SAME browser that
      // started `/authorize` (which holds the one-time nonce cookie). A login URL captured by an
      // attacker and opened in a victim's browser carries no matching cookie → reject. Clear the
      // cookie regardless (one-time), before branching on error/token.
      val pinPresented = loginPin.isPresent(call, state)
      val pinMatches = loginPin.matches(call, state, pending.browserNonce)
      if (!pinMatches) {
        logger.warn {
          "login callback rejected: browser nonce ${if (!pinPresented) "absent" else "mismatch"} " +
            "(profile=${pending.profile}, client=${forLog(pending.clientId)})"
        }
        return@get call.respondText("login session not recognised for this browser — please restart the sign-in from your AI client", status = HttpStatusCode.BadRequest)
      }

      // Each of these re-proves the address before using it. The parked request was checked at
      // `/authorize`, but a client can be deleted between the two, and the type says an error may
      // only travel to an address that belongs to a client this server currently knows — so a
      // vanished client is answered here rather than at its former callback.
      val policy = redirectPolicyFor(dcrClientDao, pending.profile)
      suspend fun refuseLogin(description: String) = call.respondOAuthError(
        policy, pending.clientId, pending.redirectUri, OAuthErrorCode.ACCESS_DENIED, description, pending.clientState,
      )
      if (error != null) {
        // The id-server's own `error` value is a stranger's string reaching an AI client's URL
        // bar; it goes to the log, and the client gets the one thing it can act on.
        logger.info {
          "id-server refused the login (profile=${pending.profile}, client=${forLog(pending.clientId)}): ${forLog(error)}"
        }
        return@get refuseLogin("login was refused or failed")
      }
      if (code == null) {
        return@get refuseLogin("no authorization code")
      }
      // The token is fetched here, over a back channel, using a verifier that never travelled
      // through the browser — and checked against the nonce this flow sent. What arrived in the
      // browser was a code that is worth nothing without both.
      val identity = try {
        identityProvider.relyingPartyFor(OIDC_CALLBACK_PATH).completeAuthorization(code, pending.oidcCodeVerifier, pending.oidcNonce)
      } catch (e: Exception) {
        logger.warn(e) { "id-server token exchange failed (profile=${pending.profile}, client=${forLog(pending.clientId)})" }
        return@get refuseLogin("login failed")
      }

      val client = dcrClientDao.findByClientId(pending.profile, pending.clientId)
        ?: return@get call.respondText("invalid_client", status = HttpStatusCode.BadRequest)
      val clientLabel = client.clientName?.takeIf { it.isNotBlank() } ?: client.clientId

      // Capture the verified WebID + full authorize context in a one-time server-side
      // transaction. Only its opaque id is exposed to the browser.
      val txnId = consentTransactionStore.create(
        ConsentTransactionStore.Transaction(
          user = identity.webId,
          profile = pending.profile,
          clientId = pending.clientId,
          clientLabel = clientLabel,
          redirectUri = pending.redirectUri,
          clientState = pending.clientState,
          scopes = pending.scopes,
          codeChallenge = pending.codeChallenge ?: "",
          codeChallengeMethod = pending.codeChallengeMethod ?: Pkce.METHOD_S256,
        ),
      )

      // Materialise the profile now (not only at Allow): the inline pod-connect reuses the
      // /_system/ui machinery, which checks profile ownership, so the profile must exist before
      // the user connects a pod on the consent screen. Idempotent; a no-op for the default.
      profileDao.create(identity.webId, pending.profile)
      // Establish the browser web-session for the SAME verified user. Double duty: the user never
      // has to log in again for the /_system/ui dashboard, and the consent screen's inline
      // pod-connect form can post to the cookie-protected /_system/ui/pods/connect with a valid
      // CSRF token (returned here so we can embed it without reading the cookie back).
      // NOTE: this always mints a fresh cookie — the existing one can't be reused here because the
      // web-session cookie is scoped to /_system/ui and so is not sent to this /oidc path. A second
      // concurrent OAuth round-trip therefore rotates the CSRF token of an already-open dashboard
      // tab; acceptable for now (re-render recovers), tracked with the cookie-scoping tradeoff below.
      val principal = webSession.establish(call, identity.webId)
      val connections = connectionRegistryDao.listForProfile(ProfileKey(identity.webId, pending.profile))
      call.respondConsent(
        base, txnId, clientLabel, identity.webId, pending.profile,
        csrfToken = principal.csrfToken, connections = connections,
        connectedBanner = null, connectedAs = null, disconnectedBanner = null, errorBanner = null,
      )
    }

    // --- Resume the consent screen after an inline pod-connect round-trip ---
    // The pod callback sends the browser here (returnTo) once a pod is connected. The txn is
    // peeked (not consumed) so "Allow" still works; the screen re-renders with the pod now listed.
    // csrf = null on purpose: the web-session cookie is scoped to /_system/ui, so the browser does
    // not send it to this /authorize path. The resumed screen therefore shows the connected pod(s)
    // + "Allow" without a fresh connect form — the first pod is attached, and connecting more can
    // happen later from the dashboard. (The first consent render, at /oidc/callback, DOES carry the
    // connect form: it gets the CSRF token straight from establish(), not from reading the cookie.)
    get("/authorize/consent/resume") {
      val q = call.request.queryParameters
      val txnId = q["txn"] ?: return@get call.respondText("missing txn", status = HttpStatusCode.BadRequest)
      // Slide the expiry: returning here means the user just finished a pod-management round-trip
      // (connect / disconnect), so the consent clock resets — an active session never times out
      // mid-work even at the modest base TTL. Still peek-only semantics (the txn is consumed only at
      // "Allow"); an already-expired txn is not resurrected.
      val txn = consentTransactionStore.touch(txnId)
        ?: return@get call.respondText("consent session expired, please retry the sign-in", status = HttpStatusCode.BadRequest)
      val connections = connectionRegistryDao.listForProfile(ProfileKey(txn.user, txn.profile))
      call.respondConsent(
        base, txnId, txn.clientLabel, txn.user, txn.profile,
        csrfToken = null, connections = connections,
        connectedBanner = q["connected"], connectedAs = q["connected_as"],
        disconnectedBanner = q["disconnected"], errorBanner = q["error"],
      )
    }

    // --- Disconnect a pod from the consent screen (authorized by the consent transaction) ---
    // The web-session CSRF cookie is scoped to /_system/ui and is NOT sent to this /authorize path,
    // so the dashboard's disconnect route is unreachable from here. Instead we authorize via the
    // opaque one-time `txn`, which already binds user+profile server-side and gates issuing an OAuth
    // code ("Allow") — plenty to authorize removing a pod. Because it rides on the txn (not the
    // cookie), the disconnect button works on BOTH the first and the resumed consent render.
    post("/authorize/consent/disconnect") {
      val form = call.receiveParameters()
      val txnId = form["txn"] ?: return@post call.respondText("missing txn", status = HttpStatusCode.BadRequest)
      // touch() so the very act of managing pods keeps the consent session alive.
      val txn = consentTransactionStore.touch(txnId)
        ?: return@post call.respondText("consent session expired, please retry the sign-in", status = HttpStatusCode.BadRequest)
      val pod = form["pod"]?.trim().orEmpty()
      if (pod.isNotEmpty()) {
        val key = PodKey(txn.user, txn.profile, pod)
        tokenVaultDao.delete(key)
        connectionRegistryDao.delete(key)
        logger.info { "pod disconnected (consent): user='${txn.user}' profile='${txn.profile}' pod='${forLog(pod)}'" }
        auditLog.podDisconnected(txn.user, txn.profile, pod)
      }
      val disc = if (pod.isNotEmpty()) "&disconnected=${enc(pod)}" else ""
      call.respondRedirect("$base/authorize/consent/resume?txn=${enc(txnId)}$disc")
    }

    // --- Consent submit → issue authorization code (resolves everything from the txn) ---
    post("/authorize/consent") {
      val form = call.receiveParameters()
      val txnId = form["txn"] ?: return@post call.respondText("missing txn", status = HttpStatusCode.BadRequest)
      // Peek (not consume) so a missing foreign-identity confirmation can re-render without burning
      // the one-time transaction.
      val txn = consentTransactionStore.peek(txnId)
        ?: return@post call.respondText("consent session expired, please retry the sign-in", status = HttpStatusCode.BadRequest)

      // Explicit foreign-identity acknowledgement: when a connected pod authorized the user under its
      // own WebID, granting this client means it will act on that pod as that identity — require the
      // user to confirm first. Enforced server-side (the form checkbox is `required`, but a crafted
      // POST must not bypass it): a missing confirmation re-renders the consent with an error.
      val connections = connectionRegistryDao.listForProfile(ProfileKey(txn.user, txn.profile))
      if (connections.any { it.foreignIdentity } && form["confirm_foreign"] != "on") {
        return@post call.respondConsent(
          base, txnId, txn.clientLabel, txn.user, txn.profile,
          csrfToken = null, connections = connections,
          connectedBanner = null, connectedAs = null, disconnectedBanner = null,
          errorBanner = "Please confirm you understand a connected pod acts under a different identity before allowing.",
        )
      }
      // Only now that we will proceed, consume the transaction (atomic; a lost race reports expiry).
      consentTransactionStore.consume(txnId)
        ?: return@post call.respondText("consent session expired, please retry the sign-in", status = HttpStatusCode.BadRequest)

      // First time a user authorizes an AI client against a named profile, materialise it so the
      // profile becomes a first-class record (visible in the web-UI switcher, addressable for
      // pod-connect) without the user having to pre-create it. The AI-client flow "just works":
      // pointing a client at `…/<profile>` is itself the creation event. No-op for the default.
      profileDao.create(txn.user, txn.profile)

      val code = authorizationCodeStore.issue(
        subject = txn.user,
        realm = txn.profile,
        clientId = txn.clientId,
        scopes = txn.scopes,
        redirectUri = txn.redirectUri,
        codeChallenge = txn.codeChallenge,
        codeChallengeMethod = txn.codeChallengeMethod,
      )
      // The last hand-built OAuth redirect in this file, and it goes the same way its error
      // siblings did: the address is the client's own and may carry a query, so which separator
      // it needs is nimbus's problem rather than a rule repeated per call site.
      call.respondRedirect(
        AuthorizationSuccessResponse(
          URI(txn.redirectUri),
          AuthorizationCode(code),
          null,
          // Blank means absent, for the reason `respondOAuthError` spells out: nimbus refuses a
          // blank `State`, and a success response is the worst place to discover that.
          txn.clientState?.takeIf { it.isNotBlank() }?.let { State(it) },
          ResponseMode.QUERY,
        ).toURI().toString(),
      )
    }

    // --- Token endpoint (default + named-profile path) ---
    // Each named profile is presented as its own authorization-server issuer, so its
    // `token_endpoint` may only redeem grants issued for that same profile. [pathProfile] is the
    // profile this endpoint was addressed on (default for the root); the handlers reject a code /
    // refresh token whose stored profile does not match it, so a `/private` grant cannot be redeemed
    // at `/token` or `/<other>/token`. Discovery-driven clients always hit their own profile's
    // token endpoint, so this only refuses genuinely cross-profile redemption.
    suspend fun doToken(call: ApplicationCall, pathProfile: String) {
      val form = call.receiveParameters()
      when (val grantType = form["grant_type"]) {
        "authorization_code" -> handleAuthorizationCode(call, form, pathProfile, authorizationCodeStore, refreshTokenStore, tokenIssuer, objectMapper)
        "refresh_token" -> handleRefreshToken(call, form, pathProfile, refreshTokenStore, tokenIssuer, objectMapper, auditLog)
        else -> call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.UNSUPPORTED_GRANT_TYPE, "grant_type='$grantType'"))
      }
    }
    post("/token") { doToken(call, PodKey.DEFAULT_PROFILE) }
    post("/{profile}/token") {
      call.resolveProfileOr404()?.let { doToken(call, it) }
    }
  }
}

private suspend fun handleAuthorizationCode(
  call: ApplicationCall,
  form: Parameters,
  pathProfile: String,
  authorizationCodeStore: AuthorizationCodeStore,
  refreshTokenStore: McpRefreshTokenStore,
  tokenIssuer: TokenIssuer,
  objectMapper: ObjectMapper,
) {
  val code = form["code"]
  val redirectUri = form["redirect_uri"]
  val clientId = form["client_id"]
  val codeVerifier = form["code_verifier"]
  if (code == null || redirectUri == null || clientId == null) {
    return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_REQUEST, "code, redirect_uri, client_id required"))
  }
  val entry = authorizationCodeStore.consume(code)
    ?: return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "unknown or expired code"))
  if (entry.clientId != clientId || entry.redirectUri != redirectUri) {
    return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "code does not match client/redirect_uri"))
  }
  // A code issued for one profile may only be redeemed at that profile's token endpoint (each
  // profile is its own AS issuer). The code is already consumed above, so a mismatched attempt
  // also burns the code — a cross-profile redemption cannot be retried against the right endpoint.
  if (entry.realm != pathProfile) {
    return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "code was issued for a different profile"))
  }
  // PKCE: the code was issued with an S256 challenge; require a matching verifier.
  // Bound to a local because the store now lives in another module, where Kotlin will not smart-cast
  // a public property across the boundary.
  val issuedChallenge = entry.codeChallenge
  if (issuedChallenge.isNullOrBlank() || codeVerifier.isNullOrBlank() ||
    !Pkce.verifyS256(codeVerifier, issuedChallenge)
  ) {
    return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "PKCE verification failed"))
  }

  val accessToken = tokenIssuer.issueAccessToken(entry.subject, entry.realm, entry.clientId, entry.scopes)
  val refresh = refreshTokenStore.issueNewFamily(entry.subject, entry.realm, entry.clientId, entry.scopes)
  call.respondJson(HttpStatusCode.OK, objectMapper, tokenResponse(accessToken, refresh.plaintext, entry.scopes))
}

private suspend fun handleRefreshToken(
  call: ApplicationCall,
  form: Parameters,
  pathProfile: String,
  refreshTokenStore: McpRefreshTokenStore,
  tokenIssuer: TokenIssuer,
  objectMapper: ObjectMapper,
  auditLog: AuditLog,
) {
  val presented = form["refresh_token"]
    ?: return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_REQUEST, "refresh_token required"))
  val lookup = refreshTokenStore.lookup(presented)
  when (lookup.state) {
    RefreshTokenStore.LookupState.ACTIVE -> { /* proceed */ }
    RefreshTokenStore.LookupState.REUSED -> {
      // Replay of a rotated token → revoke the whole family (OAuth 2.1 reuse detection).
      lookup.token?.let {
        refreshTokenStore.revokeFamily(it.familyId)
        auditLog.serviceTokenFamilyRevoked(it.owner.user, it.owner.profile, it.owner.clientId, detail = "refresh_reuse")
      }
      return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "refresh token reuse detected"))
    }
    else -> return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "refresh token ${lookup.state}"))
  }
  val token = lookup.token!!
  // The refresh token is bound to a client; require the presented client_id to match before
  // rotating, so a leaked token alone cannot be exchanged for a fresh access token.
  val clientId = form["client_id"]
  if (clientId.isNullOrBlank() || clientId != token.owner.clientId) {
    return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "client_id does not match the refresh token"))
  }
  // A refresh token belongs to its profile's AS; refuse redemption at another profile's endpoint.
  // Checked before rotation so a wrong-endpoint attempt does not consume/revoke a valid token.
  if (token.owner.profile != pathProfile) {
    return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "refresh token was issued for a different profile"))
  }
  // Atomically rotate; a concurrent rotation means treat as reuse.
  if (!refreshTokenStore.markRotated(token.tokenHash)) {
    refreshTokenStore.revokeFamily(token.familyId)
    auditLog.serviceTokenFamilyRevoked(token.owner.user, token.owner.profile, token.owner.clientId, detail = "refresh_reuse")
    return call.respondJson(HttpStatusCode.BadRequest, objectMapper, oauthError(OAuthErrorCode.INVALID_GRANT, "refresh token reuse detected"))
  }
  val accessToken = tokenIssuer.issueAccessToken(token.owner.user, token.owner.profile, token.owner.clientId, token.scopes)
  val successor = refreshTokenStore.issueInFamily(token, token.scopes)
  auditLog.serviceTokenRotated(token.owner.user, token.owner.profile, token.owner.clientId)
  call.respondJson(HttpStatusCode.OK, objectMapper, tokenResponse(accessToken, successor.plaintext, token.scopes))
}

// --- helpers ---

private fun tokenResponse(accessToken: String, refreshToken: String, scopes: Set<String>) =
  linkedMapOf<String, Any?>(
    "access_token" to accessToken,
    "token_type" to "Bearer",
    "expires_in" to TokenIssuer.USER_TOKEN_TTL_SECONDS,
    "refresh_token" to refreshToken,
    "scope" to scopes.joinToString(" "),
  )

/**
 * RFC 6749 §5.2's error document, for the token endpoint's failures — which are always rendered
 * directly, never redirected, so they need no [org.sempods.auth.core.Redirectable].
 */
private fun oauthError(code: OAuthErrorCode, description: String) =
  linkedMapOf<String, Any?>("error" to code.code, "error_description" to description)

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, objectMapper: ObjectMapper, body: Any) =
  respondText(objectMapper.writeValueAsString(body), ContentType.Application.Json, status)

private fun redirectUriRegistered(redirectUri: String, registered: Set<String>): Boolean {
  val canonical = RedirectUri.canonicalize(redirectUri)
  return registered.any { RedirectUri.canonicalize(it) == canonical }
}

/**
 * Whether a dynamically registered client may be answered at an address, for one request profile.
 *
 * A `fun interface` and a factory rather than a class, because the profile is a request value: a
 * `client_id` resolves only inside its own profile ([DcrClientDao.findByClientId]), so a single
 * long-lived instance could not answer the question. The canonicalisation is loopback
 * port-insensitive, which is what lets a client that restarted on a new ephemeral port keep the
 * registration it deduped to — see the note in `doRegister`.
 */
private fun redirectPolicyFor(dcrClientDao: DcrClientDao, profile: String) = ClientRedirectPolicy { clientId, redirectUri ->
  if (!RedirectUri.isValid(redirectUri)) false
  else dcrClientDao.findByClientId(profile, clientId)?.let { redirectUriRegistered(redirectUri, it.redirectUris) } == true
}

private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)


private fun newOpaqueId(): String {
  val bytes = ByteArray(18)
  java.security.SecureRandom().nextBytes(bytes)
  return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Followed by the flow's `state`, so concurrent sign-ins do not share a cookie. */
private const val LOGIN_NONCE_COOKIE_PREFIX = "mcp_login_"
private const val LOGIN_NONCE_COOKIE_PATH = "/oidc"

/**
 * The consent screen for the AI-client → service binding, with the **inline first pod-connect**
 * woven in. "Allow" (the [txnId]-carrying form) confirms "let this AI client act as <WebID>".
 * Above it the screen shows the pods already connected to this profile and — when a web-session
 * CSRF token is available — a "connect a pod" form that posts to the cookie-protected
 * `/_system/ui/pods/connect` with a `return_to` back to this same consent screen, so the user can
 * attach their first pod without leaving the flow and without a second login. Connecting is
 * **skippable**: "Allow" is always present, so a user with no pod handy can finish and connect
 * later from the dashboard (the hosted service has no anonymous mode, so a pod-less session simply
 * has nothing to serve until then). The form carries **only** the opaque one-time transaction id —
 * the WebID and all flow context stay server-side.
 */
private suspend fun ApplicationCall.respondConsent(
  base: String,
  txnId: String,
  clientLabel: String,
  webId: String,
  profile: String,
  csrfToken: String?,
  connections: List<PodConnection>,
  connectedBanner: String?,
  connectedAs: String?,
  disconnectedBanner: String?,
  errorBanner: String?,
) {
  val mcpUrl = ProfilePath.baseUrlFor(base, profile)
  val resumeUrl = "$base/authorize/consent/resume?txn=$txnId"
  val html = buildString {
    append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>sempods MCP — connect</title>")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    // Deliberately lightweight, system-ui, no external assets. The rules below only tidy the
    // existing look: safe wrapping for long WebIDs/URLs, card layout for pods, subtle badges.
    append("<style>")
    append(":root{--fg:#1a1a1a;--muted:#6b7280;--line:#e2e5ea;--card:#fbfcfe;--accent:#2d6cdf;--ok-bg:#e6f4ea;--ok-line:#bfe3c9;--err-bg:#fdecea;--err-line:#f4c9c4}")
    append("*{box-sizing:border-box}")
    append("body{font-family:system-ui,-apple-system,sans-serif;color:var(--fg);max-width:36rem;margin:2.5rem auto;padding:0 1.1rem;line-height:1.55}")
    append("h1{font-size:1.35rem;margin:0 0 .3rem}")
    append("h2{font-size:.8rem;text-transform:uppercase;letter-spacing:.04em;color:var(--muted);margin:1.8rem 0 .6rem;padding-bottom:.35rem;border-bottom:1px solid var(--line)}")
    append("p{margin:.4rem 0}")
    append("code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.9em;overflow-wrap:anywhere;word-break:break-word}")
    append(".lead{color:var(--muted);font-size:.95rem}")
    append(".who{color:var(--muted);font-size:.9rem;margin-top:.2rem}.who code{color:var(--fg)}")
    append(".muted{color:var(--muted);font-size:.88rem}")
    append("input[type=url]{width:100%;padding:.55rem .65rem;border:1px solid var(--line);border-radius:.45rem;font-size:.95rem}")
    append("input[type=url]:focus{outline:2px solid var(--accent);outline-offset:0;border-color:var(--accent)}")
    append("button{font:inherit;font-size:.95rem;padding:.55rem 1.1rem;border:0;border-radius:.45rem;background:var(--accent);color:#fff;cursor:pointer}")
    append("button:hover{filter:brightness(.95)}")
    append(".allow{background:#1f9d55;font-weight:600}")
    // Pod as a card: identity block on the left, a subtle "Remove" (ghost-red) button on the right.
    append(".pod{display:flex;justify-content:space-between;align-items:flex-start;gap:.75rem;background:var(--card);border:1px solid var(--line);border-radius:.55rem;padding:.65rem .8rem;margin:.5rem 0}")
    append(".pod-main{min-width:0;flex:1}.pod-main code{font-size:.92rem}")
    append(".pod form{margin:0;flex:none}")
    append(".rm{background:none;color:#c0392b;border:1px solid var(--err-line);padding:.3rem .65rem;font-size:.82rem;border-radius:.4rem}")
    append(".rm:hover{background:var(--err-bg);filter:none}")
    // Badges: scopes + the "unverified" / foreign-identity flags.
    append(".badges{margin-top:.35rem;display:flex;flex-wrap:wrap;gap:.3rem}")
    append(".badge{font-size:.72rem;line-height:1.5;padding:0 .45rem;border-radius:.6rem;background:#eef1f6;color:#4b5563;font-family:ui-monospace,monospace}")
    append(".badge.warn{background:#fef3e2;color:#9a5b00}")
    append(".acts{margin-top:.3rem;font-size:.82rem;color:var(--muted)}.acts code{color:var(--fg)}")
    append(".url{background:#f4f6fb;border:1px solid #dce3f2;border-radius:.45rem;padding:.55rem .7rem;font-family:ui-monospace,monospace;font-size:.9rem;overflow-wrap:anywhere;word-break:break-word;margin:.35rem 0}")
    append(".banner{padding:.6rem .8rem;border-radius:.5rem;margin:.6rem 0;font-size:.92rem}")
    append(".banner code{overflow-wrap:anywhere}")
    append(".ok{background:var(--ok-bg);border:1px solid var(--ok-line)}.err{background:var(--err-bg);border:1px solid var(--err-line)}")
    append(".confirm{background:#fff8ee;border:1px solid #f2dcae;border-radius:.5rem;padding:.6rem .8rem;margin:.6rem 0;font-size:.9rem}")
    append(".confirm label{display:flex;gap:.5rem;align-items:flex-start}")
    append(".finish{margin-top:1.4rem}")
    append("</style>")
    append("</head><body>")
    append("<h1>Connect to the sempods MCP service</h1>")
    append("<p class=\"lead\"><strong>").appendEscapedHtml(clientLabel).append("</strong> wants to act on your behalf.</p>")
    append("<p class=\"who\">Signed in as <code>").appendEscapedHtml(webId).append("</code></p>")
    connectedBanner?.let {
      append("<div class=\"banner ok\">Connected <code>").appendEscapedHtml(it).append("</code>.")
      // Mirror the dashboard: when the pod authorized us as its own identity, say so at connect time
      // rather than letting the caller assume the connection acts as their sempods identity.
      connectedAs?.let { who ->
        append(" Acting as <code>").appendEscapedHtml(who).append("</code> (this pod's own identity, not your sempods identity).")
      }
      append("</div>")
    }
    disconnectedBanner?.let {
      append("<div class=\"banner ok\">Removed <code>").appendEscapedHtml(it).append("</code> from this connection.</div>")
    }
    errorBanner?.let { append("<div class=\"banner err\">").appendEscapedHtml(it).append("</div>") }

    // --- Pods available through this connection ---
    append("<h2>Pods in this connection</h2>")
    if (connections.isEmpty()) {
      append("<p class=\"muted\">No pods connected yet. Connect at least one below so this client has something to work with (you can also skip and add pods later from the dashboard).</p>")
    } else {
      for (c in connections.sortedBy { it.pod }) {
        append("<div class=\"pod\"><div class=\"pod-main\"><code>").appendEscapedHtml(c.pod).append("</code>")
        // Scopes + foreign-identity state as small badges instead of a raw muted line.
        if (c.scopes.isNotEmpty() || c.foreignIdentity) {
          append("<div class=\"badges\">")
          for (s in c.scopes.sorted()) append("<span class=\"badge\">").appendEscapedHtml(s).append("</span>")
          if (c.foreignIdentity && !c.subjectVerified) append("<span class=\"badge warn\">unverified</span>")
          append("</div>")
        }
        // A pod that runs its own identity provider authorized us as a WebID of its own; the caller
        // acts on it as that WebID. Name it (as the dashboard does) so the acting identity is explicit.
        if (c.foreignIdentity) {
          append("<div class=\"acts\">acts as <code>").appendEscapedHtml(c.podSubject.orEmpty()).append("</code></div>")
        }
        append("</div>")
        // Remove: authorized by the consent txn (works on first AND resumed render — see the route).
        append("<form method=\"post\" action=\"").append(base).append("/authorize/consent/disconnect\"")
        append(" onsubmit=\"return confirm('Remove this pod from the connection?')\">")
        append("<input type=\"hidden\" name=\"txn\" value=\"").appendEscapedHtml(txnId).append("\">")
        append("<input type=\"hidden\" name=\"pod\" value=\"").appendEscapedHtml(c.pod).append("\">")
        append("<button type=\"submit\" class=\"rm\">Remove</button></form>")
        append("</div>")
      }
    }

    // --- Inline connect (only when we hold a web-session CSRF token) ---
    if (csrfToken != null) {
      append("<h2>Connect a pod</h2>")
      append("<form method=\"post\" action=\"").append(base).append("/_system/ui/pods/connect\">")
      append("<input type=\"hidden\" name=\"csrf\" value=\"").appendEscapedHtml(csrfToken).append("\">")
      append("<input type=\"hidden\" name=\"profile\" value=\"").appendEscapedHtml(profile).append("\">")
      append("<input type=\"hidden\" name=\"return_to\" value=\"").appendEscapedHtml(resumeUrl).append("\">")
      append("<p><input type=\"url\" name=\"pod_base_url\" placeholder=\"https://sempods.org/your-pod\" required></p>")
      append("<p><button type=\"submit\">Connect pod</button></p></form>")
    } else {
      // Resumed render (no CSRF token on this /authorize path): the inline connect form is
      // unavailable (its POST needs the web-session cookie, scoped to /_system/ui). Removing a pod
      // still works here — it rides on the txn, not the cookie. Point the user at the dashboard to
      // connect more; important on the error path, where a failed connect attached no pod.
      // Preserve the profile so a named-profile flow lands on the right dashboard tab (a missing
      // ?profile= would default to the root, connecting the pod into the wrong profile). The default
      // profile needs no query — its `default` sentinel is reserved in URL-segment normalisation.
      val dashUrl =
        if (profile == PodKey.DEFAULT_PROFILE) "$base/_system/ui" else "$base/_system/ui?profile=${enc(profile)}"
      append("<h2>Connect a pod</h2>")
      append("<p class=\"muted\">To connect another pod, open the <a href=\"").append(dashUrl)
        .append("\">pod dashboard</a>, then return to your AI client.</p>")
    }

    // --- Finish: authorize the AI client (always available; connecting is optional) ---
    append("<div class=\"finish\"><h2>Finish</h2>")
    append("<p class=\"muted\">MCP URL for your AI client:</p>")
    append("<div class=\"url\">").appendEscapedHtml(mcpUrl).append("</div>")
    append("<form method=\"post\" action=\"").append(base).append("/authorize/consent\">")
    append("<input type=\"hidden\" name=\"txn\" value=\"").appendEscapedHtml(txnId).append("\">")
    // When a connected pod authorized the user under its own identity, require an explicit
    // acknowledgement before Allow — the client will act on that pod as that WebID (also enforced
    // server-side on submit). See the "acts as" lines above.
    if (connections.any { it.foreignIdentity }) {
      append("<div class=\"confirm\"><label><input type=\"checkbox\" name=\"confirm_foreign\" required> ")
      append("<span>I understand at least one connected pod authorized me under its own identity, and this client will act on that pod as that identity — not my sempods WebID.</span></label></div>")
    }
    append("<p><button type=\"submit\" class=\"allow\">Allow</button></p>")
    append("</form></div></body></html>")
  }
  respondText(html, ContentType.Text.Html)
}
