package org.sempods.mcp.persist.oauth

import com.mongodb.DuplicateKeyException
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
import java.util.Date
import org.sempods.commons.mongo.isDuplicateKey
import org.sempods.mcp.SempodsMcpCollections

/**
 * One RFC 7591 Dynamic Client Registration: an AI client (Claude Desktop/Code/Web, ChatGPT,
 * …) that registered against the service to obtain a `client_id` for `/authorize`.
 *
 * Scoped by **profile** (not pod): the service is the resource. The profile path is the
 * variable segment that separates the first OAuth layer (AI client → service). A profile holds
 * at most one registration per fingerprint ([findOrCreate]), so a client that re-registers on
 * every reconnect (no persistent client-state) reuses its `client_id` and keeps consent anchored
 * to one row.
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
  private val collectionName: String = SempodsMcpCollections.OAUTH_CLIENT_REGISTRATIONS,
) {

  private val clients = db.getCollection(collectionName)

  init {
    clients.createIndex(
      Indexes.ascending("profile", "clientId"),
      IndexOptions().unique(true),
    )
    createFingerprintIndex()
  }

  /**
   * The index that makes the dedup in [findOrCreate] a constraint rather than a lookup.
   *
   * It carries a name of its own, where the other index takes MongoDB's default, so that it is
   * built *beside* an earlier non-unique index over the same two fields rather than conflicting
   * with it. A deployment that has run before this one holds exactly that, and boots with nobody
   * touching it; the index it no longer needs is one command whenever somebody is there anyway.
   *
   * Rows two registrations already split are the one thing a person has to answer for: the build
   * refuses them, and clearing data at boot is the pass `AGENTS.md` §"Deployment stance" rules out.
   */
  private fun createFingerprintIndex() {
    try {
      clients.createIndex(
        Indexes.ascending("profile", "fingerprint"),
        IndexOptions().name("profile_1_fingerprint_1_unique").unique(true),
      )
    } catch (duplicates: DuplicateKeyException) {
      throw IllegalStateException(
        "cannot make (profile, fingerprint) unique on $collectionName — rows still share one " +
          "fingerprint. Delete all but one of each group; the AI client whose row goes registers " +
          "again on its next connect.",
        duplicates,
      )
    }
  }

  /**
   * The registration this profile holds under [candidate]'s fingerprint — [candidate] itself when
   * it is the one that lands.
   *
   * It takes both halves to hold that. The lookup catches the ordinary reconnect; the unique
   * index catches the pair that looked at the same moment, because the digest carries nothing
   * that tells two reconnects in the same second apart and both are told the client is unknown.
   * Whoever loses re-reads and gets the winner's row, which it cannot tell from an ordinary dedup
   * hit, because it is one.
   */
  fun findOrCreate(candidate: DcrClient): DcrClient {
    findByFingerprint(candidate.profile, candidate.fingerprint)?.let { return it }
    if (create(candidate)) return candidate
    return checkNotNull(findByFingerprint(candidate.profile, candidate.fingerprint)) {
      "insert refused as a duplicate fingerprint, but no row holds it (profile=${candidate.profile})"
    }
  }

  /** Inserts a registration, or answers `false` when the unique index refuses it. */
  internal fun create(client: DcrClient): Boolean =
    try {
      clients.insertOne(client.toDocument())
      true
    } catch (e: MongoWriteException) {
      // `clientId` is an opaque random string, so the only unique index a duplicate can be hitting
      // is the fingerprint one.
      if (!e.isDuplicateKey()) throw e
      false
    }

  /** Profile-scoped fingerprint lookup, so `/register` can reuse a previously-issued `client_id`. */
  fun findByFingerprint(profile: String, fingerprint: String): DcrClient? =
    clients.find(
      Filters.and(Filters.eq("profile", profile), Filters.eq("fingerprint", fingerprint)),
    ).firstOrNull()?.toClient()

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
