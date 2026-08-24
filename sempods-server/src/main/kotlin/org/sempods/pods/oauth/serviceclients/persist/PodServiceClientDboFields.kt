package org.sempods.pods.oauth.serviceclients.persist

object PodServiceClientDboFields {
  // Mongo's own key, like PodDboFields.id / UserDbo's — Morphia maps @Id onto `_id`.
  const val id = "_id"
  const val podId = "podId"
  const val clientId = "clientId"
  const val secretHash = "secretHash"
  const val scopes = "scopes"
  const val label = "label"
  const val createdAt = "createdAt"
  const val lastUsedAt = "lastUsedAt"
}
