package org.sempods.auth.core

/**
 * The OAuth error codes this stack produces (RFC 6749 §4.1.2.1 / §5.2, OIDC Core §3.1.2.6).
 *
 * A closed set rather than free strings: a client switches on these, and a typo becomes an error
 * nobody can handle.
 */
enum class OAuthErrorCode(val code: String) {
  INVALID_REQUEST("invalid_request"),
  UNAUTHORIZED_CLIENT("unauthorized_client"),
  ACCESS_DENIED("access_denied"),
  UNSUPPORTED_RESPONSE_TYPE("unsupported_response_type"),
  INVALID_SCOPE("invalid_scope"),
  SERVER_ERROR("server_error"),
  TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),
  LOGIN_REQUIRED("login_required"),
  CONSENT_REQUIRED("consent_required"),
  INTERACTION_REQUIRED("interaction_required"),
  INVALID_CLIENT("invalid_client"),
  INVALID_GRANT("invalid_grant"),
  UNSUPPORTED_GRANT_TYPE("unsupported_grant_type"),
  ;

  companion object {
    fun of(code: String): OAuthErrorCode? = entries.firstOrNull { it.code == code }
  }
}

/**
 * How an authorization error reaches the caller.
 *
 * The distinction is the whole point of the type. An authorization server may only report an error
 * *by redirect* once it knows the redirect address belongs to the client that named it — otherwise
 * the error path is an open redirector on the server's own origin, usable to launder a link
 * through a trusted host. The pod server had exactly that: it reported an unknown `client_id` by
 * redirecting to the unvalidated `redirect_uri`, before the check that would have rejected it.
 *
 * So there is no way to build a redirect response here without a [Redirectable] target, and the
 * only way to get one is [OAuthErrors.redirectTargetFor], which validates first.
 */
sealed interface OAuthErrorDelivery {

  /** Rendered by the authorization server itself — the client is unknown or its address is not. */
  data class Direct(val code: OAuthErrorCode, val description: String) : OAuthErrorDelivery

  /** Sent back to a redirect address already proven to belong to the client. */
  data class Redirect(
    val target: Redirectable,
    val code: OAuthErrorCode,
    val description: String,
    val state: String?,
  ) : OAuthErrorDelivery
}

/**
 * A redirect address that has been checked against the client that presented it.
 *
 * Deliberately impossible to construct from outside this file: holding one *is* the proof that the
 * check ran. A `String` parameter would let a caller pass the raw request value, which is the
 * mistake the type exists to prevent.
 */
class Redirectable private constructor(val uri: String) {
  internal companion object {
    fun of(uri: String) = Redirectable(uri)
  }
}

object OAuthErrors {

  /**
   * The redirect target for an error, or `null` when the error must be rendered directly.
   *
   * `null` means one of two things and both have the same answer: the client is not one this
   * server knows, or the address is not one that client may use. In neither case may the server
   * send anything to it.
   */
  fun redirectTargetFor(policy: ClientRedirectPolicy, clientId: String?, redirectUri: String?): Redirectable? {
    val id = clientId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val uri = redirectUri?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return if (policy.permits(id, uri)) Redirectable.of(uri) else null
  }

  /** `error_uri` — a stable page per error code, so a client can point a human at the recovery steps. */
  fun errorUri(docBase: String, code: OAuthErrorCode): String = "$docBase#${code.code}"
}
