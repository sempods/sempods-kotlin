package org.sempods.client

import okhttp3.Call
import okhttp3.FormBody
import java.io.IOException
import java.time.Duration

/**
 * A pod's token endpoint, `POST {pod}/_system/auth/token` (SPS-AUTH-027): a service client's
 * `client_credentials` grant (RFC 6749 §4.4), and a public client redeeming an authorization code
 * (RFC 6749 §4.1.3).
 *
 * ```java
 * SempodsSession clientSession = new SempodsSession(alice,
 *     SempodsRequestAuth.clientSecretBasic("notes-app", secret));
 *
 * SempodsRequestAuth podBearer = SempodsRequestAuth.refreshable((forceRefresh, attempt) ->
 *     new SempodsPodTokens(clientSession, attempt.calls(client)).clientCredentials().getBody().getAccessToken());
 * SempodsPod pod = new SempodsPod(new SempodsSession(alice, podBearer), client);
 * ```
 *
 * **Built on a session of its own**, unlike the groups a [SempodsPod] hands out: [session] carries the
 * client's credential, usually [SempodsRequestAuth.clientSecretBasic], so a pod session's bearer never
 * reaches this endpoint. A supplier that mints through the client it serves passes
 * [SempodsAuthAttempt.calls], as above. What to cache and when to mint again is the caller's.
 *
 * [clientCredentials] sends `Accept: application/json` and the form `grant_type=client_credentials`,
 * without `scope`, which a pod refuses for this grant (SPS-AUTH-032). [authorizationCode] sends the
 * code, its redirect, the client's identifier and the PKCE verifier; its session is anonymous, since a
 * public client authenticates with the verifier. Neither is sent again after a lost connection: a
 * code is redeemed once.
 *
 * | The pod answers | The caller gets |
 * |---|---|
 * | any 2xx | the token response |
 * | `401 invalid_client`, `400 invalid_scope` and every other status | a [SempodsStatusException] whose `bodyExcerpt` holds the error document of RFC 6749 §5.2 |
 *
 * A 2xx body can hold a token, so it is read as the token response and never kept as an excerpt. One
 * that is not a token response is a [SempodsDecodingException], which quotes nothing from it.
 */
class SempodsPodTokens(
  val session: SempodsSession,
  val calls: Call.Factory,
) {

  private val exchange = Exchange(calls)

  /**
   * The token response. A body without a string `access_token` or `token_type`, or with an `expires_in`
   * that is not a non-negative integer, is a [SempodsDecodingException].
   */
  @Throws(IOException::class)
  fun clientCredentials(): SempodsResponse<SempodsTokenResponse> = exchange.run(request(), ANSWERS, TOKEN)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun clientCredentialsJson(): SempodsResponse<String> = exchange.run(request(), ANSWERS, BodyReading.TEXT)

  /** The same answer with the body as the bytes the server sent. */
  @Throws(IOException::class)
  fun clientCredentialsBytes(): SempodsResponse<ByteArray> = exchange.run(request(), ANSWERS, BodyReading.BYTES)

  /**
   * Redeems [code] for the token response, under the same answers and decoding as [clientCredentials].
   *
   * A pod mints no refresh token for an installation or a management authority, and this response
   * reads none: [SempodsTokenResponse.expiresIn] is how long the token lasts, and a new one takes a new
   * authorization. A code that was already redeemed, has expired, or does not match [codeVerifier] or
   * [redirectUri] is `400 invalid_grant`.
   */
  @Throws(IOException::class)
  fun authorizationCode(clientId: String, code: String, redirectUri: String, codeVerifier: String): SempodsResponse<SempodsTokenResponse> =
    exchange.run(codeRequest(clientId, code, redirectUri, codeVerifier), ANSWERS, TOKEN)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun authorizationCodeJson(clientId: String, code: String, redirectUri: String, codeVerifier: String): SempodsResponse<String> =
    exchange.run(codeRequest(clientId, code, redirectUri, codeVerifier), ANSWERS, BodyReading.TEXT)

  private fun request() = post(FormBody.Builder().add("grant_type", "client_credentials").build())

  private fun codeRequest(clientId: String, code: String, redirectUri: String, codeVerifier: String) = post(
    FormBody.Builder()
      .add("grant_type", "authorization_code")
      .add("code", code)
      .add("redirect_uri", redirectUri)
      .add("client_id", clientId)
      .add("code_verifier", codeVerifier)
      .build(),
  )

  private fun post(form: FormBody) = session.newRequest("POST", ROUTE).header("Accept", "application/json").post(form).build()

  private companion object {

    const val ROUTE = "_system/auth/token"

    val ANSWERS = (200..299).toSet()

    val TOKEN = BodyReading<SempodsTokenResponse> { bytes, _ ->
      val document = decodeObject(bytes)
      val expiresIn = document.longOrNull("expires_in")
      if (expiresIn != null && expiresIn < 0) throw ProtocolViolation("/expires_in: expected a non-negative integer")
      SempodsTokenResponse.of(
        accessToken = document.string("access_token"),
        tokenType = document.string("token_type"),
        expiresIn = expiresIn?.let(Duration::ofSeconds),
        scope = document.stringOrNull("scope"),
      )
    }
  }
}
