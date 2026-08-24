package org.sempods.auth.persist

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import org.bson.Document
import org.bson.types.ObjectId
import org.sempods.auth.SempodsAuthCollections
import org.sempods.auth.core.SigningKeyStore
import java.util.Date

/**
 * The identity service's signing keys, in `oauth.signingKeys`.
 *
 * The adapter, not the lifecycle — generating, choosing and publishing live in
 * [org.sempods.auth.core.SigningKeys]. What is here is the part that differs per service: which
 * database, which collection.
 *
 * The stored JWK carries its **private** parameters, so a process can both sign with it and
 * publish the public half. It is not encrypted at rest, consistent with the rest of this
 * deployment's secrets; encryption at rest is a deployment concern rather than an application one.
 *
 * `retiredAt` is written but never read yet. It is what makes rotation a change to `SigningKeys`
 * rather than a migration: a retired key stays in the JWKS so tokens it signed keep verifying
 * while they live, and stops being chosen for signing.
 */
class MongoSigningKeyStore(db: MongoDatabase) : SigningKeyStore {

  private val keys = db.getCollection(SempodsAuthCollections.OAUTH_SIGNING_KEYS)

  init {
    keys.createIndex(Indexes.ascending(FIELD_KID), IndexOptions().unique(true))
    keys.createIndex(Indexes.ascending(FIELD_CREATED_AT))
  }

  /** Newest first — [org.sempods.auth.core.SigningKeys] signs with the head and publishes all. */
  override fun loadAll(): List<String> =
    keys.find()
      .sort(Sorts.descending(FIELD_CREATED_AT))
      .mapNotNull { it.getString(FIELD_JWK) }
      .toList()

  /**
   * The bootstrap row goes into a singleton slot — the fixed [BOOTSTRAP_ID] — so of N replicas
   * booting against an empty collection exactly one insert survives. Losing is a duplicate-key
   * error and nothing else: `kid` is a fresh UUID, so `_id` is the only unique index a second
   * bootstrap can collide on.
   */
  override fun createInitial(jwk: String, kid: String, algorithm: String): Boolean = try {
    keys.insertOne(
      Document().apply {
        put(FIELD_ID, BOOTSTRAP_ID)
        put(FIELD_KID, kid)
        put(FIELD_ALGORITHM, algorithm)
        put(FIELD_JWK, jwk)
        put(FIELD_CREATED_AT, Date())
        put(FIELD_RETIRED_AT, null)
      },
    )
    true
  } catch (e: MongoWriteException) {
    if (ErrorCategory.fromErrorCode(e.error.code) != ErrorCategory.DUPLICATE_KEY) throw e
    false
  }

  private companion object {
    /**
     * Fixed `_id` of the bootstrap key row. All-zero, which is a value no generated ObjectId can
     * take — its leading four bytes are a Unix timestamp, so this one reads as 1970 and is
     * self-evidently synthetic.
     */
    private val BOOTSTRAP_ID = ObjectId("000000000000000000000000")

    private const val FIELD_ID = "_id"
    private const val FIELD_KID = "kid"
    private const val FIELD_ALGORITHM = "algorithm"
    private const val FIELD_JWK = "jwk"
    private const val FIELD_CREATED_AT = "createdAt"
    private const val FIELD_RETIRED_AT = "retiredAt"
  }
}
