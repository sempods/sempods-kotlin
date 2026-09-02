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
  internal fun revokeForUser(podId: ObjectId, clientId: String, webIds: Collection<String>): Long {
    val distinct = webIds.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return 0
    return store.revokeWhere(
      Filters.and(
        Filters.eq(FIELD_POD_ID, podId),
        Filters.eq(FIELD_CLIENT_ID, clientId),
        Filters.`in`(FIELD_WEB_ID, distinct),
      ),
    )
  }

  /**
   * Revokes every token within [podId] whose `scopes` array holds any of
   * `<contextUri>#read|write|manage`.
   *
   * The context-cascade delete path, so a token issued before the context was dropped cannot be
   * exchanged after the fact — critical for the re-create-with-same-URI window, where the live
   * context-existence check at write time would otherwise let a new context inherit the old token's
   * authority. Access tokens are self-contained and unaffected; they expire after an hour.
   *
   * `Filters.in` against an array field matches when **any** element is in the supplied list, which
   * is exactly the semantics wanted here.
   */
  internal fun revokeByContextScope(podId: ObjectId, contextUri: String): Long =
    store.revokeWhere(
      Filters.and(
        Filters.eq(FIELD_POD_ID, podId),
        Filters.`in`(
          RefreshTokenStore.Field.SCOPES,
          listOf("$contextUri#read", "$contextUri#write", "$contextUri#manage"),
        ),
      ),
    )

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
