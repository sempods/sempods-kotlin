package org.sempods.pods.oauth.serviceclients.persist

internal object PodServiceAuditLogDboFields {
  // Mongo's own key, spelled `_id` because this collection is on the driver, which does not
  // translate the way Morphia did — see OAuthSigningKeyDboFields.
  const val id = "_id"
  const val ts = "ts"
  const val podId = "podId"
  const val clientId = "clientId"
  const val operation = "operation"
  const val path = "path"
  const val statusCode = "statusCode"
  const val expiresAt = "expiresAt"
}
