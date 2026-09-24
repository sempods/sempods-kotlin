package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.io.IOException
import java.time.Instant
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.auth.PodBrowserCookies
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Secrets
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.BasicAuth
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.oauth.flows.PodAuthorizeFlow
import org.sempods.pods.oauth.flows.PodClientRegistration
import org.sempods.pods.oauth.flows.PodAuthorizeRequest
import org.sempods.pods.oauth.flows.PodConsentFlow
import org.sempods.pods.oauth.flows.PodRegistrationRequest
import org.sempods.pods.oauth.flows.PodConsentForm
import org.sempods.pods.oauth.flows.PodConsentResult
import org.sempods.pods.oauth.flows.PodAuthorizeResult
import org.sempods.pods.oauth.PodSignOut
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.pods.oauth.flows.PodTokenExchange
import org.sempods.pods.oauth.flows.PodTokenResult

@Path("{pod}/_system/auth")
class PodAuthEndpoint @Inject constructor(
  private val podAuthorizeFlow: PodAuthorizeFlow,
  private val podConsentFlow: PodConsentFlow,
  private val podTokenExchange: PodTokenExchange,
  private val podClientRegistration: PodClientRegistration,
  private val templateRenderer: TemplateRenderer,
  private val podTokenIssuer: PodTokenIssuer,
  private val podSignOut: PodSignOut,
  private val tokenRateLimiter: PodTokenRateLimiter,
  private val registrationRateLimiter: PodRegistrationRateLimiter,
  private val identityProvider: PodIdentityProvider,
  private val loginStateStore: PodLoginStateStore,
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
  // One route: a pod has one registration endpoint, which is what `registration_endpoint` in
  // AS-metadata points at. What it answers is [PodClientRegistration]'s.

  @POST
  @Path("register")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  fun register(
    @PathParam("pod") pod: String,
    @HeaderParam("User-Agent") userAgent: String?,
    @HeaderParam("X-Forwarded-For") forwardedFor: String?,
    // The raw body, so that a body which is not JSON earns RFC 7591's `invalid_client_metadata`
    // — an answer a registering client can act on.
    body: String?,
  ): Response {
    // Ahead of the pod row, as at `/token`, so a refused request costs no query at all.
    if (!registrationRateLimiter.tryAcquireAddress(forwardedFor, bearerPresented = bearerToken() != null)) {
      return PodRegistrationResponses.rateLimited()
    }
    val podDbo = fetchPodOrThrow(pod)
    // Asked before the body is read, so that a caller which presented a credential hears about the
    // credential whatever its body looks like. It costs the unauthenticated profile nothing: with
    // no `Authorization` header this returns before anything is verified.
    val caller = resolveBearerOrNull(podDbo)
    val result = when (val read = PodRegistrationMessages.read(body)) {
      is PodRegistrationRead.Unreadable -> read.refusal
      is PodRegistrationRead.Metadata -> podClientRegistration.register(
        pod = podDbo.hosted,
        request = PodRegistrationRequest(
          client = read.client,
          raw = read.raw,
          userAgent = userAgent,
          forwardedFor = forwardedFor,
          caller = caller,
        ),
      )
    }
    return PodRegistrationResponses.render(result) { error -> buildBearerChallenge(podDbo.name, error) }
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
    val session = readSession(podDbo, sessionCookie)
    return render(
      podDbo.name,
      podConsentFlow.submit(
        pod = podDbo.hosted,
        form = PodConsentForm(
          clientId = clientId,
          redirectUri = redirectUri,
          state = state,
          codeChallenge = codeChallenge,
          codeChallengeMethod = codeChallengeMethod,
          csrf = csrf,
          scopes = scopes,
          newContexts = newContexts,
          newContextScopes = newContextScopes,
          // An unticked checkbox sends nothing at all, so the question is whether the field
          // arrived. What a ticked one spells — `on`, in every browser that has ever sent this
          // form — is the browser's business and is deliberately not read.
          durable = durable != null,
          action = action,
        ),
        session = session,
      ),
    )
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
      ).asResponse(podDbo.name)

      "refresh_token" -> podTokenExchange.refresh(
        pod = podDbo.podId(),
        podName = podDbo.name,
        refreshToken = refreshToken,
        clientId = clientId,
        requestedScope = scope,
      ).asResponse(podDbo.name)

      // HTTP Basic is this adapter's format: a request that presents no credentials at all is
      // answered here, and what a presented pair *means* is the exchange's.
      "client_credentials" -> BasicAuth.parse(authorizationHeader)?.let { basic ->
        podTokenExchange.exchangeServiceClient(
          pod = podDbo.podId(),
          podName = podDbo.name,
          clientId = basic.username,
          secret = basic.password,
          requestedScope = scope,
        ).asResponse(podDbo.name)
      } ?: PodTokenResponses.clientAuthenticationRequired(
        realm = podDbo.name,
        description = "HTTP Basic authentication required",
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
  private fun PodTokenResult.asResponse(realm: String): Response = when (this) {
    is PodTokenResult.Issued -> PodTokenResponses.tokens(
      accessToken = accessToken,
      expiresInSeconds = expiresInSeconds,
      scope = OAuthSyntax.formatScope(scopes).takeIf { statesEmptyScope || scopes.isNotEmpty() },
      refreshToken = refreshToken,
    )

    is PodTokenResult.Refused -> PodTokenResponses.error(code, description)

    is PodTokenResult.ClientAuthenticationRequired ->
      PodTokenResponses.clientAuthenticationRequired(realm, description)
  }

  // ─── JWKS ─────────────────────────────────────────────────────────────────

  /**
   * Where the id-server sends the browser back after a sign-in this server started.
   *
   * Everything of substance is server-side: `state` names a request parked by [PodAuthorizeFlow.authorize], and
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
        // The charset is stated because the sentence carries a dash — see [PodAuthorizeResponses].
        .type("text/plain;charset=UTF-8")
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

  /** The same, for the submission that answers it. */
  private fun render(podName: String, result: PodConsentResult): Response =
    PodAuthorizeResponses.render(result, podName, cookies, templateRenderer, config)

  /**
   * Reports [error] at the address a parked request was validated with.
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
  }
}
