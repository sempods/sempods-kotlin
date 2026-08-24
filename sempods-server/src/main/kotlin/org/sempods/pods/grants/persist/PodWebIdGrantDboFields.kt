package org.sempods.pods.grants.persist

object PodWebIdGrantDboFields {
  // Mongo's own key, like PodDboFields.id / OAuthSigningKeyDboFields.id. Spelled `_id` because
  // this collection is on the driver, which does not translate the way Morphia did.
  const val id = "_id"
  const val podId = "podId"
  const val webId = "webId"
  const val scope = "scope"
  const val grantedBy = "grantedBy"
  const val grantedAt = "grantedAt"
}
