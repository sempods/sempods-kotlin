package org.sempods.auth.core

import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import org.junit.jupiter.api.Test
import org.sempods.auth.core.EquivalentIdentities.CLAIM
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The claim's value contract, `SPS-OIDC-005`, `SPS-OIDC-016` and `SPS-OIDC-017`, read the way a relying
 * party reads it: from a token that went through serialization, where a JSON `null` and an absent
 * claim are different things.
 */
class EquivalentIdentitiesTest {

  private val sub = "https://id.example/alice#me"

  /** A claims set as a parsed token carries it, with [value] written verbatim — `null` included. */
  private fun claimed(value: Any?): JWTClaimsSet {
    val built = JWTClaimsSet.Builder().subject(sub).claim(CLAIM, value).serializeNullClaims(true).build()
    return PlainJWT.parse(PlainJWT(built).serialize()).jwtClaimsSet
  }

  private fun absent(): JWTClaimsSet = PlainJWT.parse(PlainJWT(JWTClaimsSet.Builder().subject(sub).build()).serialize()).jwtClaimsSet

  @Test
  fun `an absent claim and an empty array assert nothing`() {
    assertEquals(emptySet(), EquivalentIdentities.read(absent()))
    assertEquals(emptySet(), EquivalentIdentities.read(claimed(emptyList<String>())))
  }

  @Test
  fun `HTTP and HTTPS WebIDs are read, with and without a fragment`() {
    val webIds = listOf(
      "https://other.example/alice#me",
      "https://third.example/people/alice",
      "http://id.example/e/91c2",
      "HTTPS://Upper.Example/alice",
      "https://user@id.example:8443/a/b?c=d#e",
    )

    assertEquals(webIds.toSet(), EquivalentIdentities.read(claimed(webIds)))
  }

  @Test
  fun `order, duplicates and sub itself change nothing`() {
    val a = "https://other.example/alice#me"
    val b = "https://third.example/people/alice"

    assertEquals(setOf(a, b), EquivalentIdentities.read(claimed(listOf(b, a, a, sub, b))))
    assertEquals(EquivalentIdentities.read(claimed(listOf(a, b))), EquivalentIdentities.read(claimed(listOf(b, sub, a))))
    assertEquals(emptySet(), EquivalentIdentities.read(claimed(listOf(sub))))
  }

  @Test
  fun `a present claim of any other shape refuses the assertion`() {
    val refused = listOf<Any?>(
      null,
      "https://other.example/alice#me",
      42L,
      true,
      mapOf("id" to "https://other.example/alice#me"),
      listOf(null),
      listOf(42L),
      listOf(listOf("https://other.example/alice#me")),
      listOf(""),
      listOf("/alice"),
      listOf("alice"),
      listOf("//other.example/alice"),
      listOf("urn:example:alice"),
      listOf("urn:sempods:e:91c2"),
      listOf("did:web:other.example"),
      listOf("mailto:alice@other.example"),
      listOf("ftp://other.example/alice"),
      listOf("https:alice"),
      listOf("https:///alice"),
      listOf("https://other example/alice"),
      listOf("https://other.example/%zz"),
      listOf("https://other.example/a#b#c"),
      listOf("https://other.example/älice"),
      listOf("https://other.example/alice\n"),
    )

    for (value in refused) {
      // Refused by this code as a decision. A typed read would throw the library's `ParseException`.
      val failure = assertFailsWith<IllegalStateException>("value $value") { EquivalentIdentities.read(claimed(value)) }
      assertContains(failure.message.orEmpty(), CLAIM)
    }
  }

  @Test
  fun `one bad member refuses the whole array`() {
    // The valid WebID is not kept: the issuer stated a set, and this is not it.
    val mixed = listOf("https://other.example/alice#me", "urn:example:alice")

    assertFailsWith<IllegalStateException> { EquivalentIdentities.read(claimed(mixed)) }
  }

  @Test
  fun `the registered also_known_as claim asserts no identity`() {
    val built = JWTClaimsSet.Builder().subject(sub).claim("also_known_as", listOf("https://other.example/alice#me")).build()

    assertEquals(emptySet(), EquivalentIdentities.read(PlainJWT.parse(PlainJWT(built).serialize()).jwtClaimsSet))
  }

  @Test
  fun `a member is a WebID URI exactly when it is an HTTP or HTTPS URI with a host`() {
    assertTrue(EquivalentIdentities.isWebIdUri("https://id.example/alice#me"))
    assertTrue(EquivalentIdentities.isWebIdUri("http://[::1]/alice"))
    assertFalse(EquivalentIdentities.isWebIdUri("urn:sempods:oidc:91c2"))
    assertFalse(EquivalentIdentities.isWebIdUri("https://"))
  }
}
