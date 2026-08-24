package org.sempods

import java.net.URI

/**
 * What the pod server needs to know about itself: where it listens, the address it is known by,
 * which browser origins may talk to it, and which database holds its collections.
 *
 * Replaces a general application config. That type carried a whole framework's model — session
 * authentication, web push, user merge callbacks, a background-task switch — of which the pod server
 * used four fields. This one carries the four.
 *
 * Built by [SempodsModule.config] from the environment, and read from there rather than injected
 * wherever it is needed before an injector exists.
 */
data class SempodsConfig(

  /** Where the pod server listens. Configurable so a third party can run it on its own port. */
  val httpPort: Int,

  /**
   * The address this server is **known by** — not merely reachable at.
   *
   * Pod IRIs are minted by appending to it ([SempodsUriBuilder], `SempodsBaseEndpoint`), it is the
   * `iss` claim of every pod token (`PodTokenIssuer`), and grants are compared against it byte for
   * byte (`PodGrantsFacade`). Changing it on a live host therefore does not redirect traffic — it
   * starts writing a second set of identities beside the existing ones.
   *
   * Always carries a trailing slash: every call site appends to it directly.
   */
  val apiBaseUrl: String,

  /** Where the collections live. */
  val mongoUrl: String,

  /**
   * The database holding them, and this service's alone.
   *
   * It used to default to a database named after an application a self-hoster does not run, which
   * was the last private name in a module that is to be published. The collections moved in a
   * maintenance window; what a deployment sets here now only decides *where*, not *whose*.
   *
   * The collections themselves carry no service prefix any more, for the same reason — see
   * [SempodsCollections].
   */
  val mongoDb: String,

  /**
   * Where an OAuth error sends the person for the recovery procedure — the base of the `error_uri`
   * every `/authorize` error redirect carries (`PodAuthEndpoint.oauthError`), with `#<error-code>`
   * appended.
   *
   * **Null when nothing is configured, and then the redirect carries no `error_uri` at all.**
   * A configured value is what puts the parameter there. It used to be a literal
   * pointing at `sempods.org/docs/oauth-errors`, which is an address in *someone else's*
   * deployment and — as of this writing — one that nothing serves: sempods.org is chat-only and
   * renders no documentation pages. `error_uri` is optional in RFC 6749 §4.1.2.1, and a client
   * that sends a person to a 404 has done worse than one that sends them nowhere. A deployment
   * that serves `docs/auth/oauth-errors.md` sets the variable and gets the links back.
   *
   * Carries no trailing slash: the fragment is appended directly.
   */
  val oauthErrorDocBase: String?,

  /**
   * How long a row in `oauth.serviceAuditLog` is kept — the retention behind the TTL index on that
   * collection (`PodServiceAuditLogDao`).
   *
   * The anchor is computed **at write time** (`ts` + this) and stored per row, so the index never
   * changes and a retention change reaches only rows written after it. That is the same shape the
   * hosted MCP service's `AuditLogDao` has, and it is what keeps this a configuration change
   * rather than a migration.
   *
   * The default is the one that collection ran without: 90 days. It was unbounded until then —
   * 100,140 rows on the live host on 2026-08-21, about 1,400 a day since the trail started.
   */
  val serviceAuditRetentionDays: Long = 90,

  /**
   * How fast one client earns token requests back at `POST {pod}/_system/auth/token`, keyed by
   * address and client identity — see `PodTokenRateLimiter` for the key and what it is for.
   *
   * **The sustained rate, not the burst** — [tokenRateLimitBurst] is that. The two are separate
   * because one number cannot answer both questions: set high enough to absorb a service client's
   * provisioning sweep, a rate never fires against a steady stream at all. The value that matters
   * here is the one a *failing* loop is held to.
   *
   * **`0` is the endpoint's off switch, and turns off both tiers** — enforced in `init`, so no
   * object can carry the contradiction, and applied on the way out of the environment by
   * [resolveAddressRateLimit], so an operator setting only this one still boots. It is the development default: the suites drive this endpoint hard
   * from one address, and a budget there would make them a race against their own throughput. An
   * operator reaching for it mid-incident means "stop refusing", and a value named for the rate at
   * this endpoint that silenced only half of it would be a trap at exactly the wrong moment.
   * Negative is a typo rather than a decision, and is refused below.
   */
  val tokenRateLimitPerMinute: Int = 0,

  /**
   * How many token requests an *idle* client is admitted at once before [tokenRateLimitPerMinute]
   * governs — the spike a legitimate caller is allowed without being told to slow down.
   *
   * Sized for the one caller that legitimately arrives in a rush: a service client mints its tokens
   * over the server's public address like anyone else, so all of one service's mints share a
   * bucket, and a provisioning sweep across many pods asks for them back to back. A browser never
   * comes near it — one refresh per pod connection per hour.
   *
   * `0` means "the same as the rate", which is what a caller with no opinion should get.
   */
  val tokenRateLimitBurst: Int = 0,

  /**
   * The aggregate one *address* may spend at that endpoint per minute, whatever client it names —
   * the tier consulted before [tokenRateLimitPerMinute], and the one that bounds a caller who
   * varies the half of the key it controls.
   *
   * Has to sit above the per-client tier rather than beside it: one address legitimately runs
   * several clients, and a service client's provisioning sweep spends a whole per-client burst on
   * its own. `0` disables **this tier only**; [tokenRateLimitPerMinute] is what disables the
   * endpoint's limit as a whole.
   */
  val tokenRateLimitAddressPerMinute: Int = 0,

  /** The spike allowed on that tier; `0` means the same as [tokenRateLimitAddressPerMinute]. */
  val tokenRateLimitAddressBurst: Int = 0,

  /**
   * The browser origins allowed to send credentialed requests, handed to `SempodsCorsFilter`.
   *
   * Derived from [apiBaseUrl] by default, which is what an application config and a CORS filter
   * between them used to compute — see [deriveCorsOrigins]. A parameter rather
   * than a computed property so a deployment or a test can state a different list without going
   * through a fake base URL.
   */
  val corsOrigins: Set<String> = deriveCorsOrigins(apiBaseUrl),
) {

  init {
    // A negative value would reach `TokenBucketRateLimiter`, which reads every non-positive rate as
    // "disabled" — so `-300` would boot happily with the protection silently off, while the
    // documented off switch is `0`. Same reasoning `Env.int` states for an unparseable number: a
    // typo that leaves the server unprotected is worse than one that stops it.
    require(tokenRateLimitPerMinute >= 0) {
      "tokenRateLimitPerMinute must not be negative (0 disables the limit), got $tokenRateLimitPerMinute"
    }
    require(tokenRateLimitBurst >= 0) {
      "tokenRateLimitBurst must not be negative (0 means the same as the rate), got $tokenRateLimitBurst"
    }
    require(tokenRateLimitAddressPerMinute >= 0) {
      "tokenRateLimitAddressPerMinute must not be negative (0 disables the tier), got " +
          "$tokenRateLimitAddressPerMinute"
    }
    require(tokenRateLimitAddressBurst >= 0) {
      "tokenRateLimitAddressBurst must not be negative (0 means the same as the rate), got " +
          "$tokenRateLimitAddressBurst"
    }
    // The off switch is a promise this type makes, so this type is where it has to hold.
    // `resolveAddressRateLimit` applies it on the way out of the environment, which covers an
    // operator — but this class is public API of a published module, and a composition or a test
    // that builds it by hand would otherwise get the one state the documentation says cannot exist:
    // the endpoint's limit off, and an address tier still answering 429. Refused rather than
    // quietly reinterpreted, for the same reason a negative value is: a configuration that means
    // two things at once is a question for whoever wrote it.
    require(tokenRateLimitPerMinute > 0 || tokenRateLimitAddressPerMinute == 0) {
      "tokenRateLimitPerMinute=0 turns this endpoint's limit off, so tokenRateLimitAddressPerMinute " +
          "must be 0 too, but it is $tokenRateLimitAddressPerMinute. Set both to 0 to turn the " +
          "limit off, or give the per-client tier a positive rate."
    }
  }

  /**
   * The `error_uri` for one OAuth error code, or null when no documentation address is
   * configured — in which case the parameter is left off the redirect entirely.
   */
  fun oauthErrorUri(errorCode: String): String? = oauthErrorDocBase?.let { "$it#$errorCode" }

  companion object {

    /**
     * What [oauthErrorDocBase] accepts from the environment.
     *
     * A pure function for the same reason [deriveCorsOrigins] is one: the rule is checkable
     * without an injector, and `SempodsModule.config` is a `by lazy` companion that a test cannot
     * re-evaluate. Blank is unset — `FOO=` is a forgotten value, not a configured one, which is
     * the rule `Env.get` already applies. The trailing slash is *stripped* rather than added:
     * `#<error-code>` is appended straight to this, and `…/oauth-errors/#invalid_request` is a
     * different address on most static hosts. The opposite of what [apiBaseUrl] needs, and for
     * the opposite reason.
     */
    /**
     * The address tier's rate, given what the endpoint's own switch says.
     *
     * The two tiers have separate variables, so an operator who sets the documented off switch —
     * the per-client rate — and leaves the address one at its default would still be refused, by a
     * budget they had every reason to think they had turned off. The per-client rate is therefore
     * the switch for the feature and the address rate a switch within it, rather than two
     * independent halves an operator has to remember to zero together.
     *
     * **Applied to a default, not to an answer.** A value somebody typed goes through unchanged and
     * meets the `init` check above, so a configuration that says both things at once is reported
     * rather than resolved: silently keeping one half of a contradiction is the same trap in a
     * different place.
     *
     * A pure function for the same reason [normalizeErrorDocBase] is one: the rule is checkable
     * without an injector, and `SempodsModule.config` is a `by lazy` companion a test cannot
     * re-evaluate.
     */
    fun resolveAddressRateLimit(perMinute: Int, addressPerMinute: Int): Int =
      if (perMinute <= 0) 0 else addressPerMinute

    fun normalizeErrorDocBase(raw: String?): String? =
      raw?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    /**
     * The origins a pod server at [apiBaseUrl] accepts credentialed requests from.
     *
     * A pure function so the rule can be checked without an injector — the shape
     * `SempodsModule.resolveAdminClients` and `SempodsMediaModule.resolveBackend` already use.
     *
     * The set is the one the earlier derivation produced, arrived at directly instead of through
     * two layers: an application config derived a referer-host set and turned it back into origins,
     * and a CORS filter then recomputed the same derivation over every registered app. For the pod
     * server — one app, one address for both halves — that came out as the five entries below, and
     * `SempodsCorsFilterTest` pins them.
     *
     * - the public base URL itself;
     * - `http://localhost`, from the unconditional `localhost` referer host;
     * - `https://apps.<host>`, the pod apps' address, unless the server runs on localhost;
     * - the two vite ports a developer serves a frontend from.
     */
    fun deriveCorsOrigins(apiBaseUrl: String): Set<String> {
      val uri = runCatching { URI(apiBaseUrl) }.getOrNull()
      val host = uri?.host
      val scheme = uri?.scheme ?: "https"

      return buildSet {
        add(apiBaseUrl.trimEnd('/'))
        // The earlier derivation listed `localhost` as an allowed referer host for every app, regardless of where
        // that app ran, and derived an `http://` origin from it. Kept rather than tidied away: a
        // developer running the pod server behind a hostname still reaches it from a page served
        // at plain `http://localhost`.
        add("http://localhost")
        if (host != null) {
          add("$scheme://$host")
          if (!isLocalHost(host)) {
            add("https://apps.$host")
          }
        }
        add("http://localhost:3000")  // vite dev server
        add("http://localhost:3100")  // vite preview
      }
    }

    private fun isLocalHost(host: String): Boolean =
      host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
  }
}
