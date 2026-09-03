package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.sempods.SempodsCollections
import org.sempods.auth.core.RefreshTokenStore

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

  internal fun issueNewFamily(
    podId: ObjectId,
    podName: String,
    clientId: String,
    webId: String,
    scopes: Set<String>,
    ttlSeconds: Long = RefreshTokenStore.DEFAULT_TTL_SECONDS,
  ): RefreshTokenStore.Issued<Owner> = store.issueNewFamily(
    owner = Owner(podId = podId, podName = podName, clientId = clientId, webId = webId),
    scopes = scopes,
    ttlSeconds = ttlSeconds,
  )

  internal fun issueInFamily(
    previous: PodRefreshToken,
    scopes: Set<String>,
    ttlSeconds: Long = RefreshTokenStore.DEFAULT_TTL_SECONDS,
  ): RefreshTokenStore.Issued<Owner> = store.issueInFamily(previous, scopes, ttlSeconds)

  internal fun lookup(plaintext: String): RefreshTokenStore.Lookup<Owner> = store.lookup(plaintext)

  internal fun markRotated(tokenHash: String): Boolean = store.markRotated(tokenHash)

  internal fun revokeFamily(familyId: String): Long = store.revokeFamily(familyId)

  /**
   * Revokes what one app holds for one person on one pod. The consent-withdrawal path, and the
   * MCP surface's explicit re-authorization.
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
   * The live families this app holds for this person — what a consent about to mint one supersedes.
   *
   * A reconnect answers the lifetime question again, and the answer governs what stands afterwards:
   * withholding ends the families through [revokeForUser], granting replaces them through this pair.
   * Without it every reconnect leaves another ninety-day family behind that nobody counted and that
   * renews its own TTL on each rotation.
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
   * The grant cascade's case: it revokes because the app was left holding nothing, and a rotation
   * that succeeded after the measurement is proof that grants came back before the sweep ran. Its
   * successor must therefore outlive the sweep, which revoking by family id would not allow —
   * `issueInFamily` keeps the family, so the name selects rows that did not exist when it was read.
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
