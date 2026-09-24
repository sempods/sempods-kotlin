package org.sempods.client

import okhttp3.HttpUrl

/**
 * What the pod's `/authorize` sent the browser back with (RFC 6749 §4.1.2): a code, or an error.
 *
 * ```java
 * SempodsAuthorizationRedirect answer = SempodsAuthorizationRedirect.readQuery(exchange.getRequestURI().getRawQuery(), state);
 * if (!answer.isApproved()) { ... answer.getError() ... }
 * ```
 *
 * | The redirect carries | [isApproved] | [code] | [error] |
 * |---|---|---|---|
 * | `code` | true | the code | null |
 * | `error`, for example `access_denied` when the owner declined | false | null | the error code |
 *
 * [toString] leaves out [code], which redeems a token.
 */
class SempodsAuthorizationRedirect private constructor(
  /** The authorization code, null when the pod answered an error. */
  val code: String?,
  /** The RFC 6749 §4.1.2.1 error code, null when the pod answered a code. */
  val error: String?,
  /** The pod's `error_description`, when it sent one. It is the pod's text, meant for a developer. */
  val errorDescription: String?,
) {

  /** Whether the pod answered a code. */
  val isApproved: Boolean get() = code != null

  override fun toString(): String = "SempodsAuthorizationRedirect(approved=$isApproved, error=$error)"

  companion object {

    /**
     * Reads the redirect's [encodedQuery], as it arrived, after checking its `state` against
     * [expectedState].
     *
     * @throws SempodsClientException when `state` is missing or different, when a member repeats,
     *   or when the query carries neither `code` nor `error`.
     */
    @JvmStatic
    @Throws(SempodsClientException::class)
    fun readQuery(encodedQuery: String?, expectedState: String): SempodsAuthorizationRedirect {
      val query = RedirectQuery.of(encodedQuery, expectedState, WHAT)
      val code = query.single("code", WHAT)
      val error = query.single("error", WHAT)
      if ((code == null) == (error == null)) {
        throw SempodsClientException("The $WHAT redirect carries ${if (code == null) "neither a code nor an error" else "both a code and an error"}.")
      }
      return SempodsAuthorizationRedirect(code, error, query.single("error_description", WHAT))
    }

    /** [readQuery] on [redirect]'s query. */
    @JvmStatic
    @Throws(SempodsClientException::class)
    fun read(redirect: HttpUrl, expectedState: String): SempodsAuthorizationRedirect = readQuery(redirect.encodedQuery, expectedState)

    private const val WHAT = "authorization"
  }
}
