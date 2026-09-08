package org.sempods.mcp.persist.oauth

import com.mongodb.MongoCommandException
import com.mongodb.MongoServerException
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
import org.bson.conversions.Bson
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
  collectionName: String = SempodsMcpCollections.OAUTH_CLIENT_REGISTRATIONS,
) {

  private val clients = db.getCollection(collectionName)

  init {
    clients.createIndex(
      Indexes.ascending("profile", "clientId"),
      IndexOptions().unique(true),
    )
    createFingerprintIndex(collectionName)
  }

  /**
   * The index that makes the dedup in [findOrCreate] a constraint rather than a lookup.
   *
   * It carries a name of its own, where the other takes MongoDB's default, so that it is built
   * *beside* a predecessor over the same two fields rather than conflicting with it: a deployment
   * that has run before this one holds exactly such a predecessor and boots with nobody touching
   * it. Build first and drop second, never the other way round — `sempods-server`'s
   * `DcrFingerprintIndex` carries why.
   *
   * What a gap already produced is the one thing left over: the build refuses duplicate rows, and
   * clearing those is an operator's, per `AGENTS.md` §"Deployment stance".
   */
  private fun createFingerprintIndex(collectionName: String) {
    try {
      clients.createIndex(FINGERPRINT_KEYS, IndexOptions().name(FINGERPRINT_UNIQUE).unique(true))
    } catch (e: MongoServerException) {
      // A build that trips over duplicates arrives as a write failure and not as a command one, so
      // the type here is the supertype of both and the code is what tells them apart.
      if (e.code != DUPLICATE_KEY) throw e
      throw IllegalStateException(
        "cannot make (profile, fingerprint) unique on $collectionName — duplicate rows still share " +
          "one fingerprint. Delete all but the newest of each group; the AI client whose row goes " +
          "registers again on its next connect.",
        e,
      )
    }
    val predecessor = clients.listIndexes().firstOrNull {
      it.get("key", Document::class.java)?.keys?.toList() == listOf("profile", "fingerprint") &&
        it.getString("name") != FINGERPRINT_UNIQUE
    }?.getString("name") ?: return
    try {
      clients.dropIndex(predecessor)
    } catch (alreadyGone: MongoCommandException) {
      // 27 = IndexNotFound: another replica dropped it first. The constraint is built either way,
      // and this is only the tidying after it.
      if (alreadyGone.errorCode != INDEX_NOT_FOUND) throw alreadyGone
    }
  }

  /**
   * The registration this profile holds under [candidate]'s fingerprint — [candidate] itself when
   * it is the one that lands.
   *
   * One logical client is one `client_id`, and it takes both halves to hold that. The lookup
   * catches the ordinary case, a client with no persistent client-state re-registering on
   * reconnect. The unique index catches the pair that looked at the same moment: the digest
   * carries nothing that tells two reconnects in the same second apart, so both are told the
   * client is unknown and both insert. Whoever loses re-reads and gets the winner's row, which it
   * cannot tell from an ordinary dedup hit, because it is one.
   */
  fun findOrCreate(candidate: DcrClient): DcrClient =
    findByFingerprint(candidate.profile, candidate.fingerprint)
      ?: if (create(candidate)) {
        candidate
      } else {
        checkNotNull(findByFingerprint(candidate.profile, candidate.fingerprint)) {
          "insert refused as a duplicate fingerprint, but no row holds it (profile=${candidate.profile})"
        }
      }

  /**
   * Inserts a registration, or answers `false` when the unique index refuses it. Separate from
   * [findOrCreate], which is the only sound way to call it, so that a test can assert the refusal
   * itself rather than what is made of it.
   */
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

  private companion object {

    val FINGERPRINT_KEYS: Bson = Indexes.ascending("profile", "fingerprint")

    /** Named, where the other index takes MongoDB's default — [createFingerprintIndex] says why. */
    const val FINGERPRINT_UNIQUE = "profile_1_fingerprint_1_unique"

    const val INDEX_NOT_FOUND = 27
    const val DUPLICATE_KEY = 11000
  }

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
