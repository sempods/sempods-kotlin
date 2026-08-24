package org.sempods

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pod server's own deployment facts.
 *
 * They used to be literals, which meant nobody could run sempods under their own address without
 * editing Kotlin. Now they come from the environment — and the assertion that matters most is the
 * boring one: with nothing set, the values are exactly what they have always been, so neither
 * making them configurable nor moving them off the old application config can move a live
 * deployment.
 *
 * [SempodsModule.config] is a companion `by lazy`, evaluated once per JVM. Setting variables from a
 * test would therefore be a race with whatever touched the class first, so this pins the defaults
 * only; the override path is covered where it is implemented, in `EnvTest`.
 */
class SempodsConfigTest {

  @Test
  fun `the defaults are unchanged`() {
    // Against the constants, not against `SempodsModule.config`. This used to read the resolved
    // config, which said what a deployment setting nothing would get only while the test JVM's
    // environment was empty. It is not: the build gives every module's test JVM its own database
    // and its own port so the suites can run side by side. The literals below are still the ones
    // that have always been shipped, so a silent change to a default still fails here.
    assertEquals(8090, SempodsModule.DEFAULT_HTTP_PORT)
    assertEquals("mongodb://localhost:27018", SempodsModule.DEFAULT_MONGODB_URL)
    // The service's own name since the collections moved out of the database they shared with an
    // application. The literal is deliberate: this assertion is what would fail if the default
    // were changed without the migration that has to go with it.
    assertEquals("sempods-server", SempodsModule.DEFAULT_MONGODB_DB_NAME)
    // The one default here that is *not* "what has always been shipped": `oauth.serviceAuditLog`
    // had no retention at all, and 90 days is the value the hosted MCP service's trail already
    // runs on. Changing it changes how long a live deployment keeps its service-client history.
    assertEquals(90L, SempodsModule.DEFAULT_SERVICE_AUDIT_RETENTION_DAYS)
    assertEquals(90L, SempodsConfig(
      httpPort = 8090,
      apiBaseUrl = "https://example.org/",
      mongoUrl = "mongodb://localhost:27018",
      mongoDb = "pods",
      oauthErrorDocBase = null,
    ).serviceAuditRetentionDays)
    // The token endpoint's budget. Also not "what has always been shipped" — there was no limit at
    // all — so both numbers are decisions, and they are two numbers on purpose: the rate has to sit
    // BELOW the 78 requests a minute the client behind this limit sustained, or it would never
    // refuse it anything, while the burst has to sit above a service client's provisioning sweep.
    // Raising the rate past 78 silently turns the protection off, which is what this pins.
    assertEquals(20, SempodsModule.DEFAULT_TOKEN_RATE_LIMIT_PER_MINUTE)
    assertTrue(
      SempodsModule.DEFAULT_TOKEN_RATE_LIMIT_PER_MINUTE < 78,
      "the rate must stay below the sustained traffic it exists to refuse",
    )
    assertEquals(300, SempodsModule.DEFAULT_TOKEN_RATE_LIMIT_BURST)
    // The address tier in front of it. Its rate has to stay far below what holding the shared key
    // map full would take — upwards of 4,000 requests a minute — or one caller could reserve that
    // map, which is the failure the eviction policy and this tier answer together.
    assertEquals(100, SempodsModule.DEFAULT_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE)
    assertEquals(1000, SempodsModule.DEFAULT_TOKEN_RATE_LIMIT_ADDRESS_BURST)
    assertTrue(
      SempodsModule.DEFAULT_TOKEN_RATE_LIMIT_ADDRESS_PER_MINUTE > SempodsModule.DEFAULT_TOKEN_RATE_LIMIT_PER_MINUTE,
      "the aggregate must sit above the per-client tier it gates, or the finer one is unreachable",
    )
    // The type defaults are `0` — off, and "burst follows the rate" — which is what a caller
    // constructing this object without an opinion should get.
    val withoutOpinion = SempodsConfig(
      httpPort = 8090,
      apiBaseUrl = "https://example.org/",
      mongoUrl = "mongodb://localhost:27018",
      mongoDb = "pods",
      oauthErrorDocBase = null,
    )
    assertEquals(0, withoutOpinion.tokenRateLimitPerMinute)
    assertEquals(0, withoutOpinion.tokenRateLimitBurst)
    assertEquals(0, withoutOpinion.tokenRateLimitAddressPerMinute)
    assertEquals(0, withoutOpinion.tokenRateLimitAddressBurst)
  }

  /**
   * The documented off switch has to turn the whole endpoint off, not half of it.
   *
   * The two tiers have separate variables, so an operator zeroing the per-client rate and leaving
   * the address one at its production default would still be refused — by a budget they had every
   * reason to think was off, in the middle of whatever made them reach for the switch.
   */
  @Test
  fun `zeroing the per-client rate disables the address tier with it`() {
    assertEquals(0, SempodsConfig.resolveAddressRateLimit(perMinute = 0, addressPerMinute = 100))
    // ...and it is the feature switch, not a general veto: an address tier stands where the
    // endpoint's limit is on.
    assertEquals(100, SempodsConfig.resolveAddressRateLimit(perMinute = 20, addressPerMinute = 100))
    // The address tier keeps a switch of its own, for a deployment that wants only the finer one.
    assertEquals(0, SempodsConfig.resolveAddressRateLimit(perMinute = 20, addressPerMinute = 0))
  }

