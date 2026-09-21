package org.sempods.api.pod.system.auth

import org.sempods.SempodsConfig
import org.sempods.SempodsModule
import org.sempods.auth.core.ClientRedirectPolicy
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.Redirectable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit — how an authorization error is written into a redirect address.
 *
 * Four of these are the reasons this is still assembled by hand rather than by a protocol library.
 * A library appends parameters and has no way to remove one it did not write, and nimbus's `State`
 * refuses a blank value from its constructor; each would change a byte a client already sees. When
 * `#154` converts this, these are the cases that say what the conversion has to keep.
 */
class PodOAuthErrorResponsesTest {

  private val anyAddressIsTheClient = ClientRedirectPolicy { _, _ -> true }

  private fun target(uri: String): Redirectable =
    checkNotNull(OAuthErrors.redirectTargetFor(anyAddressIsTheClient, "did:web:app.example", uri))

  private fun config(docBase: String?): SempodsConfig =
    SempodsModule.config.copy(oauthErrorDocBase = docBase)

  private fun locationOf(
    redirectUri: String,
    state: String? = null,
    docBase: String? = "https://docs.example/oauth-errors",
  ): String = PodOAuthErrorResponses.render(
    OAuthErrorDelivery.Redirect(target(redirectUri), OAuthErrorCode.ACCESS_DENIED, "no", state),
    config(docBase),
  ).let { response ->
    assertEquals(307, response.status, "an authorization error is reported without changing the method")
    response.location.toString()
  }

  @Test
  fun `an error names its code, its description and the page a person can act on`() {
    val location = locationOf("https://app.example/cb", state = "s1")

    assertTrue("error=access_denied" in location, location)
    assertTrue("error_description=no" in location, location)
    assertTrue("error_uri=" in location && "%23access_denied" in location, location)
    assertTrue("state=s1" in location, location)
  }

  @Test
  fun `a parameter the address already carries is overwritten, not doubled`() {
    // A registered address may carry a query of its own, and a client reading the first `error` it
    // finds would otherwise be told whatever the address was registered with.
    val location = locationOf("https://app.example/cb?error=stale&keep=mine")

    assertEquals(1, Regex("[?&]error=").findAll(location).count(), location)
    assertTrue("error=access_denied" in location, location)
    assertTrue("keep=mine" in location, "a parameter this server did not write survives: $location")
  }

  @Test
  fun `an error_uri this server has none to give is removed rather than left standing`() {
    // Deployment-specific: without SEMPODS_OAUTH_ERROR_DOC_BASE there is no page to point at, and
    // a client registered as `...cb?error_uri=...` would otherwise get its own value back looking
    // exactly like one this server chose.
    val location = locationOf("https://app.example/cb?error_uri=https://evil.example/x", docBase = null)

    assertFalse("error_uri" in location, location)
    assertTrue("error=access_denied" in location, location)
  }

  @Test
  fun `a state carrying no information is not echoed`() {
    // RFC 6749 makes `state` opaque `*VSCHAR`, so a client may legally send `state=%20`. It is the
    // one part of the answer such a client cannot use, and it is what a protocol library refuses.
    for (blank in listOf(null, "", "   ")) {
      val location = locationOf("https://app.example/cb", state = blank)
      assertFalse("state=" in location, "state='$blank' reached the address: $location")
    }
  }

  @Test
  fun `an error with no proven address is rendered here instead of sent anywhere`() {
    val response = PodOAuthErrorResponses.render(
      OAuthErrorDelivery.Direct(OAuthErrorCode.INVALID_CLIENT, "unknown client"),
      config(null),
    )

    assertEquals(400, response.status)
    assertEquals("invalid_client: unknown client", response.entity)
    assertEquals("text/plain", response.mediaType.toString())
  }
}
