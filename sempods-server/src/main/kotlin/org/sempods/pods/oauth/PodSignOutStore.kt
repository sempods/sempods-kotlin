package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import org.bson.types.ObjectId
import org.bson.conversions.Bson
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import org.sempods.pods.PodId
import org.sempods.pods.mongo.persist.objectId
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * When a person last signed out of a pod, one document per `(pod, webId)`.
 *
 * A session cookie and an access token are signatures with no row to delete, so ending one early
 * needs a list of the ones that no longer stand. This is that list, kept as one instant per person:
 * everything issued to the person at or before it has ended, and nothing issued after it is touched.
 * A person signs out of everything or of nothing, which is why an instant is enough.
 *
 * The refresh families and the codes not yet exchanged are not read against this — `PodSignOut`
 * revokes the one and moves the consent generation under the other. What reads it is whatever can
 * only be refused at the moment it is presented.
 *
 * @param collectionName the production name sits on the `@Inject` constructor; a test points an
 *   instance at a collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md`
 *   §"Conventions" states.
 */
class PodSignOutStore internal constructor(db: MongoDatabase, collectionName: String) {

  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.OAUTH_SIGN_OUTS)

  private val signOuts = db.getCollection(collectionName)

  init {
    signOuts.createIndex(
      Indexes.ascending(FIELD_POD_ID, FIELD_WEB_ID),
      IndexOptions().unique(true),
    )
    signOuts.createIndex(
      Indexes.ascending(FIELD_EXPIRES_AT),
      IndexOptions().expireAfter(0L, TimeUnit.SECONDS),
    )
  }

  /**
   * Records a sign-out at [at] under every one of [webIds], and returns the instant stored.
   *
   * `$max` on both fields: two sign-outs landing at once must leave the later one standing, whichever
   * write arrives second. One upsert per URI, because that is how the rows this sits beside are keyed.
   */
  internal fun record(pod: PodId, webIds: Collection<String>, at: Instant = Instant.now()): Instant {
    val signedOutAt = at.truncatedTo(ChronoUnit.MILLIS)
    webIds.filter { it.isNotBlank() }.distinct().forEach { webId ->
      signOuts.updateOne(
        Filters.and(
          podFilter(pod),
          Filters.eq(FIELD_WEB_ID, webId),
        ),
        Updates.combine(
          Updates.max(FIELD_SIGNED_OUT_AT, Date.from(signedOutAt)),
          Updates.max(FIELD_EXPIRES_AT, Date.from(signedOutAt.plus(RETENTION))),
        ),
        UpdateOptions().upsert(true),
      )
    }
    return signedOutAt
  }

  /** The person's latest sign-out on this pod under any of [webIds], or null where there is none. */
  internal fun signedOutAt(pod: PodId, webIds: Collection<String>): Instant? {
    val distinct = webIds.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return null
    return signOuts.find(
      Filters.and(
        podFilter(pod),
        Filters.`in`(FIELD_WEB_ID, distinct),
      ),
    ).sort(Sorts.descending(FIELD_SIGNED_OUT_AT)).first()?.getInstant(FIELD_SIGNED_OUT_AT)
  }

  /** The pod-cascade delete path, where the people's credentials go with the pod. */
  internal fun deleteByPod(pod: PodId): Long =
    signOuts.deleteMany(podFilter(pod)).deletedCount

  private fun podFilter(pod: PodId): Bson = Filters.eq(FIELD_POD_ID, pod.objectId())

  internal companion object {

    /**
     * How long a sign-out is kept: until nothing issued before it could still be presented.
     *
     * A session is the longest-lived of those, and it cannot outlive thirty days from its sign-in —
     * `PodTokenIssuer.SESSION_ABSOLUTE_TTL_SECONDS`, which a test holds this to. An access token
     * lives an hour. The refresh families are revoked at the sign-out itself and need no row here.
     */
    internal val RETENTION: Duration = Duration.ofDays(30)

    private const val FIELD_POD_ID = "podId"
    private const val FIELD_WEB_ID = "webId"
    private const val FIELD_SIGNED_OUT_AT = "signedOutAt"
    private const val FIELD_EXPIRES_AT = "expiresAt"
  }
}
