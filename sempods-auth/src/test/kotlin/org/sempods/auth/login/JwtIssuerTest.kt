package org.sempods.auth.login

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.sempods.auth.core.EquivalentIdentities
import org.sempods.auth.core.SigningKeyStore
import org.sempods.auth.core.SigningKeys
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What this service signs, and with what.
 *
 * The restart case is the one worth a test rather than a comment: nothing fails when a key is
 * regenerated — the service starts, the log is clean, and every token it ever issued silently
 * stops verifying. It was observed on the pod side as sign-ins breaking mid-session.
 */
class JwtIssuerTest {

  /** Stands in for the database: keeps what it is given, hands it back on the next boot. */
  private class InMemoryKeyStore : SigningKeyStore {
    private val stored = mutableListOf<String>()
    var writes = 0
      private set

    override fun loadAll(): List<String> = stored.toList()

    /** The singleton slot, in memory: the first key wins and every later bootstrap loses. */
    override fun createInitial(jwk: String, kid: String, algorithm: String): Boolean {
      if (stored.isNotEmpty()) return false
      stored.add(0, jwk)
      writes++
      return true
    }
  }

  private val issuerUrl = "https://id.example.invalid"
  private val webId = "https://id.example.invalid/e/abc"

  private fun issuerOn(store: SigningKeyStore) = JwtIssuer(issuerUrl, SigningKeys(store))

  @Test
  fun `the key survives a restart, and so do the tokens signed with it`() {
    val store = InMemoryKeyStore()

    val before = issuerOn(store).issueIdToken(webId, audience = "did:web:pod.test", equivalentIdentities = emptyList())
    // A second process against the same store — a deploy, in other words.
    val afterRestart = issuerOn(store)

    assertEquals(1, store.writes, "a restart must not mint a second key")
    val kidBefore = SignedJWT.parse(before).header.keyID
    assertNotNull(
      JWKSet.parse(afterRestart.jwksJson).getKeyByKeyId(kidBefore),
      "the key that signed the earlier token must still be published",
    )
  }

  @Test
  fun `an id_token names the client it was issued for`() {
    val token = SignedJWT.parse(
      issuerOn(InMemoryKeyStore()).issueIdToken(
        webIdUri = webId,
        audience = "did:web:pod.example.org",
        equivalentIdentities = listOf("https://id.example.invalid/oidc/def"),
        nonce = "n-123",
      ),
    )
    val claims = token.jwtClaimsSet

    // The claim that turns a credential usable at every pod into one usable at exactly one.
    assertEquals(listOf("did:web:pod.example.org"), claims.audience)
    assertEquals("n-123", claims.getStringClaim("nonce"))
    assertEquals(issuerUrl, claims.issuer)
    assertEquals(webId, claims.subject)
    assertEquals(webId, claims.getStringClaim("webid"))
    assertEquals(listOf("https://id.example.invalid/oidc/def"), claims.getStringListClaim(EquivalentIdentities.CLAIM))
    assertNull(claims.getClaim("also_known_as"), "a human pseudonym in OIDC, and nothing this token means")
    assertNotNull(claims.jwtid, "a jti is what lets a replay be recognised later")
    assertEquals("JWT", token.header.type.toString())
  }

  @Test
  fun `no equivalent identity means no claim`() {
    val claims = SignedJWT.parse(
      issuerOn(InMemoryKeyStore()).issueIdToken(webId, "did:web:pod.test", emptyList()),
    ).jwtClaimsSet

    assertFalse(EquivalentIdentities.CLAIM in claims.claims)
  }

  @Test
  fun `an equivalent identity that is not a WebID is never signed`() {
    // Every relying party would refuse the whole token for it (`SPS-OIDC-016`), so the issuer
    // refuses to write it.
    val issuer = issuerOn(InMemoryKeyStore())
    for (bad in listOf("urn:sempods:e:abc", "", "/alice")) {
      assertFailsWith<IllegalArgumentException>(bad) {
        issuer.issueIdToken(webId, "did:web:pod.test", listOf("https://id.example.invalid/oidc/def", bad))
      }
    }
  }

  @Test
  fun `the access token carries no identity alias`() {
    val claims = SignedJWT.parse(
      issuerOn(InMemoryKeyStore()).issueAccessToken(webId, scopes = listOf("openid")),
    ).jwtClaimsSet

    assertNull(claims.getClaim(EquivalentIdentities.CLAIM))
    assertNull(claims.getClaim("also_known_as"))
  }

  @Test
  fun `no nonce in the request means no nonce in the token`() {
    val claims = SignedJWT.parse(
      issuerOn(InMemoryKeyStore()).issueIdToken(webId, "did:web:pod.test", emptyList()),
    ).jwtClaimsSet

    assertNull(claims.getClaim("nonce"))
  }

  @Test
  fun `every token expires`() {
    val claims = SignedJWT.parse(
      issuerOn(InMemoryKeyStore()).issueIdToken(webId, "did:web:pod.test", emptyList()),
    ).jwtClaimsSet

    assertNotNull(claims.expirationTime)
    val lifetimeSeconds = (claims.expirationTime.time - claims.issueTime.time) / 1000
    assertTrue(lifetimeSeconds in 1..3600, "expected a short life, got ${lifetimeSeconds}s")
  }
}
