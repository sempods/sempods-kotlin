package org.sempods.mcp.oauth

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

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class LoginStateStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping LoginStateStore test")
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

  private val store = LoginStateStore(db!!, testSecretCipher())
  private val idServer = FakeIdentityProvider(audience = "did:web:mcp.test")
  private val relyingParty = idServer.identityProvider("https://mcp.test").relyingPartyFor("/oidc/callback")

  @Test
  fun `the whole authorize request survives the round trip`() {
    val pending = LoginStateStore.PendingAuthorize(
      profile = "default",
      clientId = "dyn:abc",
      redirectUri = "http://127.0.0.1:1/cb",
      clientState = "client-state",
      scopes = setOf("read"),
      codeChallenge = "challenge",
      codeChallengeMethod = "S256",
    )
    val started = store.signIn.begin(relyingParty, pending)
    idServer.expect(webId = "https://id.test/e/abc", nonce = Url(started.authorizationUrl).parameters["nonce"]!!)

    val done = store.signIn.complete(
      relyingParty,
      BrowserSignIn.Callback(started.state, started.browserPin, idServer.issuer, code = "c", error = null),
    )

    assertEquals(pending, assertIs<BrowserSignIn.Completion.SignedIn<LoginStateStore.PendingAuthorize>>(done).payload)
  }

  @Test
  fun `what the AI client left out stays out`() {
    val pending = LoginStateStore.PendingAuthorize(
      profile = "default", clientId = "dyn:abc", redirectUri = "http://127.0.0.1:1/cb",
      clientState = null, scopes = emptySet(), codeChallenge = null, codeChallengeMethod = null,
    )
    val started = store.signIn.begin(relyingParty, pending)
    idServer.expect(webId = "https://id.test/e/abc", nonce = Url(started.authorizationUrl).parameters["nonce"]!!)

    val done = store.signIn.complete(
      relyingParty,
      BrowserSignIn.Callback(started.state, started.browserPin, idServer.issuer, code = "c", error = null),
    )

    assertEquals(pending, assertIs<BrowserSignIn.Completion.SignedIn<LoginStateStore.PendingAuthorize>>(done).payload)
  }
}
