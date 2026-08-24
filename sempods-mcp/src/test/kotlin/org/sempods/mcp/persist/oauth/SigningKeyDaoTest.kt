package org.sempods.mcp.persist.oauth

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.crypto.SecretCipher
import org.sempods.mcp.crypto.testSecretCipher
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class SigningKeyDaoTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping SigningKeyDao test")
      mongoClient = MongoClients.create(MONGO_URL).also { db = it.getDatabase(dbName) }
    }

    @AfterAll @JvmStatic
    fun teardown() {
      db?.drop(); mongoClient?.close()
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

  private val raw = db!!.getCollection(SempodsMcpCollections.OAUTH_SIGNING_KEYS)

  // A stand-in for the full private JWK JSON — the DAO treats it as an opaque string.
  private val privateJwk = """{"kty":"RSA","kid":"k1","d":"super-secret-private-parameter","n":"..."}"""

  @Test
  fun `stores the private JWK as ciphertext but round-trips it`() {
    val dao = SigningKeyDao(db!!, testSecretCipher())
    val kid = "kid-" + UUID.randomUUID()
    dao.create(SigningKey(kid = kid, algorithm = "RS256", jwk = privateJwk, createdAt = Date()))

    val stored = raw.find(org.bson.Document("kid", kid)).first()!!
    assertTrue(stored.getString("jwk").startsWith("v1:"), "private JWK should be encrypted at rest")
    assertTrue(!stored.getString("jwk").contains("super-secret-private-parameter"))

    val reloaded = dao.findAll().first { it.kid == kid }
    assertEquals(privateJwk, reloaded.jwk)
  }

  @Test
  fun `an undecryptable signing key fails loudly instead of being skipped`() {
    val kid = "wrongkey-" + UUID.randomUUID()
    // Write under one key, read under another (simulates a lost/changed MCP_SECRET_KEY).
    SigningKeyDao(db!!, SecretCipher(ByteArray(32) { (it + 1).toByte() }))
      .create(SigningKey(kid = kid, algorithm = "RS256", jwk = privateJwk, createdAt = Date()))
    val wrongKeyDao = SigningKeyDao(db!!, SecretCipher(ByteArray(32) { (it + 9).toByte() }))

    val ex = assertFailsWith<IllegalStateException> { wrongKeyDao.findAll() }
    assertTrue(ex.message!!.contains("MCP_SECRET_KEY"), "error should name the key env var")
  }
}
