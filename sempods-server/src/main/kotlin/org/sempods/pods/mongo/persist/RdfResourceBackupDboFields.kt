package org.sempods.pods.mongo.persist

internal object RdfResourceBackupDboFields {
  // Mongo's own key, like PodDboFields.id / OAuthSigningKeyDboFields.id. Spelled `_id` because
  // this collection is on the driver, which does not translate the way Morphia did.
  const val id = "_id"
  const val podId = "podId"
  const val resourceUri = "resourceUri"
  const val context = "context"
  const val nquads = "nquads"
  const val updatedAt = "updatedAt"
}
