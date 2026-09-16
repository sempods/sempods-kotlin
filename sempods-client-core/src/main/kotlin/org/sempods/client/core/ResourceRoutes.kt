package org.sempods.client.core

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import java.util.Base64

/** RFC 3986 `pchar` without `%` and `;`: the ASCII a path segment carries to the pod as it is. */
private val PATH_CHARACTERS: Set<Char> =
  (('A'..'Z') + ('a'..'z') + ('0'..'9') + "-._~!$&'()*+,=:@".toList()).toSet()

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
  }

  /**
   * `_system/contexts/{path}`, where a context's IRI **is** the route that manages it (SPS-CTX-005).
   *
   * The IRI is taken as the pod gave it and the prefix cut off, so nothing here composes one
   * (SPS-CTX-023). **What a context may be named is the pod's to say** (SPS-CTX-009): this refuses
   * only what could not be addressed as itself, because the pod takes this path decoded and builds
   * the IRI from it — a percent-encoded octet, a `;` (#181), an empty or a dot segment would name
   * another context, or none at all (SPS-CTX-013). Anything else travels percent-encoded and arrives
   * as it was written, `grüße` included.
   */
  class RegistryPath(private val podBase: SempodsPodBase) : ResourceAddress() {

    private val namespace = "${podBase.url.toString().removeSuffix("/")}/$CATALOGUE/"

    override fun path(iri: String): String {
      val reason = reject(iri)
      require(reason == null) { "'$iri' $reason." }
      val url = podBase.url.newBuilder().addPathSegments(CATALOGUE)
      iri.substring(namespace.length).split('/').forEach { url.addPathSegment(it) }
      return url.build().encodedPath.substring(podBase.url.encodedPath.removeSuffix("/").length + 1)
    }

    /** Why [iri] is no context of this pod, or null when it is one. */
    private fun reject(iri: String): String? {
      if (!iri.startsWith(namespace)) {
        return "is not a context of the pod '$podBase', whose contexts lie under '$namespace' (SPS-CTX-004)"
      }
      val path = iri.substring(namespace.length)
      if (path.isEmpty()) return "names no context under '$namespace'"
      if ('?' in path || '#' in path) return "has a query or a fragment, which a context IRI cannot carry"
      val unfit = path.indexOfFirst { it.code < 0x80 && it != '/' && it !in PATH_CHARACTERS }
      if (unfit >= 0) {
        return "has a character at position ${namespace.length + unfit} that a context path cannot carry as it is"
      }
      val segments = path.split('/')
      if (segments.any { it.isEmpty() }) return "has an empty segment"
      if (segments.any { it == "." || it == ".." }) return "has a dot segment"
      return null
    }

    companion object {

      /** `{pod}/_system/contexts` — the catalogue of what the caller may see (SPS-CTX-021). */
      const val CATALOGUE = "_system/contexts"
    }
  }

  /**
   * `_system/resources/{b64url(iri)}`, for an IRI of any scheme (SPS-CRUD-003, SPS-CRUD-005), and the slot
   * and edge routes below it (SPS-CRUD-041, SPS-CRUD-042).
   */
  object SystemRoute : ResourceAddress() {

    override fun path(iri: String): String = "_system/resources/${segment(iri, "subject")}"

    /** `_system/resources/{b64url(subject)}/{b64url(predicate)}`. */
    fun slotPath(subjectUri: String, predicateUri: String): String = "${path(subjectUri)}/${segment(predicateUri, "predicate")}"

    /** The slot path, then `/{b64url(target)}`. */
    fun edgePath(subjectUri: String, predicateUri: String, targetUri: String): String =
      "${slotPath(subjectUri, predicateUri)}/${segment(targetUri, "target")}"

    /** base64url without padding over the IRI's UTF-8 bytes (RFC 4648 §5). */
    private fun segment(iri: String, role: String): String {
      require(iri.isNotBlank()) { "A $role IRI must not be blank." }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(iri.toByteArray(Charsets.UTF_8))
    }
  }
}

/** Reads and writes at a path under the pod: by IRI through [address] for the resource groups, by path for slots. */
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
    return readAt(path, format.mediaType, options, reading)
  }

  fun put(iri: String, format: SempodsGraphFormat, content: SempodsContent, options: SempodsWriteOptions) =
    writeAt("PUT", address.path(iri), content, format.mediaType, options, PUT)

  fun patch(iri: String, mergePatch: SempodsContent, options: SempodsWriteOptions) =
    writeAt("PATCH", address.path(iri), mergePatch, MERGE_PATCH, options, PATCH_OR_DELETE)

  fun delete(iri: String, options: SempodsWriteOptions) =
    writeAt("DELETE", address.path(iri), content = null, mediaType = null, options, PATCH_OR_DELETE)

  /** A `GET` of [path] asking for [accept]. A selection of no context is answered here, without a request. */
  fun <T : Any> readAt(
    path: String,
    accept: String,
    options: SempodsReadOptions,
    reading: BodyReading<T>,
  ): SempodsResponse<T> {
    if (options.selection.isRestricted && options.selection.contextUris.isEmpty()) {
      // A read route drops an empty `context` and answers from every readable context, so nothing is
      // sent: the answer is the absence the pod gives when nothing is visible (SPS-CRUD-017).
      return SempodsResponse(session.podBase.resolve(path).toString(), 404, Headers.headersOf(), body = null)
    }
    val url = session.podBase.resolve(path).newBuilder()
    options.selection.contextUris.forEach { url.addQueryParameter(CONTEXT, it) }
    if (options.includeContexts) url.addQueryParameter(INCLUDE_CONTEXTS, "true")
    val request = session.newRequest("GET", target(path, url)).header("Accept", accept)
    options.ifNoneMatch?.let { request.header("If-None-Match", it) }
    return exchange.run(request.build(), if (options.ifNoneMatch != null) READ_CONDITIONAL else READ, reading)
  }

  /** The pod-relative path [iri] is read and written at, or an [IllegalArgumentException] naming why there is none. */
  fun pathOf(iri: String): String = address.path(iri)

  /**
   * A `PUT` of [path] with [content] as [mediaType], asking for [accept].
   *
   * Neither a target context nor a condition: the context registry is the one route that is written
   * to without naming a context — the write *is* the context (SPS-CTX-015).
   */
  fun putAt(
    path: String,
    content: SempodsContent,
    mediaType: String,
    accept: String,
    answers: Set<Int>,
  ): SempodsResponse<ByteArray> {
    val request = session.newRequest("PUT", path)
      .header("Accept", accept)
      .put(content.requestBody(mediaType.toMediaType()))
    return exchange.run(request.build(), answers, BodyReading.BYTES)
  }

  /** A `DELETE` of [path], asking for nothing: no context parameter, no condition, no representation. */
  fun deleteAt(path: String, answers: Set<Int>): SempodsResponse<ByteArray> =
    exchange.run(session.newRequest("DELETE", path).build(), answers, BodyReading.BYTES)

  /** A write to [path] in the options' context. [answers] gains `412` when the write is conditional. */
  fun writeAt(
    method: String,
    path: String,
    content: SempodsContent?,
    mediaType: String?,
    options: SempodsWriteOptions,
    answers: Set<Int>,
  ): SempodsResponse<ByteArray> {
    val url = session.podBase.resolve(path).newBuilder()
    url.addQueryParameter(CONTEXT, options.contextUri)
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
