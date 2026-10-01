package org.sempods.pods.oauth

import java.time.Instant

/** A client registered through RFC 7591, as [DynamicClientStore] hands it out. */
data class DynamicClient(
  val clientId: String,
  val redirectUris: Set<String>,
  val clientName: String?,
  val clientUri: String?,
  val logoUri: String?,
  val softwareId: String?,
  val softwareVersion: String?,
  val contacts: List<String>,
  val tosUri: String?,
  val policyUri: String?,
  val registeredAt: Instant,
  /** The DCR request body as it arrived. */
  val rawRequest: Map<String, Any?>,
  /** The `registeredAt` of the row [DynamicClientStore.register] deduplicated to; `null` when it inserted a row. */
  val deduplicatedFromRegisteredAt: Instant? = null,
)
