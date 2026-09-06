package org.sempods.updates

import com.google.inject.Inject
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import io.mockk.every
import io.mockk.spyk
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.api.pod.system.auth.DcrFingerprintIndex
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the update leaves behind: the unique fingerprint index, on a collection that can take it.
 *
 * The two things in its way are rows sharing a fingerprint and the non-unique index the old build
 * created. What it must **not** remove is a row — a `client_id` is what the pod's grants hang off,
 * so a duplicate is retired by dropping out of the lookup rather than by being deleted — and it
 * must not remove a unique index either, which is what a second boot would do if the drop were
 * decided by a read taken before it.
 */
class DcrFingerprintUniquenessTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private val podId = ObjectId()
  private val otherPodId = ObjectId()

  @Test
  fun `a duplicate group keeps every row, and only the newest keeps the fingerprint`() {
    val collectionName = ownStore("dcr")
    val registrations = db.getCollection(collectionName)
    // The index the old build created. Non-unique, so it is the one the new options conflict with.
    registrations.createIndex(
      Indexes.ascending(POD_FIELD, FINGERPRINT_FIELD),
      IndexOptions().partialFilterExpression(Filters.exists(FINGERPRINT_FIELD, true)),
    )
    registrations.row("dyn:older", fingerprint = "fp-1", registeredAt = REGISTERED_AT)
    registrations.row("dyn:newest", fingerprint = "fp-1", registeredAt = REGISTERED_AT.plusSeconds(3600))
    registrations.row("dyn:middle", fingerprint = "fp-1", registeredAt = REGISTERED_AT.plusSeconds(60))
    // Neither of these is a duplicate: the digest is scoped per pod, and a row from before the
    // dedup existed carries none at all.
    registrations.row("dyn:elsewhere", podId = otherPodId, fingerprint = "fp-1")
    registrations.row("dyn:unfingerprinted")

    runUpdate(collectionName)

    assertEquals(5, registrations.countDocuments(), "a duplicate loses its fingerprint, never its row")
    assertEquals("fp-1", registrations.fingerprintOf("dyn:newest"), "the row the lookup answered keeps it")
    assertNull(registrations.fingerprintOf("dyn:older"))
    assertNull(registrations.fingerprintOf("dyn:middle"))
    assertEquals("fp-1", registrations.fingerprintOf("dyn:elsewhere"), "another pod's client is no duplicate")

    // The whole point of the update: the constraint is in place when it returns, and the DAO's own
    // `createIndex` — same definition — is then a no-op rather than a boot failure.
    assertEquals(
      true,
      assertNotNull(registrations.fingerprintIndex()).getBoolean("unique"),
      "the update leaves the unique index, not merely room for one",
    )
    assertEquals(
      listOf(DcrFingerprintIndex.UNIQUE_NAME),
      registrations.fingerprintIndexNames(),
      "and clears the predecessor away rather than leaving a second index over the same fields",
    )
    DcrFingerprintIndex.createOn(registrations)
  }

  @Test
  fun `a second run leaves the unique index it built in place`() {
    // Nothing records that an update ran, so every entry runs on every boot — including the boot
    // after the one that finished the work, and including a boot running beside a replica that is
    // already serving. The drop is therefore driven by the conflict `createIndex` raises and never
    // by a name read earlier: a second run that dropped what the first built would leave the other
    // replica accepting the duplicate registrations this exists to refuse.
    val collectionName = ownStore("dcr")
    val registrations = db.getCollection(collectionName)
    registrations.row("dyn:older", fingerprint = "fp-1", registeredAt = REGISTERED_AT)
    registrations.row("dyn:newest", fingerprint = "fp-1", registeredAt = REGISTERED_AT.plusSeconds(3600))

    runUpdate(collectionName)
    val built = assertNotNull(registrations.fingerprintIndex()).getString("name")
    runUpdate(collectionName)

    assertEquals("fp-1", registrations.fingerprintOf("dyn:newest"))
    assertNull(registrations.fingerprintOf("dyn:older"))
    val after = assertNotNull(registrations.fingerprintIndex(), "the second run must not leave the index dropped")
    assertEquals(true, after.getBoolean("unique"))
    assertEquals(built, after.getString("name"))
  }

  @Test
  fun `an index built while this update was already running is kept, not dropped`() {
    // The concurrent boot, played out: this replica reads a collection whose index is the old
    // non-unique one, another replica replaces it, and only then does this one get to its own
    // create. `DcrFingerprintIndex.replaceOn` asks MongoDB rather than a remembered name, so the
    // create simply succeeds against the index that is now there.
    val collectionName = ownStore("dcr")
    val registrations = db.getCollection(collectionName)
    registrations.createIndex(
      Indexes.ascending(POD_FIELD, FINGERPRINT_FIELD),
      IndexOptions().partialFilterExpression(Filters.exists(FINGERPRINT_FIELD, true)),
    )
    registrations.row("dyn:only", fingerprint = "fp-1")
    // The other replica, finishing first.
    DcrFingerprintIndex.replaceOn(registrations)

    runUpdate(collectionName)

    assertEquals(
      true,
      assertNotNull(registrations.fingerprintIndex(), "the constraint must still be there").getBoolean("unique"),
    )
  }

  @Test
  fun `a duplicate written while the sweep was running is swept too`() {
    // The rolling upgrade: the replica still on the old build serves `/register` without the
    // constraint the whole time this runs, so it can land a duplicate after the sweep has passed
    // that group and before the index is built. MongoDB refuses the build with E11000, and
    // `SempodsUpdater` would log that and carry on — leaving the deployment with no constraint at
    // all. The spy writes the row exactly where the other replica would.
    val collectionName = ownStore("dcr")
    val registrations = db.getCollection(collectionName)
    registrations.row("dyn:first", fingerprint = "fp-1", registeredAt = REGISTERED_AT)

    var wrote = false
    val racing = spyk(registrations)
    every { racing.createIndex(any<Bson>(), any<IndexOptions>()) } answers {
      if (!wrote) {
        wrote = true
        // Later than the row the sweep saw, because it is written later — which is what makes it
        // the one the next sweep keeps.
        registrations.row("dyn:written-mid-sweep", fingerprint = "fp-1", registeredAt = REGISTERED_AT.plusSeconds(60))
      }
      callOriginal()
    }
    val database = spyk(db)
    every { database.getCollection(collectionName) } returns racing

    runUpdate(collectionName, database)

    assertEquals(
      true,
      assertNotNull(registrations.fingerprintIndex(), "the constraint must be established").getBoolean("unique"),
    )
    assertEquals(2, registrations.countDocuments(), "and the late row keeps its client_id")
    assertNull(registrations.fingerprintOf("dyn:first"), "the newer of the two is the one that keeps it")
    assertEquals("fp-1", registrations.fingerprintOf("dyn:written-mid-sweep"))
  }

  @Test
  fun `two rows written in the same millisecond retire the same way twice`() {
    // The ordinary case here, not an exotic one: these duplicates come from two inserts racing
    // inside one millisecond, and BSON stores milliseconds. Ordering on `registeredAt` alone leaves
    // the winner to whatever the aggregation returned first, so two replicas sweeping at once can
    // each unset the row the other kept — and the group ends with no fingerprint at all, which
    // sends the client off to register a third `client_id` and orphans both grant sets.
    val first = ownStore("dcr")
    val second = ownStore("dcr")
    val tied = listOf(ObjectId(), ObjectId(), ObjectId())

    // Two collections holding the same group, seeded in opposite orders — which is all a second
    // replica's aggregation has to do differently.
    tied.forEach { db.getCollection(first).row("dyn:$it", id = it, fingerprint = "fp-1") }
    tied.reversed().forEach { db.getCollection(second).row("dyn:$it", id = it, fingerprint = "fp-1") }

    runUpdate(first)
    runUpdate(second)

    val keptFirst = db.getCollection(first).clientIdsHoldingFingerprint()
    assertEquals(1, keptFirst.size, "exactly one row keeps the fingerprint")
    assertEquals(
      keptFirst,
      db.getCollection(second).clientIdsHoldingFingerprint(),
      "and it is the same row whatever order the rows came back in",
    )
    assertEquals("dyn:${tied.maxOrNull()}", keptFirst.single(), "the tie goes to the highest _id")
  }

  @Test
  fun `a replica that finished first keeps its index, and this one does not report a failure`() {
    // The interleaving being refused does not rule out: both replicas are told 85 before either
    // drops, so the second one acts on a refusal that is already stale. Dropping by key pattern
    // would take the unique index the first replica built — MongoDB derives both names from the
    // same key pattern, so by that handle they are one thing — and the gap that opens is not
    // self-correcting: two `/register` calls landing in it both insert and both return an id, and
    // the sweep unsets one row's fingerprint while the id it handed out keeps its own grants.
    val collectionName = ownStore("dcr")
    val registrations = db.getCollection(collectionName)
    registrations.createIndex(
      Indexes.ascending(POD_FIELD, FINGERPRINT_FIELD),
      IndexOptions().partialFilterExpression(Filters.exists(FINGERPRINT_FIELD, true)),
    )
    registrations.row("dyn:only", fingerprint = "fp-1")

    val racing = spyk(registrations)
    every { racing.dropIndex(any<String>()) } answers {
      // The other replica, finishing the whole replacement between this one's read and its drop.
      DcrFingerprintIndex.replaceOn(registrations)
      callOriginal()
    }
    val database = spyk(db)
    every { database.getCollection(collectionName) } returns racing

    runUpdate(collectionName, database)

    val index = assertNotNull(registrations.fingerprintIndex(), "the constraint must still be there")
    assertEquals(true, index.getBoolean("unique"))
    assertEquals(DcrFingerprintIndex.UNIQUE_NAME, index.getString("name"))
  }

  private fun MongoCollection<Document>.clientIdsHoldingFingerprint(): Set<String> =
    find(Filters.exists(FINGERPRINT_FIELD, true)).map { it.getString("clientId") }.toSet()

  private fun runUpdate(collectionName: String, database: MongoDatabase = db) {
    val update = DcrFingerprintUniqueness(collectionName)
    // Reflection rather than `injectMembers`, so a test can hand it a database of its own —
    // `SempodsUpdaterTest` reaches the updater's own list the same way.
    DcrFingerprintUniqueness::class.java.getDeclaredField("db")
      .apply { isAccessible = true }
      .set(update, database)
    update.run()
  }

  private fun MongoCollection<Document>.row(
    clientId: String,
    podId: ObjectId = this@DcrFingerprintUniquenessTest.podId,
    fingerprint: String? = null,
    registeredAt: Instant = REGISTERED_AT,
    id: ObjectId = ObjectId(),
  ) {
    val document = Document("_id", id)
      .append("clientId", clientId)
      .append(POD_FIELD, podId)
      .append("registeredForPodName", "alice")
      .append("registeredAt", Date.from(registeredAt))
      .append("redirectUris", listOf("https://app.example.org/cb"))
    if (fingerprint != null) document.append(FINGERPRINT_FIELD, fingerprint)
    insertOne(document)
  }

  private fun MongoCollection<Document>.fingerprintOf(clientId: String): String? =
    assertNotNull(
      find(Filters.eq("clientId", clientId)).first(),
      "row '$clientId' must survive the update",
    ).getString(FINGERPRINT_FIELD)

  /** The constraint, by the name it is built under — not by key pattern, which the predecessor shares. */
  private fun MongoCollection<Document>.fingerprintIndex(): Document? =
    listIndexes().firstOrNull { it.getString("name") == DcrFingerprintIndex.UNIQUE_NAME }

  /** Every index over the key pattern, so a test can say the predecessor is gone rather than assume it. */
  private fun MongoCollection<Document>.fingerprintIndexNames(): List<String> =
    listIndexes()
      .filter { it.get("key", Document::class.java)?.keys?.toList() == listOf(POD_FIELD, FINGERPRINT_FIELD) }
      .map { it.getString("name") }
      .toList()

  private companion object {
    const val POD_FIELD = "registeredForPodId"
    const val FINGERPRINT_FIELD = "fingerprint"
    val REGISTERED_AT: Instant = Instant.parse("2026-08-16T10:15:30.123Z")
  }
}
