package org.sempods.mcp.persist

import com.mongodb.client.FindIterable
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import org.sempods.commons.mongo.putNotNull
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.crypto.SecretCipher
import org.bson.Document
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date

/**
 * The token vault: the service's custody of **pod** OAuth tokens, keyed `(user, profile, pod)`.
 * This is the "token custody" cost the concept doc calls out — the main liability hardened
 * in M6 (encryption-at-rest + key management). [accessToken]/[refreshToken] are stored as
 * ciphertext under the [SecretCipher] envelope.
 *
 * A row whose ciphertext cannot be decrypted (a lost / changed [SecretCipher] key) is treated as
 * **unreadable** rather than fatal: [find] returns null and the refresh sweep skips it, so the
 * caller surfaces "reconnect this pod" instead of the read or the whole sweep crashing.
 *
 * M1 establishes the schema; rows are written from M2 (connect-a-pod) onward.
 */
data class PodTokens(
  val user: String,
  val profile: String,
  val pod: String,
  val accessToken: String,
  val refreshToken: String?,
  /** Absolute expiry of [accessToken]; the refresh loop (M2) renews before this. */
  val accessTokenExpiresAt: Date?,
  /**
   * When this row last **rotated** — written by the connect path and by every refresh. It is what
   * the sweep's preservation tier reads: a row that has not rotated in a long time is one whose
   * refresh-token family (and the pod-side DCR registration) is drifting toward its ninety-day
   * deadline, whether or not its access token is anywhere near expiry.
   */
  val updatedAt: Date,
  /**
   * When a pod-touching tool call last used this connection ([TokenVaultDao.touchLastUsed]), or
   * null for a row nothing has used since it was written. Drives the sweep's warm tier: only a
   * recently-used connection is worth renewing ahead of time, because the whole benefit of
   * warm-keeping is latency on the *next* call. Null on rows written before this field existed —
   * they read as "not warm", and the first tool call marks them.
   */
  val lastUsedAt: Date? = null,
)

/**
 * One read of the preservation queue: the rows the sweep can act on, and the keys of the rows it
 * cannot. The second list is not an error channel — an undecryptable row is a normal outcome under
 * [SecretCipher] (see [TokenVaultDao]) — it is what lets the caller mark those rows so they leave
 * the head of the queue like any other row it has visited.
 */
data class PreserveDue(val rows: List<PodTokens>, val unreadable: List<PodKey>)

/**
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md` §"Conventions" states.
 */
