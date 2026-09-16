package org.sempods.client.core

import okhttp3.Headers

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
 */
class SempodsResponse<T : Any> internal constructor(
  /**
   * The URL of the request this answers, without a fragment; after followed redirects
   * ([SempodsForeignTarget.followingRedirects]), the last one. A `Host` an interceptor set is not reflected.
   * An answer made without a request names the URL it would have asked.
   */
  val url: String,
  val status: Int,
  val headers: Headers,
  val body: T?,
) {

  /** The status alone: a body can carry a credential, and this string ends up in logs. */
  override fun toString(): String = "SempodsResponse(status=$status)"
}
