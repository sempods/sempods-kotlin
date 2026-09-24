package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.SempodsConfig
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.ForwardedFor
import org.sempods.commons.ratelimit.TokenBucketRateLimiter
import org.sempods.commons.utils.HashUtil

/**
 * The budgets at `POST {pod}/_system/auth/register`.
 *
 * The endpoint serves two profiles, and each gets its own buckets, so a flood of one never
 * throttles the other:
 *
 * | Tier | Key | Consulted |
 * |---|---|---|
 * | [Tier.PUBLIC] | address | before the pod row, for a request without a bearer |
 * | [Tier.PROTECTED] | address | before the pod row, for a request with one |
 * | [Tier.INSTALLER] | pod | after an installer bearer is verified and found spendable, before the body |
 *
 * **The address tiers** bound what a caller costs before anything is known about it. A public
 * registration writes a `dyn:` row for every fingerprint it has not seen, and a protected one
 * verifies a JWT. The pod is not part of the key, so one address does not get a fresh bucket per
 * pod. Which of the two is charged depends on whether a bearer is present, which the caller
 * decides; both are bounded, so choosing buys nothing.
 *
 * **The installer tier** bounds secret minting across authorities. One authority mints one
 * secret, at bcrypt cost, and nothing stops a person from holding many. Only the pod's owner can
 * be given an installer authority, under any of their linked identities, so a budget per pod is a
 * budget per person: keying it on the token's `sub` would give each linked identity a budget of
 * its own. The key does not depend on the caller, so it holds without a proxy header too. It is
 * consulted before the authority is spent, so a throttled installation keeps its approval — see
 * `docs/auth/oauth.md` §"Registration rate limit". Requests racing on one unspent authority are
 * each charged, so one authority can empty the burst once.
 *
 * **No proxy header, no address limit**, as at the token endpoint: a single shared bucket for
 * every request would be an outage rather than a limit.
 *
 * A rate of `0` turns that tier off.
 */
class PodRegistrationRateLimiter(
  clock: () -> Long,
  private val publicPerMinute: Int,
  publicBurst: Int = publicPerMinute,
  private val protectedPerMinute: Int,
  protectedBurst: Int = protectedPerMinute,
  private val installerPerMinute: Int,
  installerBurst: Int = installerPerMinute,
) {

  /**
   * The composition's constructor. Separate from the primary one because a Kotlin default
   * argument compiles to a second constructor, which Guice refuses — as [PodTokenRateLimiter]
   * states. A burst of `0` reads as "the same as the rate".
   */
  @Inject constructor(config: SempodsConfig) : this(
    clock = TokenBucketRateLimiter.monotonicMillis(),
    publicPerMinute = config.registerRateLimitPublicPerMinute,
    publicBurst = burstOrRate(config.registerRateLimitPublicBurst, config.registerRateLimitPublicPerMinute),
    protectedPerMinute = config.registerRateLimitProtectedPerMinute,
    protectedBurst = burstOrRate(config.registerRateLimitProtectedBurst, config.registerRateLimitProtectedPerMinute),
    installerPerMinute = config.registerRateLimitInstallerPerMinute,
    installerBurst = burstOrRate(config.registerRateLimitInstallerBurst, config.registerRateLimitInstallerPerMinute),
  )

  /** The three budgets, named in the refusal log. */
  enum class Tier(val label: String) {
    PUBLIC("public"),
    PROTECTED("protected"),
    INSTALLER("installer"),
  }

  private val publicBuckets = TokenBucketRateLimiter(publicPerMinute, clock, publicBurst)
  private val protectedBuckets = TokenBucketRateLimiter(protectedPerMinute, clock, protectedBurst)
  private val installerBuckets = TokenBucketRateLimiter(installerPerMinute, clock, installerBurst)

  /** At most one warning per key per minute, so a flood does not become a log flood. */
  private val logSampler = TokenBucketRateLimiter(1, clock)

  /**
   * Whether a request may proceed to the pod row.
   *
   * @param forwardedFor the raw `X-Forwarded-For` header.
   * @param bearerPresented whether the request carries `Authorization: Bearer`, verified or not.
   */
  fun tryAcquireAddress(forwardedFor: String?, bearerPresented: Boolean): Boolean {
    val address = bounded(ForwardedFor.clientIp(forwardedFor) ?: return true)
    return if (bearerPresented) {
      admit(protectedBuckets, Tier.PROTECTED, address, protectedPerMinute)
    } else {
      admit(publicBuckets, Tier.PUBLIC, address, publicPerMinute)
    }
  }

  /**
   * Whether a verified installer may go on to spend its authority.
   *
   * @param podId the pod the installation is for.
   */
  fun tryAcquireInstallation(podId: String): Boolean =
    admit(installerBuckets, Tier.INSTALLER, podId, installerPerMinute)

  private fun admit(buckets: TokenBucketRateLimiter, tier: Tier, key: String, perMinute: Int): Boolean {
    if (buckets.tryAcquire(key)) return true
    if (logSampler.tryAcquire("${tier.label}|$key")) {
      logger.warn {
        "[oauth/register] rate limit exceeded on the ${tier.label} budget — refusing until it refills: " +
            "key='${LogSafeText.of(key)}', permitsPerMinute=$perMinute " +
            "(further refusals for this key are not logged for a minute)"
      }
    }
    return false
  }

  /**
   * A key part at a length this server chose. Past [MAX_KEY_PART_LENGTH] it is folded to a
   * digest, so a caller cannot decide what a retained key costs.
   */
  private fun bounded(part: String): String =
    if (part.length <= MAX_KEY_PART_LENGTH) part else "sha256:" + HashUtil.sha256Hex(part).take(16)

  companion object {

    private val logger = KotlinLogging.logger {}

    private const val MAX_KEY_PART_LENGTH = 128

    private fun burstOrRate(burst: Int, rate: Int): Int = burst.takeIf { it > 0 } ?: rate
  }
}
