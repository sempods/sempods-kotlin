package org.sempods.client.core

import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Whether an attempt that lost its connection may be sent once more.
 *
 * OkHttp would decide this on its own, below the session's interceptor, and repeat the attempt with
 * the headers it already carried — harmless for a fixed bearer, wrong for a header bound to one
 * attempt, and a duplicate write for a POST. [SempodsOkHttp] switches that off for a session's call,
 * so the case it covered is covered here: a pooled connection
 * the server has already closed. The next request on it fails with `Connection reset` before the
 * server reads a byte, and without a second attempt every idle timeout on the far side would reach a
 * caller as a failed request.
 *
 * **Idempotent methods, and requests marked [SempodsRepeatable]**, because RFC 9110 §9.2.2 allows an
 * automatic repeat for those alone: a client cannot tell a connection lost before the server read
 * the request from one lost after the server acted on it, and for a POST the second is a duplicate
 * write — unless the caller knows the request is safe, which is what the mark says.
 */
internal object ConnectionResend {

  private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE")

  fun allowed(failure: IOException, request: Request, repeatable: Boolean): Boolean {
    if (request.method !in IDEMPOTENT_METHODS && !repeatable) return false
    if (request.body?.isOneShot() == true) return false
    return when (failure) {
      // A deadline: a repeat would outlast it.
      is InterruptedIOException -> false
      // Nothing was connected, and OkHttp has already tried every address the host resolved to —
      // including the guard's refusal of one, which is an `UnknownHostException`.
      is ConnectException, is NoRouteToHostException, is UnknownHostException -> false
      // The peer answered, only not with HTTP, or not with a certificate this client accepts.
      is ProtocolException, is SSLException -> false
      // A refusal this library made; asking again is refused again.
      is SempodsClientException -> false
      else -> true
    }
  }
}
