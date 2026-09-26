package org.sempods.client

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
 * OkHttp would decide this on its own, below the session's interceptor, and repeat a POST as readily
 * as a GET. [SempodsOkHttp] switches that off for a session's call, so the case it covered is covered
 * here: a pooled connection the server has already closed. The next request on it fails with
 * `Connection reset` before the server reads a byte, and without a second attempt every idle timeout
 * on the far side would reach a caller as a failed request.
 *
 * **Idempotent methods, and requests marked [SempodsRepeatable]**, because RFC 9110 §9.2.2 allows an
 * automatic repeat for those alone: a client cannot tell a connection lost before the server read
 * the request from one lost after the server acted on it, and for a POST the second is a duplicate
 * write — unless the caller knows the request is safe, which is what the mark says.
 *
 * **Decided on the request that went out and on whether an answer came back** ([NetworkPass]), not
 * on the request the session handed down and not on the failure's type alone. An interceptor below
 * the session may change the method or put in a body that can be written once, and one that throws
 * after the answer arrived raises the same `IOException` a lost connection does.
 */
internal object ConnectionResend {

  private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE")

  /** [pass] is the one the failed attempt started, or null when it started none. */
  fun allowed(failure: IOException, pass: NetworkPass?, repeatable: Boolean): Boolean {
    // An answer came back, so the failure is something above the wire — an interceptor after the
    // session's, which may have failed on an operation the server has already carried out.
    if (pass?.answer != null) return false
    // The request never reached the last network interceptor, or its credential could not be applied.
    // Either way nothing went out, and nothing lost is not a lost connection.
    val request = pass?.written ?: return false
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

/**
 * One request a call wrote, as the last network interceptor saw it: authenticated as attempt
 * [number], the request as it was written, and the answer, once one came back.
 *
 * Kept on the call's record of its passes, which the session's interceptor reads after each
 * `Chain.proceed`: a failure carries no request, so this is how it learns what went out.
 */
internal class NetworkPass(val number: Int) {

  @Volatile
  var written: Request? = null

  @Volatile
  var answer: SempodsResponseFacts? = null
}
