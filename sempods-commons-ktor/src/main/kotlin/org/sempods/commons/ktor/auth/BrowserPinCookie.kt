package org.sempods.commons.ktor.auth

import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import java.time.Duration

/**
 * The cookie that carries a sign-in's browser pin: set on the redirect to the provider, read and
 * withdrawn at the callback. Minting and comparing the pin is `sempods-auth-core`'s `BrowserSignIn`.
 *
 * The name carries the sign-in's `state`, so two sign-ins in one browser — two tabs, two AI clients
 * connecting — do not overwrite each other's pin. The `state` is public anyway (it is in the URL).
 * At the callback it comes off the wire: one that cannot be part of a cookie name names no cookie,
 * so [presented] answers `null` and [clear] does nothing, where rendering it would throw.
 *
 * `HttpOnly`, a path that reaches only the callback, and `SameSite=Lax` so the cookie survives the
 * top-level redirect back from the provider.
 *
 * @param maxAge as long as the sign-in may take (`BrowserSignIn.ttl`).
 */
class BrowserPinCookie(
  private val namePrefix: String,
  private val path: String,
  private val secure: Boolean,
  maxAge: Duration,
) {

  private val maxAgeSeconds = maxAge.toSeconds().toInt()

  fun set(call: ApplicationCall, state: String, pin: String) {
    name(state)?.let { call.append(it, pin, maxAgeSeconds) }
  }

  /** Withdraws the pin for one sign-in. */
  fun clear(call: ApplicationCall, state: String?) {
    name(state)?.let { call.append(it, "", maxAge = 0) }
  }

  /** The pin this browser presents for the sign-in [state] names, or `null` for none. */
  fun presented(call: ApplicationCall, state: String?): String? = name(state)?.let { call.request.cookies[it] }

  /** Short base64url only: what a `state` is minted as, and what a cookie name may hold. */
  private fun name(state: String?): String? =
    state?.takeIf { it.length in 1..MAX_STATE_CHARS && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' } }
      ?.let { "$namePrefix$it" }

  private fun ApplicationCall.append(name: String, value: String, maxAge: Int) {
    response.cookies.append(
      Cookie(
        name = name, value = value, maxAge = maxAge, path = path,
        httpOnly = true, secure = secure, extensions = mapOf("SameSite" to "Lax"),
      ),
    )
  }

  private companion object {
    /** A 256-bit `state` is 43 characters; anything far longer was not minted as one. */
    const val MAX_STATE_CHARS = 64
  }
}
