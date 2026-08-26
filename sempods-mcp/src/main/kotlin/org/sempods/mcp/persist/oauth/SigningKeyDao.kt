package org.sempods.mcp.persist.oauth

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.crypto.SecretCipher
import org.bson.Document
import java.util.Date

/**
 * Persistent RSA signing key for the service's own OAuth access tokens (the tokens the
 * service issues to AI clients). Persisting the key means a deploy does not invalidate
 * every outstanding access token mid-session.
 *
 * The stored [jwk] is a full JSON Web Key including private parameters, so the process can
 * both sign (full JWK) and publish (public-only view via the JWKS endpoint). One active key
 * at a time; the schema already carries [retiredAt] so a later rotation is pure logic, not a
 * migration. The private [jwk] is stored **encrypted at rest** under the [SecretCipher]
 * envelope (the JWKS endpoint still publishes only public parameters).
 *
 * Unlike the token vault, an undecryptable signing key is **fatal, not skipped**: the service
 * cannot issue or verify its own access tokens without it, so [findAll] fails loudly with an
 * actionable message rather than silently minting a replacement (which would rotate every client
 * out on a mere `MCP_SECRET_KEY` typo). Recovery from a genuinely lost key is a documented manual
 * step (drop the `oauth.signingKeys` row → a fresh key is minted on next boot).
 */
data class SigningKey(
  val kid: String,
  val algorithm: String,
  /** Full JWK JSON including private parameters. */
  val jwk: String,
  val createdAt: Date,
  val retiredAt: Date? = null,
)

/**
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md` §"Conventions" states.
 */
class SigningKeyDao(
  db: MongoDatabase,
  private val cipher: SecretCipher,
  private val collectionName: String = SempodsMcpCollections.OAUTH_SIGNING_KEYS,
) {

  private val keys = db.getCollection(collectionName)

  init {
    keys.createIndex(Indexes.ascending("kid"), IndexOptions().unique(true))
    keys.createIndex(Indexes.descending("createdAt"))
  }

  fun create(key: SigningKey) {
    keys.insertOne(key.toDocument())
  }

  /**
   * Insert the **first** signing key atomically across replicas (M6.3): the bootstrap row carries
   * a fixed `_id`, so of N replicas racing an empty collection exactly one insert wins — the
   * losers get false (nothing written) and must re-read the winner's key. Without this, two
   * replicas behind a load balancer could each sign with a different key and tokens/JWKS would
   * fail cross-replica. Only the bootstrap uses the fixed `_id`; a later rotation appends via
   * [create] as usual.
   */
  fun createInitial(key: SigningKey): Boolean = try {
    keys.insertOne(key.toDocument().append("_id", BOOTSTRAP_ID))
    true
  } catch (e: MongoWriteException) {
    // The kid is a fresh UUID, so a duplicate key here can only be the bootstrap _id: another
    // replica won the race.
    if (ErrorCategory.fromErrorCode(e.error.code) != ErrorCategory.DUPLICATE_KEY) throw e
    false
  }

  /** All keys, newest first. */
  fun findAll(): List<SigningKey> =
    keys.find().sort(Sorts.descending("createdAt")).map { it.toSigningKey() }.toList()

  private fun SigningKey.toDocument() = Document().apply {
    put("kid", kid)
    put("algorithm", algorithm)
    put("jwk", cipher.encrypt(jwk))
    put("createdAt", createdAt)
    put("retiredAt", retiredAt)
  }

  private fun Document.toSigningKey() = SigningKey(
    kid = getString("kid"),
    algorithm = getString("algorithm"),
    // A key that will not decrypt is fatal (wrong/lost MCP_SECRET_KEY) — rethrow with an
    // actionable message rather than an opaque one.
    jwk = try {
      cipher.decrypt(getString("jwk"))
    } catch (e: IllegalArgumentException) {
      // The collection is named from the field rather than spelled out: this message tells an
      // operator which row to drop, and a message naming a collection that no longer exists sends
      // them to delete nothing while every restart keeps failing.
      throw IllegalStateException(
        "cannot decrypt the OAuth signing key (kid='${getString("kid")}') — MCP_SECRET_KEY is wrong " +
          "or was changed. Fix the key, or drop the $collectionName row to mint a fresh key " +
          "(outstanding client access tokens are then rejected until clients re-authenticate).",
        e,
      )
    },
    createdAt = getDate("createdAt") ?: Date(),
    retiredAt = getDate("retiredAt"),
  )

  companion object {
    /** Fixed `_id` of the bootstrap key row — the singleton slot the first-key race is decided on. */
    private const val BOOTSTRAP_ID = "bootstrap"
  }
}
