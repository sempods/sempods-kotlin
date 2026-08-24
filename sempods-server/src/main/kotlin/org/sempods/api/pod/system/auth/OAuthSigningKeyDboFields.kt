package org.sempods.api.pod.system.auth

object OAuthSigningKeyDboFields {
  // Mongo's own key, like PodDboFields.id / PodServiceClientDboFields.id. Spelled `_id` because
  // this collection is on the driver, which does not translate the way Morphia did.
  const val id = "_id"
  const val kid = "kid"
  const val algorithm = "algorithm"
  const val jwk = "jwk"
  const val createdAt = "createdAt"
  const val retiredAt = "retiredAt"
}
