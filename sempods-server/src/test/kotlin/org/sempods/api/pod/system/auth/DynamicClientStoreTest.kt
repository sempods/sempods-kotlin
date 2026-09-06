package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import io.mockk.every
import io.mockk.spyk
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What [DynamicClientStore.register] answers when two registrations of one client meet at a pod.
 *
 * Hosted MCP sends an identical registration for every user, so the fingerprint carries nothing
 * that tells two people apart: both press "Connect" for one pod, both ask "do you know this
 * client?", both are told no. One `client_id` has to come out of that, because the pod's grants are
 * keyed `(pod, client_id, WebID)` and a second id would split the consent screen into two entries
 * that look alike.
 *
 * The spy is what puts the second registration in the gap, rather than two threads: the race is a
 * property of the mechanism — a lookup and an insert that are not one statement — and asserting it
 * through timing would only ever assert the timing.
 */
class DynamicClientStoreTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private val podId = ObjectId()

  @Test
  fun `a registration that loses the insert answers the winner's client id`() {
    val collection = ownStore("dcr")
    val dao = DynamicClientRegistrationDao(db, collection)

    // The lookup this caller runs reports a miss, and the winner's row lands before the insert —
    // which is exactly the order two concurrent registrations produce. Every later call is the
    // real one, so the re-read after the refusal is the DAO's own.
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
