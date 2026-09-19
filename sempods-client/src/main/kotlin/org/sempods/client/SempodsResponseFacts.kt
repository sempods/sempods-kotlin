package org.sempods.client

import okhttp3.Challenge
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Response
import java.util.Collections

/**
 * What a [SempodsRequestAuth] is told about one answer: its status, its headers, what the attempt
 * sent, and the challenges the status defines. Never the body.
 *
 * **Every value here is immutable and holds no connection**, so a mechanism may keep one — a
 * `DPoP-Nonce` for the next request — without keeping the answer alive. There is nothing to close.
 *
 * **[challenges] are OkHttp's**: the `WWW-Authenticate` challenges of a 401, the
 * `Proxy-Authenticate` ones of a 407, and none for any other status. A token endpoint names a nonce
 * challenge with a 400 and a `DPoP-Nonce` header instead
 * ([RFC 9449 §8.2](https://www.rfc-editor.org/rfc/rfc9449#section-8.2)), which is read from
 * [headers]. A scheme is compared case-insensitively
 * ([RFC 9110 §11.1](https://www.rfc-editor.org/rfc/rfc9110#section-11.1)).
 */
class SempodsResponseFacts private constructor(
  val status: Int,
  /** The answer's headers, as they arrived. */
  val headers: Headers,
  /** The method the attempt sent. */
  val method: String,
  /** The URL the attempt was written to: the pod's host, never the session's placeholder. */
  val url: HttpUrl,
  /** The headers the attempt sent, where a mechanism finds the credential that was refused. */
  val sentHeaders: Headers,
  val challenges: List<Challenge>,
) {

  /** The status alone: [sentHeaders] carries the credential, and this string ends up in logs. */
  override fun toString(): String = "SempodsResponseFacts(status=$status)"

  companion object {

    /** The bounded facts of [response]. Its body is neither read nor kept. */
    @JvmStatic
    fun of(response: Response): SempodsResponseFacts = SempodsResponseFacts(
      status = response.code,
      headers = response.headers,
      method = response.request.method,
      url = response.request.url,
      sentHeaders = response.request.headers,
      challenges = Collections.unmodifiableList(response.challenges()),
    )
  }
}
