package org.sempods.commons.ktor.auth

import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall

/**
 * The cookie that carries a sign-in's browser pin: set on the redirect to the provider, read and
 * withdrawn at the callback. The pin itself — minting it, and comparing what comes back — is
 * `sempods-auth-core`'s `BrowserSignIn`; this is only where Ktor keeps it.
 *
 * One instance per flow, and within a flow one cookie per *sign-in*: the name carries the
 * sign-in's `state`. Two logins running at once in one browser — two tabs, two AI clients
 * connecting — would otherwise share a name, so the second redirect overwrites the first pin, the
 * first callback fails *and clears the shared cookie*, and the second then finds nothing either.
 * The `state` is already public (it is in the URL), so naming a cookie after it reveals nothing.
 *
 * `HttpOnly`, a path narrow enough that the cookie is sent nowhere but its own callback, and
 * `SameSite=Lax` so it survives the top-level redirect back from the provider.
 *
 * A cookie name has a grammar, and the `state` at a callback comes off the wire. Callers check its
 * shape (`Secrets.isWellFormed`) before they get here — otherwise a space or a separator makes
 * rendering `Set-Cookie` throw, and the 400 the caller meant to send arrives as a 500.
 *
 * @param maxAgeSeconds as long as the sign-in it guards may take (`BrowserSignIn.ttl`), or the
 *   round trip times out at the shorter of the two.
 */
class BrowserPinCookie(
  private val namePrefix: String,
  private val path: String,
  private val secure: Boolean,
  private val maxAgeSeconds: Int,
) {

  fun set(call: ApplicationCall, state: String, pin: String) =
    call.append(name(state), pin, maxAgeSeconds)

  /** Withdraws the pin for one sign-in. Naming a different one would withdraw nothing. */
  fun clear(call: ApplicationCall, state: String) = call.append(name(state), "", maxAge = 0)

  /** The pin this browser presents for the sign-in [state] names, or `null` for none. */
  fun presented(call: ApplicationCall, state: String): String? = call.request.cookies[name(state)]

  private fun name(state: String) = "$namePrefix$state"

  private fun ApplicationCall.append(name: String, value: String, maxAge: Int) {
    response.cookies.append(
      Cookie(
        name = name, value = value, maxAge = maxAge, path = path,
        httpOnly = true, secure = secure, extensions = mapOf("SameSite" to "Lax"),
      ),
    )
  }
}
