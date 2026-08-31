package org.sempods.mcp.api.web

import org.sempods.mcp.oauth.IdentityProvider
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.auth.LoginCsrfPin
import org.sempods.mcp.auth.WebLoginStateStore
import org.sempods.mcp.auth.WebSession
import org.sempods.mcp.forLog
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.PodConnection
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.PodTokens
import org.sempods.mcp.persist.ProfileDao
import org.sempods.mcp.persist.ProfileKey
import org.sempods.mcp.persist.ProfilePath
import org.sempods.mcp.persist.TokenVaultDao
import org.sempods.auth.core.DidWeb
import org.sempods.mcp.pods.PodConnectStateStore
import org.sempods.mcp.pods.PodOAuthClient
import org.sempods.mcp.pods.PodOAuthMetadata
import org.sempods.mcp.pods.PodUrlPolicy
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Date
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Secrets
import org.sempods.commons.utils.appendEscapedHtml

/**
 * The session-protected management web-UI, bundled under the `/_system/ui` sub-tree (reserved
 * system namespace; everything here requires the web-session cookie). It lets a user connect
 * pods (service → pod OAuth) and see/disconnect their connections — filling the M1 persistence
 * spine (`connections` + `podTokens`). Machine MCP/AS endpoints stay at the root.
 */
private val logger = KotlinLogging.logger("org.sempods.mcp.api.web")

