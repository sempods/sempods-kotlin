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
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the update leaves behind for `DynamicClientRegistrationDao` to build its unique index on.
 *
 * The two things it removes are the two that would otherwise make `createIndex` throw: rows sharing
 * a fingerprint, and the non-unique index the old build created. What it must **not** remove is a
 * row — a `client_id` is what the pod's grants hang off, so a duplicate is retired by dropping out
 * of the lookup rather than by being deleted.
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
    assertNull(registrations.fingerprintIndex(), "the non-unique index has to go before the unique one exists")

    // The whole point of the update: the collection now takes the index the DAO asks for.
    registrations.createIndex(
      Indexes.ascending(POD_FIELD, FINGERPRINT_FIELD),
      IndexOptions().unique(true).partialFilterExpression(Filters.exists(FINGERPRINT_FIELD, true)),
    )
  }

  @Test
  fun `a second run leaves the unique index it found in place`() {
    // Nothing records that an update ran, so every entry runs on every boot — including the boot
    // after the one that finished the work. The second run must not undo the first: dropping the
    // index by key spec would take the unique one with it.
    val collectionName = ownStore("dcr")
    val registrations = db.getCollection(collectionName)
    registrations.row("dyn:older", fingerprint = "fp-1", registeredAt = REGISTERED_AT)
    registrations.row("dyn:newest", fingerprint = "fp-1", registeredAt = REGISTERED_AT.plusSeconds(3600))

    runUpdate(collectionName)
    registrations.createIndex(
      Indexes.ascending(POD_FIELD, FINGERPRINT_FIELD),
      IndexOptions().unique(true).partialFilterExpression(Filters.exists(FINGERPRINT_FIELD, true)),
    )
    runUpdate(collectionName)

    assertEquals("fp-1", registrations.fingerprintOf("dyn:newest"))
    assertNull(registrations.fingerprintOf("dyn:older"))
    assertEquals(
      true,
      assertNotNull(registrations.fingerprintIndex()).getBoolean("unique"),
      "the index the DAO created must survive the next boot's update",
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
