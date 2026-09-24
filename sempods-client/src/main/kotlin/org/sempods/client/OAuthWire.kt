package org.sempods.client

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType

/** `POST {pod}/_system/auth/register`, where both registration profiles are served. */
internal const val REGISTER_ROUTE = "_system/auth/register"

@get:JvmSynthetic
internal val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

/** An OAuth scope parameter's value as its scopes, in order and without repeats (RFC 6749 §3.3). */
@JvmSynthetic
internal fun scopesOf(text: String?): Set<String> = text.orEmpty().split(' ').filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

/** [scopes] as one scope parameter's value. */
@JvmSynthetic
internal fun scopeText(scopes: Collection<String>): String = scopes.joinToString(" ")

/**
 * [route] followed by [segments], each encoded as one segment, and [query]: a path for
 * [SempodsSession.newRequest]. An empty, `.` or `..` segment is refused, because the URL builder would
 * drop or collapse it and the request would reach another route; a `/` in one is refused by the
 * session's confinement.
 */
@JvmSynthetic
internal fun podPath(route: String, segments: List<String>, query: Map<String, String> = emptyMap()): String {
  val url = PATH_BASE.newBuilder().apply {
    segments.forEach {
      require(it.isNotEmpty() && it != "." && it != "..") { "'$it' cannot be one path segment." }
      addPathSegment(it)
    }
    query.forEach { (name, value) -> addQueryParameter(name, value) }
  }.build()
  return route + url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
}

private val PATH_BASE: HttpUrl = "http://path.invalid/".toHttpUrl()