fun Application.webUiEndpoint(
  config: SempodsMcpConfig,
  webSession: WebSession,
  webLoginStateStore: WebLoginStateStore,
  podOAuthClient: PodOAuthClient,
  podConnectStateStore: PodConnectStateStore,
  podUrlPolicy: PodUrlPolicy,
  connectionRegistryDao: ConnectionRegistryDao,
  tokenVaultDao: TokenVaultDao,
  profileDao: ProfileDao,
  auditLog: AuditLog,
  identityProvider: IdentityProvider,
) {
  val base = config.mcpBaseUrl
  val loginBaseUrl = config.authIssuers.firstOrNull()

  /** Where the id-server returns; also this client's registered redirect address. */
  val UI_CALLBACK_PATH = "/_system/ui/login/callback"

  /**
   * Distinct from the AI-client flow's prefix: one browser may be in both flows at once. Followed
   * by the flow's `state`, so two browser sign-ins do not share a cookie either.
   */
  val UI_LOGIN_PIN_COOKIE_PREFIX = "mcp_ui_login_"
  val podCallbackUri = "$base/_system/ui/pods/callback"

  // The web session identifies the *user*; the profile is a request-scoped selection (one WebID
  // session legitimately spans all of that user's profiles). For a **mutating** action the profile
  // must be one the user owns (the default or a created named profile); a stale/tampered/unknown
  // value returns null so the caller errors out instead of silently redirecting the write into the
  // default bundle.
  //
  // The form carries the profile *name* the dashboard rendered — for the root that is the explicit
  // `default` sentinel. ProfilePath.normalize() must NOT be used here: it is URL-segment semantics
  // where `default` is reserved (so it can't become a second URL for the root) and would map to
  // null, wrongly rejecting every mutation on the default profile. So accept the sentinel (and a
  // blank field) as the default, then require the user actually own the profile.
  fun ownedProfile(raw: String?, user: String): String? {
    val name = raw?.trim().orEmpty().ifEmpty { PodKey.DEFAULT_PROFILE }
    if (name != PodKey.DEFAULT_PROFILE && !ProfilePath.isValidName(name)) return null
    return if (profileDao.exists(user, name)) name else null
  }

  // The stored client_id a re-auth may reuse, or null when this connect has to register afresh.
  //
  // Reuse is the normal answer, and the reason is the pod's grant key: grants are held per
  // (pod, client_id, WebID), so presenting the same id is what makes the pod pre-check the person's
  // prior context selection on its consent screen. A fresh DCR against a pod that does not dedup
  // could mint a different id and orphan those grants.
  //
  // The exception is a connection the pod has already declared finished (`deadGrantSince`). Then
  // those grants are unreachable through that id anyway, and a cleared `dyn:` registration looks
  // exactly like this from here — the pod answers /authorize with a flat 400 that the browser, not
  // this service, is holding, which is why the reconnect used to dead-end and the only way out was
  // to disconnect the pod first. Re-registering is the only way to find out which of the two it is,
  // and it costs nothing when the registration is alive: the pod's DCR dedups on a fingerprint this
  // service keeps stable (client name, User-Agent, redirect URI), so a live registration hands back
  // the *same* id and the grants stay anchored.
  //
  // Only for `dyn:` — the static did:web client has no registration to lose — and only where the
  // pod publishes somewhere to register.
  fun reusableClientId(existing: PodConnection?, metadata: PodOAuthMetadata): String? {
    val stored = existing?.podClientId ?: return null
    if (existing.deadGrantSince == null) return stored
    if (!stored.startsWith("dyn:")) return stored
    if (metadata.registrationEndpoint == null) return stored
    return null
  }

  // Shared tail for both /pods/connect and /pods/reauthorize: discover the pod's OAuth metadata,
  // pin a client_id, stash a one-time Pending connect state, and return the pod authorize URL to
  // redirect the browser to. For a first connect `existing` is null → we DCR (full pod) or present
  // our static did:web client. For a **re-auth** the connection decides, see [reusableClientId].
  //
  // A re-registered id is persisted by /pods/callback, which writes the whole connection row from
  // the pending state once the flow completes. An abandoned flow therefore leaves the stale id in
  // place — which is correct: the connection is still dead, and the next attempt registers again
  // and (via the pod's dedup) arrives at the same new id.
  suspend fun buildPodAuthorizeRedirect(
    user: String,
    profile: String,
    podBaseUrl: String,
    returnTo: String?,
    existing: PodConnection?,
  ): String {
    val metadata = podOAuthClient.discoverMetadata(podBaseUrl)
    val podClientId = reusableClientId(existing, metadata)
      ?: metadata.registrationEndpoint
        ?.let { podOAuthClient.registerClient(metadata, podCallbackUri, softwareVersion = SERVICE_VERSION) }
      ?: DidWeb.clientId(base)
    if (existing != null && existing.podClientId != podClientId) {
      logger.info {
        "pod '${forLog(podBaseUrl)}' no longer knows client_id '${forLog(existing.podClientId)}' " +
          "— re-registered as '${forLog(podClientId)}' for user='${forLog(user)}' profile='$profile'"
      }
    }
    val verifier = org.sempods.auth.core.Pkce.generateVerifier()
    val challenge = org.sempods.auth.core.Pkce.challengeFor(verifier)
    val state = podConnectStateStore.create { expiresAt ->
      PodConnectStateStore.Pending(
        user = user, profile = profile, pod = podBaseUrl, metadata = metadata,
        podClientId = podClientId, codeVerifier = verifier, redirectUri = podCallbackUri,
        expiresAt = expiresAt, returnTo = returnTo,
      )
    }
    // Asked for only where the pod says it is understood. RFC 6749 §4.1.2.1 lets an authorization
    // server answer `invalid_scope` for a value it does not know, and this service connects to pods
    // it does not host — so a pod that advertises nothing gets the scope-less request it got before
    // rather than a flow that dies in the browser.
    val scope = OFFLINE_ACCESS_SCOPE.takeIf { it in metadata.scopesSupported }
    if (scope == null) {
      logger.info {
        "pod '${forLog(podBaseUrl)}' does not advertise '$OFFLINE_ACCESS_SCOPE' — " +
          "connecting without it, so the connection lasts as long as the pod chooses to make it"
      }
    }
    return podOAuthClient.buildAuthorizeUrl(metadata, podClientId, podCallbackUri, challenge, state, scope)
  }

  routing {

    // --- Dashboard ---
    get("/_system/ui") {
      val session = webSession.read(call)
        ?: return@get call.respondRedirect("$base/_system/ui/login")
      // Fetch the profile set once; a `?profile=` the user does not own falls back to the default
      // (a read-only view, so a lenient clamp is fine here — mutations use ownedProfile()).
      val profiles = profileDao.listForUser(session.user)
      val profile = ProfilePath.normalize(call.request.queryParameters["profile"])
        ?.takeIf { it in profiles } ?: PodKey.DEFAULT_PROFILE
      val connections = connectionRegistryDao.listForProfile(ProfileKey(session.user, profile))
      call.respondText(
        dashboardHtml(
          base = base,
          user = session.user,
          csrfToken = session.csrfToken,
          selectedProfile = profile,
          allProfiles = profiles,
          connections = connections,
          connectedBanner = call.request.queryParameters["connected"],
          connectedAs = call.request.queryParameters["connected_as"],
          errorBanner = call.request.queryParameters["error"],
        ),
        ContentType.Text.Html,
      )
    }

    // --- Create a named profile (explicit isolation boundary) ---
    post("/_system/ui/profiles/create") {
      val session = webSession.read(call)
        ?: return@post call.respondRedirect("$base/_system/ui/login")
      val form = call.receiveParameters()
      if (!csrfOk(form["csrf"], session)) return@post call.respondRedirect("$base/_system/ui?error=${enc("invalid request token")}")
      val name = form["name"]?.trim().orEmpty()
      if (!ProfilePath.isValidName(name)) {
        return@post call.respondRedirect("$base/_system/ui?error=${enc("invalid profile name (use a-z, 0-9, hyphen; not a reserved word)")}")
      }
      profileDao.create(session.user, name)
      logger.info { "profile created: user='${session.user}' profile='$name'" }
      call.respondRedirect("$base/_system/ui?profile=${enc(name)}")
    }

    // --- Browser login via id.sempods.org → web-session cookie ---
    // Scoped to the callback path alone, and under its own name: the AI-client flow at
    // `/oidc/callback` runs the same defence, and one browser may be in both at once.
    val loginPin = LoginCsrfPin(
      cookieNamePrefix = UI_LOGIN_PIN_COOKIE_PREFIX,
      cookiePath = UI_CALLBACK_PATH,
      secure = config.isSecure,
    )

    get("/_system/ui/login") {
      val relyingParty = try {
        identityProvider.relyingPartyFor(UI_CALLBACK_PATH)
      } catch (e: Exception) {
        logger.warn(e) { "identity provider discovery failed" }
        return@get call.respondText("identity provider unavailable", status = HttpStatusCode.ServiceUnavailable)
      }
      // A standard OIDC round trip: what comes back through the browser is a single-use code, and
      // the token is fetched over a back channel. The id-server's `state` doubles as the key this
      // flow is stored under.
      val started = relyingParty.beginAuthorization(state = webLoginStateStore.newState())
      // …and a second factor pinning the callback to *this* browser. Without it an attacker starts
      // a login, gets the callback link opened in someone else's browser, and that browser holds a
      // session for the attacker's identity — under which the UI would then save pod connections.
      val browserNonce = loginPin.mint()
      webLoginStateStore.create(
        started.state,
        WebLoginStateStore.Pending(
          next = "$base/_system/ui",
          codeVerifier = started.codeVerifier,
          nonce = started.nonce,
          browserNonce = browserNonce,
        ),
      )
      loginPin.set(call, started.state, browserNonce)
      call.respondRedirect(started.authorizationUrl)
    }

    get("/_system/ui/login/callback") {
      val q = call.request.queryParameters
      val state = q["state"] ?: return@get call.respondText("missing state", status = HttpStatusCode.BadRequest)
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
      val pending = webLoginStateStore.consume(state)
        ?: return@get call.respondText("invalid or expired login state", status = HttpStatusCode.BadRequest)

      // The callback must be completed in the SAME browser that started the sign-in. Checked
      // before the code is exchanged — an attacker's callback link opened here must cost nothing
      // and establish nothing. The cookie is cleared either way; it is one-time.
      val pinPresented = loginPin.isPresent(call, state)
      val pinMatches = loginPin.matches(call, state, pending.browserNonce)
      if (!pinMatches) {
        logger.warn { "ui login callback rejected: browser pin ${if (!pinPresented) "absent" else "mismatch"}" }
        return@get call.respondText(
          "this sign-in was not started in this browser — please sign in again",
          status = HttpStatusCode.BadRequest,
        )
      }

      val code = q["code"]
        ?: return@get call.respondText("login failed", status = HttpStatusCode.Unauthorized)
      val identity = try {
        identityProvider.relyingPartyFor(UI_CALLBACK_PATH).completeAuthorization(code, pending.codeVerifier, pending.nonce)
      } catch (e: Exception) {
        logger.warn(e) { "id-server token exchange failed" }
        return@get call.respondText("login failed", status = HttpStatusCode.Unauthorized)
      }
      // The web session binds only the user; profile selection is request-scoped per the UI.
      webSession.establish(call, identity.webId)
      call.respondRedirect(pending.next)
    }

    get("/_system/ui/logout") {
      webSession.clear(call)
      call.respondRedirect("$base/_system/ui/login")
    }

    // --- Connect a pod (service → pod OAuth) ---
    post("/_system/ui/pods/connect") {
      val session = webSession.read(call)
        ?: return@post call.respondRedirect("$base/_system/ui/login")
      val form = call.receiveParameters()
      if (!csrfOk(form["csrf"], session)) return@post call.respondRedirect("$base/_system/ui?error=${enc("invalid request token")}")
      val profile = ownedProfile(form["profile"], session.user)
        ?: return@post call.respondRedirect("$base/_system/ui?error=${enc("unknown profile")}")
      // Optional caller-supplied landing page for after the pod is connected. Only an internal
      // `$base/…` URL is honoured (open-redirect guard); anything else falls back to the dashboard.
      // The inline consent flow passes its consent-resume URL here so the user returns to consent.
      val returnTo = form["return_to"]?.takeIf { it.startsWith("$base/") }
      fun errorBack(msg: String): String =
        returnTo?.let { withParam(it, "error=${enc(msg)}") } ?: "$base/_system/ui?profile=${enc(profile)}&error=${enc(msg)}"
      val podBaseUrl = form["pod_base_url"]?.trim()?.trimEnd('/').orEmpty()
      if (podBaseUrl.isEmpty()) return@post call.respondRedirect(errorBack("missing pod URL"))
      podUrlPolicy.reject(podBaseUrl)?.let { reason ->
        return@post call.respondRedirect(errorBack("rejected pod URL: $reason"))
      }

      val redirect = runCatching {
        // First connect: no pinned client_id yet — DCR when the pod offers it (full sempods pod),
        // otherwise our static did:web client_id (did:web:<mcp-host>). Whether the pod resolves the
        // did.json we serve or just matches the origin is its own choice — the method permits both,
        // and this branch is for pods we did not write. A sempods pod fetches nothing (`DidWeb`).
        buildPodAuthorizeRedirect(session.user, profile, podBaseUrl, returnTo, existing = null)
      }.getOrElse { e ->
        logger.warn(e) { "pod connect failed for '$podBaseUrl'" }
        errorBack("could not reach pod")
      }
      call.respondRedirect(redirect)
    }

    // --- Re-authorize a connected pod (change the granted contexts on the pod's consent screen) ---
    // The pod re-shows its consent UI (our MCP registration is a `dyn:` client, so consent is always
    // re-displayed) with the prior context grants pre-checked; the shared /pods/callback below then
    // re-`upsert`s the connection with whatever scopes come back. Which client_id is presented —
    // the stored one, or a freshly registered one for a connection the pod has declared dead — is
    // [reusableClientId]'s decision. This is also the button the dashboard offers for a pod that
    // needs reconnecting, so it must survive a registration the pod no longer holds.
    //
    // TODO: a registration cleared while the connection was never flagged dead (nothing refreshed
    //  against it since) still dead-ends here: nothing tells this service the id is gone, so the
    //  stored one is presented and the pod answers a flat 400 in the browser. Closing it means
    //  either re-registering on every re-auth — which orphans grants on a pod that does not dedup —
    //  or a way to ask a pod whether a client_id is still live (RFC 7592 needs a registration
    //  access token this service does not keep). Neither is a line to slip in here.
    post("/_system/ui/pods/reauthorize") {
      val session = webSession.read(call)
        ?: return@post call.respondRedirect("$base/_system/ui/login")
      val form = call.receiveParameters()
      if (!csrfOk(form["csrf"], session)) return@post call.respondRedirect("$base/_system/ui?error=${enc("invalid request token")}")
      val profile = ownedProfile(form["profile"], session.user)
        ?: return@post call.respondRedirect("$base/_system/ui?error=${enc("unknown profile")}")
      fun errorBack(msg: String): String = "$base/_system/ui?profile=${enc(profile)}&error=${enc(msg)}"
      val pod = form["pod"]?.trim()?.trimEnd('/').orEmpty()
      if (pod.isEmpty()) return@post call.respondRedirect(errorBack("missing pod URL"))
      // The connection must already exist for this (user, profile) — re-auth reuses its client_id.
      val connection = connectionRegistryDao.find(PodKey(session.user, profile, pod))
        ?: return@post call.respondRedirect(errorBack("unknown connection"))

      val redirect = runCatching {
        buildPodAuthorizeRedirect(session.user, profile, connection.pod, returnTo = null, existing = connection)
      }.getOrElse { e ->
        logger.warn(e) { "pod reauthorize failed for '$pod'" }
        errorBack("could not reach pod")
      }
      call.respondRedirect(redirect)
    }

    // --- Pod redirects here with the authorization code ---
    get("/_system/ui/pods/callback") {
      val session = webSession.read(call)
        ?: return@get call.respondRedirect("$base/_system/ui/login")
      val q = call.request.queryParameters
      // Resolve the pending connect FIRST — even on an OAuth error the pod echoes `state`, so
      // consuming it here (and cross-checking the session) is what makes `pending.returnTo`
      // available to every subsequent branch. Consuming on error also cleans the one-time entry.
      // Only a callback with no usable `state` has to fall back to the dashboard: without pending
      // there is no returnTo to honour.
      val state = q["state"] ?: return@get call.respondRedirect("$base/_system/ui?error=${enc("missing state")}")
      val pending = podConnectStateStore.consume(state)
        ?: return@get call.respondRedirect("$base/_system/ui?error=${enc("invalid or expired connect state")}")
      if (pending.user != session.user) {
        return@get call.respondRedirect("$base/_system/ui?error=${enc("session/user mismatch")}")
      }

      // Where to send the browser: back to the consent screen if this connect was started there
      // (pending.returnTo, already validated internal at creation), else the dashboard. The
      // separator is chosen per target (returnTo may or may not already carry a query string), so
      // a query-less internal returnTo can't produce a broken '…/path&error=…' that 404s.
      fun landing(extra: String): String =
        pending.returnTo?.let { withParam(it, extra) } ?: "$base/_system/ui?profile=${enc(pending.profile)}&$extra"

      // A pod-denied consent or a malformed callback with no code returns to wherever the connect
      // was started — the consent screen keeps the flow (and the selected profile), not the dashboard.
      q["error"]?.let { return@get call.respondRedirect(landing("error=${enc("pod denied: $it")}")) }
      val code = q["code"] ?: return@get call.respondRedirect(landing("error=${enc("missing code")}"))

      val result = runCatching {
        val tokens = podOAuthClient.exchangeCode(pending.metadata, code, pending.redirectUri, pending.podClientId, pending.codeVerifier)
        // Identity classification (not a gate): read the pod token's `sub` — the pod-local WebID.
        // The trust that this token belongs to *this* caller comes from the flow (the same signed-in
        // browser session that started the connect — enforced by pending.user == session.user above),
        // not from `sub == session.user`. A pod running its own identity provider legitimately mints a
        // different WebID for the same person; we record it rather than reject it. Only a token whose
        // subject we cannot read at all is a hard failure (nothing to bind the connection to).
        // At connect time a usable subject is required (it becomes the connection's recorded identity):
        // a VerificationFailed (advertised JWKS rejected the token) or Unreadable (opaque/sub-less)
        // token has no identity we can bind to, so the connect fails rather than storing a blind token.
        val subject = (podOAuthClient.verifyAccessTokenSubject(pending.metadata, tokens.accessToken)
          as? PodOAuthClient.SubjectOutcome.Readable)?.subject
          ?: error("pod access token carried no usable subject")
        val now = Date()
        val scopes = OAuthSyntax.parseScope(tokens.scope)
        val connection = PodConnection(
          user = pending.user, profile = pending.profile, pod = pending.pod,
          issuer = pending.metadata.issuer, podClientId = pending.podClientId, scopes = scopes,
          podSubject = subject.webId, subjectVerified = subject.verified,
          createdAt = now, updatedAt = now,
        )
        tokenVaultDao.upsert(
          PodTokens(
            user = pending.user, profile = pending.profile, pod = pending.pod,
            accessToken = tokens.accessToken, refreshToken = tokens.refreshToken,
            accessTokenExpiresAt = tokens.expiresInSeconds?.let { Date(now.time + it * 1000) },
            updatedAt = now,
            // A connect IS a use: the person is right here, and whatever they do next should not
            // pay for a cold connection the sweep has not been given a reason to keep warm yet.
            lastUsedAt = now,
          ),
        )
        connectionRegistryDao.upsert(connection)
        logger.info {
          "pod connected: user='${pending.user}' profile='${pending.profile}' pod='${pending.pod}' scopes=$scopes podSubject='${subject.webId}' verified=${subject.verified} foreign=${connection.foreignIdentity}"
        }
        auditLog.podConnected(pending.user, pending.profile, pending.pod, ok = true)
        // Carry the pod-local identity into the landing when it differs from the service identity,
        // so the connect-time screen can tell the user this connection acts as a different WebID.
        val connected = "connected=${enc(pending.pod)}"
        landing(if (connection.foreignIdentity) "$connected&connected_as=${enc(subject.webId)}" else connected)
      }.getOrElse { e ->
        logger.warn(e) { "pod token exchange failed for '${pending.pod}'" }
        auditLog.podConnected(pending.user, pending.profile, pending.pod, ok = false, detail = "connect_failed")
        landing("error=${enc("connect failed")}")
      }
      call.respondRedirect(result)
    }

    post("/_system/ui/pods/disconnect") {
      val session = webSession.read(call)
        ?: return@post call.respondRedirect("$base/_system/ui/login")
      val form = call.receiveParameters()
      if (!csrfOk(form["csrf"], session)) return@post call.respondRedirect("$base/_system/ui?error=${enc("invalid request token")}")
      val profile = ownedProfile(form["profile"], session.user)
        ?: return@post call.respondRedirect("$base/_system/ui?error=${enc("unknown profile")}")
      val pod = form["pod"]?.trim().orEmpty()
      if (pod.isNotEmpty()) {
        val key = PodKey(session.user, profile, pod)
        tokenVaultDao.delete(key)
        connectionRegistryDao.delete(key)
        logger.info { "pod disconnected: user='${session.user}' profile='$profile' pod='$pod'" }
        auditLog.podDisconnected(session.user, profile, pod)
      }
      call.respondRedirect("$base/_system/ui?profile=${enc(profile)}")
    }
  }
}

