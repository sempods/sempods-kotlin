package org.sempods.pods.oauth.spi

import java.time.Instant

/**
 * Value of the JWT `client_type` claim on tokens issued through `client_credentials`. Absent on
 * authorization-code tokens.
 *
 * Here, beside [PodAccessToken], because the token's own [PodAccessToken.isServiceClient] reads it;
 * `PodTokenIssuer` only writes it.
 */
const val SERVICE_CLIENT_TYPE: String = "service"

/**
 * What a verified pod bearer says about its caller. Server-side policy — which contexts the caller
 * may reach — is deliberately *not* in here; it is resolved per request from the grant store, see
 * [org.sempods.pods.grants.PodAuthorizer].
 *
 * [sub] is the WebID the person signed in as, and is null exactly on a service-client token, where
 * there is no person. The [init] block holds that invariant so an implementation of the authorizer
 * seam can rely on it instead of re-deriving it.
 */
data class PodAccessToken(
  val clientId: String,
  val sub: String?,
  val clientType: String?,
  /** Raw `scope` ∪ `scp`, unvalidated — sanitizing them is the authorizer's job. */
  val scopeValues: Set<String>,
  val jti: String?,
  val issuedAt: Instant?,
) {

  val isServiceClient: Boolean get() = clientType == SERVICE_CLIENT_TYPE

  init {
    require(isServiceClient || sub != null) { "a non-service pod token must carry a sub" }
  }
}
