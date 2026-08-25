package org.sempods.commons.identity

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebIdUriDeriverTest {

  private val deriver = WebIdUriDeriver("https://id.sempods.org")

  @Test
  fun `sha256Hex produces lowercase hex string of correct length`() {
    val hash = WebIdUriDeriver.sha256Hex("test")
    assertEquals(64, hash.length)
    assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
  }

  @Test
  fun `deriveFromEmail with default base URL produces expected URI format`() {
    val uri = deriver.deriveFromEmail("user@example.com")
    assertTrue(uri.startsWith("https://id.sempods.org/e/"))
    val hash = uri.removePrefix("https://id.sempods.org/e/")
    assertEquals(64, hash.length)
  }

  @Test
  fun `deriveFromEmail normalizes email before hashing`() {
    val lower = deriver.deriveFromEmail("user@example.com")
    val upper = deriver.deriveFromEmail("User@Example.COM")
    assertEquals(lower, upper)
  }

  @Test
  fun `deriveFromEmail trims whitespace`() {
    val trimmed = deriver.deriveFromEmail("user@example.com")
    val padded = deriver.deriveFromEmail("  user@example.com  ")
    assertEquals(trimmed, padded)
  }

  @Test
  fun `deriveFromEmail uses custom idBaseUrl`() {
    val custom = WebIdUriDeriver("https://custom.example.org")
    val uri = custom.deriveFromEmail("user@example.com")
    assertTrue(uri.startsWith("https://custom.example.org/e/"))
  }

  @Test
  fun `deriveUrnFromEmail produces urn scheme`() {
    val urn = WebIdUriDeriver.deriveUrnFromEmail("user@example.com")
    assertTrue(urn.startsWith("urn:sempods:e:"))
    val hash = urn.removePrefix("urn:sempods:e:")
    assertEquals(64, hash.length)
  }

  @Test
  fun `deriveUrnFromEmail and deriveFromEmail share the same hash`() {
    val email = "user@example.com"
    val uri = deriver.deriveFromEmail(email)
    val urn = WebIdUriDeriver.deriveUrnFromEmail(email)
    val uriHash = uri.substringAfterLast("/e/")
    val urnHash = urn.removePrefix("urn:sempods:e:")
    assertEquals(uriHash, urnHash)
  }

  @Test
  fun `different emails produce different hashes`() {
    val hash1 = WebIdUriDeriver.sha256Hex("alice@example.com")
    val hash2 = WebIdUriDeriver.sha256Hex("bob@example.com")
    assertNotEquals(hash1, hash2)
  }

  @Test
  fun `isEmailBasedWebId validates correctly`() {
    val uri = deriver.deriveFromEmail("user@example.com")
    assertTrue(deriver.isEmailBasedWebId(uri))
  }

  @Test
  fun `derivableAliases bridges both directions of the email namespace`() {
    val hash = WebIdUriDeriver.sha256Hex("user@example.com")
    val webId = "https://id.sempods.org/e/$hash"
    val urn = "urn:sempods:e:$hash"
    val expected = setOf(webId, urn)

    assertEquals(expected, deriver.derivableAliases(webId))
    assertEquals(expected, deriver.derivableAliases(urn))
  }

  @Test
  fun `derivableAliases bridges both directions of the oidc namespace`() {
    // sempods-auth mints these for logins without an email; the two forms share one hash exactly
    // like the email pair, so a revocation naming either must reach grants stored under the other.
    val hash = WebIdUriDeriver.sha256Hex("https://accounts.example.com:subject-42")
    val webId = "https://id.sempods.org/oidc/$hash"
    val urn = "urn:sempods:oidc:$hash"
    val expected = setOf(webId, urn)

    assertEquals(expected, deriver.derivableAliases(webId))
    assertEquals(expected, deriver.derivableAliases(urn))
  }

  @Test
  fun `derivableAliases keeps the namespaces apart`() {
    val hash = WebIdUriDeriver.sha256Hex("user@example.com")

    // Same hash in both namespaces would be a coincidence, but must never be treated as the same
    // subject — they are hashes of different inputs.
    assertEquals(
      setOf("https://id.sempods.org/e/$hash", "urn:sempods:e:$hash"),
      deriver.derivableAliases("urn:sempods:e:$hash"),
    )
    assertEquals(
      setOf("https://id.sempods.org/oidc/$hash", "urn:sempods:oidc:$hash"),
      deriver.derivableAliases("urn:sempods:oidc:$hash"),
    )
  }

  @Test
  fun `derivableAliases returns the input alone when nothing is derivable`() {
    // Anonymous subjects carry a UUID, foreign WebIDs are opaque, malformed hashes prove nothing.
    listOf(
      "urn:sempods:anon:0b7f2c1e-5d3a-4f10-9a2b-6c8e4d1f7a30",
      "https://acme.example/people/jane",
      "urn:sempods:e:not-a-sha256",
      "https://id.sempods.org/e/short",
    ).forEach { uri ->
      assertEquals(setOf(uri), deriver.derivableAliases(uri), "unexpected aliases for $uri")
    }
  }

  @Test
  fun `derivableAliases honours a custom idBaseUrl`() {
    val custom = WebIdUriDeriver("https://custom.example.org")
    val hash = WebIdUriDeriver.sha256Hex("user@example.com")

    assertEquals(
      setOf("https://custom.example.org/oidc/$hash", "urn:sempods:oidc:$hash"),
      custom.derivableAliases("urn:sempods:oidc:$hash"),
    )
    // A WebID from a different deployment is not this instance's to bridge.
    assertEquals(
      setOf("https://id.sempods.org/e/$hash"),
      custom.derivableAliases("https://id.sempods.org/e/$hash"),
    )
  }

  // Verify the formula is byte-identical to LoginService.sha256Hex in sempods-auth.
  // Known vector: sha256("user@example.com") computed externally.
  @Test
  fun `sha256Hex matches known vector for user@example com`() {
    val hash = WebIdUriDeriver.sha256Hex("user@example.com")
    assertEquals("b4c9a289323b21a01c3e940f150eb9b8c542587f1abfd8f0e1cc1ffc5e475514", hash)
  }
}
