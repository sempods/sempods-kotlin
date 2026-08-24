package org.sempods.mcp.oauth

import org.sempods.auth.core.DidWeb
import org.sempods.auth.core.HttpTransport
import org.sempods.auth.core.OidcRelyingParty
import org.sempods.auth.core.OidcRelyingPartyCache

/**
 * The id-server, as this service's OpenID Provider.
 *
 * Two endpoints need it — the AI-client flow and the browser UI — and each redirects back to its
 * own callback, so there is one relying party per callback path. Keeping them, and discovering the
 * provider once for all of them, is [OidcRelyingPartyCache]'s job; what is this service's own is
 * the pair below: where a callback path sits, and what to say when no issuer is configured.
 *
 * The client identifier is this service's own origin (`did:web:<host>`). Nothing is registered
 * anywhere: the id-server permits an address only on the origin the identifier names, which is what
 * stands in for a client secret.
 */
class IdentityProvider(
  private val issuer: String?,
  private val serviceBaseUrl: String,
  private val transport: HttpTransport,
) {

  // `lazy` rather than a constructor call: the issuer is optional configuration, and a service that
  // refused to build this object would refuse to boot over a feature it may never use. Kotlin does
  // not memoize a failed initializer, so a later call still reports the same missing setting.
  private val relyingParties by lazy {
    OidcRelyingPartyCache(
      issuer = checkNotNull(issuer) { "no auth issuer configured (SEMPODS_AUTH_ISSUERS)" },
      clientId = DidWeb.clientId(serviceBaseUrl),
      transport = transport,
    )
  }

  /**
   * @param callbackPath where this particular flow returns, relative to the service base.
   * @throws IllegalStateException when no issuer is configured, or discovery fails. Both are
   *   conditions a caller answers with 503 rather than a login page that cannot work.
   */
  fun relyingPartyFor(callbackPath: String): OidcRelyingParty =
    relyingParties.forRedirectUri(serviceBaseUrl.trimEnd('/') + callbackPath)
}
