package org.sempods.auth.core

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Refresh tokens with rotation (OAuth 2.1), for an authorization server that issues its own.
 *
 * Two services do this and did it twice. What differs between them is **who a token belongs to** —
 * a pod, a person and a client on the pod server; a user, a profile and a client on the hosted MCP
 * service — and that is the type parameter. Everything else is the mechanism, and losing any part
 * of it is silent:
 *
 * - **The token is not stored.** Only its SHA-256, so a database dump holds nothing usable.
 * - **Rotation is a conditional `updateOne`.** Exactly one of N concurrent exchanges of the same
 *   token wins; every other one is told it lost, which is the replay signal.
 * - **Reuse revokes the family.** If a client rotated A → B and something replays A, both die, so
 *   the thief's in-flight B is worthless.
 * - **Expiry is a TTL index**, and rotated-but-unexpired rows stay: the reuse-detection window is
 *   the token's natural lifetime rather than a shorter one.
 *
 * [OWNER] is carried by two lambdas rather than a map, so the fields keep their types and their
 * order. The order matters: a row is written as `_id`, `tokenHash`, `familyId`, **the owner's
 * fields**, `scopes`, `issuedAt`, `expiresAt`, `rotatedAt`, `revokedAt`, and both collections that
 * exist were already laid out that way — the owner block sits between `familyId` and `scopes` in
 * each. One writer therefore reproduces both, which is what let live collections move onto this
 * class without rewriting a stored document.
 *
 * Not built on [OneTimeStore], though both hash their key: that one is consumed once and gone,
 * this one is a mutating chain whose spent links have to stay readable.
 *
 * @param collectionName each service keeps its own — the tokens of one are meaningless to another.
 * @param ownerIndexFields the compound index over the owner, which is **not** the owner's field
 *   list: the pod server keys by `(podId, clientId, webId)` while storing `podName` beside them,
 *   because the name is carried for the caller's benefit and never queried on.
 * @param writeOwner / @param readOwner the only things that know what an owner is. A row missing an
 *   owner field fails loudly, the way [readInstant] does and for the same reason — the row cannot
 *   serve the exchange it belongs to, and inventing a value would hand out authority nobody granted.
 * @param clock the "now" of issuing, rotation and revocation. A parameter because the interesting
 *   cases are about time and testing them by waiting is not testing them.
 */
