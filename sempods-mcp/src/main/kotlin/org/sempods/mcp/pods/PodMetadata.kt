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