private fun dashboardHtml(
  base: String,
  user: String,
  csrfToken: String,
  selectedProfile: String,
  allProfiles: List<String>,
  connections: List<PodConnection>,
  connectedBanner: String?,
  connectedAs: String?,
  errorBanner: String?,
): String = buildString {
  // The MCP URL the AI client points at IS the resource: default profile = service root, a named
  // profile = `$base/<profile>` (suffix-free, M5).
  val mcpUrl = ProfilePath.baseUrlFor(base, selectedProfile)

  append("<!doctype html><html><head><meta charset=\"utf-8\"><title>sempods MCP — connections</title>")
  append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
  // Design system shared with the OAuth consent screen (AuthEndpoint.respondConsent): tokenized
  // palette, flex pod-cards, badge pills, ghost buttons, wrap-safe code. Self-contained inline CSS —
  // no external assets. The `.pod-main{min-width:0}` + `gap` + wrap-safe `code` fix the old overlap
  // where a long "acts as" WebID slid under the Disconnect button.
  append("<style>:root{--fg:#1a1a1a;--muted:#6b7280;--line:#e2e5ea;--card:#fbfcfe;--accent:#2d6cdf;--ok-bg:#e6f4ea;--ok-line:#bfe3c9;--err-bg:#fdecea;--err-line:#f4c9c4}")
  append("*{box-sizing:border-box}")
  append("body{font-family:system-ui,-apple-system,sans-serif;color:var(--fg);max-width:40rem;margin:2.5rem auto;padding:0 1.1rem;line-height:1.55}")
  append("h1{font-size:1.35rem;margin:0 0 .3rem}")
  append("h2{font-size:.8rem;text-transform:uppercase;letter-spacing:.04em;color:var(--muted);margin:1.8rem 0 .6rem;padding-bottom:.35rem;border-bottom:1px solid var(--line)}")
  append("p{margin:.4rem 0}")
  append("code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.9em;overflow-wrap:anywhere;word-break:break-word}")
  append(".who{color:var(--muted);font-size:.9rem;margin-top:.2rem}.who code{color:var(--fg)}.who a{color:var(--accent)}")
  append(".muted{color:var(--muted);font-size:.88rem}")
  append("input[type=url]{width:100%;padding:.55rem .65rem;border:1px solid var(--line);border-radius:.45rem;font-size:.95rem}")
  append("select,input[type=text]{font:inherit;font-size:.95rem;padding:.45rem .55rem;border:1px solid var(--line);border-radius:.45rem;background:#fff}")
  append("input:focus,select:focus{outline:2px solid var(--accent);outline-offset:0;border-color:var(--accent)}")
  append("button{font:inherit;font-size:.95rem;padding:.55rem 1.1rem;border:0;border-radius:.45rem;background:var(--accent);color:#fff;cursor:pointer}")
  append("button:hover{filter:brightness(.95)}")
  append(".row{display:flex;gap:.5rem;align-items:center;flex-wrap:wrap;margin:.5rem 0}.row form{margin:0}")
  append(".pod{display:flex;justify-content:space-between;align-items:flex-start;gap:.75rem;background:var(--card);border:1px solid var(--line);border-radius:.55rem;padding:.65rem .8rem;margin:.5rem 0}")
  append(".pod-main{min-width:0;flex:1}.pod-main code{font-size:.92rem}")
  append(".pod-actions{display:flex;flex-direction:column;gap:.35rem;flex:none}.pod-actions form{margin:0}.pod-actions button{width:100%}")
  append(".reauth{background:none;color:var(--accent);border:1px solid var(--line);padding:.3rem .65rem;font-size:.82rem;border-radius:.4rem}.reauth:hover{background:#f4f6fb;filter:none}")
  append(".rm{background:none;color:#c0392b;border:1px solid var(--err-line);padding:.3rem .65rem;font-size:.82rem;border-radius:.4rem}.rm:hover{background:var(--err-bg);filter:none}")
  append(".badges{margin-top:.35rem;display:flex;flex-wrap:wrap;gap:.3rem}")
  append(".badge{font-size:.72rem;line-height:1.5;padding:0 .45rem;border-radius:.6rem;background:#eef1f6;color:#4b5563;font-family:ui-monospace,monospace}.badge.warn{background:#fef3e2;color:#9a5b00}")
  append(".acts{margin-top:.3rem;font-size:.82rem;color:var(--muted)}.acts code{color:var(--fg)}")
  append(".url{background:#f4f6fb;border:1px solid #dce3f2;border-radius:.45rem;padding:.55rem .7rem;font-family:ui-monospace,monospace;font-size:.9rem;overflow-wrap:anywhere;word-break:break-word;margin:.35rem 0}")
  append(".banner{padding:.6rem .8rem;border-radius:.5rem;margin:.6rem 0;font-size:.92rem}.banner code{overflow-wrap:anywhere}")
  append(".ok{background:var(--ok-bg);border:1px solid var(--ok-line)}.err{background:var(--err-bg);border:1px solid var(--err-line)}</style>")
  append("</head><body>")
  append("<h1>Your connected pods</h1>")
  append("<p class=\"who\">Signed in as <code>").appendEscapedHtml(user).append("</code> · <a href=\"").append(base).append("/_system/ui/logout\">log out</a></p>")
  connectedBanner?.let {
    append("<div class=\"banner ok\">Connected <code>").appendEscapedHtml(it).append("</code>")
    // When the pod authorized a different WebID than the service identity, name it here so the user
    // sees, at connect time, that this connection acts as the pod's own identity.
    connectedAs?.let { who ->
      append(" — acting as <code>").appendEscapedHtml(who).append("</code> (this pod's own identity, not your sempods identity)")
    }
    append(".</div>")
  }
  errorBanner?.let { append("<div class=\"banner err\">").appendEscapedHtml(it).append("</div>") }

  // --- Profile switcher + create ---
  append("<h2>Profile</h2>")
  append("<div class=\"row\">")
  append("<form method=\"get\" action=\"").append(base).append("/_system/ui\">")
  append("<select name=\"profile\" onchange=\"this.form.submit()\">")
  for (p in allProfiles) {
    val label = if (p == PodKey.DEFAULT_PROFILE) "default (root)" else p
    append("<option value=\"").appendEscapedHtml(p).append("\"")
    if (p == selectedProfile) append(" selected")
    append(">").appendEscapedHtml(label).append("</option>")
  }
  append("</select> <noscript><button type=\"submit\">Switch</button></noscript></form>")
  append("<form method=\"post\" action=\"").append(base).append("/_system/ui/profiles/create\">")
  append("<input type=\"hidden\" name=\"csrf\" value=\"").appendEscapedHtml(csrfToken).append("\">")
  append("<input type=\"text\" name=\"name\" placeholder=\"new profile\" pattern=\"[a-z0-9][a-z0-9-]*\" required>")
  append(" <button type=\"submit\">Create</button></form>")
  append("</div>")
  append("<p class=\"muted\">MCP URL for your AI client (this profile):</p>")
  append("<div class=\"url\">").appendEscapedHtml(mcpUrl).append("</div>")

  // --- Pods in the selected profile ---
  append("<h2>Connected pods</h2>")
  if (connections.isEmpty()) {
    append("<p class=\"muted\">No pods connected to this profile yet.</p>")
  } else {
    for (c in connections.sortedBy { it.pod }) {
      append("<div class=\"pod\"><div class=\"pod-main\"><code>").appendEscapedHtml(c.pod).append("</code>")
      // Feature scopes (e.g. `public-read`) as pills, plus an "unverified" flag when the pod exposes
      // no JWKS. Per-context grants are NOT held here — they live on the pod; edit them via Re-authorize.
      // TODO: surface the pod's per-context grants here once a pod-side grants read API exists.
      val showUnverified = c.foreignIdentity && !c.subjectVerified
      val needsReconnect = c.deadGrantSince != null
      if (c.scopes.isNotEmpty() || showUnverified || needsReconnect) {
        append("<div class=\"badges\">")
        for (s in c.scopes.sorted()) append("<span class=\"badge\">").appendEscapedHtml(s).append("</span>")
        if (showUnverified) append("<span class=\"badge warn\">unverified</span>")
        if (needsReconnect) append("<span class=\"badge warn\">reconnect needed</span>")
        append("</div>")
      }
      // A pill is easy to miss, and this one means the pod is doing nothing at all — say it in
      // words next to the action that fixes it.
      if (needsReconnect) {
        append("<div class=\"acts\">this pod refused the connection's access; Re-authorize restores it</div>")
      }
      // Surface the pod-local identity when it differs from the service identity — this connection
      // acts on the pod as that WebID.
      if (c.foreignIdentity) {
        append("<div class=\"acts\">acts as <code>").appendEscapedHtml(c.podSubject.orEmpty()).append("</code></div>")
      }
      append("</div>")
      // Per-pod actions: Re-authorize (re-open the pod consent to change contexts) + Disconnect.
      append("<div class=\"pod-actions\">")
      append("<form method=\"post\" action=\"").append(base).append("/_system/ui/pods/reauthorize\">")
      append("<input type=\"hidden\" name=\"pod\" value=\"").appendEscapedHtml(c.pod).append("\">")
      append("<input type=\"hidden\" name=\"profile\" value=\"").appendEscapedHtml(selectedProfile).append("\">")
      append("<input type=\"hidden\" name=\"csrf\" value=\"").appendEscapedHtml(csrfToken).append("\">")
      append("<button type=\"submit\" class=\"reauth\">Re-authorize</button></form>")
      append("<form method=\"post\" action=\"").append(base).append("/_system/ui/pods/disconnect\">")
      append("<input type=\"hidden\" name=\"pod\" value=\"").appendEscapedHtml(c.pod).append("\">")
      append("<input type=\"hidden\" name=\"profile\" value=\"").appendEscapedHtml(selectedProfile).append("\">")
      append("<input type=\"hidden\" name=\"csrf\" value=\"").appendEscapedHtml(csrfToken).append("\">")
      append("<button type=\"submit\" class=\"rm\">Disconnect</button></form>")
      append("</div></div>")
    }
  }

  append("<h2>Connect a pod</h2>")
  append("<form method=\"post\" action=\"").append(base).append("/_system/ui/pods/connect\">")
  append("<input type=\"hidden\" name=\"csrf\" value=\"").appendEscapedHtml(csrfToken).append("\">")
  append("<input type=\"hidden\" name=\"profile\" value=\"").appendEscapedHtml(selectedProfile).append("\">")
  append("<p><input type=\"url\" name=\"pod_base_url\" placeholder=\"https://sempods.org/your-pod\" required></p>")
  append("<p><button type=\"submit\">Connect</button></p></form>")
  append("</body></html>")
}

/** Per-session CSRF check: the submitted token must equal the session's (cookie-only) jti. */
private fun csrfOk(submitted: String?, session: ServiceBearerVerifier.WebPrincipal): Boolean {
  if (submitted.isNullOrEmpty()) return false
  return MessageDigest.isEqual(
    submitted.toByteArray(StandardCharsets.UTF_8),
    session.csrfToken.toByteArray(StandardCharsets.UTF_8),
  )
}

private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

/** Append a pre-encoded `key=value` to a URL, choosing `?` or `&` by whether it already has a query. */
private fun withParam(url: String, param: String): String =
  url + (if (url.contains('?')) '&' else '?') + param

private const val SERVICE_VERSION = "0.2.0-M2"

/**
 * The scope this service asks a pod for — on connect and on re-authorize, from every pod whose
 * discovery says it is understood.
 *
 * It holds the connection open with the pod's refresh token instead of sending the user back
 * through consent every hour, so the authority is asked for rather than taken: a pod is free to
 * refuse it, and one that does not know the scope ignores it. A sempods extension — the OIDC scope
 * name used outside OIDC, requested bare, without `openid`, because no `id_token` is involved.
 */
private const val OFFLINE_ACCESS_SCOPE = "offline_access"
