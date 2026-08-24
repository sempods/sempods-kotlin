package org.sempods.auth.core

import com.nimbusds.jose.util.JSONObjectUtils

/**
 * What an OpenID Provider says about itself (OIDC Discovery 1.0 §3).
 *
 * Read rather than assumed. Every consumer in this repository has so far built a provider's URLs
 * by string concatenation, which is why moving an endpoint has meant editing several services —
 * and why the pod server's browser SDK carries a comment naming the one place to change once
 * metadata-driven endpoints land. This is that.
 *
 * @param issuer the value a token's `iss` must equal, byte for byte. A provider that advertises
 *   one string and signs another is misconfigured, and the mismatch is worth catching here rather
 *   than as a rejected token later.
 */
data class OidcProviderMetadata(
  val issuer: String,
  val authorizationEndpoint: String,
  val tokenEndpoint: String,
  val jwksUri: String,
) {

  companion object {

    /**
     * @throws IllegalArgumentException when a member this client needs is missing. A provider
     *   without a token endpoint is not one this code can use, and failing at discovery says so
     *   where it can be acted on — at startup or at the first login, not at the exchange.
     */
    fun parse(json: String): OidcProviderMetadata {
      val document = runCatching { JSONObjectUtils.parse(json) }.getOrElse {
        throw IllegalArgumentException("provider metadata is not JSON: ${it.message}")
      }
      fun required(key: String): String =
        (document[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
          ?: throw IllegalArgumentException("provider metadata has no '$key'")

      return OidcProviderMetadata(
        issuer = required("issuer"),
        authorizationEndpoint = required("authorization_endpoint"),
        tokenEndpoint = required("token_endpoint"),
        jwksUri = required("jwks_uri"),
      )
    }

    /** Where the document lives for a given issuer (OIDC Discovery 1.0 §4). */
    fun discoveryUrl(issuer: String): String =
      issuer.trimEnd('/') + OpenIdDiscovery.WELL_KNOWN_PATH
  }
}

/** The one path OIDC fixes; everything else a provider serves is named in the document. */
object OpenIdDiscovery {
  const val WELL_KNOWN_PATH = "/.well-known/openid-configuration"
}
