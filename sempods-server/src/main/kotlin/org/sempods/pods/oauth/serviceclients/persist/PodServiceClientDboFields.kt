package org.sempods.pods.oauth.serviceclients.persist

internal object PodServiceClientDboFields {
  // Mongo's own key, like PodDboFields.id / UserDbo's — Morphia maps @Id onto `_id`.
  const val id = "_id"
  const val podId = "podId"
  const val clientId = "clientId"
  const val secretHash = "secretHash"
  const val scopes = "scopes"
  const val label = "label"
  const val createdAt = "createdAt"
  const val lastUsedAt = "lastUsedAt"
  const val grantsVersion = "grantsVersion"
  const val grantsChangedAt = "grantsChangedAt"
  const val grantsChangedBy = "grantsChangedBy"
  const val redirectUris = "redirectUris"
  const val pendingUntil = "pendingUntil"
}
