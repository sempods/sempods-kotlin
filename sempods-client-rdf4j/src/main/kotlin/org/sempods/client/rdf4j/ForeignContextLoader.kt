package org.sempods.client.rdf4j

import no.hasmac.jsonld.JsonLdError
import no.hasmac.jsonld.JsonLdErrorCode
import no.hasmac.jsonld.document.Document
import no.hasmac.jsonld.document.JsonDocument
import no.hasmac.jsonld.http.media.MediaType
import no.hasmac.jsonld.loader.DocumentLoader
import no.hasmac.jsonld.loader.DocumentLoaderOptions
import org.sempods.client.SempodsForeignTarget
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI

/**
 * Loads the remote contexts of one JSON-LD document through [target]: its guard, redirect budget,
 * deadline and admission, and never a credential.
 *
 * At most [MAX_CONTEXTS] documents per parse, each answered `2xx` as JSON. A refusal is a [JsonLdError],
 * which the parser reports as the document's own parse failure. An `IOException` of [target] — a guard's
 * refusal, a deadline, a cancelled call — is kept in [failure] for the caller to throw as it is.
 */
internal class ForeignContextLoader(
  private val target: SempodsForeignTarget,
) : DocumentLoader {

  private var loads = 0

  var failure: IOException? = null
    private set

  override fun loadDocument(url: URI, options: DocumentLoaderOptions): Document {
    if (++loads > MAX_CONTEXTS) {
      throw JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED, "A document names more than $MAX_CONTEXTS remote contexts.")
    }
    val answer = try {
      target.getBytes(url.toString(), ACCEPT)
    } catch (thrown: IOException) {
      failure = failure ?: thrown
      throw JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED, thrown)
    }
    val body = answer.body
    if (answer.status !in 200..299 || body == null) {
      throw JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED, "A remote context answered ${answer.status}.")
    }
    val mediaType = answer.headers["Content-Type"]?.let(::mediaTypeOf)
    if (mediaType == null || !JsonDocument.accepts(mediaType)) {
      throw JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED, "A remote context answered no JSON.")
    }
    val document = JsonDocument.of(mediaType, ByteArrayInputStream(body))
    document.documentUrl = URI(answer.url)
    return document
  }

  private fun mediaTypeOf(contentType: String): MediaType? {
    val (type, subtype) = contentType.substringBefore(';').trim().lowercase().split('/').takeIf { it.size == 2 } ?: return null
    return MediaType.of(type, subtype)
  }

  private companion object {

    const val MAX_CONTEXTS = 10

    const val ACCEPT = "application/ld+json, application/json;q=0.9"
  }
}
