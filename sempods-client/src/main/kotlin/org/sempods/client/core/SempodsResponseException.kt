package org.sempods.client.core

import okhttp3.Headers

/**
 * The pod answered, and the answer is outside what the operation accepts.
 *
 * [status] and [headers] are the answer's, so a caller still reads `Retry-After` or
 * `WWW-Authenticate` from a failure.
 *
 * **No message quotes the body.** A body can hold a credential — a token response does — and a
 * message ends up in a log. It names the method, the URL without its query, the status and what was
 * wrong.
 */
sealed class SempodsResponseException(
  message: String,
  val status: Int,
  val headers: Headers,
) : SempodsClientException(message)

/** A status the operation does not list as an answer. Which statuses a route lists, its operation's KDoc says. */
class SempodsStatusException private constructor(
  message: String,
  status: Int,
  headers: Headers,
  /**
   * The start of what the server wrote: at most 4 KiB, decoded as UTF-8, with a character cut at the
   * limit replaced. Empty when there was no body or it could not be read.
   */
  val bodyExcerpt: String,
) : SempodsResponseException(message, status, headers) {

  internal companion object {

    @JvmSynthetic
    internal fun of(
      message: String,
      status: Int,
      headers: Headers,
      bodyExcerpt: String,
    ): SempodsStatusException = SempodsStatusException(message, status, headers, bodyExcerpt)
  }
}

/**
 * A body the operation cannot read: over the size limit, malformed, or JSON that is not the route's
 * document. The message says where — a JSON Pointer, or a line and column — and
 * what was expected.
 */
class SempodsDecodingException private constructor(
  message: String,
  status: Int,
  headers: Headers,
) : SempodsResponseException(message, status, headers) {

  internal companion object {

    @JvmSynthetic
    internal fun of(message: String, status: Int, headers: Headers): SempodsDecodingException =
      SempodsDecodingException(message, status, headers)
  }
}
