package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.SempodsConfig
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.ForwardedFor
import org.sempods.commons.ratelimit.TokenBucketRateLimiter
import org.sempods.pods.PodId
import org.sempods.pods.oauth.flows.PodInstallationBudget

/**
 * The budgets at `POST {pod}/_system/auth/register`.
 *
 * The endpoint serves two profiles, and each gets its own buckets, so a flood of one never
 * throttles the other:
 *
 * | Budget | Key | Consulted |
 * |---|---|---|
 * | public | address | by the endpoint, before the pod row, for a request without a bearer |
 * | protected | address | by the endpoint, before the pod row, for a request with one |
 * | installer | pod | by `PodClientRegistration`, as [PodInstallationBudget], before the authority is spent |
 *
 * **The address budgets** bound what a caller costs before anything is known about it. A public
 * registration writes a `dyn:` row for every fingerprint it has not seen, and a protected one
 * verifies a JWT. The pod is not part of the key, so one address does not get a fresh bucket per
 * pod. Which of the two is charged depends on whether a bearer is present, which the caller
 * decides; both are bounded, so choosing buys nothing.
 *
 * **The installer budget** bounds secret minting across authorities. One authority mints one
 * secret, at bcrypt cost, and nothing stops a person from holding many. Only the pod's owner can
 * be given an installer authority, under any of their linked identities, so a budget per pod is a
 * budget per person: keying it on the token's `sub` would give each linked identity a budget of
 * its own. Requests racing on one unspent authority are each charged, so one authority can empty
 * the burst once — `docs/auth/oauth.md` §"Registration rate limit".
 *
 * **No proxy header, no address limit**, as at the token endpoint: a single shared bucket for
 * every request would be an outage rather than a limit.
 *
 * A rate of `0` turns that budget off.
 */
class PodRegistrationRateLimiter(
  clock: () -> Long,
  publicPerMinute: Int,
  publicBurst: Int,
  protectedPerMinute: Int,
  protectedBurst: Int,
  installerPerMinute: Int,
  installerBurst: Int,
) : PodInstallationBudget {

  /**
   * The composition's constructor. Separate from the primary one because a Kotlin default
   * argument compiles to a second constructor, which Guice refuses — as [PodTokenRateLimiter]
   * states.
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

  /** At most one warning per key per minute, so a flood does not become a log flood. */
  private val logSampler = TokenBucketRateLimiter(1, clock)

  /** One named budget: its buckets, and what a refusal says about it. */
  private inner class Budget(private val name: String, private val perMinute: Int, burst: Int, clock: () -> Long) {
    private val buckets = TokenBucketRateLimiter(perMinute, clock, burst)

    fun admit(key: String): Boolean {
      if (buckets.tryAcquire(key)) return true
      if (logSampler.tryAcquire("$name|$key")) {
        logger.warn {
          "[oauth/register] rate limit exceeded on the $name budget — refusing until it refills: " +
              "key='${LogSafeText.of(key)}', permitsPerMinute=$perMinute " +
              "(further refusals for this key are not logged for a minute)"
        }
      }
      return false
    }
  }

  private val public = Budget("public", publicPerMinute, publicBurst, clock)
  private val protected = Budget("protected", protectedPerMinute, protectedBurst, clock)
  private val installer = Budget("installer", installerPerMinute, installerBurst, clock)

  /**
   * Whether a request may proceed to the pod row.
   *
   * @param forwardedFor the raw `X-Forwarded-For` header.
   * @param bearerPresented whether the request carries `Authorization: Bearer`, verified or not.
   */
  fun tryAcquireAddress(forwardedFor: String?, bearerPresented: Boolean): Boolean {
    val address = boundedKeyPart(ForwardedFor.clientIp(forwardedFor) ?: return true)
    return (if (bearerPresented) protected else public).admit(address)
  }

  override fun tryAcquire(pod: PodId): Boolean = installer.admit(pod.value)

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
