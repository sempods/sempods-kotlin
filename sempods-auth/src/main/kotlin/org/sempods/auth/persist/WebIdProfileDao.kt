package org.sempods.auth.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import org.sempods.auth.SempodsAuthCollections
import java.util.Date

class WebIdProfileDao(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, which is the shape the
   * other services' DAOs already have.
   */
  @Inject
  constructor(db: MongoDatabase) : this(db, SempodsAuthCollections.WEB_ID_PROFILES)

  private val profiles = db.getCollection(collectionName)

  init {
    // Index on linkedIdentities for reverse lookup (sameAs resolution)
    profiles.createIndex(
      Indexes.ascending("linkedIdentities"),
      IndexOptions().sparse(true),
    )
  }

  fun findByUri(uri: String): WebIdProfile? =
    profiles.find(Filters.eq("_id", uri)).firstOrNull()?.toProfile()

  /**
   * Returns all profiles that have [uri] in their linkedIdentities.
   * Used for sameAs resolution: given one WebID URI, find all linked profiles.
   */
  fun findByLinkedIdentity(uri: String): List<WebIdProfile> =
    profiles.find(Filters.eq("linkedIdentities", uri)).map { it.toProfile() }.toList()

  fun insert(profile: WebIdProfile) {
    profiles.insertOne(profile.toDocument())
  }

  /** Insert or replace the profile with matching _id. Used by the login flow. */
  fun upsert(profile: WebIdProfile) {
    profiles.replaceOne(
      Filters.eq("_id", profile.id),
      profile.toDocument(),
      ReplaceOptions().upsert(true),
    )
  }

  private fun WebIdProfile.toDocument() = Document().apply {
    put("_id", id)
    put("namespace", namespace.name)
    put("displayName", displayName)
    put("createdAt", createdAt)
    if (linkedIdentities.isNotEmpty()) {
      put("linkedIdentities", linkedIdentities)
    }
  }

  private fun Document.toProfile() = WebIdProfile(
    id = getString("_id"),
    namespace = WebIdNamespace.valueOf(getString("namespace")),
    displayName = getString("displayName") ?: "",
    createdAt = getDate("createdAt") ?: Date(),
    linkedIdentities = getList("linkedIdentities", String::class.java) ?: emptyList(),
  )
}
