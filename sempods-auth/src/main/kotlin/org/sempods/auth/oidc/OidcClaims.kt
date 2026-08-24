package org.sempods.auth.oidc

/**
 * Normalized claims extracted from an OIDC provider's id_token.
 *
 * [email] is null when the provider did not supply one (Apple without email scope,
 * or any provider where the email was not verified). In that case the OIDC namespace
 * URI (`id.sempods.org/oidc/<hash>`) is used instead of the email namespace.
 */
data class OidcClaims(
  val iss: String,
  val sub: String,
  val email: String?,
  val displayName: String?,
)
