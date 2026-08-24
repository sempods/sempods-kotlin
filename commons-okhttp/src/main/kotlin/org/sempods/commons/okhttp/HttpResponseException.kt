package org.sempods.commons.okhttp

/** A non-2xx response the caller asked to be treated as an error. */
class HttpResponseException(
  val statusCode: Int,
  val responseBody: String?,
) : RuntimeException("[$statusCode]: $responseBody")
