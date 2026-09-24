package org.sempods.client

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The query a browser brought back to a caller's redirect, once its `state` is known to be the
 * caller's own (RFC 6749 §10.12). [what] names the round trip in a refusal.
 */
internal class RedirectQuery private constructor(private val url: HttpUrl) {

  /** The single value of [name], and null when it is missing. Two values are refused: the answer is ambiguous. */
  fun single(name: String, what: String): String? {
    val values = url.queryParameterValues(name)
    if (values.size > 1) throw SempodsClientException("The $what redirect carries '$name' more than once.")
    return values.singleOrNull()
  }

  companion object {

    @JvmSynthetic
    fun of(encodedQuery: String?, expectedState: String, what: String): RedirectQuery {
      val query = RedirectQuery("http://redirect.invalid/".toHttpUrl().newBuilder().encodedQuery(encodedQuery).build())
      // A mismatch is someone else's answer, or a forged one: its code or outcome is not this caller's to act on.
      if (query.single("state", what) != expectedState) {
        throw SempodsClientException("The $what redirect does not carry the state this caller sent, so it is not the answer to its request.")
      }
      return query
    }
  }
}
