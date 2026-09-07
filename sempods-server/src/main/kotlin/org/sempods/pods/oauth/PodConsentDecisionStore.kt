package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import org.bson.Document
import org.bson.types.ObjectId
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * What a person decided about an app's credential lifetime, one document per authorization
 * `(podId, appId, webId)`.
 *
 * There are three answers and only two of them are a decision: **granted**, **refused**, and
 * **nothing recorded** — the last being every authorization made before the consent dialog offered
 * the choice. An absent document *is* that third state, which is why this is a document of its own
 * rather than a flag on a grant row: nothing has to be written for the pods that already exist, and
 * a refusal stays distinguishable from a silence. Reading them as the same thing is a bug in both
 * directions — it either ends connections nobody ended, or lets a withdrawal be ignored.
 *
 * [Decision.generation] rises with every answer, a refusal included, and with every other event
 * that resets what the authorization stands at — see [bumpGeneration]. It is written from the
 * first row because it cannot be reconstructed afterwards: it is what a later authorization code
 * or consent form is bound to, so that neither can outlive the consent that produced it.
 *
 * @param collectionName the production name sits on the `@Inject` constructor; a test points an
 *   instance at a collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md`
 *   §"Conventions" states.
 */
class PodConsentDecisionStore internal constructor(db: MongoDatabase, collectionName: String) {

  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.OAUTH_CONSENT_DECISIONS)

  private val decisions = db.getCollection(collectionName)

  init {
    decisions.createIndex(
      Indexes.ascending(FIELD_POD_ID, FIELD_APP_ID, FIELD_WEB_ID),
      IndexOptions().unique(true),
    )
  }

  /**
   * @param durable whether the person granted a connection that outlives the access token.
   * @param generation how many times this authorization has been answered. Rises on every write.
   */
  internal data class Decision(val durable: Boolean, val generation: Long, val decidedAt: Instant)

  /**
   * The decision this authorization holds, or null when none was ever recorded.
   *
   * [webIds] is a collection because a person is a set of equivalent URIs on every path that ends
   * access, and the newest answer wins where an alias carries one of its own — the same reason
   * `PodGrantsDao.fetchGrantStrings` takes a list rather than a WebID.
   */
  internal fun find(podId: ObjectId, appId: String, webIds: Collection<String>): Decision? {
    if (webIds.isEmpty()) return null
    return decisions.find(
      Filters.and(
        Filters.eq(FIELD_POD_ID, podId),
        Filters.eq(FIELD_APP_ID, appId),
        Filters.`in`(FIELD_WEB_ID, webIds),
      ),
    ).sort(Sorts.descending(FIELD_GENERATION)).first()?.toDecision()
  }

  /**
   * Write the answer a consent submission just produced, and return what now stands.
   *
   * `$inc` on the generation rather than a read-then-write: two screens submitting at once must
   * produce two generations, because the point of the counter is that the older submission can be
   * told apart from the newer one.
   */
  internal fun record(
    podId: ObjectId,
    appId: String,
    webId: String,
    durable: Boolean,
    at: Instant = Instant.now(),
  ): Decision {
    val decidedAt = at.truncatedTo(ChronoUnit.MILLIS)
    val updated = decisions.findOneAndUpdate(
      Filters.and(
        Filters.eq(FIELD_POD_ID, podId),
        Filters.eq(FIELD_APP_ID, appId),
        Filters.eq(FIELD_WEB_ID, webId),
      ),
      Updates.combine(
        Updates.set(FIELD_DURABLE, durable),
        Updates.set(FIELD_DECIDED_AT, Date.from(decidedAt)),
        Updates.inc(FIELD_GENERATION, 1L),
      ),
      FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
    )
    return checkNotNull(updated) { "upsert returned no document" }.toDecision()
  }

  /**
   * Raises the generation without answering anything, for an event that ends what this
   * authorization stood at while asking the person nothing. Returns how many documents moved.
   *
   * An explicit `authorize(reauthorize=true)` is such an event: it forces the person back through
   * consent, and every authorization code bound to the generation before it is spent — that is
   * what the token endpoint's own re-checks compare against.
   *
   * **A consent form is not.** `submitConsent` lets a mismatched generation through wherever the
   * app still holds something, which a forced reauthorization does not change, so a page rendered
   * just before one stays submittable. That is the coexistence rule I13 keeps on purpose — several
   * consent screens may be open at once — and the person submitting such a page ticked what it
   * shows. What the mismatch does refuse is a page whose app now holds nothing, which is the
   * disconnect it was rendered before.
   *
   * **No upsert.** An
   * authorization with nothing recorded has no generation to raise and needs none: the durable
   * connection is read from the decision, so without one no family is minted and there is nothing
   * for a racing exchange to walk away with.
   *
   * This is the ordering half of the same rule the exchange keeps. The caller raises the
   * generation *before* it sweeps what the client holds, so whichever of the two lands second sees
   * the first: a family minted before the sweep is revoked by it, and one minted after is given up
   * by the exchange, whose re-read then finds a generation its code does not carry. `$inc` in the
   * database rather than a timestamp comparison, so the answer does not depend on two replicas
   * agreeing about the time.
   *
   * [webIds] is the person's URI set, and every document among them moves: the authorization is
   * one thing however many URIs the pod recorded it under.
   */
  internal fun bumpGeneration(podId: ObjectId, appId: String, webIds: Collection<String>): Long {
    if (webIds.isEmpty()) return 0
    return decisions.updateMany(
      Filters.and(
        Filters.eq(FIELD_POD_ID, podId),
        Filters.eq(FIELD_APP_ID, appId),
        Filters.`in`(FIELD_WEB_ID, webIds),
      ),
      Updates.inc(FIELD_GENERATION, 1L),
    ).modifiedCount
  }

  /** The pod-cascade delete path, where the authorizations themselves are going away. */
  internal fun deleteByPod(podId: ObjectId): Long =
    decisions.deleteMany(Filters.eq(FIELD_POD_ID, podId)).deletedCount

  private fun Document.toDecision() = Decision(
    durable = getBoolean(FIELD_DURABLE, false),
    generation = get(FIELD_GENERATION, Number::class.java)?.toLong() ?: 0L,
    decidedAt = getInstant(FIELD_DECIDED_AT) ?: Instant.EPOCH,
  )

  private companion object {
    const val FIELD_POD_ID = "podId"
    const val FIELD_APP_ID = "appId"
    const val FIELD_WEB_ID = "webId"
    const val FIELD_DURABLE = "durable"
    const val FIELD_GENERATION = "generation"
    const val FIELD_DECIDED_AT = "decidedAt"
  }
}
