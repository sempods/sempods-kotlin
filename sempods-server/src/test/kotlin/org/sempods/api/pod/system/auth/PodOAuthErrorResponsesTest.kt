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
 * These are the reasons this is still assembled by hand rather than by a protocol library. A
 * library appends parameters and has no way to remove one it did not write, and nimbus's `State`
 * refuses a blank value from its constructor — which this now echoes. Each case here is a byte a
 * client already sees, so `#154`'s conversion has to keep it.
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
  ).location.toString()

  @Test
  fun `an error sends the browser without re-sending the consent form`() {
    val response = PodOAuthErrorResponses.render(
      OAuthErrorDelivery.Redirect(target("https://app.example/cb"), OAuthErrorCode.ACCESS_DENIED, "no", null),
      config(null),
    )

    assertEquals(303, response.status, "a 307 would re-POST the consent form to the app")
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
  fun `a state is written as it stands, and one that was never sent stays absent`() {
    // What counts as sent was decided before this, by
    // [suppliedState][org.sempods.pods.oauth.flows.suppliedState].
    locationOf("https://app.example/cb", state = "  ").let { assertTrue("state=++" in it, it) }
    locationOf("https://app.example/cb", state = null).let { assertFalse("state=" in it, it) }
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
