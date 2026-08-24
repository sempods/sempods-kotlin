package org.sempods.auth.oidc

import org.sempods.auth.core.OidcPrompt
import org.sempods.auth.oidc.google.GoogleOidcClient
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `prompt=login` is how a caller demands that the user actually authenticates again. If it stops
 * at this bridge, an existing provider session satisfies the flow and the pod server that asked
 * for re-authentication never learns that none happened — the failure is silent, which is what
 * makes it worth a test.
 */
class OidcPromptForwardingTest {

  private val client = GoogleOidcClient(
    clientId = "test-client-id",
    clientSecret = "test-client-secret",
    loginBaseUrl = "https://id.example.invalid",
    tokenExchange = OidcTokenExchange(io.ktor.client.HttpClient()),
  )

  @Test
  fun `a forced login reaches the provider`() {
    val url = client.authorizeUrl(state = "s1", prompt = OidcPrompt.LOGIN)

    assertTrue("prompt=login" in url, "got: $url")
  }

  @Test
  fun `select_account reaches the provider`() {
    val url = client.authorizeUrl(state = "s1", prompt = OidcPrompt.SELECT_ACCOUNT)

    assertTrue("prompt=select_account" in url, "got: $url")
  }

  @Test
  fun `no prompt means no parameter`() {
    val url = client.authorizeUrl(state = "s1")

    assertFalse("prompt=" in url, "an ordinary login must not force a re-prompt; got: $url")
  }

  @Test
  fun `the state still travels`() {
    val url = client.authorizeUrl(state = "abc123", prompt = OidcPrompt.LOGIN)

    assertTrue("state=abc123" in url, "got: $url")
  }

  @Test
  fun `only the two known prompts are forwarded`() {
    // `/authorize` is public, so whatever arrives there would otherwise land in the provider's URL.
    assertEquals(OidcPrompt.LOGIN, OidcPrompt.parse("login"))
    assertEquals(OidcPrompt.SELECT_ACCOUNT, OidcPrompt.parse("select_account"))
    assertEquals(OidcPrompt.LOGIN, OidcPrompt.parse("  LOGIN "))

    assertNull(OidcPrompt.parse(null))
    assertNull(OidcPrompt.parse(""))
    assertNull(OidcPrompt.parse("consent"))
    assertNull(OidcPrompt.parse("none"))
    assertNull(OidcPrompt.parse("login&scope=evil"))
  }
}