class TokenVaultDao(
  db: MongoDatabase,
  private val cipher: SecretCipher,
  collectionName: String = SempodsMcpCollections.POD_TOKENS,
) {

  private val tokens = db.getCollection(collectionName)

  init {
    tokens.createIndex(
      Indexes.ascending("user", "profile", "pod"),
      IndexOptions().unique(true),
    )
    // The two sweep selections below. `lastUsedAt` leads the warm one because it is the selective
    // half — it bounds the scan to connections somebody actually used, which is the whole point of
    // the cadence: the work scales with use, not with inventory — and it carries that selection's
    // ordering, so the reverse scan needs no sort. The preservation one leads on
    // `lastRefreshAttemptAt` because that is what its queue is ordered by, and `updatedAt` follows so
    // that both halves of the queue are bounded *and* ordered by the index alone. Leading on
    // `updatedAt` instead reads as the obvious choice — it is the range — and costs a blocking sort
    // over the entire backlog on every tick.
    // Both are **partial**, on the one thing every sweep row must have: something to rotate with. A
    // row without a refresh token can never be swept, and nothing ever moves it — it is not rotated,
    // so its stamp stays put, and it is never handed to the sweep, so it is never marked. In a plain
    // index it would sit in the access path for good, fetched on every tick to be discarded, and the
    // batch bound would stop bounding retrieval. `$type` rather than `$exists`: the field is always
    // present and carries BSON null when there is no token, so existence discriminates nothing.
    val refreshable = IndexOptions().partialFilterExpression(Filters.type("refreshToken", REFRESH_TOKEN_TYPE))
    tokens.createIndex(Indexes.ascending("lastUsedAt", "accessTokenExpiresAt"), refreshable)
    tokens.createIndex(Indexes.ascending("lastRefreshAttemptAt", "updatedAt"), refreshable)
  }

  fun find(key: PodKey): PodTokens? =
    tokens.find(keyFilter(key)).firstOrNull()?.toTokensOrNull()

  /**
   * The sweep's **warm** selection: refreshable rows used since [usedSince] whose access token
   * expires before [cutoff].
   *
   * A row with an unknown expiry is deliberately not selected — [PodTokenProvider.isDue] answers
   * false for one anyway, so it used to be handed back on every tick only to be skipped, and
   * keeping the `accessTokenExpiresAt == null` branch out of the filter is what lets an index cover
   * it. Those rows are not dropped: [findNotRotatedSince] holds their family alive on the long
   * cadence, which is the only thing they ever needed.
   *
   * Most-recently-used first, and at most [limit] of them — this tier serves latency, so if a tick
   * cannot renew everything it should renew what is likeliest to be called next. The limit bounds
   * what one tick decrypts and materialises; the sweep's time budget is what actually rations the
   * work.
   *
   * Unreadable (undecryptable) rows are skipped so one bad row cannot wedge the whole sweep.
   */
  fun findExpiringBefore(cutoff: Date, usedSince: Date, limit: Int): List<PodTokens> =
    expiringBeforeQuery(cutoff, usedSince, limit).mapNotNull { it.toTokensOrNull() }.toList()

  /**
   * The query itself, so a test can put the planner's answer to *this* on the record — filter,
   * ordering and bound together. Explaining the filter alone would pass while the ordering forced a
   * blocking sort over the whole selection, which is the failure worth catching.
   */
  internal fun expiringBeforeQuery(cutoff: Date, usedSince: Date, limit: Int): FindIterable<Document> =
    tokens.find(
      Filters.and(
        Filters.type("refreshToken", REFRESH_TOKEN_TYPE),
        Filters.lt("accessTokenExpiresAt", cutoff),
        Filters.gte("lastUsedAt", usedSince),
      ),
    ).sort(Sorts.descending("lastUsedAt")).limit(limit)

  /**
   * The sweep's **preservation** selection: at most [limit] refreshable rows that have not rotated
   * since [cutoff], regardless of access-token expiry. One rotation resets the pod's refresh-token
   * family and its DCR liveness in full, so this is the only cadence the ninety-day deadline
   * actually asks for.
   *
   * **Least-recently-attempted first** ([markRefreshAttempted]), never-attempted rows ahead of all
   * of them, ties broken by the oldest rotation — i.e. round-robin, closest-to-deadline first. That
   * ordering is what makes the pass advance, and it has to, because a refresh that fails persists
   * nothing: a failing row's `updatedAt` never moves, so ordered by that alone it would sit at the
   * head of a time-budgeted pass forever and the rows behind it would never be attempted at all,
   * their families expiring while the sweep reported healthy passes. Attempting a row sends it to
   * the back instead, so no row is tried twice before every other due row has been tried once —
   * which an exclusion window could only approximate, and only until the backlog took longer to
   * traverse than the window.
   *
   * Whether the head is exhausted is decided on the **raw** document count, not on how many of them
   * decoded: an unreadable row is still a row the head held, and treating a short readable result as
   * "head exhausted" would move on to the tail while readable rows sat just past the batch. The
   * unreadable ones come back in [PreserveDue.unreadable] rather than being dropped here, because
   * they need the same thing every other row in this queue needs — a mark, so they sink to the back.
   * Nothing else would ever move them: they carry no mark, and their rotation stamp cannot move
   * because nothing can rotate them.
   */
  fun findNotRotatedSince(cutoff: Date, limit: Int): PreserveDue {
    val head = notRotatedSinceQuery(cutoff, limit, attempted = false).toList()
    if (head.size >= limit) return head.toPreserveDue()
    return (head + notRotatedSinceQuery(cutoff, limit - head.size, attempted = true)).toPreserveDue()
  }

  /** One decode pass per document — [toTokensOrNull] logs, so decoding twice would report twice. */
  private fun Iterable<Document>.toPreserveDue(): PreserveDue {
    val rows = mutableListOf<PodTokens>()
    val unreadable = mutableListOf<PodKey>()
    forEach { document ->
      val tokens = document.toTokensOrNull()
      if (tokens != null) rows += tokens else unreadable += document.toKey()
    }
    return PreserveDue(rows, unreadable)
  }

  /**
   * One half of the preservation queue: [attempted] `false` is the head of it (rows carrying no
   * attempt mark, oldest rotation first), `true` the tail (least-recently-attempted first).
   *
   * Two queries rather than one sorted by `(lastRefreshAttemptAt, updatedAt)`, because a single one
   * cannot have both halves bounded and ordered by an index: pinning the mark to a single value is
   * what lets the head's bound close on `updatedAt` as well, and the tail is small — a rotation
   * replaces the row and drops the mark, so what carries one is essentially what is currently
   * failing. Retrieval then scales with [limit] rather than with the backlog, which is the whole
   * point of bounding it.
   */
  internal fun notRotatedSinceQuery(cutoff: Date, limit: Int, attempted: Boolean): FindIterable<Document> =
    tokens.find(
      Filters.and(
        Filters.type("refreshToken", REFRESH_TOKEN_TYPE),
        Filters.lt("updatedAt", cutoff),
        // `$type` on the tail, not `$ne: null`: the negation's index bounds are two ranges around
        // null and the scan pays a key at their boundary, where one date bracket is a single range.
        if (attempted) Filters.type("lastRefreshAttemptAt", "date") else Filters.eq("lastRefreshAttemptAt", null),
      ),
    ).sort(Sorts.ascending("lastRefreshAttemptAt", "updatedAt")).limit(limit)

  /**
   * Record that the preservation sweep has just tried this row, whatever came of it — a rotation, a
   * refusal, a claim lost to another replica, a pod that never answered. Read only by
   * [findNotRotatedSince]'s ordering, and the reason it exists is there.
   *
   * Like the claim fields, this lives on the wire and not on [PodTokens]: every full replace
   * ([upsert], [replaceIfClaimedBy]) drops it, and that is right — a row that was replaced has
   * either just rotated or just been re-connected, so the last *attempt* on it says nothing any
   * more, and a row with no attempt on record sorts first, which is where a fresh one belongs.
   */
  fun markRefreshAttempted(key: PodKey, at: Date) {
    tokens.updateOne(keyFilter(key), Updates.set("lastRefreshAttemptAt", at))
  }

  /**
   * Full-document replace — the connect/re-connect write path (`WebUiEndpoint`). Note this drops
   * any refresh-claim fields ([tryClaimRefresh]): a re-connect thereby both clears a stale claim
   * AND makes an in-flight refresh's [replaceIfClaimedBy] lose — a fresh connect supersedes.
   * The refresh path itself persists via [replaceIfClaimedBy], never this.
   */
  fun upsert(podTokens: PodTokens) {
    tokens.replaceOne(
      keyFilter(PodKey(podTokens.user, podTokens.profile, podTokens.pod)),
      podTokens.toDocument(),
      ReplaceOptions().upsert(true),
    )
  }

  /**
   * Record that a pod-touching tool call just used this connection — the marker the sweep's warm
   * tier selects on. A targeted `$set`, not a replace: it must not disturb the tokens, the rotation
   * stamp, or a refresh claim in flight.
   *
   * Conditional on [ifOlderThan] so a burst of tool calls collapses into one write per connection
   * per interval; the caller already holds the row, so the throttle costs no extra read. [at] is
   * the caller's clock, as on `ConnectionRegistryDao.markDeadGrant`.
   *
   * @return whether the marker moved (false = already fresh enough, or the row is gone).
   */
  fun touchLastUsed(key: PodKey, at: Date, ifOlderThan: Date): Boolean =
    tokens.updateOne(
      Filters.and(
        keyFilter(key),
        Filters.or(
          Filters.exists("lastUsedAt", false),
          Filters.eq("lastUsedAt", null),
          Filters.lt("lastUsedAt", ifOlderThan),
        ),
      ),
      Updates.set("lastUsedAt", at),
    ).modifiedCount == 1L

  /**
   * Atomically claim the refresh of this row across replicas (M6.3): succeeds when no claim
   * exists or the existing one expired. The claim only serialises *refreshes* — reads are
   * untouched. A crashed holder's claim expires on its own; [holder] is the replica's
   * [InstanceId], [until] should be generous enough for a worst-case refresh (discover + token
   * POST + JWKS fetch) but small against the refresh window.
   */
  fun tryClaimRefresh(key: PodKey, holder: String, until: Date): Boolean {
    val result = tokens.updateOne(
      Filters.and(
        keyFilter(key),
        Filters.or(
          Filters.exists("refreshClaimedUntil", false),
          Filters.eq("refreshClaimedUntil", null),
          Filters.lt("refreshClaimedUntil", Date()),
          // Idempotent for the current holder (a retry after a failed release must not stall).
          Filters.eq("refreshClaimedBy", holder),
        ),
      ),
      Updates.combine(
        Updates.set("refreshClaimedBy", holder),
        Updates.set("refreshClaimedUntil", until),
      ),
    )
    return result.matchedCount > 0
  }

  /**
   * Persist a refreshed row ONLY while this holder's claim is still on it. A concurrent pod
   * re-connect (`WebUiEndpoint`) replaces the row wholesale — clearing the claim — with a
   * brand-new token family; an in-flight refresh of the OLD family must then lose, or its late
   * write would clobber the newer tokens with dead ones (and a disconnect's delete must not be
   * resurrected). Returns false when the claim is gone (row replaced or deleted mid-refresh); the
   * successful replace drops the claim fields, so it doubles as the release.
   *
   * It also carries `lastUsedAt` from the row the refresh was built on, so a [touchLastUsed]
   * landing during the refresh's HTTP round trips is lost. That window is one refresh long
   * (~150–400 ms) and nothing durable rests on the marker: the next tool call re-touches it.
   */
  fun replaceIfClaimedBy(podTokens: PodTokens, holder: String): Boolean =
    tokens.replaceOne(
      Filters.and(
        keyFilter(PodKey(podTokens.user, podTokens.profile, podTokens.pod)),
        Filters.eq("refreshClaimedBy", holder),
      ),
      podTokens.toDocument(),
    ).matchedCount > 0

  /** Failure-path cleanup: drop the claim early so the next holder need not wait it out. Only the holder may. */
  fun releaseRefreshClaim(key: PodKey, holder: String) {
    tokens.updateOne(
      Filters.and(keyFilter(key), Filters.eq("refreshClaimedBy", holder)),
      Updates.combine(Updates.unset("refreshClaimedBy"), Updates.unset("refreshClaimedUntil")),
    )
  }

  fun delete(key: PodKey) {
    tokens.deleteOne(keyFilter(key))
  }

  private fun Document.toKey() = PodKey(getString("user"), getString("profile"), getString("pod"))

  private fun keyFilter(key: PodKey) = Filters.and(
    Filters.eq("user", key.user),
    Filters.eq("profile", key.profile),
    Filters.eq("pod", key.pod),
  )

  private fun PodTokens.toDocument() = Document().apply {
    put("user", user)
    put("profile", profile)
    put("pod", pod)
    put("accessToken", cipher.encrypt(accessToken))
    put("refreshToken", cipher.encryptMaybe(refreshToken))
    put("accessTokenExpiresAt", accessTokenExpiresAt)
    put("updatedAt", updatedAt)
    // `putNotNull`: an absent field is the contract `sempods-commons-mongo/docs/document-contract.md` states, and a
    // never-used connection is the common case for a row this path writes.
    putNotNull("lastUsedAt", lastUsedAt)
  }

  /** Map a row, or null if it is unreadable (undecryptable ciphertext / corrupt) — logged, not thrown. */
  private fun Document.toTokensOrNull(): PodTokens? = try {
    toTokens()
  } catch (e: Exception) {
    logger.warn(e) { "unreadable pod token row for pod='${getString("pod")}' — treating as disconnected" }
    null
  }

  private fun Document.toTokens() = PodTokens(
    user = getString("user"),
    profile = getString("profile"),
    pod = getString("pod"),
    accessToken = cipher.decrypt(getString("accessToken")),
    refreshToken = cipher.decryptMaybe(getString("refreshToken")),
    accessTokenExpiresAt = getDate("accessTokenExpiresAt"),
    updatedAt = getDate("updatedAt") ?: Date(),
    lastUsedAt = getDate("lastUsedAt"),
  )

  companion object {
    private val logger = KotlinLogging.logger {}

    /**
     * What "has a refresh token" is, on the wire: ciphertext under [SecretCipher], or BSON null when
     * the pod issued none. Spelled the same way in the sweep filters and in the partial indexes
     * above, because the planner matches a partial index by the shape of the predicate rather than
     * by what it means — an equivalent `$ne: null` would read the same and silently take the index
     * away.
     */
    private const val REFRESH_TOKEN_TYPE = "string"
  }
}
