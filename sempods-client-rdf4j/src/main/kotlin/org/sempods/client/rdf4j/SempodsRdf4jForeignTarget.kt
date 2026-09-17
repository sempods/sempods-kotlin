package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFHandler
import org.sempods.client.core.SempodsBodyReader
import org.sempods.client.core.SempodsForeignTarget
import org.sempods.client.core.SempodsRequestAuth
import org.sempods.client.core.SempodsResponse
import java.io.IOException

/**
 * [SempodsForeignTarget] with RDF4J values: a URI outside any pod, read as a [Model] or into an
 * [RDFHandler], with no pod's credential.
 *
 * ```java
 * var foreign = new SempodsRdf4jForeignTarget(new SempodsForeignTarget(client).followingRedirects(5));
 * Model profile = foreign.getModel("https://bob.example/profile", List.of(RDFFormat.TURTLE, RDFFormat.JSONLD)).getBody();
 * ```
 *
 * **The formats are the caller's, in order of preference.** `Accept` names the first without a
 * `q`-value and each one after it strictly lower, in thousandths (RFC 9110 §12.4.2), so a list holds at
 * most 1000 formats. In [getModel] the answer's `Content-Type` picks the parser among them, and an
 * answer in a format not asked for, or without a `Content-Type`, is a
 * [org.sempods.client.core.SempodsDecodingException]. [getStream] parses as the one format it was given,
 * whatever the answer says it is: a reader sees no headers, and by the time the type could be read the
 * handler would hold statements already
 * ([#225](https://github.com/sempods/sempods-kotlin/issues/225)). Turtle, N-Quads, N-Triples and JSON-LD come with this module;
 * any other format needs its RDF4J parser on the classpath, and one without is an
 * [IllegalArgumentException] before anything is sent.
 *
 * **A remote JSON-LD context is loaded for [getModel]**, through [target] — its guard, its redirects,
 * its admission — and never with [getModel]'s credential, since a context lives on its own origin. At
 * most ten are loaded per document. A context that cannot be loaded makes the document unreadable; an
 * `IOException` while loading one, a refused address or an expired deadline, is thrown as it is.
 * [getStream] loads none: the call still holds its admission slot while it parses.
 *
 * Statuses, redirects, limits and the guard are [SempodsForeignTarget]'s: every status is an answer, and
 * outside `2xx` without a body.
 */
class SempodsRdf4jForeignTarget(
  val target: SempodsForeignTarget,
) {

  /**
   * [uri] as a model, in whichever of [formats] it answers with. Relative IRIs resolve against
   * [SempodsResponse.url], the URL that answered.
   *
   * @throws IllegalArgumentException for no format, more than 1000, or a format no parser on the classpath reads.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun getModel(
    uri: String,
    formats: List<RDFFormat>,
    auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
  ): SempodsResponse<Model> {
    require(formats.size in 1..MAX_FORMATS) { "A foreign read asks for 1 to $MAX_FORMATS formats, not ${formats.size}." }
    formats.forEach { Rdf4jCodec.parser(it) }
    val answer = target.getBytes(uri, accept(formats), auth)
    return answer.map { bytes ->
      val mediaType = answer.headers["Content-Type"]?.substringBefore(';')?.trim()
      val format = mediaType?.let { RDFFormat.matchMIMEType(it, formats).orElse(null) }
        ?: throw IllegalStateException("The answer is in none of the formats asked for.")
      val loader = ForeignContextLoader(target)
      try {
        Rdf4jCodec.readModel(format, bytes, answer.url, loader)
      } catch (unreadable: Exception) {
        loader.failure?.let { throw it }
        throw unreadable
      }
    }
  }

  /**
   * [uri] parsed as [format] into [handler] while it arrives; the body is the number of statements handed
   * on. Relative IRIs resolve against [uri]: a stream is read before a redirect's final URL is known to it.
   *
   * **[format] is what the body is read as**, whatever `Content-Type` the answer carries — a server that
   * ignores `Accept` is not caught here, and a redirect's final URL is not the base either. [getModel] is
   * the read that picks its parser by the answer and resolves against the URL that answered; a streamed
   * read sees neither until the core hands a reader its answer
   * ([#225](https://github.com/sempods/sempods-kotlin/issues/225)).
   *
   * What [handler] throws, and an `IOException` of the connection, reach the caller as they are. A body
   * that does not parse is a [org.sempods.client.core.SempodsDecodingException], after the statements
   * before it were handed on.
   *
   * @throws IllegalArgumentException for a format no parser on the classpath reads.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun getStream(
    uri: String,
    format: RDFFormat,
    handler: RDFHandler,
    auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
  ): SempodsResponse<Long> {
    Rdf4jCodec.parser(format)
    val reader = SempodsBodyReader { body -> readStatements(body, format, uri, handler, context = null) }
    return target.getStream(uri, accept(listOf(format)), reader, auth).map { it.countOrThrow() }
  }

  private fun accept(formats: List<RDFFormat>): String =
    formats.mapIndexed { index, format ->
      if (index == 0) format.defaultMIMEType else "${format.defaultMIMEType};q=0.${(1000 - index).toString().padStart(3, '0')}"
    }.joinToString(", ")

  private companion object {

    /** A `q`-value has three decimals, so 999 preferences fit below the first. */
    const val MAX_FORMATS = 1000
  }
}
