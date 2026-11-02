package org.sempods.auth.login

/**
 * What the `id_token` says about the person beyond their WebID, under the standard scopes
 * `profile` and `email` (OpenID Connect Core §5.4). A null field is left out of the token.
 *
 * Only ever built for scopes the person agreed to release to the client that receives the token.
 */
data class PersonClaims(
  /** `name`: the profile's display name, under `profile`. */
  val name: String? = null,
  /** `picture`: an HTTPS address, under `profile`. */
  val picture: String? = null,
  /** `email`, with `email_verified: true` beside it, under `email`. */
  val email: String? = null,
) {

  companion object {
    val NONE = PersonClaims()
  }
}
