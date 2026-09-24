package org.sempods.client

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The browser half of a pod's OAuth for a public client: registering one, and the authorization
 * request its user is sent to (Authorization Code + PKCE, RFC 6749 §4.1 and RFC 7636). The code comes
 * back to the caller's redirect, [SempodsAuthorizationRedirect] reads it there, and
 * [SempodsPodTokens.authorizationCode] redeems it.
 *
 * ```java
 * var authorization = new SempodsPodAuthorization(new SempodsSession(alice), client);
 * String clientId = authorization.registerClient("Notes installer", List.of(redirectUri)).getBody().getClientId();
 * SempodsPkce pkce = SempodsPkce.generate();
 * HttpUrl consent = authorization.authorizationUrl(clientId, redirectUri, "service-clients:install", state, pkce);
 * ```
 *
 * **Built on a session of its own**, anonymous as a rule: a public client holds no credential, and
 * nothing here needs one. A redirect to a loopback address is registered once and answers on any port
 * (RFC 8252 §7.3).
 *
 * [SempodsPodServiceClients] has the installation this leads to.
 */
class SempodsPodAuthorization(
  val session: SempodsSession,
  val calls: Call.Factory,
) {

  private val exchange = Exchange(calls)

  /**
   * Registers a public client at `POST {pod}/_system/auth/register` (RFC 7591), with
   * `token_endpoint_auth_method: none` and the `authorization_code` grant.
   *
   * **Safe to repeat**, and sent again after a lost connection: a pod answers the same metadata with
   * the client it registered before. Each call still counts against the pod's registration budget,
   * so a caller keeps the identifier rather than registering on every start.
   *
   * | The pod answers | The caller gets |
   * |---|---|
   * | any 2xx | the client |
   * | `400 invalid_redirect_uri` or `invalid_client_metadata`, `429 slow_down` and every other status | a [SempodsStatusException] whose `bodyExcerpt` holds the RFC 7591 §3.2.2 error document |
   */
  @Throws(IOException::class)
  fun registerClient(clientName: String, redirectUris: List<String>): SempodsResponse<SempodsPublicClient> =
    exchange.run(registration(clientName, redirectUris), ANSWERS, PUBLIC_CLIENT)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun registerClientJson(clientName: String, redirectUris: List<String>): SempodsResponse<String> =
    exchange.run(registration(clientName, redirectUris), ANSWERS, BodyReading.TEXT)

  /**
   * Where to send the user's browser: `{pod}/_system/auth/authorize` with `response_type=code`, the
   * given members and [pkce]'s challenge. The URL carries the pod's own host, not a session's
   * placeholder, because a browser opens it.
   *
   * [scope] is space-separated, as it goes on the wire. [state] is the caller's, and comes back with
   * the answer; make it unguessable and check it with [SempodsAuthorizationRedirect.readQuery].
   */
  fun authorizationUrl(clientId: String, redirectUri: String, scope: String, state: String, pkce: SempodsPkce): HttpUrl =
    session.podBase.resolve(AUTHORIZE).newBuilder()
      .addQueryParameter("response_type", "code")
      .addQueryParameter("client_id", clientId)
      .addQueryParameter("redirect_uri", redirectUri)
      .addQueryParameter("scope", scope)
      .addQueryParameter("state", state)
      .addQueryParameter("code_challenge", pkce.challenge)
      .addQueryParameter("code_challenge_method", pkce.method)
      .build()

  private fun registration(clientName: String, redirectUris: List<String>) =
    SempodsRepeatable.mark(session.newRequest("POST", REGISTER_ROUTE))
      .header("Accept", "application/json")
      .post(
        encodeObject(
          linkedMapOf(
            "client_name" to clientName,
            "redirect_uris" to redirectUris,
            "grant_types" to listOf("authorization_code"),
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to "none",
          ),
        ).toRequestBody(JSON_MEDIA_TYPE),
      )
      .build()

  private companion object {

    const val AUTHORIZE = "_system/auth/authorize"

    val ANSWERS = (200..299).toSet()

    val PUBLIC_CLIENT = BodyReading<SempodsPublicClient> { bytes, _ ->
      val document = decodeObject(bytes)
      SempodsPublicClient.of(
        clientId = document.string("client_id"),
        clientName = document.stringOrNull("client_name"),
        redirectUris = document.strings("redirect_uris"),
      )
    }
  }
}
