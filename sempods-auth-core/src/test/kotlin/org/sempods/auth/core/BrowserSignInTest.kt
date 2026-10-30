package org.sempods.auth.core

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The browser round trip, against a real MongoDB and a provider that signs real tokens.
 *
 * Skipped rather than failed where Mongo is absent, the repository convention.
 */
class BrowserSignInTest {

  /** What a flow parks: where the person was going. */
  private data class Next(val next: String)

  private val provider = FakeOidcProvider(ISSUER)

  private fun relyingParty(provider: FakeOidcProvider = this.provider, redirectUri: String = REDIRECT_URI) =
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
    code: String? = provider.expectFor(started.authorizationUrl, WEB_ID),
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
  fun `a sign-in begun for another redirect address is unknown there, and still completes at its own`() {
    val signIn = signIn()
    val started = signIn.begin(relyingParty(), parked)
    val elsewhere = relyingParty(redirectUri = "https://app.example.invalid/another/cb")

    assertEquals(UNKNOWN, signIn.complete(elsewhere, callbackFor(started)))
    assertIs<BrowserSignIn.Completion.SignedIn<Next>>(signIn.complete(relyingParty(), callbackFor(started)))
  }

  @Test
  fun `an unknown state is answered without asking for the provider`() {
    val signIn = signIn()

    val done = signIn.complete(REDIRECT_URI, callbackFor(signIn.begin(relyingParty(), parked)).copy(state = Secrets.newSecret())) {
      error("an unknown state must not cost a discovery")
    }

    assertEquals(UNKNOWN, done)
  }

  @Test
  fun `an unreachable provider leaves the sign-in parked for a retry`() {
    val signIn = signIn()
    val rp = relyingParty()
    val started = signIn.begin(rp, parked)

    assertNull(signIn.complete(REDIRECT_URI, callbackFor(started)) { null })
    assertIs<BrowserSignIn.Completion.SignedIn<Next>>(signIn.complete(rp, callbackFor(started)))
  }

  @Test
  fun `a repeated iss is no single answer, and is refused`() {
    val signIn = signIn()
    val rp = relyingParty()
    val started = signIn.begin(rp, parked)

    val repeated = callbackFor(started, iss = BrowserSignIn.responseIssuer(listOf(ISSUER, "https://attacker.example.invalid")))

    assertEquals(refused(BrowserSignIn.Refusal.WRONG_ISSUER), signIn.complete(rp, repeated))
    assertEquals(ISSUER, BrowserSignIn.responseIssuer(listOf(ISSUER)))
    assertNull(BrowserSignIn.responseIssuer(null))
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
    val silent = FakeOidcProvider(ISSUER).apply { advertisesIss = false }
    val rp = relyingParty(silent)
    val signIn = signIn()

    val started = signIn.begin(rp, parked)
    val callback = callbackFor(started, code = silent.expectFor(started.authorizationUrl, WEB_ID), iss = null)
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
    val code = provider.expect(WEB_ID, nonce = "another-sign-ins-nonce")

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
