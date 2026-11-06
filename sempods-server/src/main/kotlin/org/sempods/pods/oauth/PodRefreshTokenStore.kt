package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.bson.conversions.Bson
import org.sempods.SempodsCollections
import org.sempods.SempodsConfig
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.auth.core.RefreshTokenStore.Field
import org.sempods.pods.PodId
import org.sempods.pods.mongo.persist.objectId
import org.sempods.pods.mongo.persist.toPodId
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A refresh token of this pod server, with the owner already resolved. */
internal typealias PodRefreshToken = RefreshTokenStore.Token<PodRefreshTokenStore.Owner>

/**
 * The pod server's refresh tokens: [RefreshTokenStore] with a pod-shaped owner, plus the
 * revocations that are this server's domain rather than the mechanism's.
 *
 * Under `pods/` rather than under `api/`, which is where it used to live: what is stored here is
 * durable authorization state — who may still come back with which authority — and not an HTTP
 * concern. `PodGrantsFacade` reaches it from the same layer now instead of upward out of it.
 *
 * @param collectionName the production name sits on the `@Inject` constructor; a test points an
 *   instance at a collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md` §"Conventions"
 *   states.
 * @param sequenceCollectionName stores the issuance counter for this token collection. Keep it
 *   with the token collection when restoring data; it is retained when tokens expire.
 */
