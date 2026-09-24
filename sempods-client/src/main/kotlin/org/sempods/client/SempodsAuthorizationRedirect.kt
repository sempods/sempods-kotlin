package org.sempods.client

/**
 * What the pod's `/authorize` sent the browser back with (RFC 6749 §4.1.2): a code, or an error.
 * [SempodsPodAuthorization.readRedirect] reads it.
 *
 * ```java
 * SempodsAuthorizationRedirect answer = authorization.readRedirect(exchange.getRequestURI().getRawQuery(), state);
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

  internal companion object {

    @JvmSynthetic
    internal fun of(code: String?, error: String?, errorDescription: String?) = SempodsAuthorizationRedirect(code, error, errorDescription)
  }
}
