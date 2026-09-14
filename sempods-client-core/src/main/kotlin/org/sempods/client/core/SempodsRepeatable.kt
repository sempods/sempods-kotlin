package org.sempods.client.core

import okhttp3.Call
import okhttp3.Request

/**
 * Marks a request whose repetition has the same intended effect as sending it once, whatever its
 * method. A SPARQL query sent as POST is the case this exists for.
 *
 * A client [SempodsOkHttp] configured sends a session's request once more after a connection lost
 * before any response, but only for an idempotent method: a POST may already have been acted on, and
 * repeating it would be a duplicate write. RFC 9110 §9.2.2 lets a client repeat a POST when it knows
 * the request is safe for that resource, and this is how the caller says it knows.
 *
 * ```java
 * var query = session.newRequest("POST", sparqlPath).post(queryBody);
 * try (Response response = client.newCall(SempodsRepeatable.mark(query).build()).execute()) {
 *   ...
 * }
 * ```
 *
 * A body that can be written only once is still not sent again.
 */
object SempodsRepeatable {

  /** Marks [request] as safe to send again, and returns it for chaining. */
  @JvmStatic
  fun mark(request: Request.Builder): Request.Builder = request.tag(SempodsRepeatable::class.java, SempodsRepeatable)

  internal fun isMarked(call: Call): Boolean = call.tag(SempodsRepeatable::class.java) != null
}
