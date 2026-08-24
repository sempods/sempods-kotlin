package org.sempods.auth

/**
 * Who the id-server says someone is, as this server carries it through an authorization.
 *
 * It arrives from `OidcRelyingParty.completeAuthorization` at the login callback and is held in a
 * consent ticket across the consent screen. It is *not* a credential and never travels: a pod
 * request identifies its caller by the `sub` of a pod-issued access token. The type this replaced
 * was called `PersonIdentity` because it was parsed out of an identity JWT presented as a
 * bearer — the arrangement the OIDC cutover removed.
 *
 * @param alsoKnownAs equivalent identity URIs for the same person. Ownership and grants are
 *   decided against [allUris], so an owner whose pod records an alias still resolves.
 */
data class PersonIdentity(
  val webId: String,
  val alsoKnownAs: List<String>,
) {
  /** All identity URIs for this person: the primary WebID URI plus any aliases. */
  val allUris: List<String> get() = listOf(webId) + alsoKnownAs
}
