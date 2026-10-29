package org.sempods.mcp.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.sempods.auth.core.BrowserSignIn
import org.sempods.auth.core.OidcRelyingParty
import org.sempods.commons.ktor.auth.BrowserPinCookie
import org.sempods.mcp.oauth.IdentityProvider

private val logger = KotlinLogging.logger("org.sempods.mcp.api")

/** The relying party for [callbackPath], or `null` once this call has been answered 503. */
internal suspend fun ApplicationCall.relyingPartyOrUnavailable(
  identityProvider: IdentityProvider,
  callbackPath: String,
): OidcRelyingParty? = try {
  identityProvider.relyingPartyFor(callbackPath)
} catch (e: Exception) {
  logger.warn(e) { "identity provider discovery failed" }
  respondText("identity provider unavailable", status = HttpStatusCode.ServiceUnavailable)
  null
}

/**
 * Completes the sign-in this callback names, or answers 503 and returns `null`.
 *
 * The pin is withdrawn first, so every answer expires it: a cookie named after its `state` is not
 * overwritten by the next attempt. Reading it is a request concern and clearing it a response one,
 * so the comparison still sees it.
 */
internal suspend fun <T> ApplicationCall.completeSignIn(
  signIn: BrowserSignIn<T>,
  pin: BrowserPinCookie,
  identityProvider: IdentityProvider,
  callbackPath: String,
): BrowserSignIn.Completion<T>? {
  val q = request.queryParameters
  val state = q["state"]
  pin.clear(this, state)
  val relyingParty = relyingPartyOrUnavailable(identityProvider, callbackPath) ?: return null
  return signIn.complete(
    relyingParty,
    BrowserSignIn.Callback(
      state = state, presentedPin = pin.presented(this, state), iss = q["iss"],
      code = q["code"], error = q["error"], errorDescription = q["error_description"],
    ),
  )
}
