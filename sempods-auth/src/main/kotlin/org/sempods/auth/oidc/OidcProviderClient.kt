package org.sempods.auth.oidc

import org.sempods.auth.core.OidcPrompt

/**
 * Abstraction for an OIDC provider in the sempods-auth login bridge.
 *
 * Each provider knows how to:
 * 1. Build an authorization redirect URL (with state for CSRF protection)
 * 2. Handle the callback code exchange and ID-token verification
 *
 * Implementations are registered by name in [org.sempods.auth.SempodsAuthModule]
 * and selected dynamically via the `provider` query parameter on `GET /authorize`
 * and the `{provider}` path segment in `GET|POST /login/oidc/{provider}/callback`.
 *
 * Adding a new provider requires only implementing this interface and adding
 * it to the providers map — no routing changes needed.
 */
interface OidcProviderClient {
  /** Unique name used as path segment and as the `provider` query value. */
  val name: String

  /**
   * What the provider is called on the login page.
   *
   * Carried by the provider rather than looked up in the page, so that adding a provider cannot
   * half-succeed: a new implementation is unusable without a label, instead of rendering under a
   * lowercase path segment because a table somewhere was not updated.
   */
  val displayName: String

  /**
   * @param prompt forwarded to the provider so that a caller demanding fresh authentication
   *   actually gets it. Dropping it here would make `prompt=login` a promise the chain does not
   *   keep: an existing provider session would satisfy the flow silently, and the pod server
   *   asking for re-authentication would never learn that none happened.
   */
  fun authorizeUrl(state: String, prompt: OidcPrompt? = null): String

  /**
   * @param callbackParams every parameter of the callback request (query for a redirect callback,
   *   form body for `response_mode=form_post`). Present because some providers put claims outside
   *   the `id_token`: Apple sends the user's name as a `user` form field, and only on the very
   *   first authorization. A provider that needs nothing beyond [code] ignores this.
   */
  suspend fun handleCallback(code: String, callbackParams: Map<String, String> = emptyMap()): OidcClaims
}
