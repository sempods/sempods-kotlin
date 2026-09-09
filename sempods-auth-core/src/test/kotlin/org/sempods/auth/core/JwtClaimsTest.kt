package org.sempods.auth.core

import com.nimbusds.jwt.JWTClaimsSet
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * That a claim survives the round trip these helpers exist for: the value is written by one
 * process, serialized, and read back by another with no shared types between them.
 *
 * [instantClaimOrNull] is the one worth pinning. A custom NumericDate is a `Date` in the claims set
 * that was built and a `Long` in the one that was parsed, and a helper that handled only the first
 * would work in every unit test and return null against every real token — which for `auth_time`
 * means a limit measured from it silently never arrives.
 */
class JwtClaimsTest {

  private fun roundTrip(claims: JWTClaimsSet): JWTClaimsSet =
    JWTClaimsSet.parse(claims.toString())

  @Test
  fun `a NumericDate written as epoch seconds reads back as that instant`() {
    val authTime = Instant.parse("2026-09-07T19:12:31Z")
    val parsed = roundTrip(
      JWTClaimsSet.Builder().claim("auth_time", authTime.epochSecond).build(),
    )

    assertEquals(authTime, parsed.instantClaimOrNull("auth_time"))
  }

  @Test
  fun `a NumericDate written as a Date reads back as the same instant`() {
    // The other representation Nimbus accepts. Both are the same claim on the wire, and a caller
    // that built one must not read differently from a caller that parsed the other.
    val authTime = Instant.parse("2026-09-07T19:12:31Z")
    val built = JWTClaimsSet.Builder().claim("auth_time", Date.from(authTime)).build()

    assertEquals(authTime, built.instantClaimOrNull("auth_time"))
    assertEquals(authTime, roundTrip(built).instantClaimOrNull("auth_time"))
  }

  @Test
  fun `an absent claim and one of the wrong type are the same answer`() {
    // The rule this file is built on: a token that does not say a thing, and one that says it in a
    // shape nobody can read, both mean "this token does not say that" — never an exception on a
    // public endpoint.
    val parsed = roundTrip(
      JWTClaimsSet.Builder()
        .claim("auth_time", "not-a-number")
        .claim("token_use", listOf("session"))
        .claim("also_known_as", "not-a-list")
        .build(),
    )

    assertNull(parsed.instantClaimOrNull("auth_time"))
    assertNull(parsed.instantClaimOrNull("absent"))
    assertNull(parsed.stringClaimOrNull("token_use"))
    assertNull(parsed.stringListClaimOrNull("also_known_as"))
  }
}
