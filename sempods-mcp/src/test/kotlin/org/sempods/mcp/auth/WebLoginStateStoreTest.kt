package org.sempods.mcp.auth

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import io.ktor.http.Url
import org.sempods.auth.core.BrowserSignIn
import org.sempods.mcp.crypto.testSecretCipher
import org.sempods.mcp.oauth.FakeIdentityProvider

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class WebLoginStateStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping WebLoginStateStore test")
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

  private val store = WebLoginStateStore(db!!, testSecretCipher())
  private val idServer = FakeIdentityProvider(audience = "did:web:mcp.test")
  private val relyingParty = idServer.identityProvider("https://mcp.test").relyingPartyFor("/_system/ui/login/callback")

  @Test
  fun `where the person was going survives the round trip`() {
    val started = store.signIn.begin(relyingParty, WebLoginStateStore.Pending(next = "/_system/ui?profile=work"))
    idServer.expect(webId = "https://id.test/e/web-user", nonce = Url(started.authorizationUrl).parameters["nonce"]!!)

    val done = store.signIn.complete(
      relyingParty,
      BrowserSignIn.Callback(started.state, started.browserPin, idServer.issuer, code = "c", error = null),
    )

    assertEquals("/_system/ui?profile=work", assertIs<BrowserSignIn.Completion.SignedIn<WebLoginStateStore.Pending>>(done).payload.next)
  }
}
