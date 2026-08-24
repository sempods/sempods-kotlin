package org.sempods.auth.api.login

import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.commons.net.UrlUtil
import org.sempods.auth.login.LoginService
import org.sempods.auth.login.StateStore
import org.sempods.auth.oidc.OidcProviderClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Where Google and Apple answer.
 *
 * ```
 * GET  /login/oidc/{provider}/callback   — query-parameter callback (Google)
 * POST /login/oidc/{provider}/callback   — form_post callback (Apple)
 * ```
 *
 * The `/login` prefix is **not** movable: both addresses are registered in Apple's and Google's
 * developer consoles, and Apple compares its Return URL byte for byte. The endpoint that used to
 * live at `GET /login` is gone; this path only looks like part of it.
 *
 * The flow is `/authorize`'s (`api/provider/OpenIdProviderEndpoint.kt`). It parks the authorization
 * request under the `state` it sends upstream; this route consumes that state, exchanges the
 * provider's code for claims, and answers the parked request with a single-use authorization code.
 * Nothing that authenticates anyone travels back through the browser.
 *
 * id.sempods.org has no knowledge of pods or OAuth grants. It authenticates people and says who
 * they are.
 */
fun Application.providerCallbackEndpoint(
  stateStore: StateStore,
  providers: Map<String, OidcProviderClient>,
  loginService: LoginService,
  authorizationCodeStore: AuthorizationCodeStore,
  issuer: String,
) {
  routing {
    route("/login/oidc/{provider}") {
      get("/callback") { handleCallback(call, providers, stateStore, loginService, authorizationCodeStore, issuer) }
      post("/callback") { handleCallback(call, providers, stateStore, loginService, authorizationCodeStore, issuer) }
    }
  }
}

private suspend fun handleCallback(
  call: ApplicationCall,
  providers: Map<String, OidcProviderClient>,
  stateStore: StateStore,
  loginService: LoginService,
  authorizationCodeStore: AuthorizationCodeStore,
  issuer: String,
) {
  val name = call.parameters["provider"]
    ?: return call.respondText("Missing provider", status = HttpStatusCode.BadRequest)
  val provider = providers[name]
    ?: return call.respondText("Unknown provider: $name", status = HttpStatusCode.NotFound)

  val params = if (call.request.httpMethod == HttpMethod.Post)
    call.receiveParameters()
  else
    call.request.queryParameters

  val state = params["state"]
    ?: return call.respondText("Missing state", status = HttpStatusCode.BadRequest)
  // Unknown, already used, expired — or a row the old `/login` flow parked, which carries no
  // authorization request to answer and is therefore no row at all.
  val pending = stateStore.consume(state)
    ?: return call.respondText("Invalid or expired state", status = HttpStatusCode.BadRequest)

  // The provider declining is a normal outcome, not a malfunction: Apple sends
  // `error=user_cancelled_authorize` when the user backs out. Handing that back to the app lets it
  // say so; falling through to "Missing code" would show a bare 400 to someone who simply changed
  // their mind.
  params["error"]?.takeIf { it.isNotBlank() }?.let { error ->
    val description = params["error_description"]?.takeIf { it.isNotBlank() }
      ?: "the login provider declined the request"
    // The authorization request knows where its answer goes, and the address was validated before
    // the flow started — so a declined login comes back as an OAuth error rather than a page the
    // client cannot act on.
    return call.respondRedirect(
      buildErrorRedirect(pending.redirectUri, error, description) +
        (pending.clientState?.let { "&state=" + UrlUtil.urlEncode(it) } ?: ""),
    )
  }

  val code = params["code"]
    ?: return call.respondText("Missing code", status = HttpStatusCode.BadRequest)

  val callbackParams = params.entries().associate { (key, values) -> key to values.first() }
  val oidcClaims = runCatching { provider.handleCallback(code, callbackParams) }.getOrElse { e ->
    return call.respondText("OIDC error: ${e.message}", status = HttpStatusCode.BadGateway)
  }
  val webId = loginService.processLogin(oidcClaims)

  // The person is authenticated, so the authorization request that started this can now be
  // answered — with a single-use code, not with the token itself. The token is fetched by the
  // client over a back channel it alone can reach.
  val authorizationCode = authorizationCodeStore.issue(
      subject = webId,
      realm = issuer,
      clientId = pending.clientId,
      scopes = pending.scopes,
      redirectUri = pending.redirectUri,
      codeChallenge = pending.codeChallenge,
      codeChallengeMethod = pending.codeChallengeMethod,
      nonce = pending.nonce,
  )
  val separator = if (pending.redirectUri.contains('?')) '&' else '?'
  call.respondRedirect(
    buildString {
      append(pending.redirectUri).append(separator)
      append("code=").append(UrlUtil.urlEncode(authorizationCode))
      pending.clientState?.let { append("&state=").append(UrlUtil.urlEncode(it)) }
    },
  )
}
private fun buildErrorRedirect(returnTo: String, error: String, description: String): String {
  val separator = if (returnTo.contains('?')) '&' else '?'
  return "$returnTo${separator}error=${UrlUtil.urlEncode(error)}" +
      "&error_description=${UrlUtil.urlEncode(description)}"
}
