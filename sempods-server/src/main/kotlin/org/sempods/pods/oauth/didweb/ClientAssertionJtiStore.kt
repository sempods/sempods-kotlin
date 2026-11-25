package org.sempods.pods.oauth.didweb

import com.google.inject.Inject
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.isDuplicateKey
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The `jti`s a `did:web` client has spent, in `oauth.clientAssertionJtis`, so that each assertion
 * is accepted once (RFC 7523 §3, item 7).
 *
 * [claim] is one insert, and the unique `_id` decides: of N requests carrying the same assertion,
 * exactly one inserts. A check followed by a write would let concurrent replays through on every
 * replica at once. `_id` is the SHA-256 of issuer and `jti`, so the identifiers a client chose are
 * not stored.
 *
 * A row lives for [ttl], which outlasts every assertion [DidWebClientAuthentication] accepts, so a
 * `jti` cannot become usable again while the assertion carrying it is still good.
 */
class ClientAssertionJtiStore @Inject internal constructor(db: MongoDatabase) {

  private val rows = db.getCollection(SempodsCollections.OAUTH_CLIENT_ASSERTION_JTIS)

  init {
    rows.createIndex(Indexes.ascending(FIELD_EXPIRES_AT), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
  }

  /** Spends [jti] for [issuer]: `true` the first time, `false` for every repeat. */
  internal fun claim(issuer: String, jti: String, ttl: Duration): Boolean = try {
    rows.insertOne(
      Document()
        .append("_id", sha256Hex("$issuer\n$jti"))
        .putInstant(FIELD_EXPIRES_AT, Instant.now().plus(ttl)),
    )
    true
  } catch (e: MongoWriteException) {
    if (e.isDuplicateKey()) false else throw e
  }

  private companion object {
    const val FIELD_EXPIRES_AT = "expiresAt"
  }
}
