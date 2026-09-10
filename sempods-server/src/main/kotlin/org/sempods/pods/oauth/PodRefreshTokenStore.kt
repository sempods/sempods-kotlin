package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.sempods.SempodsCollections
import org.sempods.auth.core.RefreshTokenStore
import java.time.Instant

/** A refresh token of this pod server, with the owner already resolved. */
internal typealias PodRefreshToken = RefreshTokenStore.Token<PodRefreshTokenStore.Owner>

/**
 * The pod server's refresh tokens: [RefreshTokenStore] with a pod-shaped owner, plus the three
 * revocations that are this server's domain rather than the mechanism's.
 *
 * Under `pods/` rather than under `api/`, which is where it used to live: what is stored here is
 * durable authorization state — who may still come back with which authority — and not an HTTP
 * concern. `PodGrantsFacade` reaches it from the same layer now instead of upward out of it.
 *
 * @param collectionName the production name sits on the `@Inject` constructor; a test points an
 *   instance at a collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md` §"Conventions"
 *   states.
 */
class PodRefreshTokenStore internal constructor(db: MongoDatabase, collectionName: String) {

  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.OAUTH_REFRESH_TOKENS)

  /**
   * @param podId the pod the token is good for, and the key everything bulk-revoking starts from.
   * @param podName carried for the caller's benefit and never queried on, which is why it is not in
   *   the compound index.
   * @param webId the person, as their WebID — the token's subject.
   */
  internal data class Owner(
    val podId: ObjectId,
    val podName: String,
    val clientId: String,
    val webId: String,
  )

  private val store = RefreshTokenStore<Owner>(
    db = db,
    collectionName = collectionName,
    ownerIndexFields = listOf(FIELD_POD_ID, FIELD_CLIENT_ID, FIELD_WEB_ID),
    writeOwner = {
      put(FIELD_POD_ID, it.podId)
      put(FIELD_POD_NAME, it.podName)
      put(FIELD_CLIENT_ID, it.clientId)
      put(FIELD_WEB_ID, it.webId)
    },
    readOwner = {
      Owner(
        podId = getObjectId(FIELD_POD_ID),
        podName = getString(FIELD_POD_NAME),
        clientId = getString(FIELD_CLIENT_ID),
        webId = getString(FIELD_WEB_ID),
      )
    },
  )

  /**
   * Which lifetime a family was minted under, as this server's consent control decides it, and how
   * long each one lives.
   *
   * The class is stored on every row as [RefreshTokenStore.Token.kind] and inherited by each
   * rotation, so a rotation reads the terms off the credential rather than off the consent decision
   * — that document is the person's to edit, and a durable family a withdrawal has not yet swept
   * would otherwise be read as a session family: the short window **and** an escape from the
   * withdrawal.
   *
   * The numbers are this server's. RFC 10017 §6.3.2.3 requires a maximum lifetime or an idle expiry
   * and fixes neither, and says an authorization server MAY set different policies for
   * browser-based applications.
   *
   * @param idleSeconds how long a family survives unused. Every rotation renews it, which is what
   *   makes it an idle window rather than a life.
   * @param absoluteSeconds the family's outer bound, fixed when it is seeded and never moved again.
   *   `null` leaves the family unbounded, which is where [DURABLE] still stands.
   */
  internal enum class Lifetime(val kind: String, val idleSeconds: Long, val absoluteSeconds: Long?) {
    SESSION("session", 12L * 60 * 60, 7L * 24 * 60 * 60),
    DURABLE("durable", RefreshTokenStore.DEFAULT_TTL_SECONDS, null),
  }

  /**
   * The terms a row was minted under — what a caller asks before deciding whether something may end
   * this family.
   *
   * A row carrying no [RefreshTokenStore.Token.kind] predates the field, and it is [Lifetime.DURABLE]:
   * back then the pod minted a family only where the person ticked the connection, so every one that
   * exists was ticked. Reading such a row as [Lifetime.SESSION] would hand it both halves of the
   * failure the class exists to prevent. Every row written from here on names its own class, so this
   * answers for rows older than that and for nothing else.
   *
   * An unrecognised value falls to [Lifetime.DURABLE] too, and that direction is the point: a class
   * added later is ended by a refusal rather than quietly spared by one.
   */
  internal fun lifetimeOf(token: PodRefreshToken): Lifetime =
    Lifetime.entries.firstOrNull { it.kind == token.kind } ?: Lifetime.DURABLE

  /**
   * Seeds a family on [lifetime]'s terms: its idle window becomes the row's TTL, and its outer bound
   * becomes the family's [RefreshTokenStore.Token.endsAt] — computed once, here, and copied verbatim
   * by every rotation afterwards.
   *
   * No `ttlSeconds` beside it. The class decides how long the family lives, and a second lever next
   * to it is how a caller would set one of the two numbers and forget the other.
   */
  internal fun issueNewFamily(
    podId: ObjectId,
    podName: String,
    clientId: String,
    webId: String,
    scopes: Set<String>,
    lifetime: Lifetime,
  ): RefreshTokenStore.Issued<Owner> = store.issueNewFamily(
    owner = Owner(podId = podId, podName = podName, clientId = clientId, webId = webId),
    scopes = scopes,
    kind = lifetime.kind,
    endsAt = lifetime.absoluteSeconds?.let { Instant.now().plusSeconds(it) },
    ttlSeconds = lifetime.idleSeconds,
  )

  /**
   * The successor in an existing family, on that family's own idle window.
   *
   * The window comes from [lifetimeOf] and not from the store's default, or a session family would
   * rotate on a ninety-day TTL: the clamp would hold it to its seven-day deadline and it would never
   * expire from disuse at all. A grandfathered row keeps the ninety days it has always had.
   */
  internal fun issueInFamily(
    previous: PodRefreshToken,
    scopes: Set<String>,
  ): RefreshTokenStore.Issued<Owner> =
    store.issueInFamily(previous, scopes, ttlSeconds = lifetimeOf(previous).idleSeconds)

  internal fun lookup(plaintext: String): RefreshTokenStore.Lookup<Owner> = store.lookup(plaintext)

  internal fun markRotated(tokenHash: String): Boolean = store.markRotated(tokenHash)

  internal fun revokeFamily(familyId: String): Long = store.revokeFamily(familyId)

  /**
   * Revokes what one app holds for one person on one pod — the consent-withdrawal and disconnect
   * paths. The MCP surface's explicit re-authorization uses [revokeLiveFamiliesFor] instead, for
   * the reason stated there.
   */
  internal fun revokeForUser(podId: ObjectId, clientId: String, webId: String): Long =
    revokeForUser(podId, clientId, listOf(webId))

  /**
   * The same, for every URI that names the same person.
   *
   * A pod stores whichever WebID authenticated at the time, so an authorization can hold families
   * under an alias while its owner is signed in under their canonical URI. Revoking one of them and
   * calling that a withdrawal leaves the connection the person meant to end running — and the
   * survivor then reads as an authorization with nothing recorded, which is grandfathered.
   */
  internal fun revokeForUser(podId: ObjectId, clientId: String, webIds: Collection<String>): Long =
    ownerFilter(podId, clientId, webIds)?.let(store::revokeWhere) ?: 0

  /**
   * Ends the families this app holds for this person **as they stand now**, and no later ones.
   *
   * For an event that reviews a consent rather than replacing it — the MCP surface's explicit
   * re-authorization. [revokeForUser] would do it in one write, but its filter also catches a
   * family inserted while it runs, and that one belongs to a consent completing beside the call:
   * taking it hands the person a refresh token dead on arrival. Naming the set first cannot reach
   * anything minted after the look. What is minted between the caller's own generation raise and
   * this look is still taken; removing that needs the family to carry its own generation.
   */
  internal fun revokeLiveFamiliesFor(podId: ObjectId, clientId: String, webIds: Collection<String>): Long =
    revokeFamilies(liveFamilies(podId, clientId, webIds))

  /**
   * The live families this app holds for this person — what a consent about to mint one supersedes.
   *
   * A reconnect answers the lifetime question again, and **either answer mints a family**, so either
   * answer has to replace what it supersedes — that is this pair. Withholding sweeps them through
   * [revokeForUser] as well, which is the same retirement from the other end. Without this one every
   * reconnect leaves another family behind that nobody counted, each renewing a window of its own;
   * an auto-granted reconnect records no new decision and so revokes nothing, which is how they
   * accumulate one per visit.
   *
   * **Measured before the successor is minted, and that ordering is the correctness argument.** Two
   * exchanges can run under one standing consent — auto-grant issues a code without recording a new
   * decision — and a sweep phrased as "everything but my own family" would have each of them revoke
   * the other's, handing both clients a refresh token that is already dead. A set read beforehand
   * cannot name a family minted after it, and each caller reads before it inserts, so at most one of
   * the two can have observed the other: either one retires the other, or neither does.
   */
  internal fun liveFamilies(podId: ObjectId, clientId: String, webIds: Collection<String>): Set<String> =
    ownerFilter(podId, clientId, webIds)?.let(store::familiesWhere) ?: emptySet()

  /**
   * Revokes each of [familyIds] — the sweep [liveFamilies] measured, successors included.
   *
   * The reach past what was measured is wanted here: a reconnect replaces the whole connection, so
   * a rotation of a family it is retiring belongs to the connection being replaced. Where the reason
   * to revoke is an observation that a later rotation would falsify, use [liveTokens] instead.
   */
  internal fun revokeFamilies(familyIds: Collection<String>): Long =
    if (familyIds.isEmpty()) 0
    else store.revokeWhere(Filters.`in`(RefreshTokenStore.Field.FAMILY_ID, familyIds))

  /**
   * The live rows this app holds for this person, named one by one — the snapshot for a caller
   * whose reason to revoke is an observation rather than a decision.
   *
   * The grant cascade's case: it revokes because the app was left holding nothing, and a successor
   * rotated in after the measurement is one a client may already be holding. Revoking by family id
   * would reach it — `issueInFamily` keeps the family, so the name selects rows that did not exist
   * when it was read — and handing back a credential that is already dead is the failure this
   * milestone exists to remove. Whether that successor may live is settled by the refresh exchange,
   * which asks the grants again after inserting it.
   */
  internal fun liveTokens(podId: ObjectId, clientId: String, webIds: Collection<String>): Set<String> =
    ownerFilter(podId, clientId, webIds)?.let(store::liveTokensWhere) ?: emptySet()

  /**
   * Revokes exactly the rows [liveTokens] named, and nothing minted since — **skipping any that
   * has been rotated in the meantime.**
   *
   * A spent row is not worth revoking and revoking it costs the family its reuse detection.
   * `lookup` answers `REVOKED` before it answers `REUSED`, so a replay of a row that is both would
   * be reported as merely revoked, and the refresh exchange would refuse it without killing the
   * family — leaving the successor a thief may hold. Nothing is lost by skipping it: a rotated row
   * cannot be exchanged either way, and staying merely rotated is what makes replaying it end the
   * family. The `null` comparison matches a row that never carried the field, for the reason
   * [RefreshTokenStore.markRotated] states.
   */
  internal fun revokeTokens(tokenHashes: Collection<String>): Long =
    if (tokenHashes.isEmpty()) 0
    else store.revokeWhere(
      Filters.and(
        Filters.`in`(RefreshTokenStore.Field.TOKEN_HASH, tokenHashes),
        Filters.eq(RefreshTokenStore.Field.ROTATED_AT, null),
      ),
    )

  /**
   * Whether this row has stopped standing — revoked, or gone — since it was read. The question a
   * rotation has to ask after inserting its successor.
   *
   * `markRotated` refuses a revoked row, so a retirement landing *before* the rotation is already
   * answered. One landing between the rotation and the insert is not: it revokes the rows it finds,
   * and the successor appears after it, alive in a family that was meant to be retired. Asking
   * about the predecessor answers for the family, because retirement is family-wide.
   *
   * A row the TTL index reaped answers the same as a revoked one. That is deliberate rather than
   * imprecise: both mean this rotation no longer has a predecessor standing behind it, and the
   * caller's response to either is the one a client of a retired family is owed.
   *
   * A point lookup on the unique hash index — the cheapest question this collection answers.
   */
  internal fun noLongerStands(tokenHash: String): Boolean =
    store.liveTokensWhere(Filters.eq(RefreshTokenStore.Field.TOKEN_HASH, tokenHash)).isEmpty()

  /** What one app holds for one person, or null where no URI names them. */
  private fun ownerFilter(podId: ObjectId, clientId: String, webIds: Collection<String>): Bson? {
    val distinct = webIds.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return null
    return Filters.and(
      Filters.eq(FIELD_POD_ID, podId),
      Filters.eq(FIELD_CLIENT_ID, clientId),
      Filters.`in`(FIELD_WEB_ID, distinct),
    )
  }

  /**
   * Hard-deletes every refresh token of the pod — the pod-cascade delete path, where family
   * revocation is moot because the pod itself is gone.
   */
  internal fun deleteByPod(podId: ObjectId): Long = store.deleteWhere(podFilter(podId))

  /** Diagnostics and tests: used to assert that family-wide revocation happened. */
  internal fun findByFamily(familyId: String): List<PodRefreshToken> = store.findByFamily(familyId)

  private fun podFilter(podId: ObjectId): Bson = Filters.eq(FIELD_POD_ID, podId)

  private companion object {

    // The owner's field names, which are this class's rather than the shared store's. The order of
    // the four is the order they are written in, and a row on disk carries it.
    const val FIELD_POD_ID = "podId"
    const val FIELD_POD_NAME = "podName"
    const val FIELD_CLIENT_ID = "clientId"
    const val FIELD_WEB_ID = "webId"
  }
}
