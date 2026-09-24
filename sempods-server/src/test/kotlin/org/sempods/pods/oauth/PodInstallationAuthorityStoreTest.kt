package org.sempods.pods.oauth

import com.google.inject.Inject
import org.sempods.SempodsStoreTest
import org.sempods.commons.tests.TestUtil.randomId
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.bson.types.ObjectId
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.utils.HashUtil
import java.time.Duration
import java.time.Instant
import org.sempods.pods.PodId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The once-only half of an installation authority.
 *
 * Every case here is about the same property, asked from a different side: the authority behind an
 * installer token can be spent exactly once. #126 binds it to a registration, and a store that
 * handed the same row to two callers would let one authorization create two service clients.
 *
 * The real store, for the reason `PodTokenExchangeTest` gives: what "once" means under two callers
 * arriving together is a property of the database operation, so a fake would be testing the fake.
 */
internal class PodInstallationAuthorityStoreTest : SempodsStoreTest() {

  @Inject
  private lateinit var authorities: PodInstallationAuthorityStore

  @Inject
  private lateinit var consentDecisions: PodConsentDecisionStore

  @Inject
  private lateinit var db: MongoDatabase

  // Real ids: `consume` reads the standing consent, and that collection keys on an ObjectId.
  private val pod = PodId(ObjectId().toHexString())
  private val clientId = "dyn:${randomId()}"
  private val webId = "https://id.test/${randomId()}"

  /** A second URI for the same person, the kind only sempods-auth can resolve. */
  private val alias = "https://id.test/e/${randomId()}"

  /** The consent the authority hangs off, and the generation it is granted under. */
  private fun approve(pod: PodId = this.pod): Long =
    consentDecisions.recordWithoutLifetime(pod = pod, appId = clientId, webId = webId).generation

  private fun record(jti: String, pod: PodId = this.pod, generation: Long = approve(pod)) {
    authorities.record(
      pod = pod,
      jti = jti,
      clientId = clientId,
      webId = webId,
      generation = generation,
      subjectUris = setOf(webId, alias),
    )
  }

  @Test
  fun `the authority is handed over once and never again`() {
    val jti = randomId()
    record(jti)

    val first = assertNotNull(authorities.consume(pod, jti))
    assertEquals(clientId, first.clientId)
    assertEquals(webId, first.webId)
    assertEquals(pod, first.pod)
    assertEquals(
      setOf(webId, alias),
      first.subjectUris,
      "registration asks who owns the pod now, and this is the set it asks about",
    )

    assertNull(authorities.consume(pod, jti), "a second registration has nothing to stand on")
  }

  @Test
  fun `a row a pre-upgrade node wrote is still worth its one registration`() {
    // The rolling deploy: an old node redeemed the code and wrote the shape it knew — no
    // generation, no URI set. Refusing it would spend an authority the owner is still holding,
    // for a flow that node could not serve anyway.
    val jti = randomId()
    db.getCollection(SempodsCollections.OAUTH_INSTALLATION_AUTHORITIES).insertOne(
      Document().apply {
        put("_id", HashUtil.sha256Hex(jti))
        put("podId", pod.value)
        put("clientId", clientId)
        put("webId", webId)
        putInstant("expiresAt", Instant.now().plus(Duration.ofHours(1)))
      },
    )

    val authority = assertNotNull(authorities.consume(pod, jti), "an owner mid-deploy keeps their install")
    assertEquals(setOf(webId), authority.subjectUris, "the person it names is the one it recorded")
    assertNull(authority.generation, "and it says it cannot be compared")
  }

  @Test
  fun `an app the person has answered again since holds nothing`() {
    // Disconnecting an app raises the generation, and an installer token outlives that by up to
    // its hour. Checked here rather than at issuance, so the bearer dies with the consent.
    val jti = randomId()
    record(jti)
    consentDecisions.bumpGeneration(pod = pod, appId = clientId, webIds = listOf(webId))

    assertNull(authorities.consume(pod, jti), "the authority goes with the consent it was granted under")
  }

  @Test
  fun `a jti nothing was recorded under is worth nothing`() {
    assertNull(authorities.consume(pod, randomId()))
  }

  @Test
  fun `an authority recorded for another pod is refused here`() {
    // The token is pod-bound by its issuer already; this is the store saying the same thing, so a
    // row cannot be spent at a door it was not granted for.
    val jti = randomId()
    record(jti, pod = PodId(ObjectId().toHexString()))

    assertNull(authorities.consume(pod, jti))
  }

  @Test
  fun `exactly one of eight callers arriving together gets it`() {
    // The one that matters. Two registration calls racing on the same installer token is the
    // concurrency #35 names outright, and `findOneAndDelete` is what decides it.
    val jti = randomId()
    record(jti)

    val callers = 8
    val ready = CountDownLatch(callers)
    val go = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(callers)
    try {
      val attempts = (1..callers).map {
        pool.submit<PodInstallationAuthorityStore.Authority?> {
          ready.countDown()
          go.await()
          authorities.consume(pod, jti)
        }
      }
      ready.await()
      go.countDown()

      val won = attempts.count { it.get(30, TimeUnit.SECONDS) != null }
      assertEquals(1, won, "one registration, whatever the interleaving")
    } finally {
      pool.shutdownNow()
    }
  }
}
