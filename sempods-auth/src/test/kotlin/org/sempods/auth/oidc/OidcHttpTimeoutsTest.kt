package org.sempods.auth.oidc

import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import io.ktor.client.engine.cio.CIOEngineConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The timeouts the upstream OIDC leg actually runs under.
 *
 * Nothing in this repository sets them — `SempodsAuthModule.httpClient` installs one plugin and
 * takes the engine as it comes — so they are Ktor's CIO defaults, and `docs/auth/oauth.md`
 * §"Sharp edges" quotes them. That pairing is why this test exists: the same paragraph has now
 * carried two different wrong descriptions of this behaviour, first a "10 s with two retries" that
 * matched nothing in the tree, then a "no connect timeout, holds a coroutine indefinitely" that
 * was taken from a stale code comment instead of measured. A documented number that nothing checks
 * goes stale silently; this fails on a Ktor upgrade that moves one, which is the point at which
 * the documentation has to move with it.
 *
 * Not an assertion that these values are *right*. They are nobody's decision — see the TODO on
 * [OidcTokenExchange].
 */
class OidcHttpTimeoutsTest {

  @Test
  fun `the CIO defaults the OIDC leg inherits are the ones the docs quote`() {
    val config = CIOEngineConfig()

    // Whole-request budget, engine-wide: what actually bounds a call to an unresponsive provider.
    assertEquals(15_000, config.requestTimeout)
    assertEquals(5_000, config.endpoint.connectTimeout)
    // Unbounded on its own — the request timeout above is what stops it mattering. Documented
    // together for that reason: quoting the socket timeout alone reads as "hangs forever".
    assertEquals(Long.MAX_VALUE, config.endpoint.socketTimeout)
    // One attempt. There is no retry policy here, which the docs used to claim there was.
    assertEquals(1, config.endpoint.connectAttempts)
  }

  /**
   * The signature check after the exchange runs on a *different* client again.
   *
   * `IdTokenVerifier` builds its key source with `JWKSourceBuilder.create(url)` and configures no
   * timeouts, so a JWKS fetch gets Nimbus' defaults — which are half a second, two orders of
   * magnitude tighter than the exchange that precedes it on the same login. Cheap most of the
   * time, because the source caches for five minutes and refreshes ahead of expiry in the
   * background; the window that matters is a cold cache, which is every process's first login.
   *
   * Pinned for the same reason as the pair above: `oauth.md` quotes these, and this leg is the
   * one the paragraph missed twice while claiming to describe the whole round trip.
   */
  @Test
  fun `the JWKS fetch has its own, much tighter budget`() {
    assertEquals(500, JWKSourceBuilder.DEFAULT_HTTP_CONNECT_TIMEOUT)
    assertEquals(500, JWKSourceBuilder.DEFAULT_HTTP_READ_TIMEOUT)
    // What keeps the half second off the critical path for all but the first login.
    assertEquals(300_000, JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE)
    assertEquals(30_000, JWKSourceBuilder.DEFAULT_REFRESH_AHEAD_TIME)
  }
}
