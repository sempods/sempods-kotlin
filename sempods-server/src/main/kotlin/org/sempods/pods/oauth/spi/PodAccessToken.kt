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
 * The JWT claim a service-client token names its registration in: the row id, which a registration
 * removed and made again under the same `client_id` does not share. Absent on every other token.
 */
const val SERVICE_REGISTRATION_CLAIM: String = "sempods_registration_id"

/**
 * What a verified pod bearer says about its caller. Server-side policy — which contexts the caller
 * may reach — is deliberately *not* in here; it is resolved per request from the grant store, see
 * [org.sempods.pods.grants.PodAuthorizer].
 *
 * [sub] is the WebID the person signed in as, and is null exactly on a service-client token, where
 * there is no person. The [init] block holds that invariant so an implementation of the authorizer
 * seam can rely on it instead of re-deriving it.
 */
data class PodAccessToken @JvmOverloads constructor(
  val clientId: String,
  val sub: String?,
  val clientType: String?,
  /** Raw `scope` ∪ `scp`, unvalidated — sanitizing them is the authorizer's job. */
  val scopeValues: Set<String>,
  val jti: String?,
  val issuedAt: Instant?,
  /**
   * The registration a service-client token was issued for ([SERVICE_REGISTRATION_CLAIM]); `null`
   * on any other token, and on one a server from before the claim issued.
   */
  val registrationId: String? = null,
) {

  val isServiceClient: Boolean get() = clientType == SERVICE_CLIENT_TYPE

  init {
    require(isServiceClient || sub != null) { "a non-service pod token must carry a sub" }
  }
}
