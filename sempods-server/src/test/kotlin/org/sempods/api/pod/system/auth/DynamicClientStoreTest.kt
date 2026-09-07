package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import io.mockk.every
import io.mockk.spyk
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [DynamicClientStore.register] answers when two registrations of one client meet at a pod.
 * One `client_id` has to come out of it: the pod's grants are keyed `(pod, client_id, WebID)`.
 *
 * The spy puts the second registration in the gap rather than two threads — the race is a property
 * of the mechanism, and asserting it through timing would only assert the timing.
 */
class DynamicClientStoreTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private val podId = ObjectId()

  @Test
  fun `a registration that loses the insert answers the winner's client id`() {
    val collection = ownStore("dcr")
    val dao = DynamicClientRegistrationDao(db, collection)

    // The lookup misses and the winner's row lands before the insert, which is the order two
    // concurrent registrations produce. Every later call is the real one.
    var raced = false
    val racing = spyk(dao)
    every { racing.findByFingerprint(any(), any()) } answers {
      if (raced) callOriginal()
      else {
        raced = true
        register(dao, WINNER_ID, fingerprint = secondArg())
        null
      }
    }

    val registration = DynamicClientStore(racing).register(
      registeredForPodId = podId,
      registeredForPodName = "alice",
      redirectUris = setOf("https://app.example.org/cb"),
      clientName = "Example",
      userAgent = "Example/1.2.3",
    )

    assertEquals(WINNER_ID, registration.clientId, "the loser must hand back the id the winner minted")
    assertNotNull(
      registration.deduplicatedFromRegisteredAt,
      "and it must read as an ordinary dedup hit — the caller has no reason to tell the two apart",
    )
    assertEquals(
      1,
      db.getCollection(collection).countDocuments(),
      "one logical client, one row",
    )
  }

  @Test
  fun `a winner that is gone by the re-read is registered afresh, not answered as a 500`() {
    // The other direction of the same gap: the pod-deletion cascade can clear the row that refused
    // this insert before the loser reads it. Answering nothing would make that a 500 on a pre-auth
    // endpoint.
    val collection = ownStore("dcr")
    val dao = DynamicClientRegistrationDao(db, collection)

    var pass = 0
    val racing = spyk(dao)
    every { racing.findByFingerprint(any(), any()) } answers {
      when (++pass) {
        // The winner lands after this caller's lookup…
        1 -> register(dao, WINNER_ID, fingerprint = secondArg()).let { null }
        // …and the pod is deleted before the loser gets to read it back.
        2 -> dao.deleteByPod(podId).let { null }
        else -> callOriginal()
      }
    }

    val registration = DynamicClientStore(racing).register(
      registeredForPodId = podId,
      registeredForPodName = "alice",
      redirectUris = setOf("https://app.example.org/cb"),
      clientName = "Example",
      userAgent = "Example/1.2.3",
    )

    assertNotEquals(WINNER_ID, registration.clientId, "the row that id names is gone")
    assertTrue(registration.clientId.startsWith("dyn:"))
    assertNull(registration.deduplicatedFromRegisteredAt, "nothing was deduplicated to — this is a first registration")
    assertEquals(1, db.getCollection(collection).countDocuments())
  }

  private fun register(dao: DynamicClientRegistrationDao, clientId: String, fingerprint: String) =
    dao.create(
      clientId = clientId,
      registeredForPodId = podId,
      registeredForPodName = "alice",
      redirectUris = setOf("https://app.example.org/cb"),
      clientName = "Example",
      clientUri = null,
      logoUri = null,
      softwareId = null,
      softwareVersion = null,
      contacts = emptyList(),
      tosUri = null,
      policyUri = null,
      rawRequest = emptyMap(),
      fingerprint = fingerprint,
    )

  private companion object {
    const val WINNER_ID = "dyn:winner"
  }
}