class PodRefreshTokenStore internal constructor(
  db: MongoDatabase,
  collectionName: String,
  config: SempodsConfig,
  sequenceCollectionName: String = "$collectionName.sequence",
) {

  @Inject
  internal constructor(db: MongoDatabase, config: SempodsConfig) :
    this(db, SempodsCollections.OAUTH_REFRESH_TOKENS, config, SempodsCollections.OAUTH_REFRESH_FAMILY_SEQUENCE)

  /**
   * @param pod the pod the token is good for, and the key everything bulk-revoking starts from.
   * @param podName carried for the caller's benefit and never queried on, which is why it is not in
   *   the compound index.
   * @param webId the person, as their WebID — the token's subject.
   * @param familyOrder the immutable issuance order, shared by every row of a family. Allocated
   *   atomically in MongoDB before seeding, independent of clocks and rotations. Zero denotes a
   *   family issued before this field existed; new families always sort after those families.
   */
  internal data class Owner(
    val pod: PodId,
    val podName: String,
    val clientId: String,
    val webId: String,
    val familyOrder: Long = 0,
  )

  private val tokens = db.getCollection(collectionName)
  private val sequence = db.getCollection(sequenceCollectionName)
  private val maxFamilies = config.connectionMaxFamilies

  private val store = RefreshTokenStore<Owner>(
    db = db,
    collectionName = collectionName,
    ownerIndexFields = listOf(FIELD_POD_ID, FIELD_CLIENT_ID, FIELD_WEB_ID),
    writeOwner = {
      put(FIELD_POD_ID, it.pod.objectId())
      put(FIELD_POD_NAME, it.podName)
      put(FIELD_CLIENT_ID, it.clientId)
      put(FIELD_WEB_ID, it.webId)
      put(FIELD_FAMILY_ORDER, it.familyOrder)
    },
    readOwner = {
      Owner(
        pod = getObjectId(FIELD_POD_ID).toPodId(),
        podName = getString(FIELD_POD_NAME),
        clientId = getString(FIELD_CLIENT_ID),
        webId = getString(FIELD_WEB_ID),
        familyOrder = getLong(FIELD_FAMILY_ORDER) ?: 0L,
      )
    },
  )

  /**
   * Which lifetime a family was minted under, as this server's consent control decides it.
   *
   * Every rotation inherits the stored class, so later consent choices affect only new families.
   * How long each class lives is [termsOf]'s answer.
   */
  internal enum class Lifetime(val kind: String) {
    SESSION("session"),
    DURABLE("durable"),
  }

  private val sessionTerms = ConnectionTerms(
    idle = Duration.ofHours(config.sessionConnectionIdleHours.toLong()),
    absolute = Duration.ofDays(config.sessionConnectionAbsoluteDays.toLong()),
  )

  private val durableTerms = ConnectionTerms(
    idle = Duration.ofDays(config.durableConnectionIdleDays.toLong()),
    absolute = Duration.ofDays(config.durableConnectionAbsoluteDays.toLong()),
  )

  /** The terms a family of [lifetime] is seeded and rotated on, and the ones the consent dialog names. */
  internal fun termsOf(lifetime: Lifetime): ConnectionTerms = when (lifetime) {
    Lifetime.SESSION -> sessionTerms
    Lifetime.DURABLE -> durableTerms
  }

  /**
   * The terms a row was minted under — what a caller asks before deciding whether something may end
   * this family.
   *
   * A row carrying no [RefreshTokenStore.Token.kind] predates the field, and it is [Lifetime.DURABLE]:
   * only durable connections were issued before the field existed. Unknown classes also use the
   * durable terms; the stored absolute deadline still bounds every rotation.
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
    pod: PodId,
    podName: String,
    clientId: String,
    webId: String,
    scopes: Set<String>,
    lifetime: Lifetime,
  ): RefreshTokenStore.Issued<Owner> {
    val terms = termsOf(lifetime)
    return store.issueNewFamily(
      owner = Owner(pod = pod, podName = podName, clientId = clientId, webId = webId, familyOrder = nextFamilyOrder()),
      scopes = scopes,
      kind = lifetime.kind,
      endsAt = Instant.now().plus(terms.absolute),
      ttlSeconds = terms.idle.seconds,
    )
  }

  /** One persistent counter per token collection; gaps from failed exchanges are harmless. */
  private fun nextFamilyOrder(): Long = checkNotNull(sequence.findOneAndUpdate(
    Filters.eq("_id", "familyOrder"),
    Updates.inc("value", 1L),
    FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
  )).getLong("value")

  /**
   * The successor in an existing family, on its class's idle window, and **under a deadline
   * where the family reaches this without one**.
   *
   * The window comes from [lifetimeOf] and not from the store's default, or a session family would
   * rotate on a ninety-day TTL: the clamp would hold it to its own deadline and it would never expire
   * from disuse at all.
   *
   * The deadline is the predecessor's own expiry — the only one such a family demonstrably has, and
   * taking it extends nothing, because the clamp then hands the successor that same instant. At this
   * pod the rule is asked of **every** family missing a deadline, which is wider than
   * [RefreshTokenStore.issueInFamily]'s own: that one keys on a missing class, and leaves a family
   * that names one to the service that minted it. Two populations here name a class and carry no
   * deadline all the same — the families minted between the terms arriving and this server deciding
   * what they mean — and keyed on the class they would rotate on a rolling window forever, which is
   * the whole of what the ceiling is for. Every family seeded from here on carries one at the mint,
   * so this answers for what a running deployment already holds and for nothing else.
   */
  internal fun issueInFamily(
    previous: PodRefreshToken,
    scopes: Set<String>,
  ): RefreshTokenStore.Issued<Owner> = store.issueInFamily(
    previous = previous.copy(endsAt = previous.endsAt ?: previous.expiresAt),
    scopes = scopes,
    ttlSeconds = termsOf(lifetimeOf(previous)).idle.seconds,
  )

  internal fun lookup(plaintext: String): RefreshTokenStore.Lookup<Owner> = store.lookup(plaintext)

  internal fun markRotated(tokenHash: String): Boolean = store.markRotated(tokenHash)

  internal fun revokeFamily(familyId: String, reason: String): Long =
    revokeWhere(Filters.eq(Field.FAMILY_ID, familyId), reason)

  /**
   * Revokes what one app holds for one person on one pod — the consent-withdrawal and disconnect
   * paths. The MCP surface's explicit re-authorization uses [revokeLiveFamiliesFor] instead, for
   * the reason stated there.
   */
  internal fun revokeForUser(pod: PodId, clientId: String, webId: String): Long =
    revokeForUser(pod, clientId, listOf(webId))

  /**
   * The same, for every URI that names the same person.
   *
   * A pod stores whichever WebID authenticated at the time, so an authorization can hold families
   * under an alias while its owner is signed in under their canonical URI. Revoking one of them and
   * calling that a withdrawal leaves the connection the person meant to end running.
   */
  internal fun revokeForUser(pod: PodId, clientId: String, webIds: Collection<String>): Long =
    ownerFilter(pod, clientId, webIds)?.let { revokeWhere(it, reason = "withdrawn grant") } ?: 0

  /**
   * Revokes every row this person holds on the pod, whichever app it belongs to — the sign-out path.
   *
   * One write over the owner filter, which also catches a family inserted while it runs. That is
   * wanted here: no consent completes beside a sign-out that such a family could belong to. Rotated
   * rows are revoked too, and that is what reaches a rotation in flight — its successor lands after
   * this sweep, and [noLongerStands] then finds the predecessor revoked.
   */
  internal fun revokeForPerson(pod: PodId, webIds: Collection<String>): Long {
    val distinct = webIds.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return 0
    return revokeWhere(
      Filters.and(
        podFilter(pod),
        Filters.`in`(FIELD_WEB_ID, distinct),
      ),
      reason = "sign-out",
    )
  }

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
  internal fun revokeLiveFamiliesFor(pod: PodId, clientId: String, webIds: Collection<String>): Long =
    revokeFamilies(liveFamilies(pod, clientId, webIds))

  /** Families selected for an explicit re-authorization, including rotated rows. */
  internal fun liveFamilies(pod: PodId, clientId: String, webIds: Collection<String>): Set<String> =
    ownerFilter(pod, clientId, webIds)?.let(store::familiesWhere) ?: emptySet()

  /** Ends the named families, including successors inserted since the caller selected them. */
  internal fun revokeFamilies(familyIds: Collection<String>): Long =
    if (familyIds.isEmpty()) 0
    else revokeWhere(Filters.`in`(Field.FAMILY_ID, familyIds), reason = "reauthorization")

  /**
   * Retires excess live families after a code exchange has inserted its new family.
   * [webIds] must include the person's derivable aliases. Expired and revoked rows do not count.
   * Every sweep protects the live family with the highest [Owner.familyOrder]. Concurrent
   * exchanges therefore agree on one survivor; an older exchange may itself be retired and must
   * check its token before returning success. Among the others, last rotation (or issuance for an
   * unused family) orders victims, with the id breaking ties. If [newFamilyId] has already ended,
   * this sweep does nothing. Rotated rows count while their successor is being inserted.
   * Any revoked row excludes its family, including a tip revoked by the grant cascade while
   * rotated predecessors remain for reuse detection.
   */
  internal fun enforceConnectionLimit(
    pod: PodId,
    clientId: String,
    webIds: Collection<String>,
    newFamilyId: String,
  ) {
    connectionLimitVictims(pod, clientId, webIds, newFamilyId).forEach { revokeFamily(it, reason = "limit") }
  }

  /** A snapshot of the families selected for retirement; rotations of those families share their fate. */
  internal fun connectionLimitVictims(
    pod: PodId,
    clientId: String,
    webIds: Collection<String>,
    newFamilyId: String,
  ): List<String> {
    val owner = ownerFilter(pod, clientId, webIds) ?: return emptyList()
    val families = tokens.aggregate(listOf(
      Aggregates.match(Filters.and(
        owner,
        Filters.gt(Field.EXPIRES_AT, Instant.now()),
      )),
      Document("\$group", Document("_id", "\$${Field.FAMILY_ID}")
        .append("lastUsed", Document("\$max", Document("\$ifNull", listOf("\$${Field.ROTATED_AT}", "\$${Field.ISSUED_AT}"))))
        .append("revokedAt", Document("\$max", "\$${Field.REVOKED_AT}"))
        .append(FIELD_FAMILY_ORDER, Document("\$max", "\$$FIELD_FAMILY_ORDER"))),
      Aggregates.match(Filters.eq("revokedAt", null)),
      Document("\$sort", Document("lastUsed", 1).append("_id", 1)),
    )).map { Family(it.getString("_id"), it.getLong(FIELD_FAMILY_ORDER) ?: 0L) }.toList()
    if (families.none { it.id == newFamilyId }) return emptyList()
    val protected = families.maxWith(compareBy<Family> { it.order }.thenBy { it.id }).id
    return families.filterNot { it.id == protected }
      .take((families.size - maxFamilies).coerceAtLeast(0))
      .map { it.id }
  }

  private data class Family(val id: String, val order: Long)

  /** The first recorded reason survives later sweeps, including the cleanup of an in-flight rotation. */
  internal fun revocationReason(familyId: String): String? = tokens.find(Filters.and(
    Filters.eq(Field.FAMILY_ID, familyId),
    Filters.ne(Field.REVOKED_AT, null),
  )).sort(Sorts.ascending(Field.REVOKED_AT)).first()?.getString(FIELD_REVOCATION_REASON)

  internal fun revocationDescription(familyId: String): String =
    if (revocationReason(familyId) == "limit") "refresh token retired: connection limit"
    else "refresh token revoked"

  /** Records the cause atomically with revocation and logs every affected family. */
  private fun revokeWhere(filter: Bson, reason: String): Long {
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    val changed = tokens.updateMany(
      Filters.and(filter, Filters.eq(Field.REVOKED_AT, null)),
      Updates.combine(Updates.set(Field.REVOKED_AT, now), Updates.set(FIELD_REVOCATION_REASON, reason)),
    ).modifiedCount
    if (changed > 0) {
      tokens.find(Filters.and(filter, Filters.eq(Field.REVOKED_AT, now), Filters.eq(FIELD_REVOCATION_REASON, reason)))
        .projection(Document(Field.FAMILY_ID, 1).append(FIELD_CLIENT_ID, 1).append(FIELD_POD_NAME, 1))
        .toList().distinctBy { it.getString(Field.FAMILY_ID) }.forEach { row ->
          logger.info {
            "[oauth] family revoked: cause='$reason', pod='${row.getString(FIELD_POD_NAME)}', " +
                "clientId='${row.getString(FIELD_CLIENT_ID)}', familyId='${row.getString(Field.FAMILY_ID)}'"
          }
        }
    }
    return changed
  }

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
  internal fun liveTokens(pod: PodId, clientId: String, webIds: Collection<String>): Set<String> =
    ownerFilter(pod, clientId, webIds)?.let(store::liveTokensWhere) ?: emptySet()

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
    else revokeWhere(
      Filters.and(
        Filters.`in`(RefreshTokenStore.Field.TOKEN_HASH, tokenHashes),
        Filters.eq(RefreshTokenStore.Field.ROTATED_AT, null),
      ),
      reason = "withdrawn grant",
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
  private fun ownerFilter(pod: PodId, clientId: String, webIds: Collection<String>): Bson? {
    val distinct = webIds.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return null
    return Filters.and(
      podFilter(pod),
      Filters.eq(FIELD_CLIENT_ID, clientId),
      Filters.`in`(FIELD_WEB_ID, distinct),
    )
  }

  /**
   * Hard-deletes every refresh token of the pod — the pod-cascade delete path, where family
   * revocation is moot because the pod itself is gone.
   */
  internal fun deleteByPod(pod: PodId): Long = store.deleteWhere(podFilter(pod))

  /** Diagnostics and tests: used to assert that family-wide revocation happened. */
  internal fun findByFamily(familyId: String): List<PodRefreshToken> = store.findByFamily(familyId)

  private fun podFilter(pod: PodId): Bson = Filters.eq(FIELD_POD_ID, pod.objectId())

  private companion object {
    val logger = KotlinLogging.logger {}

    /** Optional cause on revoked rows; absent on credentials written before cause tracking. */
    const val FIELD_REVOCATION_REASON = "revocationReason"

    // The owner's field names, which are this class's rather than the shared store's. The order of
    // the four is the order they are written in, and a row on disk carries it.
    const val FIELD_POD_ID = "podId"
    const val FIELD_POD_NAME = "podName"
    const val FIELD_CLIENT_ID = "clientId"
    const val FIELD_WEB_ID = "webId"
    const val FIELD_FAMILY_ORDER = "familyOrder"
  }
}
