package org.sempods.mcp.api.mcp

import org.sempods.commons.ratelimit.TokenBucketRateLimiter

/**
 * The per-user quota on `tools/call` (M6.4): a token bucket per `(user, profile)`, enforced by
 * [McpEndpoint] AFTER bearer verification and the profile-isolation gate — the key is a verified
 * identity, so an unauthenticated spray can never drain a victim's budget. Only `tools/call` is
 * throttled: `initialize` / `tools/list` / `ping` are cheap, static, touch no vault, and throttling
 * them would break client handshakes and reconnect loops.
 *
 * Like the per-pod outbound limiter this is in-memory per replica (no Mongo on the per-request hot
 * path); the effective budget scales with the replica count — see `docs/multi-tenancy-review.md`.
 * [permitsPerMinute] `<= 0` disables the quota (the relaxed/self-host default).
 */
class UserRateLimiter(
  permitsPerMinute: Int,
  clock: () -> Long = TokenBucketRateLimiter.monotonicMillis(),
) {

  private val buckets = TokenBucketRateLimiter(permitsPerMinute, clock)

  // Key order (profile first) is unambiguous: ProfilePath restricts profile names to [a-z0-9-]
  // (never a '|'), while a WebID URL is unconstrained and may contain anything.
  fun tryAcquire(user: String, profile: String): Boolean = buckets.tryAcquire("$profile|$user")
}
