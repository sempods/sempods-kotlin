package org.sempods.auth

import com.google.inject.Inject
import com.google.inject.name.Named
import org.sempods.SempodsConfig
import org.sempods.auth.core.DidWeb
import org.sempods.auth.core.HttpTransport
import org.sempods.auth.core.OidcRelyingParty
import org.sempods.auth.core.OidcRelyingPartyCache

/**
 * The id-server, as this pod server's OpenID Provider.
 *
 * The client identifier is this server's own origin (`did:web:<host>`). Nothing is registered
 * anywhere: the id-server permits a redirect address only on the origin the identifier names,
 * which is what stands in for a client secret. A host-root `did:web:` covers every path on that
 * host, so the per-pod callback addresses below are all permitted by the one identifier.
 *
 * Per pod because JAX-RS routes this server's whole auth surface under `{pod}/_system/auth`, and
 * a callback outside that prefix would need a second resource class for no gain. What is shared,
 * and worth being, is the discovered metadata: one round trip for the server, not one per pod —
 * which is [OidcRelyingPartyCache]'s job, since a pod here is exactly one redirect address.
 */
class PodIdentityProvider @Inject constructor(
  @param:Named("idBaseUrl") idBaseUrl: String,
  config: SempodsConfig,
  /**
   * Every call to the id-server rides this, discovery and JWKS included. Injected rather than
   * built here so a test can answer without a socket — and so nothing in the flow can quietly
   * open its own connection past whatever policy a deployment put on the client.
   */
  transport: HttpTransport,
) {

  private val apiBaseUrl = config.apiBaseUrl.trimEnd('/')

  /** This server's client identifier at the id-server. */
  val clientId: String = DidWeb.clientId(apiBaseUrl)

  private val relyingParties = OidcRelyingPartyCache(idBaseUrl, clientId, transport)

  /** Where the id-server sends the browser back for a login started at [pod]. */
  fun callbackUri(pod: String): String = "$apiBaseUrl/$pod$CALLBACK_SUFFIX"

  /**
   * @throws IllegalStateException when discovery fails — a condition a caller answers by telling
   *   the user that sign-in is unavailable, not by sending them to a login page that cannot work.
   */
  fun relyingParty(pod: String): OidcRelyingParty = relyingParties.forRedirectUri(callbackUri(pod))

  companion object {
    /** Appended to `{apiBaseUrl}/{pod}`. Matches the JAX-RS route on `PodAuthEndpoint`. */
    const val CALLBACK_SUFFIX = "/_system/auth/oidc/callback"
  }
}
