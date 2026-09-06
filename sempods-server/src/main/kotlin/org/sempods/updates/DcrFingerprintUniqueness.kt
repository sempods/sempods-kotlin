package org.sempods.updates

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.sempods.SempodsCollections
import org.sempods.api.pod.system.auth.DynamicClientRegistrationDboFields
import org.sempods.commons.mongo.getInstant
import java.time.Instant

/**
 * Clears the way for the unique `(registeredForPodId, fingerprint)` index that
 * `DynamicClientRegistrationDao` creates.
 *
 * Two things stand in its way on a database that ran the non-unique version, and both are removed
 * here:
 *
 * 1. **Rows sharing a fingerprint.** They exist because the dedup was a lookup and not a
 *    constraint: two registrations of one client arriving together both missed and both inserted.
 *    All but the newest of each group lose their `fingerprint` — not the row, and not the
 *    `client_id`, because the pod's grants are keyed `(pod, client_id, WebID)` and deleting the row
 *    would drop what a person allowed under it. Unsetting the field takes the row out of the
 *    partial index and out of every future lookup, which is what the partial filter was built for.
 * 2. **The old index itself.** `createIndex` answers `IndexOptionsConflict` for an existing index
 *    whose options differ, so the non-unique one has to go before the unique one can be built.
 *
 * Idempotent in both halves: a second run finds no groups and no non-unique index.
 *
 * **It must not reach for `DynamicClientRegistrationDao`**, and works the collection directly
 * instead. The DAO builds the very index this clears the way for, in its constructor — injecting it
 * here would build the index before the duplicates are gone. Ordering is what keeps that safe:
 * this update is [blocking], so it finishes while Guice builds the injector, and the DAO is a lazy
 * singleton nothing eager pulls in, so it is not constructed until the first request needs it.
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

  override fun run() {
    val registrations = db.getCollection(collectionName)

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

    // The old index, dropped by name rather than by key spec: `dropIndex(Bson)` would also match
    // the unique one this build creates, so a re-run after a successful boot would drop what it
    // just asked for. Only an index over exactly these two fields that is not unique is this one.
    val stale = registrations.listIndexes().firstOrNull { index ->
      index.get("key", Document::class.java)?.keys?.toList() == OLD_INDEX_FIELDS &&
        index.getBoolean("unique") != true
    }
    if (stale != null) {
      val indexName = stale.getString("name")
      registrations.dropIndex(indexName)
      logger.info { "[sempods/updates] $name: dropped the non-unique index '$indexName'" }
    }
  }

  private companion object {

    private val logger = KotlinLogging.logger {}

    /** Field names inside this update's own aggregation output — not part of the stored shape. */
    const val POD = "pod"
    const val FINGERPRINT = "fingerprint"
    const val ROWS = "rows"
    const val COUNT = "count"

    val OLD_INDEX_FIELDS: List<String> = listOf(
      DynamicClientRegistrationDboFields.registeredForPodId,
      DynamicClientRegistrationDboFields.fingerprint,
    )
  }
}
