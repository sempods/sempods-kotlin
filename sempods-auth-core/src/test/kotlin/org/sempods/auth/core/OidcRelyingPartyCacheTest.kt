package org.sempods.auth.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the cache is for: the provider is discovered once for the whole process, and each redirect
 * address keeps its own relying party.
 *
 * Both halves had a witness only by accident before — the pod server shared its metadata, the
 * hosted service re-discovered per callback path, and neither behaviour was pinned anywhere.
 */
class OidcRelyingPartyCacheTest {

  private val clientId = "did:web:service.example.invalid"

  /** Counts what it was asked for. Nothing else is needed: no flow is completed here. */
  private class CountingTransport(private val advertisedIssuer: String = ISSUER) : HttpTransport {
    val fetched = mutableListOf<String>()

    override fun get(url: String): String {
      fetched += url
      if (url != "$ISSUER/.well-known/openid-configuration") error("unexpected GET: $url")
      return """
        {"issuer":"$advertisedIssuer","authorization_endpoint":"$ISSUER/authorize",
         "token_endpoint":"$ISSUER/token","jwks_uri":"$ISSUER/.well-known/jwks.json"}
      """.trimIndent()
    }

    override fun postForm(url: String, form: Map<String, String>): String = error("not used")
  }

  @Test
  fun `a second redirect address costs no second discovery`() {
    // The bug this closes: the hosted service ran a discovery round per callback path, so the
    // AI-client flow and the browser UI each paid for the same document.
    val transport = CountingTransport()
    val cache = OidcRelyingPartyCache(ISSUER, clientId, transport)

    cache.forRedirectUri("https://service.example.invalid/a/cb")
    cache.forRedirectUri("https://service.example.invalid/b/cb")
    cache.forRedirectUri("https://service.example.invalid/c/cb")

    assertEquals(1, transport.fetched.size, transport.fetched.toString())
  }

  @Test
  fun `one address, one relying party`() {
    // Not just one document: the instance holds the SDK's self-refreshing view of the provider's
    // signing keys, and handing out a fresh one per sign-in would turn that into a fetch per login.
    val cache = OidcRelyingPartyCache(ISSUER, clientId, CountingTransport())

    val first = cache.forRedirectUri("https://service.example.invalid/cb")
    val second = cache.forRedirectUri("https://service.example.invalid/cb")

    assertSame(first, second)
  }

  @Test
  fun `each address gets its own, and asks the provider to answer there`() {
    // The whole reason there is more than one: a callback belongs to the flow that started it.
    val cache = OidcRelyingPartyCache(ISSUER, clientId, CountingTransport())

    val ui = cache.forRedirectUri("https://service.example.invalid/ui/cb")
    val ai = cache.forRedirectUri("https://service.example.invalid/oidc/cb")

    assertEquals("https://service.example.invalid/ui/cb", redirectUriOf(ui))
    assertEquals("https://service.example.invalid/oidc/cb", redirectUriOf(ai))
  }

  @Test
  fun `a provider advertising another issuer is still refused`() {
    // `discover` runs once now rather than per address, so this is worth saying: the check that
    // catches a misconfigured provider at wiring time did not become optional.
    val cache = OidcRelyingPartyCache(ISSUER, clientId, CountingTransport(advertisedIssuer = "https://someone.else.invalid"))

    val failure = assertFails { cache.forRedirectUri("https://service.example.invalid/cb") }

    assertContains(failure.message.orEmpty(), "someone.else.invalid")
  }

  @Test
  fun `a failed discovery is not cached as a failure`() {
    // Discovery is lazy so an unreachable provider does not stop a service booting. That is only
    // worth anything if the next sign-in tries again.
    var reachable = false
    val transport = object : HttpTransport {
      override fun get(url: String): String {
        if (!reachable) error("provider unreachable")
        return """
          {"issuer":"$ISSUER","authorization_endpoint":"$ISSUER/authorize",
           "token_endpoint":"$ISSUER/token","jwks_uri":"$ISSUER/.well-known/jwks.json"}
        """.trimIndent()
      }

      override fun postForm(url: String, form: Map<String, String>): String = error("not used")
    }
    val cache = OidcRelyingPartyCache(ISSUER, clientId, transport)
    assertFails { cache.forRedirectUri("https://service.example.invalid/cb") }

    reachable = true

    assertTrue(cache.forRedirectUri("https://service.example.invalid/cb").metadata.issuer == ISSUER)
  }

  private fun redirectUriOf(relyingParty: OidcRelyingParty): String? =
    URI(relyingParty.beginAuthorization().authorizationUrl).rawQuery
      .split('&')
      .map { it.split('=', limit = 2) }
      .firstOrNull { it.first() == "redirect_uri" }
      ?.let { URLDecoder.decode(it[1], StandardCharsets.UTF_8) }

  private companion object {
    private const val ISSUER = "https://id.example.invalid"
  }
}
