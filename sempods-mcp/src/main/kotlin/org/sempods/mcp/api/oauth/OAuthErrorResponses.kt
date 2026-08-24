package org.sempods.mcp.api.oauth

import com.nimbusds.oauth2.sdk.AuthorizationErrorResponse
import com.nimbusds.oauth2.sdk.ErrorObject
import com.nimbusds.oauth2.sdk.ResponseMode
import com.nimbusds.oauth2.sdk.id.State
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import org.sempods.auth.core.ClientRedirectPolicy
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import java.net.URI

/**
 * How an authorization error becomes an HTTP answer — the rendering half of `auth-core`'s
 * [OAuthErrorDelivery].
 *
 * The types this renders exist to make one rule un-forgettable: an error may only travel to a
 * redirect address once that address is known to belong to the client that named it. `auth-core`
 * enforces the rule by making [org.sempods.auth.core.Redirectable] impossible to construct except
 * through [OAuthErrors.redirectTargetFor]; this file is what turns the two outcomes into a
 * response, so that neither branch can be written by hand at a call site and get it wrong.
 *
 * The redirect is built by nimbus rather than by string concatenation for the same reason
 * `PodOAuthClient` is: an address may carry a query of its own, and choosing `?` versus `&`
 * correctly is not something worth re-deriving per call site.
 */
// TODO: no `error_uri` is sent. `OAuthErrors.errorUri(docBase, code)` exists and has no caller —
//  it needs a documentation base this service does not configure, and the page that exists
//  (`docs/auth/oauth-errors.md`) describes the pod server's codes rather than these. Worth
//  doing once there is a page for this service: it is the one part of an OAuth error a human can
//  act on without reading a log.
suspend fun ApplicationCall.respondOAuthError(delivery: OAuthErrorDelivery) {
  when (delivery) {
    is OAuthErrorDelivery.Direct ->
      respondText("${delivery.code.code}: ${delivery.description}", status = HttpStatusCode.BadRequest)

    is OAuthErrorDelivery.Redirect ->
      respondRedirect(
        AuthorizationErrorResponse(
          URI(delivery.target.uri),
          ErrorObject(delivery.code.code, delivery.description),
          // Blank means absent. RFC 6749 makes `state` opaque VSCHAR, so a client may legally send
          // `state=%20` — and nimbus's `State` rejects a blank value from its constructor, which
          // would turn a well-formed error response into a 500. A state carrying no information is
          // the one part of the answer such a client cannot use anyway, so it is simply not echoed.
          delivery.state?.takeIf { it.isNotBlank() }?.let { State(it) },
          ResponseMode.QUERY,
        ).toURI().toString(),
      )
  }
}

/**
 * Reports [code] at the client's own address when that address is proven, and directly otherwise.
 *
 * The single entry point for an authorization-endpoint failure, so the choice between the two is
 * made by the policy rather than by whoever writes the next error branch.
 */
suspend fun ApplicationCall.respondOAuthError(
  policy: ClientRedirectPolicy,
  clientId: String?,
  redirectUri: String?,
  code: OAuthErrorCode,
  description: String,
  state: String?,
) {
  val target = OAuthErrors.redirectTargetFor(policy, clientId, redirectUri)
  respondOAuthError(
    if (target == null) OAuthErrorDelivery.Direct(code, description)
    else OAuthErrorDelivery.Redirect(target, code, description, state),
  )
}
