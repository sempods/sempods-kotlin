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
 * Two things to do, and the first one asks whether the second is needed:
 *
 * 1. **Build the index**, through [DcrFingerprintIndex.replaceOn], which builds it beside the
 *    predecessor and clears that away afterwards. The order is the point and its KDoc carries why:
 *    the index has a name of its own, so the constraint is in place before anything is dropped and
 *    a second replica cannot drop what the first has just built.
 * 2. **Retire the rows sharing a fingerprint**, where the build says there are some. They exist
 *    because the dedup was a lookup and not a constraint: two registrations of one client arriving
 *    together both missed and both inserted. All but the newest of each group lose their
 *    `fingerprint` — not the row, and not the `client_id`, because the pod's grants are keyed
 *    `(pod, client_id, WebID)` and deleting the row would drop what a person allowed under it.
 *    Unsetting the field takes the row out of the partial index and out of every future lookup,
 *    which is what the partial filter was built for.
 *
 * Idempotent, and cheap once it has run: a build that succeeds proves there is nothing to sweep.
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
   * Builds the index, and sweeps only when the build says there is something to sweep.
   *
   * **The build is the question, not the sweep.** MongoDB refuses to build a unique index over
   * duplicate data, so a `createIndex` that succeeds has proved the collection has no duplicates —
   * and once the index stands, none can be written. Asking that way costs one command on a
   * database that has already run this, where scanning first would group the whole of the pod
   * server's second unbounded collection on every boot, before Jetty accepts a request, forever.
   *
   * The refusal is also what a rolling upgrade produces: the replica still on the old build serves
   * `/register` without the constraint the whole time this runs, so it can land a duplicate
   * between a sweep and the next build. Each refusal sweeps and asks again.
   *
   * Bounded rather than open-ended, because the writer is another process and no number of passes
   * can promise it stops. Three is enough to make losing all of them a coincidence, and the last
   * failure is raised rather than swallowed — a boot that could not establish the constraint says
   * so, and the next one tries again.
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
        // The shape a failed *index build* takes. An `insertOne` that hits the same constraint
        // arrives as `MongoWriteException` instead, which is what the DAO reads — one collection,
        // two exception types, because they come from two commands.
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
      // Newest first, so a client that re-registers after the update keeps the id it was last
      // handed. `_id` breaks the tie, and it is not a formality: these duplicates come from two
      // inserts racing inside one millisecond, and BSON stores milliseconds — so equal timestamps
      // are the ordinary case here, not the exotic one. Without a second key the winner is
      // whatever order the aggregation happened to return, which two replicas sweeping at once
      // can answer differently, each unsetting the row the other kept and leaving the group with
      // no fingerprint at all. An ObjectId is monotonic and reads the same everywhere.
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

    /** Sweep-and-build passes before a duplicate written by another process is somebody's problem. */
    const val ATTEMPTS = 3

    /** Field names inside this update's own aggregation output — not part of the stored shape. */
    const val POD = "pod"
    const val FINGERPRINT = "fingerprint"
    const val ROWS = "rows"
    const val COUNT = "count"
  }
}
