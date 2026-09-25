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
  fun `a redirect URI's own members may repeat`() {
    assertEquals("c-1", authorization.readRedirect("tag=a&tag=b&code=c-1&state=s1", "s1").code)
  }

  @Test
  fun `an issuer that is this pod's base URL is accepted (RFC 9207)`() {
    val answer = authorization.readRedirect("code=c-1&state=s1&iss=https%3A%2F%2Fpods.example%2Falice", "s1")

    assertEquals("c-1", answer.code)
  }

  @Test
  fun `a pod at the host root is its own issuer, without a slash`() {
    val root = SempodsPodAuthorization(SempodsSession(SempodsPodBase.of("https://alice.example")), OkHttpClient())

    assertEquals("c-1", root.readRedirect("code=c-1&state=s1&iss=https%3A%2F%2Falice.example", "s1").code)
    assertThrows<SempodsClientException> { root.readRedirect("code=c-1&state=s1&iss=https%3A%2F%2Falice.example%2F", "s1") }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "code=c&state=other", "code=c", "code=c&state=s1&state=s1", "state=s1", "code=c&code=d&state=s1", "code=c&state=s1&iss=a&iss=b",
      "code=c&state=s1&iss=https%3A%2F%2Fevil.example%2Falice",
      "error=access_denied&state=s1&iss=https%3A%2F%2Fpods.example%2Fbob",
      // The auth route is not the issuer (SPS-AUTH-028), and neither is the base with a slash.
      "code=c&state=s1&iss=https%3A%2F%2Fpods.example%2Falice%2F_system%2Fauth",
      "code=c&state=s1&iss=https%3A%2F%2Fpods.example%2Falice%2F",
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

  @Test
  fun `the granted scopes cannot be changed by the caller`() {
    val outcome = SempodsGrantOutcome.readQuery("result=granted&scope=urn%3Aa%23read&state=g1", "g1")

    assertThrows<UnsupportedOperationException> { (outcome.scopes as MutableSet<String>).clear() }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "result=granted&scope=a&state=other", "result=granted&scope=a", "state=g1", "result=maybe&state=g1",
      "result=granted&error=x&state=g1", "result=granted&state=g1", "result=granted&scope=&state=g1",
    ],
  )
  fun `a grant redirect that is not this caller's single answer is refused`(query: String) {
    assertThrows<SempodsClientException> { SempodsGrantOutcome.readQuery(query, "g1") }
  }
}
