package org.sempods.auth.core

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.sempods.commons.net.UrlUtil
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.net.URI
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The browser round trip, against a real MongoDB and a provider that signs real tokens.
 *
 * Skipped rather than failed where Mongo is absent, the repository convention.
 */
class BrowserSignInTest {

  /** What a flow parks: where the person was going. */
  private data class Next(val next: String)

  /**
   * A provider that remembers the nonce of each authorization request it was sent and answers the
   * code it hands out with a token for it — which is what a real one does.
   */
  private class FakeProvider(advertisesIss: Boolean = true) : HttpTransport {
    private val nonceByCode = mutableMapOf<String, String>()

    val discoveryDocument = """
      {"issuer":"$ISSUER","authorization_endpoint":"$ISSUER/authorize",
       "token_endpoint":"$ISSUER/token","jwks_uri":"$ISSUER/jwks",
       "authorization_response_iss_parameter_supported":$advertisesIss}
    """.trimIndent()

    /** The code the provider redirects back with, for the authorization request at [authorizationUrl]. */
    fun codeFor(authorizationUrl: String, nonce: String = nonceOf(authorizationUrl)): String =
      "code-${UUID.randomUUID()}".also { nonceByCode[it] = nonce }

    override fun get(url: String): String = when (url) {
      "$ISSUER/.well-known/openid-configuration" -> discoveryDocument
      "$ISSUER/jwks" -> JWKSet(RSAKey.Builder(keyPair.public as RSAPublicKey).keyID(KEY_ID).build()).toString()
      else -> error("unexpected GET: $url")
    }

    override fun postForm(url: String, form: Map<String, String>): String {
      val nonce = nonceByCode[form["code"]] ?: return """{"error":"invalid_grant"}"""
      val claims = JWTClaimsSet.Builder()
        .issuer(ISSUER).subject(WEB_ID).audience(CLIENT_ID).claim("nonce", nonce)
        .issueTime(Date()).expirationTime(Date(System.currentTimeMillis() + 300_000))
        .build()
      val idToken = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims)
        .apply { sign(RSASSASigner(keyPair.private as RSAPrivateKey)) }
        .serialize()
      return """{"access_token":"at","token_type":"Bearer","expires_in":900,"id_token":"$idToken"}"""
    }

