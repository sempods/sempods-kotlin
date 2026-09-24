package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.auth.core.OAuthErrorCode

/**
 * Every answer `POST /{pod}/_system/auth/token` gives, built in one place.
 *
 * The endpoint decides *what* to answer — which scopes survived, whether a family is already over,
 * whether a caller has spent its budget. This decides how that reaches the wire. It takes values
 * already computed, mints nothing and reads no store.
 *
 * **RFC 6749 §5.1/§5.2 — every answer carries `Cache-Control: no-store` and `Pragma: no-cache`,
 * the refusals included.** Strict OAuth clients (observed: GitHub Copilot CLI) silently drop tokens
 * received without them, which shows up as "consent completed, tokens issued, but no follow-up
 * request ever carries a Bearer".
 */
internal object PodTokenResponses {

  /**
   * A successful token response (RFC 6749 §5.1).
   *
   * @param scope the `scope` member verbatim, or `null` to leave it out. The endpoint's three
   *   success shapes differ only here and in [refreshToken], and the difference is deliberate: a
   *   user token omits the member when it carries no feature scope at all — §3.3's grammar is one
   *   `scope-token` followed by more, so `""` is not a scope a response may name, and §5.1 makes
   *   the member optional — while a service token states its registered set even when that set is
   *   empty. A strict client is entitled to refuse an exchange over the empty string, so making the
   *   two agree means narrowing the one that states it.
   *
   *   `offline_access` never appears here whichever answer the person gave: §5.1 defines this
   *   member as the scope of the *access token*, and a credential's lifetime has no standing in it.
   *   A client could do nothing with it either — it starts a fresh flow when the family ends,
   *   whatever it knew beforehand. The consent screen is where the person is told.
   * @param refreshToken absent rather than null when none is handed back: §5.1 makes the member
   *   optional, and a client reading `"refresh_token": null` as a token is a bug this response
   *   should not be able to provoke.
   */
  fun tokens(
    accessToken: String,
    expiresInSeconds: Long,
    scope: String?,
    refreshToken: String? = null,
  ): Response {
    val body = linkedMapOf<String, Any>(
      "access_token" to accessToken,
      "token_type" to "Bearer",
      "expires_in" to expiresInSeconds,
    )
    scope?.let { body["scope"] = it }
    refreshToken?.let { body["refresh_token"] = it }
    return finish(Response.ok(body))
  }

  /** A refusal naming the request's fault (RFC 6749 §5.2). */
  fun error(error: OAuthErrorCode, description: String): Response =
    finish(Response.status(400).entity(errorBody(error.code, description)))

  /**
   * The refusal when client authentication is absent or wrong.
   *
   * 401 and a challenge rather than [error]'s 400, per RFC 6749 §5.2: the client tried to
   * authenticate and this server would not have it, so it is told which scheme to try with.
   */
  fun clientAuthenticationRequired(realm: String, description: String): Response =
    finish(
      Response.status(401)
        .header("WWW-Authenticate", """Basic realm="$realm"""")
        .entity(errorBody("invalid_client", description)),
    )

  /**
   * The refusal when a caller has spent its budget at this endpoint.
   *
   * `slow_down` rather than an invented code: RFC 8628 registered it for the token endpoint, and
   * it says exactly this — you are asking too often, keep going more slowly. The status is 429
   * rather than the 400 [error] uses, because nothing about the request itself is wrong.
   *
   * `Retry-After` is stated in whole seconds and deliberately as one flat number: the bucket
   * refills continuously, so any single value is a hint rather than a deadline, and the hint worth
   * giving is the window the budget itself is stated in.
   */
  fun rateLimited(description: String = "too many token requests — retry later"): Response =
    finish(
      Response.status(429)
        .header("Retry-After", RETRY_AFTER_SECONDS)
        .entity(errorBody("slow_down", description)),
    )

  /**
   * The error document, assembled as text.
   *
   * [description] is interpolated unescaped, which holds only because every value reaching here is
   * a literal this server wrote: one carrying a `"` or a `\` would produce a body no client can
   * parse. A description derived from a request has to be escaped before it is passed in — or this
   * has to become a map, which is what a conversion to a protocol library would do anyway.
   */
  private fun errorBody(code: String, description: String): String =
    """{"error":"$code","error_description":"$description"}"""

  /** The media type and the cache rules every answer from this endpoint carries. */
  private fun finish(builder: Response.ResponseBuilder): Response =
    builder
      .type(MediaType.APPLICATION_JSON)
      .header("Cache-Control", "no-store")
      .header("Pragma", "no-cache")
      .build()

  /** What a rate-limited caller is told to wait — the window the budget is stated in. */
  private const val RETRY_AFTER_SECONDS = 60
}
