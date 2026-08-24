package org.sempods.mcp.auth

import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import org.sempods.auth.core.Secrets

/**
 * Ties a login callback to the browser that started it.
 *
 * `state` alone does not: it is a bearer, so a login URL captured by one party completes in
 * whoever's browser it is opened in. Without a second factor an attacker starts a login, sends the
 * callback link to someone else, and that person's browser ends up holding a session for the
 * attacker's identity — which then owns whatever they connect next. This is the second factor: a
 * secret minted on the redirect out, set as an httpOnly cookie, and required back on the way in.
 *
 * One instance per flow, and within a flow one cookie per *sign-in*: the name carries the flow's
 * `state`. Two logins running at once in one browser — two tabs, two AI clients connecting —
 * would otherwise share a name, so the second redirect overwrites the first pin, the first
 * callback fails *and clears the shared cookie*, and the second then finds nothing either. One
 * concurrent login breaks both. The `state` is already public (it is in the URL), so naming a
 * cookie after it reveals nothing; the value stays the secret.
 *
 * The path stays narrow enough that the cookie is sent nowhere but its own callback, and
 * `SameSite=Lax` so it survives the top-level redirect back from the id-server.
 *
 * @param ttlSeconds must outlive the login state it guards, or the round trip effectively times
 *   out at the shorter of the two.
 */
class LoginCsrfPin(
  private val cookieNamePrefix: String,
  private val cookiePath: String,
  private val secure: Boolean,
  private val ttlSeconds: Int = 15 * 60,
) {

  fun mint(): String = Secrets.newSecret()

  fun set(call: ApplicationCall, state: String, nonce: String) =
    call.append(cookieName(state), nonce, maxAge = ttlSeconds)

  /** Withdraws the pin for one sign-in. Naming a different one would withdraw nothing. */
  fun clear(call: ApplicationCall, state: String) = call.append(cookieName(state), "", maxAge = 0)

  /**
   * Whether this browser presents [expected].
   *
   * Compared in constant time so a mismatch cannot be probed byte by byte, and `false` when the
   * cookie is absent — a request that carries no pin is exactly the case this exists to refuse.
   */
  fun matches(call: ApplicationCall, state: String, expected: String): Boolean =
    Secrets.matches(call.request.cookies[cookieName(state)], expected)

  /** Whether a pin was presented at all — for telling "no cookie" from "wrong cookie" in a log. */
  fun isPresent(call: ApplicationCall, state: String): Boolean =
    call.request.cookies[cookieName(state)] != null

  /**
   * A cookie name has a grammar, and [state] comes off the wire. Callers check the shape with
   * `Secrets.isWellFormed` before they get here — otherwise a space or a separator makes rendering
   * `Set-Cookie` throw, and the 400 the caller meant to send arrives as a 500.
   */
  private fun cookieName(state: String) = "$cookieNamePrefix$state"

  private fun ApplicationCall.append(name: String, value: String, maxAge: Int) {
    response.cookies.append(
      Cookie(
        name = name, value = value, maxAge = maxAge, path = cookiePath,
        httpOnly = true, secure = secure, extensions = mapOf("SameSite" to "Lax"),
      ),
    )
  }
}
