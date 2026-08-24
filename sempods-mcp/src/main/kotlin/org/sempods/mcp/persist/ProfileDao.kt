package org.sempods.mcp.persist

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
import java.util.Date
import org.sempods.mcp.SempodsMcpCollections

/** One explicitly-created named profile for a user. The default profile is implicit (never stored). */
data class Profile(
  val user: String,
  val profile: String,
  val createdAt: Date,
)

/**
 * The per-user list of **named** profiles (M5). A profile is the coarse isolation bundle the
 * `(user, profile, pod)` key carries; named profiles are created explicitly through the web UI so
 * a profile exists (and can show its MCP URL) even before any pod is connected to it.
 *
 * The [PodKey.DEFAULT_PROFILE] is **implicit**: it always exists, is never written to the
 * collection, and is folded into [listForUser] / [exists] so callers never special-case it.
 */
/**
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `docs/persistence.md` §"Conventions" states.
 */
class ProfileDao(db: MongoDatabase, collectionName: String = SempodsMcpCollections.PROFILES) {

  private val profiles = db.getCollection(collectionName)

  init {
    profiles.createIndex(
      Indexes.ascending("user", "profile"),
      IndexOptions().unique(true),
    )
  }

  /**
   * Create a named profile; idempotent even under concurrent calls. Rather than check-then-insert
   * (which races: two callers both see "absent" then both insert), rely on the unique
   * `(user, profile)` index and treat a duplicate-key violation as the no-op success it represents.
   */
  fun create(user: String, profile: String) {
    if (profile == PodKey.DEFAULT_PROFILE) return
    try {
      profiles.insertOne(
        Document().apply {
          put("user", user)
          put("profile", profile)
          put("createdAt", Date())
        },
      )
    } catch (e: MongoWriteException) {
      // A concurrent create already inserted the same (user, profile) — idempotent no-op.
      if (ErrorCategory.fromErrorCode(e.error.code) != ErrorCategory.DUPLICATE_KEY) throw e
    }
  }

  /** All profile names for the user, with the implicit default first. */
  fun listForUser(user: String): List<String> {
    val named = profiles.find(Filters.eq("user", user))
      .map { it.getString("profile") }
      .toList()
      .sorted()
    return listOf(PodKey.DEFAULT_PROFILE) + named.filter { it != PodKey.DEFAULT_PROFILE }
  }

  /** Whether the user may address this profile — true for the default and for any created one. */
  fun exists(user: String, profile: String): Boolean =
    profile == PodKey.DEFAULT_PROFILE ||
      profiles.find(keyFilter(user, profile)).firstOrNull() != null

  // TODO: M5+ — profile delete/rename in the UI (and the cascade onto mcp.connections /
  //  mcp.podTokens for that (user, profile)). M5 ships create + switch only.
  fun delete(user: String, profile: String) {
    if (profile == PodKey.DEFAULT_PROFILE) return
    profiles.deleteOne(keyFilter(user, profile))
  }

  private fun keyFilter(user: String, profile: String) = Filters.and(
    Filters.eq("user", user),
    Filters.eq("profile", profile),
  )
}
