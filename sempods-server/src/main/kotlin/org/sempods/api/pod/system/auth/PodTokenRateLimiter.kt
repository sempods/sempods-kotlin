package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.SempodsConfig
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.BasicAuth
import org.sempods.commons.net.ForwardedFor
import org.sempods.commons.ratelimit.TokenBucketRateLimiter
import org.sempods.commons.utils.HashUtil

/**
 * The budget one client gets at `POST {pod}/_system/auth/token`.
 *
 * **What it is for.** One client holding a refresh token this server did not recognise sent 102,642
 * requests over twenty-one hours — 78 a minute sustained, 819 inside its densest minute — every one
 * of them refused, and it stopped only when its user re-authorised by hand. Nothing slowed it down,
 * because there was no limiter of any kind. Each of those requests took the `NOT_FOUND` branch and
 * cost two Mongo point queries and a warning; consulted before the pod row is read, this saves both.
 *
 * **The budget is a rate and a burst, and the rate is the half that answers this.** 78 a minute
 * sustained is not a spike, so a bucket sized for one — anything refilling faster than the client
 * asks — is never emptied and never refuses anything, however long the loop runs. The rate is
 * therefore chosen below that sustained figure, and the capacity separately, above the spike a
 * service client's provisioning sweep legitimately produces.
 *
 * **What it is not for.** The family-revoking branches beside that one are not something throttling
 * addresses: one accepted request is enough to revoke a family, and every limiter admits the first
 * request. That is replay policy, and it belongs with the reuse-detection design.
 *
 * **The key is `<client address>|<client identity>`, and the pod is deliberately absent.**
 *  - The address alone would punish a shared NAT, and would put a service client's token mints in
 *    the same bucket as every browser behind that address.
 *  - The identity alone would punish an app rather than an installation: four different WebIDs
 *    presented the same static `did:web:` client id in those logs, so a budget on that string turns
 *    one broken client into a throttle on everyone else running the same app.
 *  - Leaving the pod out is what catches a client retrying several pods in lockstep: one browser
 *    holding grants on five pods stays one bucket, rather than a fifth of the traffic it is
 *    actually causing appearing at each of five.
 *
 * **Two tiers, because half the key is caller-written.** `client_id` is a form parameter on an
 * unauthenticated endpoint, so a caller may vary it and draw a fresh bucket every time — which
 * would sidestep a per-client budget entirely and mint a bucket per request while doing it. So a
 * budget keyed on the **address alone** is consulted first, and nothing reaches the per-client tier
 * without spending it. That is the tier that bounds a caller in aggregate; the per-client one below
 * it is what keeps a shared address fair, so that one broken installation does not throttle the
 * others beside it.
 *
 * The order matters and is not interchangeable: the address is the part of the key a caller cannot
 * choose, so it is the part that has to be counted before anything a caller can.
 */