class RefreshTokenStore<OWNER>(
  db: MongoDatabase,
  collectionName: String,
  ownerIndexFields: List<String>,
  private val writeOwner: Document.(OWNER) -> Unit,
  private val readOwner: Document.() -> OWNER,
  private val clock: () -> Instant = Instant::now,
) {

  private val tokens = db.getCollection(collectionName)

  init {
    require(ownerIndexFields.isNotEmpty()) { "an owner with no indexed field cannot be revoked in bulk" }
    tokens.createIndex(Indexes.ascending(Field.TOKEN_HASH), IndexOptions().unique(true))
    tokens.createIndex(Indexes.ascending(Field.FAMILY_ID))
    tokens.createIndex(Indexes.ascending(ownerIndexFields))
    // `expireAfterSeconds = 0` means "expire at the instant stored in the field", which is what
    // makes `expiresAt` the deadline rather than an offset from it. `createIndex` throws
    // `IndexOptionsConflict` when an existing index's options differ, so a wrong expiry here does
    // not silently prune — it refuses to boot.
    tokens.createIndex(Indexes.ascending(Field.EXPIRES_AT), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
  }

  /**
   * A stored token, as it is on disk — everything but the row's `_id`.
   *
   * That one field stays behind because nothing above the store has ever had a use for it: a row is
   * found by [tokenHash], a family is revoked by [familyId], and a service's own bulk revocations go
   * through [revokeWhere] over the owner's fields. Carrying the key out would put the collection's
   * identifier type in this class's signature to answer a question no caller asks.
   *
   * [issuedAt] and [expiresAt] are millisecond-truncated at mint, so the rest really is the row and
   * not an in-memory value that will read back slightly different — BSON's date type has nowhere to
   * put the nanoseconds, and `commons-mongo` documents the same trap.
   */
  data class Token<OWNER>(
    val tokenHash: String,
    val familyId: String,
    val owner: OWNER,
    val scopes: Set<String>,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val rotatedAt: Instant? = null,
    val revokedAt: Instant? = null,
  )

  /** The plaintext, which exists only here and in the response, beside the row it was stored as. */
  data class Issued<OWNER>(val plaintext: String, val token: Token<OWNER>)

  enum class LookupState {
    ACTIVE,

    /**
     * No row carries this token's hash, and that covers **two** situations this store cannot tell
     * apart: a token that was never issued here, and one that expired and has since been reaped.
     * A log line reporting it must say so — see [EXPIRED].
     */
    NOT_FOUND,

    /**
     * The row is still on disk and its `expiresAt` has passed — reachable only between the expiry
     * instant and the next sweep of the TTL index, after which the same token reports [NOT_FOUND].
     * So which of the two an ordinary lapse produces is a matter of timing rather than of the
     * token, and the common case is [NOT_FOUND].
     *
     * That is deliberate: reporting the state reliably would mean keeping rows past their expiry
     * and giving up the TTL index as the sole reaper, which is a retention decision and not a
     * diagnostic one. The honest half is the log line.
     */
    EXPIRED,

    REVOKED,

    /** The token exists but was already exchanged — a replay. The caller must revoke the family. */
    REUSED,
  }

  /**
   * @param fingerprint a short prefix of the presented token's SHA-256, safe to log: it is what
   *   tells one failing token apart from a hundred thousand of them without naming the credential.
   *   Present on every state, including [LookupState.NOT_FOUND], which is the one with no [token]
   *   and therefore no family id to name. A **prefix** and not the digest, because the digest is
   *   this collection's lookup key — rule 1 in `docs/logging.md`.
   */
  data class Lookup<OWNER>(
    val state: LookupState,
    val token: Token<OWNER>? = null,
    val fingerprint: String,
  )

  /** The first token of a new family — the `authorization_code` exchange, where there is no predecessor. */
  fun issueNewFamily(
    owner: OWNER,
    scopes: Set<String>,
    ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  ): Issued<OWNER> = issue(owner, scopes, UUID.randomUUID().toString(), ttlSeconds)

  /** The successor to an existing token, as part of a rotation. The owner is the predecessor's. */
  fun issueInFamily(
    previous: Token<OWNER>,
    scopes: Set<String>,
    ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  ): Issued<OWNER> = issue(previous.owner, scopes, previous.familyId, ttlSeconds)

  private fun issue(owner: OWNER, scopes: Set<String>, familyId: String, ttlSeconds: Long): Issued<OWNER> {
    val plaintext = PLAINTEXT_PREFIX + Secrets.newSecret()
    val now = clock().truncatedTo(ChronoUnit.MILLIS)
    val token = Token(
      tokenHash = sha256Hex(plaintext),
      familyId = familyId,
      owner = owner,
      scopes = scopes,
      issuedAt = now,
      expiresAt = now.plusSeconds(ttlSeconds),
    )
    tokens.insertOne(token.toDocument())
    return Issued(plaintext, token)
  }

  /**
   * What the presented token is, in the order the answers exclude each other.
   *
   * The hash is computed once and its prefix travels out on [Lookup.fingerprint] — the query needs
   * it anyway, so a caller wanting to name the token in a log line costs nothing.
   */
  fun lookup(plaintext: String): Lookup<OWNER> {
    val tokenHash = sha256Hex(plaintext)
    val fingerprint = tokenHash.take(FINGERPRINT_LENGTH)
    val token = tokens.find(Filters.eq(Field.TOKEN_HASH, tokenHash)).first()?.toToken()
      ?: return Lookup(LookupState.NOT_FOUND, fingerprint = fingerprint)
    return when {
      token.revokedAt != null -> Lookup(LookupState.REVOKED, token, fingerprint)
      token.rotatedAt != null -> Lookup(LookupState.REUSED, token, fingerprint)
      token.expiresAt.isBefore(clock()) -> Lookup(LookupState.EXPIRED, token, fingerprint)
      else -> Lookup(LookupState.ACTIVE, token, fingerprint)
    }
  }

  /**
   * Atomically marks the token rotated. `false` means a concurrent caller already did (or the token
   * is revoked, or absent) — a reuse event the caller must treat as a hostile replay.
   *
   * **The two `eq(field, null)` filters are load-bearing and are not what they look like.** A live
   * token carries neither `rotatedAt` nor `revokedAt` — the `commons-mongo` helpers omit a null
   * field rather than writing BSON `null` — so these filters have always been matching *missing*
   * fields. `{field: null}` is the one equality that does, which is why the filter works at all and
   * why it must not be "corrected" to `exists(false)`: that would stop matching a row whose field
   * was explicitly set to `null` by a migration or by hand, and report a legitimate rotation as a
   * replay. `RefreshTokenStoreTest` measures both halves against a real server.
   *
   * `updateOne`, because `tokenHash` is unique and there is never a second row to reach.
   */
  fun markRotated(tokenHash: String): Boolean =
    tokens.updateOne(
      Filters.and(
        Filters.eq(Field.TOKEN_HASH, tokenHash),
        Filters.eq(Field.ROTATED_AT, null),
        Filters.eq(Field.REVOKED_AT, null),
      ),
      Updates.set(Field.ROTATED_AT, clock()),
    ).modifiedCount > 0L

  /**
   * Revokes every token in the family — the answer to reuse detection.
   *
   * `updateMany` is not optional: without it only the first row of the family is touched, which is
   * the one row the thief is not using. Already-revoked rows are not excluded, because re-setting
   * `revokedAt` is a harmless no-op.
   */
  fun revokeFamily(familyId: String): Long = revokeWhere(Filters.eq(Field.FAMILY_ID, familyId))

  /**
   * Revokes every row matching [filter] — the hook for whatever "belongs to" means to a service.
   *
   * The pod server revokes by person-and-client when a consent is withdrawn, and everything but
   * the successor's family when a consent replaces it; both are its domain and neither is
   * expressible here, so they are filters it builds over its own owner fields plus [Field.FAMILY_ID].
   */
  fun revokeWhere(filter: Bson): Long =
    tokens.updateMany(filter, Updates.set(Field.REVOKED_AT, clock())).modifiedCount

  /**
   * The families of the still-live rows matching [filter] — the ids, not the rows.
   *
   * For a caller that has to decide *what to revoke before it inserts*: a filter evaluated after
   * the insert would also select what a concurrent caller inserted in the meantime, and two such
   * callers would then revoke each other. Naming the families first makes the sweep unable to
   * reach anything minted after it was measured. `revokedAt` is compared to `null` for the reason
   * [markRotated] states — a live row does not carry the field at all.
   */
  fun familiesWhere(filter: Bson): Set<String> =
    tokens.distinct(
      Field.FAMILY_ID,
      Filters.and(filter, Filters.eq(Field.REVOKED_AT, null)),
      String::class.java,
    ).toSet()

  /**
   * The owners of the still-live rows matching [filter], each named once.
   *
   * For a service asking *whose* credentials are still standing — a question about the owner, which
   * only the service can then answer against its own records. Rotated rows share their family's
   * owner, so the result is far shorter than the rows it is read from.
   */
  fun ownersWhere(filter: Bson): Set<OWNER> =
    tokens.find(Filters.and(filter, Filters.eq(Field.REVOKED_AT, null)))
      .map { it.readOwner() }
      .toSet()

  /**
   * Hard-deletes every row matching [filter].
   *
   * For the cascade where revocation is moot because the thing the tokens name is gone. Deleting
   * rather than revoking is deliberate there: the rows have no remaining purpose and the audit
   * trail lives in the application log.
   */
  fun deleteWhere(filter: Bson): Long = tokens.deleteMany(filter).deletedCount

  /** Every row of the family, spent ones included. Diagnostics and tests — no request path reads it. */
  fun findByFamily(familyId: String): List<Token<OWNER>> =
    tokens.find(Filters.eq(Field.FAMILY_ID, familyId)).map { it.toToken() }.toList()

  /**
   * The row, in the order it is written — and the `_id` is minted here rather than left to the
   * driver on purpose. Field order is the encoder's (`sempods-commons-mongo/docs/document-contract.md` §"Field order"), and
   * both live collections carry `_id` first; a driver-generated key would hand that placement to a
   * code path this file does not control. Called once, at insert.
   */
  private fun Token<OWNER>.toDocument(): Document = Document()
    .putNotNull(Field.ID, ObjectId())
    .putNotNull(Field.TOKEN_HASH, tokenHash)
    .putNotNull(Field.FAMILY_ID, familyId)
    .apply { writeOwner(owner) }
    .putStrings(Field.SCOPES, scopes)
    .putInstant(Field.ISSUED_AT, issuedAt)
    .putInstant(Field.EXPIRES_AT, expiresAt)
    .putInstant(Field.ROTATED_AT, rotatedAt)
    .putInstant(Field.REVOKED_AT, revokedAt)

  private fun Document.toToken(): Token<OWNER> = Token(
    tokenHash = getString(Field.TOKEN_HASH),
    familyId = getString(Field.FAMILY_ID),
    owner = readOwner(),
    // Empty is the common case rather than an edge one wherever context permissions are resolved
    // per request: only a feature scope puts an array on the row, and an empty one is not written.
    scopes = getStringSet(Field.SCOPES),
    issuedAt = readInstant(Field.ISSUED_AT),
    expiresAt = readInstant(Field.EXPIRES_AT),
    rotatedAt = getInstant(Field.ROTATED_AT),
    revokedAt = getInstant(Field.REVOKED_AT),
  )

  /**
   * Both timestamps have been non-null since either collection existed, and failing loudly beats
   * defaulting: an `expiresAt` invented at read time hands out either a token the TTL index is
   * about to delete, or one that never expires.
   */
  private fun Document.readInstant(field: String): Instant = checkNotNull(getInstant(field)) {
    "refresh token without $field: ${getObjectId(Field.ID)}"
  }

  /** The shared field names. The owner's own are the owner codec's, which is where they belong. */
  object Field {
    const val ID = "_id"
    const val TOKEN_HASH = "tokenHash"
    const val FAMILY_ID = "familyId"
    const val SCOPES = "scopes"
    const val ISSUED_AT = "issuedAt"
    const val EXPIRES_AT = "expiresAt"
    const val ROTATED_AT = "rotatedAt"
    const val REVOKED_AT = "revokedAt"
  }

  companion object {

    /** Lets a log scraper tell refresh tokens apart from access tokens at a glance. */
    private const val PLAINTEXT_PREFIX = "rt_"

    /**
     * 12 hex characters — 48 bits of the digest. Long enough that two tokens in one log file do
     * not share one, short enough that it is a discriminator rather than the key a row is found
     * by, which the full 64 characters would be.
     */
    const val FINGERPRINT_LENGTH: Int = 12

    /** 90 days — long enough that a typical desktop or CLI client never hits it, short enough to bound exposure. */
    const val DEFAULT_TTL_SECONDS: Long = 90L * 24 * 60 * 60
  }
}
