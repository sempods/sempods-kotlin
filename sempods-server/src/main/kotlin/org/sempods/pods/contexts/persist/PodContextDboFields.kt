package org.sempods.pods.contexts.persist

internal object PodContextDboFields {
  // Mongo's own key, like PodDboFields.id / OAuthSigningKeyDboFields.id. Spelled `_id` because
  // this collection is on the driver, which does not translate the way Morphia did.
  const val id = "_id"
  const val podId = "podId"
  const val contextUri = "contextUri"
  const val label = "label"
  const val description = "description"
  const val isPublic = "isPublic"
  const val createdAt = "createdAt"
  const val createdBy = "createdBy"
}
