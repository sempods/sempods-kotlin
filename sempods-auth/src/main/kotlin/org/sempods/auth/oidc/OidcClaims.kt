package org.sempods.auth.oidc

/**
 * Normalized claims extracted from an OIDC provider's id_token.
 *
 * [email] is null when the provider did not supply one (Apple without email scope,
 * or any provider where the email was not verified). In that case the OIDC namespace
 * URI (`id.sempods.org/oidc/<hash>`) is used instead of the email namespace.
 *
 * [picture] is an absolute HTTPS address of the person's picture at the provider, or null. Google
 * supplies one; Apple has none to give.
 */
data class OidcClaims(
  val iss: String,
  val sub: String,
  val email: String?,
  val displayName: String?,
  val picture: String? = null,
)