  /**
   * A negative budget is a typo, not a decision — and the one typo that must not boot.
   *
   * `Env.int` parses `-300` happily, and `TokenBucketRateLimiter` reads every non-positive rate as
   * "disabled". Without this guard an operator who fat-fingers a minus sign gets a server that
   * starts, logs nothing, and has the protection switched off; the documented off switch is `0`.
   */
  @Test
  fun `a negative budget is refused rather than read as off`() {
    assertFailsWith<IllegalArgumentException> { configWith(rate = -300) }
    assertFailsWith<IllegalArgumentException> { configWith(rate = 20, burst = -1) }
    assertFailsWith<IllegalArgumentException> { configWith(rate = 20, addressRate = -100) }
    assertFailsWith<IllegalArgumentException> { configWith(rate = 20, addressBurst = -1) }
    // Zero is the documented way to turn it off, and stays legal.
    assertEquals(0, configWith(rate = 0).tokenRateLimitPerMinute)
  }

  /**
   * The off switch is a promise the *type* makes, so no object may carry the contradiction.
   *
   * `resolveAddressRateLimit` covers the environment path, which is the operator's. This class is
   * public API of a published module, though, and a composition or a test building it by hand would
   * otherwise reach the one state the documentation says cannot exist — the endpoint's limit off
   * while an address tier goes on answering 429.
   */
  @Test
  fun `a config cannot say the limit is off and keep an address tier`() {
    val refused = assertFailsWith<IllegalArgumentException> {
      configWith(rate = 0, addressRate = 100)
    }
    // The message has to say which way out, since either is legitimate.
    assertTrue("Set both to 0" in refused.message.orEmpty(), refused.message.orEmpty())

    // Both halves off is the off switch, and is fine.
    assertEquals(0, configWith(rate = 0, addressRate = 0).tokenRateLimitAddressPerMinute)
    // And the limit on, with a tier above it, is the production shape.
    assertEquals(100, configWith(rate = 20, addressRate = 100).tokenRateLimitAddressPerMinute)
  }

  private fun configWith(rate: Int, burst: Int = 0, addressRate: Int = 0, addressBurst: Int = 0) = SempodsConfig(
    httpPort = 8090,
    apiBaseUrl = "https://example.org/",
    mongoUrl = "mongodb://localhost:27018",
    mongoDb = "pods",
    oauthErrorDocBase = null,
    tokenRateLimitPerMinute = rate,
    tokenRateLimitBurst = burst,
    tokenRateLimitAddressPerMinute = addressRate,
    tokenRateLimitAddressBurst = addressBurst,
  )

  /**
   * The documentation address behind every OAuth `error_uri`.
   *
   * It has no default, which is the point: it used to be a literal pointing at
   * `sempods.org/docs/oauth-errors`, an address in somebody else's deployment for a self-hoster
   * and one that nothing serves — the site is chat-only. Unset therefore has to mean "no
   * `error_uri`", not "link to a 404". `PodAuthEndpointHttpTest` pins the redirect behaviour;
   * these pin the rule that decides it.
   */
  @Test
  fun `an unset documentation address means no error_uri at all`() {
    assertNull(SempodsConfig.normalizeErrorDocBase(null))
    // Blank is unset — `FOO=` is a forgotten value, not a configured one. `Env.get` applies the
    // same rule, so this holds for a variable set to whitespace too.
    assertNull(SempodsConfig.normalizeErrorDocBase(""))
    assertNull(SempodsConfig.normalizeErrorDocBase("   "))
    assertNull(
      SempodsConfig(
        httpPort = 8090,
        apiBaseUrl = "https://example.org/",
        mongoUrl = "mongodb://localhost:27018",
        mongoDb = "pods",
        oauthErrorDocBase = null,
      ).oauthErrorUri("access_denied")
    )
  }

  @Test
  fun `a configured address loses its trailing slash and carries the error code as a fragment`() {
    // `#<error-code>` is appended straight to it: with a slash the anchor would sit on
    // `…/oauth-errors/`, which is a different address on most static hosts.
    assertEquals(
      "https://example.org/docs/oauth-errors",
      SempodsConfig.normalizeErrorDocBase("  https://example.org/docs/oauth-errors/  "),
    )
    assertEquals(
      "https://example.org/docs/oauth-errors#consent_required",
      SempodsConfig(
        httpPort = 8090,
        apiBaseUrl = "https://example.org/",
        mongoUrl = "mongodb://localhost:27018",
        mongoDb = "pods",
        oauthErrorDocBase = SempodsConfig.normalizeErrorDocBase("https://example.org/docs/oauth-errors/"),
      ).oauthErrorUri("consent_required"),
    )
  }

  @Test
  fun `the address ends in a slash`() {
    // Pod IRIs are `apiBaseUrl + podName`. Without the slash they would read
    // `https://example.orgalice/…` — and they are persisted.
    assertTrue(SempodsModule.config.apiBaseUrl.endsWith("/"))
  }

  /**
   * The CORS allowlist used to be computed in two places — an application config derived a
   * referer-host set and turned it back into origins, and a filter recomputed the same derivation
   * over every registered app. Both are gone; this pins that the answer did not change.
   */
  @Test
  fun `a deployed pod server allows its own address, its apps host and the dev frontends`() {
    assertEquals(
      setOf(
        "https://sempods.org",
        "http://localhost",
        "https://apps.sempods.org",
        "http://localhost:3000",
        "http://localhost:3100",
      ),
      SempodsConfig.deriveCorsOrigins("https://sempods.org/"),
    )
  }

  @Test
  fun `on localhost there is no apps host to allow`() {
    // `https://apps.localhost` addresses nothing, and the earlier derivation left it out for the
    // same reason. The port-carrying base URL stays in, since that is where the server answers.
    assertEquals(
      setOf(
        "http://localhost:8090",
        "http://localhost",
        "http://localhost:3000",
        "http://localhost:3100",
      ),
      SempodsConfig.deriveCorsOrigins("http://localhost:8090/"),
    )
  }
}
