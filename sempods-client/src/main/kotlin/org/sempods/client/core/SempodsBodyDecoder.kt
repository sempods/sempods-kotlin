package org.sempods.client.core

/**
 * Turns an answer's body into another representation, for [SempodsResponse.map].
 *
 * ```java
 * SempodsResponse<Model> model = pod.resources()
 *     .getBytes(event, SempodsGraphFormat.N_QUADS, options)
 *     .map(bytes -> Rio.parse(new ByteArrayInputStream(bytes), RDFFormat.NQUADS));
 * ```
 *
 * A failure other than an `IOException` is the body's fault: [SempodsResponse.map] reports it as a
 * [SempodsDecodingException]. An `IOException` passes through as it is.
 */
fun interface SempodsBodyDecoder<in T : Any, out R : Any> {

  /** [body] in the new representation. */
  @Throws(Exception::class)
  fun decode(body: T): R
}
