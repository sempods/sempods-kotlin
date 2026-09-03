package org.sempods.auth.api.provider

import com.nimbusds.oauth2.sdk.AuthorizationErrorResponse
import com.nimbusds.oauth2.sdk.OAuth2Error
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.ResponseMode
import com.nimbusds.oauth2.sdk.TokenErrorResponse
import com.nimbusds.oauth2.sdk.ErrorObject
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.token.OIDCTokens
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.sempods.auth.api.login.LoginPage
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.ClientRedirectPolicy
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.OidcPrompt
import org.sempods.auth.core.Pkce
import org.sempods.auth.core.RedirectUri
import org.sempods.auth.login.JwtIssuer
import org.sempods.auth.login.LoginService
import org.sempods.auth.login.StateStore
import org.sempods.auth.oidc.OidcProviderClient
import java.net.URI

/**
 * This service in its **OpenID Provider** role: it authenticates a person and tells a client who
 * they are.
 *
 * Not to be confused with `org.sempods.auth.oidc`, which is the opposite role — this service as a
 * *client* of Google and Apple. Both legs are OIDC and they point in different directions, which
 * is why they live in different packages and why `oidc` never appears in a path here. The
 * `id.sempods.org/oidc/{hash}` namespace is something else again: a person's identity document.
 *
 * ```
 *   pod  ──/authorize──▶  this service  ──▶  Google / Apple      (the RP leg, in `oidc/`)
 *        ◀──?code=─────                 ◀──  callback
 *        ──/token────▶                       id_token
 * ```
 *
 * An OpenID Provider is an authorization server that additionally authenticates, which is why the
 * endpoint is called `/authorize` even though nothing here grants access to a resource: what the
 * person authorizes is the client learning who they are. Google and Apple — pure identity
 * providers both — name theirs the same.
 *
 * **The protocol messages are the SDK's; the decisions are ours.** `AuthenticationRequest`,
 * `AuthorizationErrorResponse` and `OIDCTokenResponse` parse and build what the specification
 * fixes — and the last of those is why this is worth a dependency: `OIDCTokens` cannot be
 * constructed without an access token, which is exactly the defect review found in the version
 * that built its JSON by hand. What stays here is what no library can decide: which clients are
 * acceptable, where codes live, which upstream provider to use, and how a WebID is derived.
 *
 * The token endpoint reads its own form. `TokenRequest.parse` takes an SDK `HTTPRequest`, and
 * bridging Ktor into one costs more than the five flat fields it would parse; the response side,
 * where the defect was, is the SDK's.
 *
 * The order of checks in the authorize handler is load-bearing: **nothing may be delivered to a
 * redirect address before that address is known to belong to the client that named it**, an error
 * included. The SDK hands back the address it parsed on a failed parse — that is the one that must
 * not be used, so the policy check runs before parsing rather than after.
 */
