package org.sempods.pods.grants.persist

object PodGrantDboFields {
  // Mongo's own key, like PodDboFields.id / OAuthSigningKeyDboFields.id. Spelled `_id` because
  // this collection is on the driver, which does not translate the way Morphia did.
  const val id = "_id"
  const val podId = "podId"
  const val scope = "scope"
  const val grantedBy = "grantedBy"
  const val grantedAt = "grantedAt"
  const val appId = "appId"
  const val webId = "webId"
  const val subjectUris = "subjectUris"
}