    private fun nonceOf(authorizationUrl: String): String =
      checkNotNull(UrlUtil.queryParams(URI(authorizationUrl).rawQuery, decodeParams = true)["nonce"])
  }

  private val provider = FakeProvider()

  private fun relyingParty(provider: FakeProvider = this.provider, redirectUri: String = REDIRECT_URI) =
    OidcRelyingParty.discover(ISSUER, CLIENT_ID, redirectUri, provider)

  private val collection = "test.browserSignIn." + UUID.randomUUID()

  private fun signIn(ttl: Duration = BrowserSignIn.DEFAULT_TTL) = BrowserSignIn(
    db = db!!,
    collectionName = collection,
    cipher = SecretCipher(ByteArray(SecretCipher.KEY_BYTES) { it.toByte() }),
    write = { put("next", it.next) },
    read = { getString("next")?.let(::Next) },
    ttl = ttl,
  )

  private val parked = Next("https://app.example.invalid/after")

  private fun refused(reason: BrowserSignIn.Refusal) = BrowserSignIn.Completion.Refused(reason, parked)

  /** The callback a browser that started [started] carries back, everything in order. */
  private fun callbackFor(
    started: BrowserSignIn.Started,
    code: String? = provider.codeFor(started.authorizationUrl),
    pin: String? = started.browserPin,
    iss: String? = ISSUER,
    error: String? = null,
  ) = BrowserSignIn.Callback(state = started.state, presentedPin = pin, iss = iss, code = code, error = error)

  @Test
  fun `a callback from the browser that started it signs the person in and hands back the payload`() {
    val signIn = signIn()
    val rp = relyingParty()
    val started = signIn.begin(rp, parked)

    val done = assertIs<BrowserSignIn.Completion.SignedIn<Next>>(signIn.complete(rp, callbackFor(started)))

    assertEquals(WEB_ID, done.identity.webId)
    assertEquals(parked, done.payload)
    assertTrue(started.authorizationUrl.startsWith("$ISSUER/authorize"), started.authorizationUrl)
    assertTrue("state=${started.state}" in started.authorizationUrl, "one state for the request and the row")
  }

  @Test
  fun `an unknown, a used and an expired state are refused`() {
    val signIn = signIn()
    val rp = relyingParty()

    val unknown = signIn.begin(rp, parked).copy(state = Secrets.newSecret())
    assertEquals(UNKNOWN, signIn.complete(rp, callbackFor(unknown)))

    val used = signIn.begin(rp, parked)
    assertIs<BrowserSignIn.Completion.SignedIn<Next>>(signIn.complete(rp, callbackFor(used)))
    assertEquals(UNKNOWN, signIn.complete(rp, callbackFor(used)))

    val shortLived = signIn(ttl = Duration.ofMillis(1))
    val expired = shortLived.begin(rp, parked)
    Thread.sleep(20)
    assertEquals(UNKNOWN, shortLived.complete(rp, callbackFor(expired)))
  }

  @Test
  fun `a state that could not have been minted here is unknown`() {
    val signIn = signIn()
    val rp = relyingParty()
    for (junk in listOf(null, "", " ", "a;b", "x".repeat(43) + "=")) {
      assertEquals(
        UNKNOWN,
        signIn.complete(rp, BrowserSignIn.Callback(junk, "pin", ISSUER, "code", null)),
        "state '$junk'",
      )
    }
  }

  @Test
  fun `a sign-in begun for another redirect address is unknown there, and spent`() {
    val signIn = signIn()
    val started = signIn.begin(relyingParty(), parked)
    val elsewhere = relyingParty(redirectUri = "https://app.example.invalid/another/cb")

    assertEquals(UNKNOWN, signIn.complete(elsewhere, callbackFor(started)))
    assertEquals(UNKNOWN, signIn.complete(relyingParty(), callbackFor(started)))
  }

  @Test
  fun `a missing pin and another browser's pin are refused before the code is exchanged`() {
    val signIn = signIn()
    val rp = relyingParty()

    val withoutPin = signIn.begin(rp, parked)
    assertEquals(
      refused(BrowserSignIn.Refusal.OTHER_BROWSER),
      signIn.complete(rp, callbackFor(withoutPin, code = "never-exchanged", pin = null)),
    )

    val otherBrowser = signIn.begin(rp, parked)
    val anotherSignIn = signIn.begin(rp, parked)
    assertEquals(
      refused(BrowserSignIn.Refusal.OTHER_BROWSER),
      signIn.complete(rp, callbackFor(otherBrowser, code = "never-exchanged", pin = anotherSignIn.browserPin)),
    )
    assertEquals(
      UNKNOWN,
      signIn.complete(rp, callbackFor(otherBrowser)),
      "a refused callback spends the sign-in",
    )
  }

  @Test
  fun `a wrong iss and a missing one are refused, an error included`() {
    val signIn = signIn()
    val rp = relyingParty()

    for (iss in listOf("https://attacker.example.invalid", "$ISSUER/", null)) {
      val started = signIn.begin(rp, parked)
      assertEquals(
        refused(BrowserSignIn.Refusal.WRONG_ISSUER),
        signIn.complete(rp, callbackFor(started, iss = iss)),
        "iss $iss",
      )
      val erred = signIn.begin(rp, parked)
      assertEquals(
        refused(BrowserSignIn.Refusal.WRONG_ISSUER),
        signIn.complete(rp, callbackFor(erred, code = null, iss = iss, error = "access_denied")),
        "an error under iss $iss",
      )
    }
  }

  @Test
  fun `a provider that does not promise iss may leave it out, but may not name another`() {
    // RFC 9207 §2.4: only a provider's metadata turns a missing `iss` into a refusal.
    val silent = FakeProvider(advertisesIss = false)
    val rp = relyingParty(silent)
    val signIn = signIn()

    val started = signIn.begin(rp, parked)
    val callback = callbackFor(started, code = silent.codeFor(started.authorizationUrl), iss = null)
    assertIs<BrowserSignIn.Completion.SignedIn<Next>>(signIn.complete(rp, callback))

    val misnamed = signIn.begin(rp, parked)
    assertEquals(
      refused(BrowserSignIn.Refusal.WRONG_ISSUER),
      signIn.complete(rp, callbackFor(misnamed, iss = "https://attacker.example.invalid")),
    )
  }

  @Test
  fun `an error answered by the provider ends the sign-in, and a callback with nothing ends it too`() {
    val signIn = signIn()
    val rp = relyingParty()

    val refused = signIn.begin(rp, parked)
    assertEquals(
      BrowserSignIn.Completion.Failed(parked, "access_denied", "the person said no"),
      signIn.complete(rp, callbackFor(refused, code = null, error = "access_denied").copy(errorDescription = "the person said no")),
    )

    val empty = signIn.begin(rp, parked)
    assertEquals(BrowserSignIn.Completion.Failed(parked), signIn.complete(rp, callbackFor(empty, code = " ")))
  }

  @Test
  fun `a token minted for another sign-in is refused`() {
    val signIn = signIn()
    val rp = relyingParty()
    val started = signIn.begin(rp, parked)
    // Still signed, still unexpired, for the same person — only the nonce says it is not this one.
    val code = provider.codeFor(started.authorizationUrl, nonce = "another-sign-ins-nonce")

    val failed = assertIs<BrowserSignIn.Completion.Failed<Next>>(signIn.complete(rp, callbackFor(started, code = code)))
    assertNotNull(failed.cause, "the exchange is what failed")

    assertEquals(parked, failed.payload)
  }

  @Test
  fun `a parked row holds neither the verifier nor the pin in the clear`() {
    val signIn = signIn()
    val transport = object : HttpTransport by provider {
      val forms = mutableListOf<Map<String, String>>()
      override fun postForm(url: String, form: Map<String, String>) = provider.postForm(url, form).also { forms += form }
    }
    val rp = OidcRelyingParty.discover(ISSUER, CLIENT_ID, REDIRECT_URI, transport)
    val started = signIn.begin(rp, parked)

    val row = assertNotNull(db!!.getCollection(collection).find(Filters.eq("_id", sha256Hex(started.state))).first())
    val stored = row.toJson()
    assertFalse(started.browserPin in stored, "the pin is stored as its SHA-256 only")
    assertEquals(sha256Hex(started.browserPin), row.getString("browserPinSha256"))
    assertEquals(parked.next, row.get("payload", Document::class.java).getString("next"))

    // The verifier only exists once it is presented, so compare against what reached the token endpoint.
    assertIs<BrowserSignIn.Completion.SignedIn<Next>>(signIn.complete(rp, callbackFor(started)))
    val verifier = assertNotNull(transport.forms.single()["code_verifier"])
    assertFalse(verifier in stored, "the verifier is stored encrypted")
    assertTrue(row.getString("encryptedCodeVerifier").startsWith("v1:"))
  }

  @Test
  fun `a row parked under another key reads as an unknown state`() {
    val rp = relyingParty()
    val started = signIn().begin(rp, parked)
    val otherKey = BrowserSignIn(
      db = db!!,
      collectionName = collection,
      cipher = SecretCipher(ByteArray(SecretCipher.KEY_BYTES) { 7 }),
      write = { put("next", it.next) },
      read = { getString("next")?.let(::Next) },
    )

    assertEquals(UNKNOWN, otherKey.complete(rp, callbackFor(started)))
  }

  companion object {
    private val UNKNOWN = BrowserSignIn.Completion.Refused(BrowserSignIn.Refusal.UNKNOWN_STATE, null)
    private const val ISSUER = "https://id.example.invalid"
    private const val CLIENT_ID = "did:web:app.example.invalid"
    private const val REDIRECT_URI = "https://app.example.invalid/cb"
    private const val WEB_ID = "$ISSUER/e/abc"
    private const val KEY_ID = "browser-sign-in-test"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      db = TestMongo.database()
    }

    @AfterAll @JvmStatic
    fun teardown() {
      db?.drop()
    }
  }
}
