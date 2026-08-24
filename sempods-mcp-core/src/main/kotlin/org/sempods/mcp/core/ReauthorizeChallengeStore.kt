package org.sempods.mcp.core

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The note a surface leaves itself when it answers `authorize(reauthorize=true)` with a 401.
 *
 * Two calls that the server cannot otherwise tell apart carry the same arguments:
 *
 *  1. The genuine incremental-authorization request — the caller wants the consent screen
 *     re-rendered to extend its grants. Must answer 401 + `WWW-Authenticate` to start the flow.
 *  2. The MCP client's automatic replay of (1) after the OAuth roundtrip, carrying the brand-new
 *     bearer with the unchanged body. Must answer with the idempotent success body — a second 401
 *     here surfaces to the user as "Authentication failed".
 *
 * The challenge records the bearer's `jti` and the moment it was written. A follow-up call is the
 * replay when it presents a **different** `jti` **issued at or after** that moment. Both halves are
 * needed: a different `jti` alone is satisfied by a parallel-session token the same user already
 * held in another tab or from a prior refresh-token rotation, which would silently skip the
 * consent screen the caller asked for.
 *
 * Durable rather than in-process, and that is the whole point of the shared version: the window is
 * five minutes wide and a deploy inside it used to cost the caller its consent roundtrip. It also
 * lets the confirmation call land on a different replica than the one that issued the challenge.
 *
 * The entry gates a loop-avoidance shortcut and mints nothing, so neither the `_id` nor the stored
 * `jti` is a secret: unlike an authorization code there is nothing here to redeem, which is why the
 * key is stored as itself rather than as a hash.
 *
 * @param collectionName each surface keeps its own — a challenge issued by one is meaningless to
 *   the other, the way `AuthorizationCodeStore` takes its collection.
 * @param clock the "now" of expiry and recording. A parameter because the interesting cases are
 *   about time (TTL, the second boundary on `iat`) and testing them by waiting is not testing them.
 */
class ReauthorizeChallengeStore(
  db: MongoDatabase,
  collectionName: String,
  private val ttl: Duration = DEFAULT_TTL,
  private val clock: () -> Instant = Instant::now,
) {

  private val challenges = db.getCollection(collectionName)

  init {
    challenges.createIndex(Indexes.ascending(FIELD_EXPIRES_AT), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
  }

  /**
   * Record that a 401 went out for [realm] / [clientId] / [sub] against the token identified by
   * [jti]. Last write wins for a key, the way the in-memory map it replaces behaved.
   *
   * [clientId] and [sub] are `null` for an **anonymous** challenge: the caller has no token yet, so
   * the OAuth flow the 401 triggers is what produces both. Such an entry is keyed by [realm] alone
   * and may be consumed by the first fresh authenticated token for that realm.
   *
   * @param realm which tenant the challenge belongs to — a pod name for the pod server, a profile
   *   for the hosted service. Compared on consumption, so a challenge from one cannot answer a call
   *   to another.
   */
  fun record(realm: String, clientId: String?, sub: String?, jti: String?) {
    val now = clock()
    val id = key(realm, clientId, sub)
    val doc = Document().apply {
      put("_id", id)
      putNotNull(FIELD_JTI, jti)
      put(FIELD_CHALLENGED_AT, now.epochSecond)
      putInstant(FIELD_EXPIRES_AT, now.plus(ttl))
    }
    try {
      challenges.replaceOne(Filters.eq("_id", id), doc, ReplaceOptions().upsert(true))
    } catch (e: MongoWriteException) {
      // Two concurrent records raced the upsert's insert on the same _id; the retry now matches the
      // winner's document and replaces it — last write wins, same as the map did.
      if (ErrorCategory.fromErrorCode(e.error.code) != ErrorCategory.DUPLICATE_KEY) throw e
      challenges.replaceOne(Filters.eq("_id", id), doc, ReplaceOptions().upsert(true))
    }
  }

  /**
   * `true` when this call is the post-OAuth replay of a pending challenge, which it consumes.
   * `false` means there is none, or the token presented cannot have come from the flow this
   * challenge started — either way the caller should issue a fresh challenge.
   *
   * An anonymous challenge is tried after the caller's own key, not instead of it: the call that
   * consumes one arrives **with** a `clientId` and `sub`, because acquiring them is what the flow
   * did. The second lookup is skipped only when it would repeat the first.
   */
  fun consumeIfReplay(
    realm: String,
    clientId: String?,
    sub: String?,
    currentJti: String?,
    currentIssuedAt: Instant?,
  ): Boolean {
    if (currentJti == null || currentIssuedAt == null) return false
    val own = key(realm, clientId, sub)
    val anonymous = key(realm, null, null)
    if (own != anonymous && consume(own, currentJti, currentIssuedAt)) return true
    return consume(anonymous, currentJti, currentIssuedAt)
  }

  /**
   * The whole replay predicate as one conditional `findOneAndDelete`, so exactly one of N
   * concurrent confirmation calls consumes the challenge and a non-matching one leaves it in place
   * for the proper replay still to come (or for the TTL reaper).
   */
  private fun consume(id: String, currentJti: String, currentIssuedAt: Instant): Boolean =
    challenges.findOneAndDelete(
      Filters.and(
        Filters.eq("_id", id),
        // The TTL reaper runs on its own schedule, so an expired row can outlive its expiry by
        // minutes. Nothing may act on one that has.
        Filters.gt(FIELD_EXPIRES_AT, Date.from(clock())),
        // "A different token id". An anonymous challenge stores no `jti` at all, and a missing
        // field satisfies `$ne` — which is correct: any fresh token is the one that flow produced.
        Filters.ne(FIELD_JTI, currentJti),
        // Second granularity, because JWT `iat` is whole seconds while recording is sub-second. A
        // flow completing inside the same second as the challenge is legitimate; an earlier second
        // means the token predates the challenge.
        Filters.lte(FIELD_CHALLENGED_AT, currentIssuedAt.epochSecond),
      ),
    ) != null

  /**
   * The three key parts joined. The separator cannot be forged: a realm is a pod name or a profile,
   * and both are restricted to `[a-z0-9-]` at their own gate (`SempodsUriBuilder.POD_NAME_REGEX`,
   * `ProfilePath`) — the same argument the hosted rate limiter's `"$profile|$user"` key makes. A
   * joined string rather than a composite `_id` document, whose field order would have to be
   * matched exactly on every lookup.
   */
  private fun key(realm: String, clientId: String?, sub: String?) =
    "$realm|${clientId.orEmpty()}|${sub.orEmpty()}"

  companion object {
    val DEFAULT_TTL: Duration = Duration.ofMinutes(5)

    private const val FIELD_JTI = "challengeTokenJti"
    private const val FIELD_CHALLENGED_AT = "challengedAtEpochSecond"
    private const val FIELD_EXPIRES_AT = "expiresAt"
  }
}
