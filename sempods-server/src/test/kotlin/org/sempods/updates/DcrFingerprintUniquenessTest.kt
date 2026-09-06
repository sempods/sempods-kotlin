package org.sempods.updates

import com.google.inject.Inject
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
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

  private fun runUpdate(collectionName: String) {
    val update = DcrFingerprintUniqueness(collectionName)
    injector.injectMembers(update)
    update.run()
  }

  private fun MongoCollection<Document>.row(
    clientId: String,
    podId: ObjectId = this@DcrFingerprintUniquenessTest.podId,
    fingerprint: String? = null,
    registeredAt: Instant = REGISTERED_AT,
  ) {
    val document = Document("_id", ObjectId())
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

  private fun MongoCollection<Document>.fingerprintIndex(): Document? =
    listIndexes().firstOrNull {
      it.get("key", Document::class.java)?.keys?.toList() == listOf(POD_FIELD, FINGERPRINT_FIELD)
    }

  private companion object {
    const val POD_FIELD = "registeredForPodId"
    const val FINGERPRINT_FIELD = "fingerprint"
    val REGISTERED_AT: Instant = Instant.parse("2026-08-16T10:15:30.123Z")
  }
}
