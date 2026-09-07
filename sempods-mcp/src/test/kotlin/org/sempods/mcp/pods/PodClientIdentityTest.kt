package org.sempods.mcp.pods

import org.sempods.auth.core.ClientId
import org.sempods.auth.core.DidWeb
import org.sempods.mcp.auth.WebSession
import org.sempods.mcp.persist.PodKey
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The three things a profile presents to a pod, and the two constraints that place them. */
class PodClientIdentityTest {

  @Test
  fun `a named profile's callback stays inside the session cookie's path`() {
    // The constraint that decides where the profile segment goes, and the one an HTTP test cannot
    // see: `WebSession` scopes the session to `/_system/ui` and RFC 6265 §5.1.4 sends a cookie only
    // to paths below its own. A callback outside that arrives with no session in a real browser —
    // the handler would redirect to sign-in instead of exchanging the code, and no named profile
    // could ever finish a connect. A test client setting the `Cookie` header by hand sees none of
    // that, so the rule is asserted here instead.
    val sessionScope = BASE + WebSession.COOKIE_PATH + "/"

    listOf(PodKey.DEFAULT_PROFILE, "cron-agent").forEach { profile ->
      val callback = PodClientIdentity.callbackUri(BASE, profile)
      assertTrue(callback.startsWith(sessionScope), "'$callback' is outside '$sessionScope'")
    }
  }

  @Test
  fun `the default profile presents what it always presented`() {
    // No migration, nothing existing re-consents: the identity every current connection was
    // registered under has to come out of here unchanged.
    assertEquals(
      "https://mcp.test/_system/ui/pods/callback",
      PodClientIdentity.callbackUri(BASE, PodKey.DEFAULT_PROFILE),
    )
    assertEquals("sempods-mcp", PodClientIdentity.clientName(PodKey.DEFAULT_PROFILE))
    assertEquals("did:web:mcp.test", PodClientIdentity.didWebClientId(BASE, PodKey.DEFAULT_PROFILE))
  }

  @Test
  fun `a named profile's identifier covers its own callback and nothing else`() {
    // The static half of the fork. The identifier has to cover the address the pod redirects to —
    // `PodAuthEndpoint.isAllowedRedirectUri` asks `covers` — and must not cover the default
    // profile's, which is the whole point of giving it one.
    val clientId = PodClientIdentity.didWebClientId(BASE, "cron-agent")
    assertEquals("did:web:mcp.test:_system:ui:pods:callback:cron-agent", clientId)
    assertTrue(ClientId.isValid(clientId), "a pod compares and stores this string")

    val target = assertNotNull(DidWeb.targetOf(clientId))
    assertTrue(target.covers(URI(PodClientIdentity.callbackUri(BASE, "cron-agent"))))
    assertFalse(target.covers(URI(PodClientIdentity.callbackUri(BASE, PodKey.DEFAULT_PROFILE))))
    assertFalse(target.covers(URI("$BASE/_system/ui/pods/callback/cron-agent-other")))
  }

  @Test
  fun `a named profile carries its name, so a consent screen can tell two clients apart`() {
    assertEquals("sempods-mcp (cron-agent)", PodClientIdentity.clientName("cron-agent"))
  }

  private companion object {
    const val BASE = "https://mcp.test"
  }
}
