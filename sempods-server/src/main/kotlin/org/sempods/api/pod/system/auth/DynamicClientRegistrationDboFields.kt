package org.sempods.api.pod.system.auth

object DynamicClientRegistrationDboFields {
  // Mongo's own key, like PodDboFields.id / OAuthSigningKeyDboFields.id. Spelled `_id` because
  // this collection is on the driver, which does not translate the way Morphia did.
  const val id = "_id"
  const val clientId = "clientId"
  const val registeredForPodId = "registeredForPodId"
  const val registeredForPodName = "registeredForPodName"
  const val registeredAt = "registeredAt"
  const val redirectUris = "redirectUris"
  const val clientName = "clientName"
  const val clientUri = "clientUri"
  const val logoUri = "logoUri"
  const val softwareId = "softwareId"
  const val softwareVersion = "softwareVersion"
  const val contacts = "contacts"
  const val tosUri = "tosUri"
  const val policyUri = "policyUri"
  const val rawRequest = "rawRequest"
  const val remoteAddr = "remoteAddr"
  const val userAgent = "userAgent"
  const val fingerprint = "fingerprint"
  const val lastAuthorizedAt = "lastAuthorizedAt"
  const val schemaVersion = "schemaVersion"
}