class PodTokenRateLimiter(
  private val permitsPerMinute: Int,
  clock: () -> Long,
  burstCapacity: Int = permitsPerMinute,
  private val addressPerMinute: Int = 0,
  addressBurst: Int = addressPerMinute,
) {

  /**
   * The composition's constructor. Separate from the primary one rather than a default argument:
   * a Kotlin default compiles to a second constructor and Guice then refuses the class outright —
   * the same reason `ApiExceptionMapper` states for its own signature.
   *
   * A burst of `0` is read as "the same as the rate" here rather than in the limiter, which keeps
   * that class's contract strict: a capacity of zero beside a positive rate refuses everything, and
   * is exactly what a config default of `0` would otherwise mean.
   */
  @Inject constructor(config: SempodsConfig) : this(
    permitsPerMinute = config.tokenRateLimitPerMinute,
    // Left to the limiter, which reads a monotonic source: nothing here reports a point in time,
    // and a budget that follows a wall clock through an NTP correction is wrong in both directions.
    clock = TokenBucketRateLimiter.monotonicMillis(),
    burstCapacity = config.tokenRateLimitBurst.takeIf { it > 0 } ?: config.tokenRateLimitPerMinute,
    addressPerMinute = config.tokenRateLimitAddressPerMinute,
    addressBurst = config.tokenRateLimitAddressBurst.takeIf { it > 0 }
      ?: config.tokenRateLimitAddressPerMinute,
  )

  /**
   * The aggregate a single address may spend, whatever it calls itself. Keyed by the one part of
   * the key no caller chooses, so its own key space is bounded by the peers that actually connect.
   */
  private val addressBuckets = TokenBucketRateLimiter(addressPerMinute, clock, addressBurst)

  private val buckets = TokenBucketRateLimiter(permitsPerMinute, clock, burstCapacity)

  /**
   * One log line per **address** per minute, not one per refusal.
   *
   * A warning on every refused request would rebuild exactly the log volume this class exists to
   * remove — in the incident above, 102,642 of them. The same token bucket serves as the sampler.
   *
   * **Keyed by the address alone, deliberately, and not by the pair the budget is spent against.**
   * A sampler keyed on anything the caller writes samples nothing: a fresh `client_id` is a fresh
   * bucket with a fresh permit in it, so a caller varying the name would have every one of its
   * refusals logged. The cost is that two clients behind one address share a line's worth of
   * attention, which is the right way round — the line says which key was refused, and what a flood
   * needs is one line rather than a faithful one per bucket.
   */
  private val logSampler = TokenBucketRateLimiter(1, clock)

  /**
   * Whether this request may proceed.
   *
   * @param forwardedFor the raw `X-Forwarded-For` header. `null` — or a header no proxy wrote —
   *   means there is no way to tell one client from another, and the request is **admitted**: a
   *   single shared bucket for everything arriving without one would be a self-inflicted outage
   *   rather than a limit.
   * @param grantType the submitted `grant_type`, which decides *which* name identifies this caller
   *   — see [clientIdentity].
   * @param clientId the submitted `client_id`, which the `client_credentials` grant does not send.
   * @param authorizationHeader the `Authorization` header, which is where `client_credentials`
   *   carries the client's name instead.
   */
  fun tryAcquire(
    forwardedFor: String?,
    grantType: String?,
    clientId: String?,
    authorizationHeader: String?,
  ): Boolean {
    val address = bounded(ForwardedFor.clientIp(forwardedFor) ?: return true)

    // The address first, and its refusal is final: a caller must not be able to reach the
    // per-client tier — nor allocate a bucket in it — by presenting a name nobody checked.
    if (!addressBuckets.tryAcquire(address)) {
      logRefusal(address, "address", address, addressPerMinute)
      return false
    }

    val key = "$address|${bounded(clientIdentity(grantType, clientId, authorizationHeader))}"
    if (!buckets.tryAcquire(key)) {
      logRefusal(address, "client", key, permitsPerMinute)
      return false
    }
    return true
  }

  /**
   * One line per address per minute, saying **which** budget was spent.
   *
   * The tier matters to whoever reads it: the address aggregate is shared, so a caller refused by
   * it may be a newcomer that has spent nothing of its own while a neighbour behind the same NAT
   * spent everything. A line naming the composite key and the per-client rate in that case sends
   * the reader after the wrong caller — which is the failure the milestone beside this one is about.
   *
   * The subject is a key, not a credential: an address and a client name are both already logged
   * elsewhere on this endpoint, and neither is a secret. Through `LogSafeText` because part of it
   * is a form parameter — a `client_id` carrying `%0A` would otherwise end this line early and
   * write the next one itself.
   */
  private fun logRefusal(address: String, tier: String, subject: String, permitsPerMinute: Int) {
    if (!logSampler.tryAcquire(address)) return
    logger.warn {
      "[oauth/token] rate limit exceeded on the $tier budget — refusing until it refills: " +
          "$tier='${LogSafeText.of(subject)}', permitsPerMinute=$permitsPerMinute " +
          "(further refusals from this address are not logged for a minute)"
    }
  }

  /**
   * Who the request claims to be — **chosen by the grant**, because that is what decides which name
   * the endpoint will actually read.
   *
   * `client_credentials` authenticates the username in the Basic header and is never handed the
   * form field; the other grants register with `token_endpoint_auth_method=none` and authenticate
   * the form `client_id`, ignoring any header. So each is keyed by the name its own branch uses,
   * and by that one only. Reading whichever happens to be present would give a caller *two* key
   * spaces to choose from: a `refresh_token` retry could invent Basic usernames, or a service
   * client could vary a form field nothing looks at, and either would draw a fresh per-client
   * bucket every time while spending only the address aggregate.
   *
   * Neither name is verified at this point and neither needs to be: an unverified name is enough to
   * tell one caller's budget from another's.
   *
   * A request carrying nothing usable still gets a bucket rather than escaping into "unkeyed" — a
   * caller that omits the parameter is refused a token anyway, and may not do so faster than
   * anyone else.
   */
  private fun clientIdentity(grantType: String?, clientId: String?, authorizationHeader: String?): String =
    if (grantType?.trim() == CLIENT_CREDENTIALS_GRANT) {
      BasicAuth.parse(authorizationHeader)?.username ?: UNIDENTIFIED
    } else {
      clientId?.trim()?.takeIf { it.isNotBlank() } ?: UNIDENTIFIED
    }

  /**
   * A key part, at a length this server chose rather than the caller.
   *
   * The ceiling on the bucket map bounds how many keys are held, not how big they are, and both
   * halves of this key arrive from the request: a `client_id` is a form field on an unauthenticated
   * endpoint and has no length anyone here agreed to. Kept whole while it is a plausible name —
   * every real one is far shorter — and folded to a digest beyond that, so a caller cannot decide
   * what a retained key, or the warning that names it, costs.
   */
  private fun bounded(part: String): String =
    if (part.length <= MAX_KEY_PART_LENGTH) part else "sha256:" + HashUtil.sha256Hex(part).take(16)

  companion object {

    private val logger = KotlinLogging.logger {}

    /** The bucket a request that names no client at all shares. */
    private const val UNIDENTIFIED = "(none)"

    /** The one grant whose caller is named in the `Authorization` header rather than in the form. */
    private const val CLIENT_CREDENTIALS_GRANT = "client_credentials"

    /**
     * Well past any real client id — a `did:web:` one runs to a few dozen characters and a `dyn:`
     * registration to about forty — and short enough that four thousand of them are a rounding
     * error rather than a heap.
     */
    private const val MAX_KEY_PART_LENGTH = 128
  }
}
