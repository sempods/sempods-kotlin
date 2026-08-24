package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.auth.core.SigningKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The multi-replica signing-key bootstrap on the pod side: with an empty
 * `oauth.signingKeys`, N racing replicas must converge on ONE key.
 *
 * Two replicas each minting their own would sign tokens the other rejects, and each would publish
 * a JWKS the other's tokens are not in — the client-visible symptom is
 * `[oauth/access] Token signature verification failed` on every other request, with nothing wrong
 * in either process's log. `PodTokenIssuerPersistenceTest` covers the sequential half (one key on
 * first boot, the same key after a restart); this covers the concurrent one.
 *
 * **On a collection this test owns**, dropped before each test: "no signing key exists yet" is the
 * whole precondition, and arranging it on the shared collection would mean deleting the
 * developer's own keys.
 */
class PodSigningKeyBootstrapTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private lateinit var signingKeyDao: OAuthSigningKeyDao

  @BeforeEach
  fun setUpOwnCollection() {
    db.getCollection(TEST_COLLECTION).drop()
    signingKeyDao = OAuthSigningKeyDao(db, TEST_COLLECTION)
  }

  @Test
  fun `createInitial admits exactly one bootstrap key`() {
    val first = OAuthSigningKeyDbo(kid = "kid-a", algorithm = "RS256", jwk = """{"kty":"RSA","kid":"kid-a"}""")
    val second = OAuthSigningKeyDbo(kid = "kid-b", algorithm = "RS256", jwk = """{"kty":"RSA","kid":"kid-b"}""")

    assertTrue(signingKeyDao.createInitial(first))
    assertFalse(signingKeyDao.createInitial(second), "the second bootstrap insert must lose")
    assertEquals(listOf("kid-a"), signingKeyDao.findAll().map { it.kid })
  }

  @Test
  fun `create still appends beside the bootstrap slot`() {
    // The fixed `_id` is a first-key slot, not a lock on the collection: a rotation has to be able
    // to add a successor. If it could not, the slot would have turned a race guard into a ceiling.
    signingKeyDao.createInitial(OAuthSigningKeyDbo(kid = "kid-a", algorithm = "RS256", jwk = """{"kty":"RSA"}"""))

    signingKeyDao.create(OAuthSigningKeyDbo(kid = "kid-b", algorithm = "RS256", jwk = """{"kty":"RSA"}"""))

    assertEquals(setOf("kid-a", "kid-b"), signingKeyDao.findAll().map { it.kid }.toSet())
  }

  @Test
  fun `two replicas bootstrapping concurrently converge on one signing key`() {
    // Two SigningKeys = two replicas booting against the same empty collection. Started as
    // simultaneously as a latch allows; regardless of interleaving both must end up on the SAME
    // persisted key.
    val ready = CountDownLatch(2)
    val go = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val replicas = (1..2).map {
        pool.submit<SigningKeys> {
          ready.countDown()
          go.await()
          SigningKeys(PodSigningKeyStore(OAuthSigningKeyDao(db, TEST_COLLECTION)))
        }
      }
      ready.await()
      go.countDown()
      val kids = replicas.map { it.get(30, TimeUnit.SECONDS).keyId }

      assertEquals(1, db.getCollection(TEST_COLLECTION).countDocuments(), "exactly one key row after the race")
      assertEquals(kids[0], kids[1], "both replicas must sign with the same kid")
    } finally {
      pool.shutdownNow()
    }
  }

  private companion object {

    /** This test's own collection, outside the `sempods.` namespace the server addresses. */
    const val TEST_COLLECTION = "test.oauthSigningKeys.bootstrap"
  }
}
