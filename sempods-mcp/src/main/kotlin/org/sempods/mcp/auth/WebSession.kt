package org.sempods.mcp.auth

import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.oauth.TokenIssuer
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall

/**
 * The browser web-session for the `/_system/ui` surface: a service-signed `web_session` JWT
 * (minted by [TokenIssuer], verified by [ServiceBearerVerifier]) carried in an httpOnly cookie
 * scoped to [COOKIE_PATH]. No new Ktor Sessions plugin — hand-rolled cookie like the rest of
 * the codebase. The session establishes the `user` for the management UI (the active profile is a
 * request-scoped selection, not bound to the session); it is distinct from (and non-interchangeable
 * with) MCP access tokens.
 */
class WebSession(
  private val config: SempodsMcpConfig,
  private val tokenIssuer: TokenIssuer,
  private val bearerVerifier: ServiceBearerVerifier,
) {

  /**
   * Set the session cookie **and** return the resulting principal (its [csrfToken]). A caller that
   * is about to render a form protected by this session — e.g. the consent screen's inline
   * pod-connect form, established during the AI-client OAuth round-trip — needs the CSRF token
   * immediately, without a second request to read the cookie back. The CSRF token is the freshly
   * minted token's `jti`, taken straight from the issuer (no parse/verify round-trip of a token we
   * just signed).
   */
  fun establish(call: ApplicationCall, user: String): ServiceBearerVerifier.WebPrincipal {
    val issued = tokenIssuer.issueWebSessionWithJti(user)
    appendSessionCookie(call, issued.token)
    return ServiceBearerVerifier.WebPrincipal(user, csrfToken = issued.jti)
  }

  /** The signed-in principal, or null if there is no valid session cookie. */
  fun read(call: ApplicationCall): ServiceBearerVerifier.WebPrincipal? =
    bearerVerifier.verifyWebSession(call.request.cookies[config.sessionCookieName])

  private fun appendSessionCookie(call: ApplicationCall, token: String) {
    call.response.cookies.append(
      Cookie(
        name = config.sessionCookieName,
        value = token,
        maxAge = TokenIssuer.WEB_SESSION_TTL_SECONDS.toInt(),
        path = COOKIE_PATH,
        httpOnly = true,
        secure = config.isSecure,
        extensions = mapOf("SameSite" to "Lax"),
      ),
    )
  }

  fun clear(call: ApplicationCall) {
    call.response.cookies.append(
      Cookie(
        name = config.sessionCookieName,
        value = "",
        maxAge = 0,
        path = COOKIE_PATH,
        httpOnly = true,
        secure = config.isSecure,
        extensions = mapOf("SameSite" to "Lax"),
      ),
    )
  }

  companion object {
    /** All web-UI routes (and the session cookie) live under this reserved sub-tree. */
    const val COOKIE_PATH = "/_system/ui"
  }
}
