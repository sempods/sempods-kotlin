package org.sempods.updates

import com.google.inject.Inject
import com.mongodb.DuplicateKeyException
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.sempods.SempodsCollections
import org.sempods.api.pod.system.auth.DcrFingerprintIndex
import org.sempods.api.pod.system.auth.DynamicClientRegistrationDboFields
import org.sempods.commons.mongo.getInstant
import java.time.Instant

/**
 * Puts the unique `(registeredForPodId, fingerprint)` index in place on a database that ran the
 * non-unique version.
 *
 * Two things to do, in this order:
 *
 * 1. **Retire the rows sharing a fingerprint.** They exist because the dedup was a lookup and not a
 *    constraint: two registrations of one client arriving together both missed and both inserted.
 *    All but the newest of each group lose their `fingerprint` — not the row, and not the
 *    `client_id`, because the pod's grants are keyed `(pod, client_id, WebID)` and deleting the row
 *    would drop what a person allowed under it. Unsetting the field takes the row out of the
 *    partial index and out of every future lookup, which is what the partial filter was built for.
 * 2. **Build the index**, through [DcrFingerprintIndex.replaceOn], which drops the predecessor
 *    holding the key pattern only when `createIndex` has just refused to sit beside it. That
 *    method's KDoc carries why the drop is driven by the conflict rather than by a prior read, and
 *    what a concurrent boot is still exposed to.
 *
 * Idempotent in both halves: a second run finds no groups and an index that needs no replacing.
 *
 * **It must not reach for `DynamicClientRegistrationDao`**, and works the collection directly
 * instead. The DAO builds this same index in its constructor — injecting it here would build it
 * before the duplicates are gone, and `createIndex` would then throw on them. Ordering is what
 * keeps that safe: this update is [blocking], so it finishes while Guice builds the injector, and
 * the DAO is a lazy singleton nothing eager pulls in, so it is not constructed until the first
 * request needs it.
 *
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md`
 *   §"Conventions" states.
 */
class DcrFingerprintUniqueness(
  private val collectionName: String = SempodsCollections.OAUTH_CLIENT_REGISTRATIONS,
) : SempodsUpdate {

  @Inject
  private lateinit var db: MongoDatabase

  override val name = "dcr-fingerprint-uniqueness"

  /**
   * Blocking: a `/register` served while duplicates are still in place would mint a third
   * `client_id` for the same client — the failure this whole change exists to close.
   */
  override val blocking = true

  /**
   * Sweeps and builds, and sweeps again where a registration written in between shares a
   * fingerprint.
   *
   * The two steps are not one statement, and during a rolling upgrade the replica still on the old
   * build is serving `/register` without the constraint the whole time — so a client can land a
   * duplicate after the sweep has passed its group and before the index is built. MongoDB then
   * refuses the build with `E11000`, and `SempodsUpdater` logs that and carries on: the deployment
   * would come up with no constraint at all and nothing to say so but one line.
   *
   * Bounded rather than open-ended, because the writer is another process and no number of passes
   * can promise it stops. Three is enough to make losing all of them a coincidence, and the last
   * failure is raised rather than swallowed — a boot that could not establish the constraint says
   * so, and the next one sweeps again.
   */
  override fun run() {
    val registrations = db.getCollection(collectionName)
    repeat(ATTEMPTS) { attempt ->
      retireDuplicateFingerprints(registrations)
      try {
        if (DcrFingerprintIndex.replaceOn(registrations)) {
          logger.info { "[sempods/updates] $name: replaced the non-unique fingerprint index" }
        }
        return
      } catch (e: DuplicateKeyException) {
        // The shape a failed *index build* takes. An `insertOne` that hits the same constraint
        // arrives as `MongoWriteException` instead, which is what the DAO reads — one collection,
        // two exception types, because they come from two commands.
        if (attempt == ATTEMPTS - 1) throw e
        logger.info {
          "[sempods/updates] $name: a registration written since the sweep shares a fingerprint " +
            "— sweeping again (attempt ${attempt + 2} of $ATTEMPTS)"
        }
      }
    }
  }

  /** Takes every duplicate but the newest of each group out of the fingerprint lookup. */
  private fun retireDuplicateFingerprints(registrations: MongoCollection<Document>) {
    val groups = registrations.aggregate(
      listOf(
        Aggregates.match(Filters.exists(DynamicClientRegistrationDboFields.fingerprint, true)),
        Aggregates.group(
          Document(POD, "\$${DynamicClientRegistrationDboFields.registeredForPodId}")
            .append(FINGERPRINT, "\$${DynamicClientRegistrationDboFields.fingerprint}"),
          Accumulators.push(
            ROWS,
            Document(DynamicClientRegistrationDboFields.id, "\$${DynamicClientRegistrationDboFields.id}")
              .append(
                DynamicClientRegistrationDboFields.registeredAt,
                "\$${DynamicClientRegistrationDboFields.registeredAt}",
              ),
          ),
          Accumulators.sum(COUNT, 1),
        ),
        Aggregates.match(Filters.gt(COUNT, 1)),
      ),
    ).allowDiskUse(true)

    var unsetRows = 0L
    var groupCount = 0
    for (group in groups) {
      // Newest first, which is the row the lookup answered before this index existed — so a client
      // that re-registers after the update keeps the id it was last handed.
      val rows = group.getList(ROWS, Document::class.java)
        .sortedByDescending { it.getInstant(DynamicClientRegistrationDboFields.registeredAt) ?: Instant.EPOCH }
      val losers = rows.drop(1).map { it.getObjectId(DynamicClientRegistrationDboFields.id) }
      if (losers.isEmpty()) continue
      groupCount++
      unsetRows += registrations.updateMany(
        Filters.`in`(DynamicClientRegistrationDboFields.id, losers),
        Updates.unset(DynamicClientRegistrationDboFields.fingerprint),
      ).modifiedCount
    }
    if (groupCount > 0) {
      logger.info {
        "[sempods/updates] $name: $unsetRows registration(s) in $groupCount duplicate group(s) " +
          "kept their client_id and dropped out of the fingerprint lookup"
      }
    }
  }

  private companion object {

    private val logger = KotlinLogging.logger {}

    /** Sweep-and-build passes before a duplicate written by another process is somebody's problem. */
    const val ATTEMPTS = 3

    /** Field names inside this update's own aggregation output — not part of the stored shape. */
    const val POD = "pod"
    const val FINGERPRINT = "fingerprint"
    const val ROWS = "rows"
    const val COUNT = "count"
  }
}
