package org.sempods.client

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** What a caller reads off its redirect after each browser round trip, and what it refuses to read. */
class SempodsRedirectReadingTest {

  /** Nothing is sent: reading a redirect needs the pod, for its issuer, and no call. */
  private val authorization = SempodsPodAuthorization(SempodsSession(SempodsPodBase.of("https://pods.example/alice")), OkHttpClient())

  @Test
  fun `an approved authorization carries its code, which is not printed`() {
    val answer = authorization.readRedirect("code=c-123&state=s1", "s1")

    assertTrue(answer.isApproved)
    assertEquals("c-123", answer.code)
    assertNull(answer.error)
    assertFalse(answer.toString().contains("c-123"), answer.toString())
  }

  @Test
  fun `a declined authorization carries the error and its description`() {
    val answer = authorization.readRedirect("error=access_denied&error_description=the%20owner%20declined&state=s1", "s1")

    assertFalse(answer.isApproved)
    assertNull(answer.code)
    assertEquals("access_denied", answer.error)
    assertEquals("the owner declined", answer.errorDescription)
  }

  @Test
  fun `an issuer that is this pod's is accepted (RFC 9207)`() {
    val answer = authorization.readRedirect("code=c-1&state=s1&iss=https%3A%2F%2Fpods.example%2Falice%2F_system%2Fauth", "s1")

    assertEquals("c-1", answer.code)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "code=c&state=other", "code=c", "code=c&state=s1&state=s1", "state=s1", "code=c&code=d&state=s1",
      "code=c&state=s1&iss=https%3A%2F%2Fevil.example%2F_system%2Fauth",
      "error=access_denied&state=s1&iss=https%3A%2F%2Fpods.example%2Fbob%2F_system%2Fauth",
    ],
  )
  fun `an authorization redirect that is not this caller's single answer from this pod is refused`(query: String) {
    assertThrows<SempodsClientException> { authorization.readRedirect(query, "s1") }
  }

  @Test
  fun `a granted consent carries the scopes the owner granted`() {
    val outcome = SempodsGrantOutcome.readQuery("result=granted&scope=urn%3Aa%23read%20urn%3Ab%23write&state=g1", "g1")

    assertTrue(outcome.isGranted)
    assertEquals(setOf("urn:a#read", "urn:b#write"), outcome.scopes)
    assertNull(outcome.error)
  }

  @Test
  fun `a refused consent is an outcome, not a failure`() {
    val outcome = SempodsGrantOutcome.readQuery("error=access_denied&state=g1", "g1")

    assertFalse(outcome.isGranted)
    assertTrue(outcome.scopes.isEmpty())
    assertEquals("access_denied", outcome.error)
  }

  @ParameterizedTest
  @ValueSource(strings = ["result=granted&scope=a&state=other", "result=granted&scope=a", "state=g1", "result=maybe&state=g1", "result=granted&error=x&state=g1"])
  fun `a grant redirect that is not this caller's single answer is refused`(query: String) {
    assertThrows<SempodsClientException> { SempodsGrantOutcome.readQuery(query, "g1") }
  }
}
