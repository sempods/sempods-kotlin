package org.sempods.mcp.persist.oauth

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import org.bson.Document
import java.util.Date
import org.sempods.mcp.SempodsMcpCollections

/**
 * One RFC 7591 Dynamic Client Registration: an AI client (Claude Desktop/Code/Web, ChatGPT,
 * …) that registered against the service to obtain a `client_id` for `/authorize`.
 *
 * Scoped by **profile** (not pod): the service is the resource. The profile path is the
 * variable segment that separates the first OAuth layer (AI client → service). Inserts are
 * fingerprint-deduped so a client that re-registers on every reconnect (no persistent
 * client-state) reuses its `client_id` and keeps consent anchored to one row.
 */
data class DcrClient(
  val clientId: String,
  /** The profile bundle this client registered against (default = "default"). */
  val profile: String,
  val redirectUris: Set<String>,
  val clientName: String?,
  val softwareId: String?,
  val softwareVersion: String?,
  /** SHA-256 dedup digest of (clientName, userAgent, profile, canonical redirect URIs). */
  val fingerprint: String,
  val userAgent: String?,
  val registeredAt: Date,
)

// TODO: /register is pre-auth (RFC 7591) and unthrottled — an anonymous flood can grow this
//  collection (disk nuisance, no tenant-isolation break; fingerprint dedup absorbs real clients'
//  re-register loops). Revisit with an IP-level limit if it becomes real — accepted residual risk
//  in docs/multi-tenancy-review.md (M6.4).
/**
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md` §"Conventions" states.
 */
class DcrClientDao(
  db: MongoDatabase,
  collectionName: String = SempodsMcpCollections.OAUTH_CLIENT_REGISTRATIONS,
) {

  private val clients = db.getCollection(collectionName)

  init {
    clients.createIndex(
      Indexes.ascending("profile", "clientId"),
      IndexOptions().unique(true),
    )
    clients.createIndex(Indexes.ascending("profile", "fingerprint"))
  }

  fun create(client: DcrClient) {
    clients.insertOne(client.toDocument())
  }

  /** Pod-... profile-scoped fingerprint lookup, newest match first (dedup on /register). */
  fun findByFingerprint(profile: String, fingerprint: String): DcrClient? =
    clients.find(
      Filters.and(Filters.eq("profile", profile), Filters.eq("fingerprint", fingerprint)),
    ).sort(Sorts.descending("registeredAt")).firstOrNull()?.toClient()

  /** Hot-path lookup from /authorize. A clientId only resolves within its issuing profile. */
  fun findByClientId(profile: String, clientId: String): DcrClient? =
    clients.find(
      Filters.and(Filters.eq("profile", profile), Filters.eq("clientId", clientId)),
    ).firstOrNull()?.toClient()

  private fun DcrClient.toDocument() = Document().apply {
    put("clientId", clientId)
    put("profile", profile)
    put("redirectUris", redirectUris.toList())
    put("clientName", clientName)
    put("softwareId", softwareId)
    put("softwareVersion", softwareVersion)
    put("fingerprint", fingerprint)
    put("userAgent", userAgent)
    put("registeredAt", registeredAt)
  }

  private fun Document.toClient() = DcrClient(
    clientId = getString("clientId"),
    profile = getString("profile"),
    redirectUris = (getList("redirectUris", String::class.java) ?: emptyList()).toSet(),
    clientName = getString("clientName"),
    softwareId = getString("softwareId"),
    softwareVersion = getString("softwareVersion"),
    fingerprint = getString("fingerprint"),
    userAgent = getString("userAgent"),
    registeredAt = getDate("registeredAt") ?: Date(),
  )
}
