package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.api.pod.system.auth.PodTokenIssuer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What [PodSignOutStore] keeps, for whom, and for how long. */
class PodSignOutStoreTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private lateinit var store: PodSignOutStore

  private val collection = ownStore("signOuts")

  private val podId = ObjectId()
  private val otherPodId = ObjectId()
  private val person = "https://id.test/e/person"
  private val twin = "urn:sempods:e:person"

  /** Truncated so the round trip through the driver is the identity. */
  private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  @BeforeEach
  fun setUpOwnCollection() {
    store = PodSignOutStore(db, collection)
  }

  @Test
  fun `a later sign-out moves the instant and an earlier one does not`() {
    // Two sign-outs landing at once must leave the later one standing, whichever write arrives second.
    store.record(podId, listOf(person), now)
    store.record(podId, listOf(person), now.minusSeconds(60))
    assertEquals(now, store.signedOutAt(podId, listOf(person)))

    store.record(podId, listOf(person), now.plusSeconds(60))
    assertEquals(now.plusSeconds(60), store.signedOutAt(podId, listOf(person)))
  }

  @Test
  fun `an instant belongs to one pod and to the URIs it was written for`() {
    store.record(podId, listOf(person, twin), now)

    assertEquals(now, store.signedOutAt(podId, listOf(twin)))
    assertNull(store.signedOutAt(otherPodId, listOf(person)), "another pod")
    assertNull(store.signedOutAt(podId, listOf("https://id.test/e/somebody-else")), "another person")
    assertNull(store.signedOutAt(podId, emptyList()))
  }

  @Test
  fun `the row expires the retention after its instant`() {
    store.record(podId, listOf(person), now)

    val row = assertNotNull(db.getCollection(collection).find(Filters.eq("webId", person)).first())
    assertEquals(podId, row.getObjectId("podId"))
    assertEquals(Date.from(now), row.getDate("signedOutAt"))
    assertEquals(Date.from(now.plus(PodSignOutStore.RETENTION)), row.getDate("expiresAt"))
  }

  @Test
  fun `the indexes are a unique one per person and a zero-delay expiry`() {
    val indexes = db.getCollection(collection).listIndexes().toList()

    val owner = assertNotNull(indexes.firstOrNull { (it["key"] as Document).keys == setOf("podId", "webId") })
    assertEquals(true, owner["unique"])
    val ttl = assertNotNull(indexes.firstOrNull { (it["key"] as Document).containsKey("expiresAt") })
    assertEquals(0L, (ttl["expireAfterSeconds"] as Number).toLong())
  }

  @Test
  fun `deleteByPod removes exactly that pod's rows`() {
    store.record(podId, listOf(person, twin), now)
    store.record(otherPodId, listOf(person), now)

    assertEquals(2L, store.deleteByPod(podId))
    assertNull(store.signedOutAt(podId, listOf(person, twin)))
    assertEquals(now, store.signedOutAt(otherPodId, listOf(person)))
  }

  @Test
  fun `an instant is kept as long as the longest session it has to end`() {
    // Reaped any earlier, a session signed in before the sign-out would stand again for the rest of
    // its thirty days.
    assertTrue(PodSignOutStore.RETENTION.seconds >= PodTokenIssuer.SESSION_ABSOLUTE_TTL_SECONDS)
  }
}
