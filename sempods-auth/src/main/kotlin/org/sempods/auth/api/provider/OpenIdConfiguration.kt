package org.sempods.auth.api.provider

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.ResponseMode
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.openid.connect.sdk.SubjectType
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import java.net.URI

/**
 * The OpenID Provider metadata document (OIDC Discovery 1.0 §3), served at
 * `/.well-known/openid-configuration`.
 *
 * This is what makes the endpoint paths below an implementation detail rather than a contract: a
 * client reads `authorization_endpoint` here instead of assuming `/authorize`. Nothing in the repo
 * published one until now — every consumer built the pod's URLs by string concatenation, which is
 * why moving an endpoint has so far meant editing three services.
 *
 * The keys are stated rather than derived from a library so that what is advertised is what is
 * actually served. RFC 8414 §2 asks for exactly that honesty, and the pod's metadata carries the
 * same note about `client_credentials`: advertise the grant you implement, not the one you wish
 * you did.
 */
object OpenIdConfiguration {

  const val AUTHORIZATION_PATH = "/authorize"
  const val TOKEN_PATH = "/token"
  const val JWKS_PATH = "/.well-known/jwks.json"
  const val DISCOVERY_PATH = "/.well-known/openid-configuration"

  /** `openid` is required for an `id_token` at all; the rest is what this provider actually returns. */
  val SUPPORTED_SCOPES = listOf("openid")

  /**
   * @param issuer already normalised — `SempodsAuthConfig.idBaseUrl` guarantees no trailing slash.
   *   Trimming again here would hide a mismatch rather than prevent one: the token's `iss` is
   *   built from the same value in `JwtIssuer`, and an OIDC client compares the two byte for byte.
   */
  /**
   * The document as it goes on the wire, built by the SDK's own metadata type.
   *
   * Not a hand-assembled map: `OIDCProviderMetadata` knows which members are required and how each
   * is spelled, and it is the same class a client uses to read the result — so a document this
   * service can produce is one the SDK can parse, checked by construction rather than by hoping.
   */
  fun documentJson(issuer: String): String {
    val metadata = OIDCProviderMetadata(
      Issuer(issuer),
      listOf(SubjectType.PUBLIC),
      URI("$issuer$JWKS_PATH"),
    ).apply {
      authorizationEndpointURI = URI("$issuer$AUTHORIZATION_PATH")
      tokenEndpointURI = URI("$issuer$TOKEN_PATH")
      responseTypes = listOf(ResponseType.CODE)
      grantTypes = listOf(GrantType.AUTHORIZATION_CODE)
      responseModes = listOf(ResponseMode.QUERY)
      scopes = Scope(*SUPPORTED_SCOPES.toTypedArray())
      idTokenJWSAlgs = listOf(JWSAlgorithm.RS256)
      codeChallengeMethods = listOf(CodeChallengeMethod.S256)
      // Clients are `did:web:` static identities: an origin, no secret, nothing registered. The
      // redirect address has to sit on the origin the identifier names, which is what stands in
      // for a client secret here.
      tokenEndpointAuthMethods = listOf(ClientAuthenticationMethod.NONE)
      // Sempods-specific, advertised so a consumer knows to expect them rather than discovering
      // them in a token. A standard client ignores both.
      claims = listOf("iss", "sub", "aud", "exp", "iat", "nonce", "webid", "also_known_as")
    }
    return metadata.toJSONObject().toJSONString()
  }
}
