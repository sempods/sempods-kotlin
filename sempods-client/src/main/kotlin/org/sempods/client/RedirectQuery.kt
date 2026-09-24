package org.sempods.client

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The query a browser brought back to a caller's redirect, once its `state` is known to be the
 * caller's own (RFC 6749 §10.12). [what] names the round trip in a refusal.
 */
internal class RedirectQuery private constructor(encodedQuery: String?, private val what: String) {

  private val url = BASE.newBuilder().encodedQuery(encodedQuery).build()

  /** The single value of [name], and null when it is missing. Two values are refused: the answer is ambiguous. */
  fun single(name: String): String? {
    val values = url.queryParameterValues(name)
    if (values.size > 1) throw refused("carries '$name' more than once")
    return values.singleOrNull()
  }

  /** A refusal naming this round trip: "The authorization redirect [detail]." */
  fun refused(detail: String) = SempodsClientException("The $what redirect $detail.")

  companion object {

    private val BASE = "http://redirect.invalid/".toHttpUrl()

    @JvmSynthetic
    fun of(encodedQuery: String?, expectedState: String, what: String): RedirectQuery {
      val query = RedirectQuery(encodedQuery, what)
      // A mismatch is someone else's answer, or a forged one: its code or outcome is not this caller's to act on.
      if (query.single("state") != expectedState) {
        throw query.refused("does not carry the state this caller sent, so it is not the answer to its request")
      }
      return query
    }
  }
}
