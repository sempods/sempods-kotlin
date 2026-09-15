package org.sempods.client.core

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import java.util.Base64

/** Where a group's operations go for an IRI. */
internal sealed class ResourceAddress {

  /** The pod-relative, percent-encoded path of [iri], or an [IllegalArgumentException] naming why there is none. */
  abstract fun path(iri: String): String

  /**
   * The IRI's own path under the pod base (SPS-CRUD-001). Checked as the string it is, so no URL parser
   * rewrites the identity on the way.
   */
  class LodPath(private val podBase: SempodsPodBase) : ResourceAddress() {

    private val base = podBase.url.toString().removeSuffix("/") + "/"

    override fun path(iri: String): String {
      val reason = reject(iri)
      require(reason == null) { "'$iri' $reason; it is reached through subjects()." }
      val url = podBase.url.newBuilder()
      iri.substring(base.length).split('/').forEach { url.addPathSegment(it) }
      return url.build().encodedPath.substring(podBase.url.encodedPath.removeSuffix("/").length + 1)
    }

    /** Why [iri] has no path under the pod that names it as it is, or null when it has one. */
    private fun reject(iri: String): String? {
      if (iri == base || iri == base.removeSuffix("/")) return "is the pod's base URL, with no path under it"
      if (!iri.startsWith(base)) return "is not under the pod '$podBase'"
      val path = iri.substring(base.length)
      if ('?' in path || '#' in path) return "has a query or a fragment"
      // The pod decodes the path and cuts a segment at `;` (#181) before it composes the IRI, so an escape
      // or a `;` sent here would name another resource.
      val unfit = path.indexOfFirst { it.code < 0x80 && it != '/' && it !in PATH_CHARACTERS }
      if (unfit >= 0) return "has a character at position ${base.length + unfit} that its path under the pod cannot carry as it is"
      val segments = path.split('/')
      if (segments.first() == "_system" || segments.first() == ".well-known") {
        return "lies in the pod's reserved '${segments.first()}' area (SPS-CRUD-004)"
      }
      if (segments.any { it == "." || it == ".." }) return "has a dot segment"
      if (segments.dropLast(1).any { it.isEmpty() }) return "has an empty segment"
      return null
    }

    private companion object {
      /** RFC 3986 `pchar` without `%` and `;`: the ASCII a path segment carries to the pod as it is. */
      val PATH_CHARACTERS: Set<Char> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + "-._~!$&'()*+,=:@".toList()).toSet()
    }
  }

  /** `_system/resources/{b64url(iri)}`, for an IRI of any scheme (SPS-CRUD-003, SPS-CRUD-005). */
  object SystemRoute : ResourceAddress() {

    override fun path(iri: String): String {
      require(iri.isNotBlank()) { "A subject IRI must not be blank." }
      return "_system/resources/${segment(iri)}"
    }

    /** base64url without padding over the IRI's UTF-8 bytes (RFC 4648 §5). */
    fun segment(iri: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(iri.toByteArray(Charsets.UTF_8))
  }
}

/** The operations both resource groups offer, run against one [address]. */
internal class ResourceOperations(
  private val session: SempodsSession,
  private val exchange: Exchange,
  private val address: ResourceAddress,
) {

  fun <T : Any> read(
    iri: String,
    format: SempodsGraphFormat,
    options: SempodsReadOptions,
    reading: BodyReading<T>,
  ): SempodsResponse<T> {
    val path = address.path(iri)
    require(!(format == SempodsGraphFormat.N_QUADS && options.includeContexts)) {
      "include_contexts groups JSON-LD by context; N-Quads already carries each statement's context (SPS-CRUD-028)."
    }
    if (options.selection.isRestricted && options.selection.contextUris.isEmpty()) {
      // A read route drops an empty `context` and answers from every readable context, so nothing is
      // sent: the answer is the absence the pod gives when nothing is visible (SPS-CRUD-017).
      return SempodsResponse(404, Headers.headersOf(), body = null)
    }
    val url = session.podBase.resolve(path).newBuilder()
    options.selection.contextUris.forEach { url.addQueryParameter(CONTEXT, it) }
    if (options.includeContexts) url.addQueryParameter(INCLUDE_CONTEXTS, "true")
    val request = session.newRequest("GET", target(path, url)).header("Accept", format.mediaType)
    options.ifNoneMatch?.let { request.header("If-None-Match", it) }
    return exchange.run(request.build(), if (options.ifNoneMatch != null) READ_CONDITIONAL else READ, reading)
  }

  fun put(iri: String, format: SempodsGraphFormat, content: SempodsContent, options: SempodsWriteOptions) =
    write("PUT", iri, content, format.mediaType, options, PUT)

  fun patch(iri: String, mergePatch: SempodsContent, options: SempodsWriteOptions) =
    write("PATCH", iri, mergePatch, MERGE_PATCH, options, PATCH_OR_DELETE)

  fun delete(iri: String, options: SempodsWriteOptions) = write("DELETE", iri, content = null, mediaType = null, options, PATCH_OR_DELETE)

  private fun write(
    method: String,
    iri: String,
    content: SempodsContent?,
    mediaType: String?,
    options: SempodsWriteOptions,
    answers: Set<Int>,
  ): SempodsResponse<ByteArray> {
    val path = address.path(iri)
    val url = session.podBase.resolve(path).newBuilder()
    options.contextUri?.let { url.addQueryParameter(CONTEXT, it) }
    val request = session.newRequest(method, target(path, url))
    if (content != null && mediaType != null) request.method(method, content.requestBody(mediaType.toMediaType()))
    options.ifMatch?.let { request.header("If-Match", it) }
    options.ifNoneMatch?.let { request.header("If-None-Match", it) }
    return exchange.run(request.build(), if (options.isConditional) answers + 412 else answers, BodyReading.BYTES)
  }

  private fun target(path: String, url: HttpUrl.Builder): String = url.build().encodedQuery?.let { "$path?$it" } ?: path

  private companion object {

    const val CONTEXT = "context"

    const val INCLUDE_CONTEXTS = "include_contexts"

    const val MERGE_PATCH = "application/merge-patch+json"

    val READ = setOf(200, 404)

    val READ_CONDITIONAL = setOf(200, 304, 404)

    val PUT = setOf(200, 201, 204)

    val PATCH_OR_DELETE = setOf(200, 204, 404)
  }
}
