package org.sempods.auth.oidc

import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import io.ktor.client.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import net.minidev.json.JSONObject
import net.minidev.json.parser.JSONParser

/**
 * Exchanges an authorization code for an `id_token` at an upstream provider's token endpoint.
 *
 * This service is the relying party here — the client asking Google or Apple who someone is. A
 * confidential one: both issue a client secret (Apple's is itself a signed JWT, minted per
 * request), which is why this is not the same code path as `OidcRelyingParty`, whose clients are
 * public and identify by origin.
 *
 * The response is read with the SDK's parser rather than as a map. What that buys is the failure
 * case: an OAuth error is a JSON document naming the reason, and a status check that throws before
 * reading it turns "the code was already redeemed" into "the login did not work" — the only
 * difference between a diagnosable problem and a mystery.
 *
 * Uses Ktor's CIO client — coroutine-native, non-blocking, no extra thread needed. The
 * [HttpClient] is injected as a singleton so all OIDC providers share one instance.
 *
 * TODO: the timeouts are Ktor's CIO defaults — 5 s connect, 15 s whole-request, one attempt,
 *  unbounded socket read — because nothing here sets them. They do bound a call to an
 *  unresponsive provider, so this is not the hang it was once documented as. What is open is that
 *  nobody chose them: the other leg of the same sign-in (`CommonsHttpTransport`, pod server →
 *  id-server) stops at a deliberate 10 s, and `sempods-client` models its timeouts explicitly
 *  with a per-call override. Neither of those shapes is available here. Persistent failure of a
 *  trusted issuer is also a case for a circuit breaker. `OidcHttpTimeoutsTest` pins the figures;
 *  named as a limitation in `docs/auth/README.md`.
 */
class OidcTokenExchange(private val httpClient: HttpClient) {

  /**
   * @return the serialized `id_token`. Nothing else in the response is used: the access token
   *   would only let this service call the provider's own APIs, which it has no reason to do.
   * @throws IllegalStateException when the provider returned an error, or a response without an
   *   `id_token`. The provider's own wording is carried into the message.
   */
  suspend fun exchangeCode(
    tokenEndpoint: String,
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    code: String,
  ): String {
    val body: String = httpClient.submitForm(
      url = tokenEndpoint,
      formParameters = parameters {
        append("grant_type", "authorization_code")
        append("client_id", clientId)
        append("client_secret", clientSecret)
        append("redirect_uri", redirectUri)
        append("code", code)
      },
    ).bodyAsText()

    val parsed = try {
      OIDCTokenResponseParser.parse(body.toJsonObject())
    } catch (e: ParseException) {
      // Neither a token response nor an error response. The body is not logged: at this endpoint
      // a success body carries tokens, and a parser that got this far cannot say which it holds.
      error("token endpoint at $tokenEndpoint returned an unreadable response: ${e.message}")
    }
    if (!parsed.indicatesSuccess()) {
      val error = parsed.toErrorResponse().errorObject
      error("token endpoint at $tokenEndpoint returned ${error.code}${error.description?.let { ": $it" }.orEmpty()}")
    }
    return ((parsed.toSuccessResponse() as OIDCTokenResponse).oidcTokens.idToken
      ?: error("token response from $tokenEndpoint carries no id_token"))
      .serialize()
  }

  private fun String.toJsonObject(): JSONObject =
    runCatching { JSONParser(JSONParser.MODE_PERMISSIVE).parse(this) as JSONObject }
      .getOrElse { throw IllegalStateException("token response is not a JSON object: ${it.message}") }
}
