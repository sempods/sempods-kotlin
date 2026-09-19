package org.sempods.client.core

import okhttp3.Headers
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A pod's answer to an endpoint operation, with its body read: the status and headers as they
 * arrived, and the body in the representation the operation was asked for — raw text, raw bytes or a
 * typed result, each from the same execution.
 *
 * **[body] is null exactly for a status the operation lists as an answer outside 2xx**, such as a 404
 * for a pod that does not exist. That body is closed unread. A listed 2xx always has a body, an empty
 * one included. A status the operation does not list, a 2xx among them, is a [SempodsStatusException],
 * and a body it cannot read a [SempodsDecodingException].
 *
 * **The body is held in memory, and bounded**: one over 16 MiB is a [SempodsDecodingException].
 *
 * [headers] are OkHttp's — case-insensitive and multi-valued, `ETag`, `Location`, `Retry-After` and
 * `Content-Type` included.
 *
 * **Another representation of the same answer is [map]'s**, which is how a module above the core — an
 * RDF adapter, say — returns the status and headers with a body of its own.
 */
class SempodsResponse<T : Any> private constructor(
  /**
   * The URL of the request this answers, without a fragment; after followed redirects
   * ([SempodsForeignTarget.followingRedirects]), the last one. A `Host` an interceptor set is not reflected.
   * An answer made without a request names the URL it would have asked.
   */
  val url: String,
  val status: Int,
  val headers: Headers,
  val body: T?,
  /** The method and URL without its query, as a failure of [map] names them ([SempodsResponseException]). */
  private val described: String,
) {

  /**
   * This answer with its body decoded by [decoder]: the same [url], [status] and [headers], and a null
   * [body] left null without calling [decoder].
   *
   * A failure of [decoder] other than an `IOException` is a [SempodsDecodingException] with this
   * answer's status and headers. Its message names the method, the URL without its query, the status
   * and the failure's class, and quotes neither the body nor the failure's own message, which may
   * quote the body. A decoder that returns null — Java allows it — fails the same way, since an answer
   * with a body keeps one. An `IOException` passes through as it is, so a decoder that maps again
   * reports its own [SempodsDecodingException] unchanged.
   */
  @Throws(IOException::class)
  fun <R : Any> map(decoder: SempodsBodyDecoder<T, R>): SempodsResponse<R> {
    val decoded = body?.let {
      val result: R? = try {
        decoder.decode(it)
      } catch (failure: IOException) {
        throw failure
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Interrupted while decoding the answer of $described.")
      } catch (failure: Exception) {
        throw SempodsDecodingException.of(
          "$described answered $status with a body the decoder cannot read: ${failure.javaClass.name}.",
          status,
          headers,
        )
      }
      result ?: throw SempodsDecodingException.of("$described answered $status, and the decoder returned no body.", status, headers)
    }
    return SempodsResponse(url, status, headers, decoded, described)
  }

  /** The status alone: a body can carry a credential, and this string ends up in logs. */
  override fun toString(): String = "SempodsResponse(status=$status)"

  internal companion object {

    @JvmSynthetic
    internal fun <T : Any> of(
      url: String,
      status: Int,
      headers: Headers,
      body: T?,
      described: String,
    ): SempodsResponse<T> = SempodsResponse(url, status, headers, body, described)
  }
}
