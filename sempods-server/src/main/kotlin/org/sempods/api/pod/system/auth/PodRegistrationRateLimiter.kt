package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.SempodsConfig
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.ForwardedFor
import org.sempods.commons.ratelimit.TokenBucketRateLimiter
import org.sempods.pods.PodId
import org.sempods.pods.oauth.flows.PodServiceRegistrationBudget

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
 * | service | pod | by `PodClientRegistration`, as [PodServiceRegistrationBudget], for a service registering itself with a body it would accept |
 *
 * **The address budgets** bound what a caller costs before anything is known about it. A public
 * registration writes a row for every `dyn:` fingerprint it has not seen or every service, and a
 * request carrying a bearer verifies a JWT. The pod is not part of the key, so one address does not get a fresh bucket per
 * pod. Which of the two is charged depends on whether a bearer is present, which the caller
 * decides; both are bounded, so choosing buys nothing.
 *
 * **The service budget** bounds secret minting on one pod. Each service registration mints a
 * secret at bcrypt cost, and nothing authenticates the caller, so addresses alone do not bound it:
 * many addresses can register on one pod. A caller can spend a pod's budget and delay other
 * services' registrations for a minute; it cannot activate anything, and it cannot delay the
 * owner's, which this budget does not count — `sempods-server/docs/auth/oauth.md` §"Registration rate limit".
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
  servicePerMinute: Int,
  serviceBurst: Int,
) : PodServiceRegistrationBudget {

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
    servicePerMinute = config.registerRateLimitServicePerMinute,
    serviceBurst = burstOrRate(config.registerRateLimitServiceBurst, config.registerRateLimitServicePerMinute),
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
  private val service = Budget("service", servicePerMinute, serviceBurst, clock)

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

  override fun tryAcquire(pod: PodId): Boolean = service.admit(pod.value)

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
