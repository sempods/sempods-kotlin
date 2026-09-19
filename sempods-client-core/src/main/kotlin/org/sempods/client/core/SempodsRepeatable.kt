package org.sempods.client.core

import okhttp3.Call
import okhttp3.Request

/**
 * Marks a request whose repetition has the same intended effect as sending it once, whatever its
 * method — a SPARQL query sent as POST. A client [SempodsOkHttp] configured then sends it once more
 * after a lost connection, as it does for an idempotent method (RFC 9110 §9.2.2).
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

  @JvmSynthetic
  internal fun isMarked(call: Call): Boolean = call.tag(SempodsRepeatable::class.java) != null
}
