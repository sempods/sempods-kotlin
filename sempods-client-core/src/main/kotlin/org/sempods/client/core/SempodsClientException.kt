package org.sempods.client.core

/**
 * What every refused or failed operation arrives as.
 *
 * Three subclasses, because a caller does different things with each and telling them apart by
 * message text is how that breaks silently: a server that answered is not a network that did not,
 * and neither is a body this process could not read.
 */
open class SempodsClientException @JvmOverloads constructor(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The server answered, and the caller asked for a failure status to be a throw
 * ([SempodsResponse.requireSuccessful]).
 *
 * The server's own reason travels with it, because a pod answers scope refusals and precondition
 * failures with one and swallowing it turns every diagnosis into a server-log expedition. It
 * travels twice — inside the message, which is written for a log line, and on its own in
 * [responseBody], for a caller that forwards the reason onward and must not forward this process's
 * URLs with it.
 *
 * [responseBody] is bounded at [MAX_ERROR_BODY_CHARS]. An error body is a reason, not a payload,
 * and reading one unbounded is how a 5xx from a large route becomes an allocation nobody asked for.
 */
class SempodsHttpException(
  val statusCode: Int,
  val headers: SempodsHeaders,
  val responseBody: String?,
) : SempodsClientException("HTTP $statusCode — ${responseBody ?: "no body"}") {

  companion object {
    /** One limit for every error body in the core; there used to be two, 4096 and 2000. */
    const val MAX_ERROR_BODY_CHARS: Int = 4096
  }
}

/**
 * The request did not complete: a connection failure, a timeout, a refused address, a budget
 * refusal, or a cancelled operation. [cause] carries what the engine said.
 */
class SempodsTransportException @JvmOverloads constructor(
  message: String,
  cause: Throwable? = null,
) : SempodsClientException(message, cause)

/**
 * A body arrived and could not be turned into the value a caller asked for.
 *
 * Declared here and thrown by adapters outside this module: the core neither parses nor generates
 * JSON, but a typed adapter needs a failure shape that a caller can catch without naming the
 * adapter's own library. Status and headers stay available so a decode failure can still be
 * diagnosed against what the server actually sent.
 */
class SempodsDecodingException @JvmOverloads constructor(
  message: String,
  val statusCode: Int,
  val headers: SempodsHeaders,
  cause: Throwable? = null,
) : SempodsClientException(message, cause)
