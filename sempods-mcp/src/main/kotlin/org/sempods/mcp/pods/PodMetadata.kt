package org.sempods.mcp.pods

/**
 * The OAuth endpoints discovered for a pod (RFC 8414 authorization-server metadata), resolved
 * from the pod's protected-resource metadata (RFC 9728). This is what the service-as-client
 * needs to register, authorize, and exchange/refresh tokens against the pod.
 */
data class PodOAuthMetadata(
  /** The pod's AS issuer, e.g. `https://sempods.org/alice/_system/auth`. */
  val issuer: String,
  val authorizationEndpoint: String,
  val tokenEndpoint: String,
  /**
   * The RFC 7591 DCR endpoint, or null for a pod that publishes no AS metadata / no DCR (the
   * sempods-convention + static `did:web` client path — see [PodOAuthClient.discoverMetadata]).
   */
  val registrationEndpoint: String?,
  val jwksUri: String?,
  /**
   * What the pod says a client may put in `scope`: `scopes_supported` from its AS metadata
   * (RFC 8414 §2) where it publishes that member, and from its protected-resource metadata
   * (RFC 9728 §2) otherwise. Empty for a pod that publishes neither.
   *
   * The authorization server wins because it is the party that answers `invalid_scope`. Both
   * members are optional and both RFCs allow a server to leave supported values out of them, so
   * neither list proves a scope is refused — which is why this is read as permission to ask rather
   * than as a contract, and why the fallback is "ask for nothing" rather than "ask anyway".
   *
   * Read once, when the authorize URL is built. `PodConnectStateStore` deliberately does not carry
   * it across the redirect: what comes back is a code to exchange, and the exchange asks for no
   * scopes.
   */
  val scopesSupported: Set<String> = emptySet(),
)

/** A pod token-endpoint response (authorization_code or refresh_token grant). */
data class PodTokenResponse(
  val accessToken: String,
  val tokenType: String,
  val expiresInSeconds: Long?,
  /** Pods rotate refresh tokens, so this is usually a NEW token on every refresh. */
  val refreshToken: String?,
  val scope: String?,
)