fun Application.openIdProviderEndpoint(
  issuer: String,
  providers: Map<String, OidcProviderClient>,
  clientRedirectPolicy: ClientRedirectPolicy,
  stateStore: StateStore,
  authorizationCodeStore: AuthorizationCodeStore,
  jwtIssuer: JwtIssuer,
  loginService: LoginService,
) {
  val logger = KotlinLogging.logger("org.sempods.auth.provider")

  routing {

    get(OpenIdConfiguration.DISCOVERY_PATH) {
      call.respondText(OpenIdConfiguration.documentJson(issuer), ContentType.Application.Json)
    }

    get(OpenIdConfiguration.AUTHORIZATION_PATH) {
      val q = call.request.queryParameters

      // ── Before a redirect may carry anything, including an error ──
      val redirectUri = q["redirect_uri"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return@get call.respondText("missing redirect_uri", status = HttpStatusCode.BadRequest)
      if (!RedirectUri.isValid(redirectUri)) {
        return@get call.respondText("redirect_uri is not a usable address", status = HttpStatusCode.BadRequest)
      }
      val clientId = q["client_id"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return@get call.respondText("missing client_id", status = HttpStatusCode.BadRequest)
      if (!clientRedirectPolicy.permits(clientId, redirectUri)) {
        logger.info { "[authorize] refused: client='$clientId' may not be answered at '$redirectUri'" }
        return@get call.respondText(
          "redirect_uri is not allowed for this client_id",
          status = HttpStatusCode.BadRequest,
        )
      }

      // ── The address is proven, so errors may travel to it ──
      val clientState = q["state"]?.takeIf { it.isNotBlank() }
      fun fail(error: ErrorObject): String = errorRedirect(redirectUri, error, clientState)

      // Checked before the SDK parses, so the error code is the one that describes the problem:
      // `AuthenticationRequest` requires `openid` and reports its absence as a malformed request,
      // where RFC 6749 §4.1.2.1 has a code that says which part was wrong.
      val scopes = OAuthSyntax.parseScope(q["scope"])
      if ("openid" !in scopes) {
        return@get call.respondRedirect(fail(OAuth2Error.INVALID_SCOPE.setDescription("the 'openid' scope is required")))
      }
      // Refused rather than ignored. RFC 6749 §3.3 permits either, but the discovery document
      // promises `scopes_supported: ["openid"]`, and ignoring means a value the client chose is
      // carried into the authorization code and read by whatever comes next.
      (scopes - OpenIdConfiguration.SUPPORTED_SCOPES.toSet()).firstOrNull()?.let { unsupported ->
        return@get call.respondRedirect(fail(OAuth2Error.INVALID_SCOPE.setDescription("unsupported scope: $unsupported")))
      }

      val request = try {
        AuthenticationRequest.parse(q.toSdkParameters())
      } catch (e: ParseException) {
        // The SDK's own error object: `unsupported_response_type` for a `token` request,
        // `invalid_request` for a malformed one, with a description worth passing on.
        logger.info { "[authorize] rejected by the parser: ${e.message}" }
        return@get call.respondRedirect(fail(e.errorObject ?: OAuth2Error.INVALID_REQUEST))
      }

      // PKCE with no exemption. The pod grants `did:web:` clients one; this provider does not,
      // because an intercepted code with no verifier is redeemable by whoever intercepted it.
      val codeChallenge = request.codeChallenge
      if (codeChallenge == null || request.codeChallengeMethod?.value != Pkce.METHOD_S256) {
        return@get call.respondRedirect(
          fail(OAuth2Error.INVALID_REQUEST.setDescription("code_challenge with method S256 is required")),
        )
      }

      val promptValues = OAuthSyntax.parsePrompt(q["prompt"])
      if (OAuthSyntax.isContradictoryPrompt(promptValues)) {
        return@get call.respondRedirect(
          fail(OAuth2Error.INVALID_REQUEST.setDescription("prompt=none cannot be combined with other values")),
        )
      }
      // This service holds no session of its own — every authorization runs the provider leg — so
      // there is never an authenticated user to answer `prompt=none` with.
      if (OAuthSyntax.PROMPT_NONE in promptValues) {
        return@get call.respondRedirect(
          fail(
            ErrorObject("login_required", "this provider cannot authenticate without interaction")
              .setHTTPStatusCode(HttpStatusCode.Found.value),
          ),
        )
      }

      // ── Which upstream provider ──
      val requested = q["provider"]?.trim()?.takeIf { it.isNotBlank() }
      val provider = when {
        requested != null -> providers[requested]
          ?: return@get call.respondRedirect(
            fail(OAuth2Error.INVALID_REQUEST.setDescription("unknown login provider: $requested")),
          )

        providers.isEmpty() ->
          return@get call.respondRedirect(
            fail(OAuth2Error.SERVER_ERROR.setDescription("no login provider is configured")),
          )

        providers.size == 1 -> providers.values.first()

        else -> return@get call.respondText(
          LoginPage.render(orderedForDisplay(providers)) { candidate ->
            chooserHref(q, candidate.name)
          },
          ContentType.Text.Html,
        )
      }

      val pending = StateStore.PendingAuthorize(
        clientId = clientId,
        redirectUri = redirectUri,
        clientState = clientState,
        nonce = request.nonce?.value,
        codeChallenge = codeChallenge.value,
        codeChallengeMethod = Pkce.METHOD_S256,
        scopes = scopes,
      )
      val state = stateStore.generate(pending = pending)
      logger.info { "[authorize] client='$clientId' provider='${provider.name}'" }
      call.respondRedirect(provider.authorizeUrl(state, OidcPrompt.parse(q["prompt"])))
    }

    post(OpenIdConfiguration.TOKEN_PATH) {
      val form = call.receiveParameters()

      suspend fun tokenError(error: ErrorObject) = call.respondText(
        TokenErrorResponse(error).toJSONObject().toJSONString(),
        ContentType.Application.Json,
        HttpStatusCode.BadRequest,
      ) { noStore(call) }

      if (form["grant_type"]?.trim() != "authorization_code") {
        return@post tokenError(OAuth2Error.UNSUPPORTED_GRANT_TYPE)
      }
      val code = form["code"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return@post tokenError(OAuth2Error.INVALID_REQUEST.setDescription("missing code"))
      val clientId = form["client_id"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return@post tokenError(OAuth2Error.INVALID_REQUEST.setDescription("missing client_id"))
      val redirectUri = form["redirect_uri"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return@post tokenError(OAuth2Error.INVALID_REQUEST.setDescription("missing redirect_uri"))
      // Not trimmed, unlike the values above it: RFC 7636 §4.1's alphabet has no whitespace, so
      // trimming would grant a leniency the rule does not. `Pkce.verifyS256` sees what was sent.
      val codeVerifier = form["code_verifier"]?.takeIf { it.isNotBlank() }
        ?: return@post tokenError(OAuth2Error.INVALID_REQUEST.setDescription("missing code_verifier"))

      // One-time: consumed here whether or not the rest of the checks pass, so a code cannot be
      // probed repeatedly.
      val entry = authorizationCodeStore.consume(code)
        ?: return@post tokenError(
          OAuth2Error.INVALID_GRANT.setDescription("code is unknown, already used, or expired"),
        )

      if (entry.clientId != clientId) {
        return@post tokenError(OAuth2Error.INVALID_GRANT.setDescription("code was issued to a different client"))
      }
      if (entry.redirectUri != redirectUri) {
        return@post tokenError(
          OAuth2Error.INVALID_GRANT.setDescription("redirect_uri does not match the authorization request"),
        )
      }
      val issuedChallenge = entry.codeChallenge
      if (issuedChallenge == null || !Pkce.verifyS256(codeVerifier, issuedChallenge)) {
        return@post tokenError(OAuth2Error.INVALID_GRANT.setDescription("PKCE verification failed"))
      }

      // Server-derived, and only server-derived. `also_known_as` is what a pod resolves grants
      // and ownership against, so nothing a client can influence may reach it. Asking
      // `LoginService` rather than the profile directly is what keeps the deterministic URN in:
      // the profile alone holds only the links an identity merge recorded, and a first-time user
      // has none.
      val idToken = jwtIssuer.issueIdToken(
        webIdUri = entry.subject,
        audience = entry.clientId,
        alsoKnownAs = loginService.aliasesFor(entry.subject),
        nonce = entry.nonce,
      )
      val accessToken = jwtIssuer.issueAccessToken(webIdUri = entry.subject, scopes = entry.scopes)
      logger.info { "[token] id_token issued: client='$clientId' sub='${entry.subject}'" }

      // `OIDCTokens` will not accept a null access token, which is the whole reason this is the
      // SDK's response rather than a hand-built map: a token response without one is invalid
      // (RFC 6749 §5.1), and libraries that check the shape reject it.
      val response = OIDCTokenResponse(
        OIDCTokens(
          idToken,
          BearerAccessToken(accessToken, ACCESS_TOKEN_TTL_SECONDS, com.nimbusds.oauth2.sdk.Scope.parse(entry.scopes)),
          null,
        ),
      )
      call.respondText(
        response.toJSONObject().toJSONString(),
        ContentType.Application.Json,
      ) { noStore(call) }
    }
  }
}

/** Mirrors `JwtIssuer`'s access-token lifetime; `expires_in` describes that token, not the id_token. */
private const val ACCESS_TOKEN_TTL_SECONDS = 15L * 60

/**
 * RFC 6749 §5.1 — a token response must not be cached. Strict clients drop tokens that arrive
 * without these, which the pod server learned from GitHub Copilot CLI and wrote down so the next
 * implementation would not have to.
 */
private fun noStore(call: ApplicationCall) {
  call.response.headers.append(HttpHeaders.CacheControl, "no-store")
  call.response.headers.append(HttpHeaders.Pragma, "no-cache")
}

/**
 * The error redirect, built by the SDK so that the parameter names, the encoding and the presence
 * of `state` follow the specification rather than this file.
 *
 * [redirectUri] must already have been checked against the client — see the handler.
 */
private fun errorRedirect(redirectUri: String, error: ErrorObject, state: String?): String =
  AuthorizationErrorResponse(
    URI(redirectUri),
    error,
    state?.let { State(it) },
    ResponseMode.QUERY,
  ).toURI().toString()

/** Ktor's multimap in the shape the SDK parses. */
private fun Parameters.toSdkParameters(): Map<String, List<String>> =
  entries().associate { (name, values) -> name to values }

/** Apple first — its guidelines put it no lower than the alternatives — then the rest, stably. */
private fun orderedForDisplay(providers: Map<String, OidcProviderClient>): List<OidcProviderClient> =
  providers.values.sortedWith(compareBy({ it.name != "apple" }, { it.name }))

/**
 * The chooser links back to this same endpoint with `provider` added, so every parameter of the
 * authorization request survives the extra hop without being re-derived — and the flow stays a
 * plain GET.
 */
private fun chooserHref(original: Parameters, provider: String): String {
  val carried = Parameters.build {
    original.forEach { name, values -> if (name != "provider") values.forEach { append(name, it) } }
    append("provider", provider)
  }
  return OpenIdConfiguration.AUTHORIZATION_PATH + "?" + carried.formUrlEncode()
}
