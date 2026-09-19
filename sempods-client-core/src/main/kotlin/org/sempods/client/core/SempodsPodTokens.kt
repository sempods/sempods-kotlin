package org.sempods.client.core

import okhttp3.Call
import okhttp3.FormBody
import java.io.IOException
import java.time.Duration

/**
 * A pod's token endpoint, `POST {pod}/_system/auth/token` (SPS-AUTH-027), for a service client's
 * `client_credentials` grant (RFC 6749 §4.4).
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
 * Every method sends the same request: `Accept: application/json` and the form
 * `grant_type=client_credentials`, without `scope`, which a pod refuses for this grant (SPS-AUTH-032). It
 * is not sent again after a lost connection.
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

  private fun request() =
    session.newRequest("POST", ROUTE)
      .header("Accept", "application/json")
      .post(FormBody.Builder().add("grant_type", "client_credentials").build())
      .build()

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
