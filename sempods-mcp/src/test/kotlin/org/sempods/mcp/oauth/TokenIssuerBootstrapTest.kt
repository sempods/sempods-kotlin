package org.sempods.mcp.oauth

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.nimbusds.jwt.SignedJWT
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.crypto.testSecretCipher
import org.sempods.auth.core.SigningKeys
import org.sempods.mcp.persist.oauth.McpSigningKeyStore
import org.sempods.mcp.persist.oauth.SigningKey
import org.sempods.mcp.persist.oauth.SigningKeyDao
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The multi-replica signing-key bootstrap (M6.3): with an empty `oauth.signingKeys`, N racing
 * replicas must converge on ONE key — two replicas each signing with their own key would fail
 * token/JWKS verification cross-replica behind a load balancer. Each test uses its own database
 * because the bootstrap `_id` is a per-database singleton. Mongo-backed; skipped when Mongo is
 * unreachable.
 */
class TokenIssuerBootstrapTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private const val BASE = "https://mcp.test"
    private val dbPrefix = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private val createdDbs = mutableListOf<String>()

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping TokenIssuer bootstrap test")
      mongoClient = MongoClients.create(MONGO_URL)
    }

    @AfterAll @JvmStatic
    fun teardown() {
      createdDbs.forEach { mongoClient?.getDatabase(it)?.drop() }
      mongoClient?.close()
    }

    private fun mongoReachable(): Boolean = runCatching {
      val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(MONGO_URL))
        .applyToClusterSettings { it.serverSelectionTimeout(1, TimeUnit.SECONDS) }
        .build()
      MongoClients.create(settings).use { it.getDatabase("admin").runCommand(Document("ping", 1)) }
      true
    }.getOrDefault(false)
  }

  private fun freshDb() = mongoClient!!.getDatabase("${dbPrefix}_${createdDbs.size}").also { createdDbs.add(it.name) }

  @Test
  fun `createInitial admits exactly one bootstrap key`() {
    val db = freshDb()
    val dao = SigningKeyDao(db, testSecretCipher())
    val keyA = SigningKey(kid = "kid-a", algorithm = "RS256", jwk = """{"kty":"RSA","kid":"kid-a"}""", createdAt = Date())
    val keyB = SigningKey(kid = "kid-b", algorithm = "RS256", jwk = """{"kty":"RSA","kid":"kid-b"}""", createdAt = Date())

    assertTrue(dao.createInitial(keyA))
    assertFalse(dao.createInitial(keyB), "the second bootstrap insert must lose")
    assertEquals(listOf("kid-a"), dao.findAll().map { it.kid })
  }

  @Test
  fun `two replicas bootstrapping concurrently converge on one signing key`() {
    val db = freshDb()
    // Two TokenIssuers = two replicas booting against the same empty collection. Start them as
    // simultaneously as a latch allows; regardless of interleaving both must end up signing with
    // the SAME persisted key.
    val ready = CountDownLatch(2)
    val go = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val issuers = (1..2).map {
        pool.submit<TokenIssuer> {
          ready.countDown(); go.await()
          TokenIssuer(BASE, SigningKeys(McpSigningKeyStore(SigningKeyDao(db, testSecretCipher()))))
        }
      }
      ready.await(); go.countDown()
      val kids = issuers.map { future ->
        val token = future.get(30, TimeUnit.SECONDS).issueAccessToken("https://id.test/e/u", "default", "dyn:c", emptySet())
        SignedJWT.parse(token).header.keyID
      }

      assertEquals(1, db.getCollection(SempodsMcpCollections.OAUTH_SIGNING_KEYS).countDocuments(), "exactly one key row after the race")
      assertEquals(kids[0], kids[1], "both replicas must sign with the same kid")
    } finally {
      pool.shutdownNow()
    }
  }
}
