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
 * non-unique version, retiring the rows that would refuse it.
 *
 * A duplicate loses its `fingerprint`, not its row: the pod's grants are keyed
 * `(pod, client_id, WebID)`, so deleting it would drop what a person allowed. Unsetting the field
 * takes it out of the partial index and out of every lookup, which is what that filter is for.
 *
 * **It must not reach for `DynamicClientRegistrationDao`**: the DAO builds this index in its
 * constructor, which would happen before the duplicates are gone. This update is [blocking] so it
 * finishes while Guice builds the injector, and the DAO is a lazy singleton nothing eager pulls in.
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
   * Builds the index, and sweeps only where the build says there is something to sweep.
   *
   * MongoDB refuses a unique index over duplicate data, so a build that succeeds has proved there
   * are none — one command on a database that has already run this, where scanning first would
   * group an unbounded collection on every boot. A refusal is also what a rolling upgrade produces,
   * since the replica still on the old build serves `/register` without the constraint, so each one
   * sweeps and asks again. Bounded at [ATTEMPTS], because the writer is another process; the last
   * failure is raised, so a boot that could not establish the constraint says so.
   */
  override fun run() {
    val registrations = db.getCollection(collectionName)
    repeat(ATTEMPTS) { attempt ->
      try {
        if (DcrFingerprintIndex.replaceOn(registrations)) {
          logger.info { "[sempods/updates] $name: replaced the non-unique fingerprint index" }
        }
        return
      } catch (e: DuplicateKeyException) {
        // What a failed index *build* throws; the DAO's insert against the same constraint gets a
        // `MongoWriteException` instead. Two commands, two types.
        if (attempt == ATTEMPTS - 1) throw e
        retireDuplicateFingerprints(registrations)
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
      // Newest first, so a re-registering client keeps the id it was last handed. `_id` breaks the
      // tie because equal timestamps are the ordinary case here — these rows come from two inserts
      // racing inside one millisecond, and BSON stores milliseconds. Without a second key two
      // replicas can pick different winners and unset each other's.
      val rows = group.getList(ROWS, Document::class.java).sortedWith(
        compareByDescending<Document> {
          it.getInstant(DynamicClientRegistrationDboFields.registeredAt) ?: Instant.EPOCH
        }.thenByDescending { it.getObjectId(DynamicClientRegistrationDboFields.id) },
      )
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

    /** Build-and-sweep passes before a duplicate written by another process is somebody's problem. */
    const val ATTEMPTS = 3

    /** Field names inside this update's own aggregation output — not part of the stored shape. */
    const val POD = "pod"
    const val FINGERPRINT = "fingerprint"
    const val ROWS = "rows"
    const val COUNT = "count"
  }
}
