package org.sempods.client.core

import okhttp3.Call
import okhttp3.Request
import java.io.IOException

/**
 * Runs a request of a route this module does not own, and reads its answer the way an endpoint group
 * reads its own.
 *
 * **This is the other half of the extension seam.** [SempodsSession.newRequest] builds a request that
 * carries the session; this sends it and turns what comes back into a [SempodsResponse] — the same
 * status handling, the same 16 MiB bound, the same failures. Without it a protocol module could make
 * the call but not answer in the shape every other group answers in, and would grow a result type and
 * a failure hierarchy of its own.
 *
 * ```java
 * var exchange = new SempodsExchange(pod.getCalls());
 * Request request = pod.getSession().newRequest("POST", "_system/media")
 *     .header("Content-Type", "image/png")
 *     .post(body)
 *     .build();
 * SempodsResponse<String> stored = exchange.text(request, 201);
 * ```
 *
 * **[answers] are every status the operation accepts**, 2xx and otherwise: a status outside them is a
 * [SempodsStatusException] carrying the answer's own, and a listed status outside 2xx is an answer
 * with a null body ([SempodsResponse]). List them rather than accepting everything — a route that
 * takes any status cannot tell a refusal from a result.
 *
 * **Decoding is [SempodsResponse.map]'s**, so a module reads its own document there and a body it
 * cannot read is a [SempodsDecodingException] with the answer's status and headers, as it is for the
 * core's own groups.
 */
class SempodsExchange(calls: Call.Factory) {

  private val exchange = Exchange(calls)

  /** The status of a listed answer, with the body closed unread. */
  @Throws(IOException::class)
  fun status(request: Request, vararg answers: Int): Int = exchange.status(request, answers.toSet())

  /** A listed answer with its body as text, in the charset the answer names, or UTF-8. */
  @Throws(IOException::class)
  fun text(request: Request, vararg answers: Int): SempodsResponse<String> =
    exchange.run(request, answers.toSet(), BodyReading.TEXT)

  /** A listed answer with its body as the bytes that arrived. */
  @Throws(IOException::class)
  fun bytes(request: Request, vararg answers: Int): SempodsResponse<ByteArray> =
    exchange.run(request, answers.toSet(), BodyReading.BYTES)

  /**
   * A listed answer whose body [reader] reads from the connection while it arrives: nothing is
   * buffered and no size limit applies. The body is closed when [reader] returns, and a listed answer
   * outside 2xx does not reach it.
   */
  @Throws(IOException::class)
  fun <T : Any> stream(request: Request, reader: SempodsBodyReader<T>, vararg answers: Int): SempodsResponse<T> =
    exchange.stream(request, answers.toSet(), reader)
}
