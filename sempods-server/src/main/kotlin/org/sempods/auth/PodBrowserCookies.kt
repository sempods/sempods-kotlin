package org.sempods.auth

import jakarta.ws.rs.core.NewCookie
import org.sempods.commons.net.SempodsPodRoutes
import java.net.URI

/**
 * The two cookies the pod's sign-in uses, and the rules that make them safe.
 *
 * Both are `HttpOnly` (nothing in a page needs to read them) and scoped to **one pod**: pods are
 * isolated tenants and share a host on a path-scoped deployment, so a value set at `/alice` must
 * never travel to `/bob`.
 *
 * `SameSite=Lax` on both: each is required on a **top-level GET** that arrives as a redirect from
 * another origin — the id-server sending the browser back. `Strict` would withhold the cookie on
 * exactly that request and break the flow; `None` would send it on cross-site sub-requests, which
 * neither needs.
 *
 * The framework-specific half of what `sempods-mcp` does with `LoginCsrfPin` and `WebSession`.
 * What the two services share — minting a secret, comparing one — lives in
 * `sempods-auth-core`'s `Secrets`; the cookie types do not travel between Ktor and JAX-RS.
 *
 * @param apiBaseUrl the address this server is reached at. Its **path** matters: a deployment
 *   mounted under a prefix (`https://example.test/sempods/`) serves every pod route below that
 *   prefix, and a cookie rooted at `/{pod}/` would then never be sent back — the browser simply
 *   withholds it, and every interactive login fails on a missing pin with nothing pointing here.
 *   Taken raw, for the reason on [prefix].
 */
class PodBrowserCookies(apiBaseUrl: String, private val secure: Boolean) {

  /**
   * `rawPath`, not `path`: a cookie's `Path` is matched against the **raw** request path (RFC 6265
   * §5.1.4), while `URI.path` hands back the decoded form. A base URL carrying an encoded segment
   * — `https://example.test/sempods%20prod/` — would otherwise produce `Path=/sempods prod/…`
   * against requests for `/sempods%20prod/…`, and the browser would send neither cookie. The pod
   * name needs no such care: `SempodsUriBuilder.checkPodName` allows only `[a-z0-9-]`.
   */
  private val prefix: String = URI(apiBaseUrl).rawPath.orEmpty().removeSuffix("/")

  /**
   * Pins a login callback to the browser that started it.
   *
   * The name carries the flow's `state`, so two sign-ins running in one browser — two tabs, two
   * apps — do not share a cookie. With one fixed name the second redirect overwrites the first
   * pin, the first callback then fails *and clears the shared cookie*, and the second callback
   * finds nothing either: one concurrent login breaks both. The `state` is already public (it is
   * in the URL), so naming a cookie after it reveals nothing; the value is still the secret.
   */
  fun loginPin(pod: String, state: String, value: String, maxAgeSeconds: Int): NewCookie =
    cookie(loginPinName(state), value, callbackPath(pod), maxAgeSeconds)

  /** An expiry in the past, which is how a cookie is withdrawn. Same name, or it withdraws nothing. */
  fun clearLoginPin(pod: String, state: String): NewCookie = loginPin(pod, state, "", 0)

  fun loginPinName(state: String): String = "$LOGIN_PIN_PREFIX$state"

  /** Remembers who signed in, so the next authorization needs no round trip. */
  fun session(pod: String, value: String, maxAgeSeconds: Int): NewCookie =
    cookie(SESSION, value, podPath(pod), maxAgeSeconds)

  private fun podPath(pod: String) = "$prefix/$pod/"

  /**
   * The pin is scoped to the callback route alone, not to the pod. It is presented exactly once,
   * on the way back in, and there is no reason for it to ride along on every other pod request.
   */
  private fun callbackPath(pod: String) = "$prefix/$pod/${SempodsPodRoutes.AUTH_OIDC_CALLBACK}"

  private fun cookie(name: String, value: String, path: String, maxAgeSeconds: Int): NewCookie =
    NewCookie.Builder(name)
      .value(value)
      .path(path)
      .maxAge(maxAgeSeconds)
      .httpOnly(true)
      .secure(secure)
      .sameSite(NewCookie.SameSite.LAX)
      .build()

  companion object {
    /**
     * Followed by the flow's `state`. A `state` is base64url, so the result is a valid cookie name
     * (RFC 6265 token) without escaping — which holds only because the callback refuses a `state`
     * that is not (`Secrets.isWellFormed`) before one is built. Off the wire it is just text.
     */
    const val LOGIN_PIN_PREFIX = "sempods_pod_login_"

    /** One session per pod, so this one needs no suffix. */
    const val SESSION = "sempods_pod_session"
  }
}
