package org.sempods.auth.oidc

import org.sempods.auth.core.OidcPrompt
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import org.junit.jupiter.api.Test
import org.sempods.auth.oidc.apple.AppleOidcClient
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apple's half of the bridge, without touching the network.
 *
 * Everything asserted here is something Apple rejects silently or accepts once and never again:
 * a redirect URI that differs by one character, a client secret that has expired, a name that
 * arrives in a form field on the first login only. None of it shows up in a compile error.
 */
class AppleOidcClientTest {

  private val keyPair = KeyPairGenerator.getInstance("EC")
    .apply { initialize(ECGenParameterSpec("secp256r1")) }
    .generateKeyPair()

  /** As downloaded from Apple: markers plus wrapped base64. */
  private val multiLinePem: String = buildString {
    appendLine("-----BEGIN PRIVATE KEY-----")
    Base64.getEncoder().encodeToString(keyPair.private.encoded).chunked(64).forEach(::appendLine)
    append("-----END PRIVATE KEY-----")
  }

  private fun clientOf(pem: String = multiLinePem) = AppleOidcClient(
    teamId = "TEAM123",
    serviceId = "org.sempods.test.service",
    keyId = "KEY123",
    applePrivateKeyPem = pem,
    loginBaseUrl = "https://id.example.invalid",
    tokenExchange = OidcTokenExchange(HttpClient()),
  )

  // ─── authorize URL ────────────────────────────────────────────────────────

  @Test
  fun `the authorize URL carries what apple requires`() {
    val url = clientOf().authorizeUrl(state = "abc123")

    assertTrue(url.startsWith("https://appleid.apple.com/auth/authorize?"), url)
    assertTrue("client_id=org.sempods.test.service" in url, url)
    assertTrue("response_type=code" in url, url)
    assertTrue("state=abc123" in url, url)
    // Requesting name/email obliges Apple to POST the callback; asking for one without the other
    // yields a GET callback and a route that never fires.
    assertTrue("scope=name%20email" in url, url)
    assertTrue("response_mode=form_post" in url, url)
  }

  @Test
  fun `the redirect URI matches the callback route apple will POST to`() {
    // Apple compares this against the portal entry byte for byte and answers `invalid_client`
    // otherwise — a failure that says nothing about which side is wrong.
    val url = clientOf().authorizeUrl(state = "s1")

    assertTrue(
      "redirect_uri=https%3A%2F%2Fid.example.invalid%2Flogin%2Foidc%2Fapple%2Fcallback" in url,
      url,
    )
  }

  @Test
  fun `a forced login is passed on, an ordinary one adds nothing`() {
    assertTrue("prompt=login" in clientOf().authorizeUrl("s1", OidcPrompt.LOGIN))
    assertFalse("prompt=" in clientOf().authorizeUrl("s1"))
  }

  // ─── client secret ────────────────────────────────────────────────────────

  @Test
  fun `the client secret is a fresh short-lived ES256 assertion`() {
    val jwt = SignedJWT.parse(clientOf().generateClientSecret())

    assertEquals(JWSAlgorithm.ES256, jwt.header.algorithm)
    assertEquals("KEY123", jwt.header.keyID)
    assertEquals("JWT", jwt.header.type.toString())
    assertTrue(jwt.verify(ECDSAVerifier(keyPair.public as ECPublicKey)), "signed with the p8 key")

    val claims = jwt.jwtClaimsSet
    assertEquals("TEAM123", claims.issuer)
    assertEquals("org.sempods.test.service", claims.subject)
    assertEquals(listOf("https://appleid.apple.com"), claims.audience)

    // The bug this replaces: one secret per process with a 30-day life, so a container running
    // longer lost Apple login with no signal beyond sign-ins that stopped working.
    val lifetimeSeconds = (claims.expirationTime.time - claims.issueTime.time) / 1000
    assertTrue(lifetimeSeconds in 1..3600, "expected a short life, got ${lifetimeSeconds}s")
    assertTrue(claims.expirationTime.after(java.util.Date()), "must not be born expired")
  }

  @Test
  fun `each exchange gets its own secret`() {
    val client = clientOf()

    assertTrue(
      SignedJWT.parse(client.generateClientSecret()).jwtClaimsSet.expirationTime
        .after(java.util.Date()),
    )
    // Not asserting inequality of the serialized form: ECDSA is randomised, so two signatures of
    // identical claims differ anyway and would prove nothing about freshness.
    assertTrue(client.generateClientSecret().isNotBlank())
  }

  @Test
  fun `the p8 loads whether or not it kept its line breaks`() {
    // `.env` files carry one line. Both the downloaded form and a joined one must work, or the
    // deployment fails at the first Apple login with a base64 error that points nowhere useful.
    val singleLine = multiLinePem.replace("\n", "")
    val escapedNewlines = multiLinePem.replace("\n", "\\n")

    for (pem in listOf(multiLinePem, singleLine, escapedNewlines)) {
      assertTrue(
        SignedJWT.parse(clientOf(pem).generateClientSecret())
          .verify(ECDSAVerifier(keyPair.public as ECPublicKey)),
        "failed for: ${pem.take(40)}…",
      )
    }
  }

  @Test
  fun `a key that is not a key names the variable that is wrong`() {
    val client = clientOf("-----BEGIN PRIVATE KEY-----not-a-real-key-----END PRIVATE KEY-----")

    val failure = runCatching { client.generateClientSecret() }.exceptionOrNull()

    assertNotNull(failure)
    assertTrue(
      generateSequence(failure) { it.cause }.any { it.message?.contains("APPLE_PRIVATE_KEY_PEM") == true },
      "the message must name the setting to fix, got: ${failure.message}",
    )
  }

  // ─── the `user` form field ────────────────────────────────────────────────

  @Test
  fun `apple's one-time user field yields a display name`() {
    // Sent on the first authorization only — there is no second chance to read it.
    val user = AppleOidcClient.AppleUserField.parse(
      """{"name":{"firstName":"Alice","lastName":"Smith"},"email":"alice@example.invalid"}"""
    )

    assertEquals("Alice Smith", user.displayName)
  }

  @Test
  fun `the address in the user field is never taken as identity`() {
    // `user` is form data from the callback POST, which the browser controls. The WebID is derived
    // from the email, so reading an address from here would let anyone who completes an Apple login
    // with their own account claim someone else's identity URI — and every grant made against it.
    // Only the signed id_token may supply an address, so the field is not even modelled.
    val forged = AppleOidcClient.AppleUserField.parse(
      """{"name":{"firstName":"Mallory"},"email":"victim@example.invalid"}"""
    )

    assertFalse(
      forged.toString().contains("victim@example.invalid"),
      "an address from the callback body must not survive parsing: $forged",
    )
  }

  @Test
  fun `a partial name still produces what there is`() {
    assertEquals("Alice", AppleOidcClient.AppleUserField.parse("""{"name":{"firstName":"Alice"}}""").displayName)
    assertNull(AppleOidcClient.AppleUserField.parse("""{"name":{}}""").displayName)
    assertNull(AppleOidcClient.AppleUserField.parse("""{"name":{"firstName":"  "}}""").displayName)
  }

  @Test
  fun `an absent or broken user field costs the name, not the login`() {
    // Unsigned display data. Refusing the login over it would trade a missing name for no access.
    for (raw in listOf(null, "", "   ", "not json", "[]", """{"name":"not-an-object"}""")) {
      val user = AppleOidcClient.AppleUserField.parse(raw)

      assertNull(user.displayName, "for: $raw")
    }
  }
}
