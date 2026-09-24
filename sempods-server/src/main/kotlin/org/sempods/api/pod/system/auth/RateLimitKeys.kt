package org.sempods.api.pod.system.auth

import org.sempods.commons.utils.HashUtil

/**
 * Well past any real key part — a `did:web:` client id runs to a few dozen characters, a `dyn:` one
 * to about forty — and short enough that four thousand of them are a rounding error rather than a
 * heap.
 */
private const val MAX_KEY_PART_LENGTH = 128

/**
 * A key part at a length this server chose rather than the caller: kept whole while it is a
 * plausible name, folded to a digest beyond that, so a caller cannot decide what a retained key, or
 * the warning that names it, costs.
 */
internal fun boundedKeyPart(part: String): String =
  if (part.length <= MAX_KEY_PART_LENGTH) part else "sha256:" + HashUtil.sha256Hex(part).take(16)

/**
 * A configured burst, where `0` means "the same as the rate". Resolved here rather than in
 * `TokenBucketRateLimiter`, whose contract stays strict: a capacity of zero beside a positive rate
 * refuses everything.
 */
internal fun burstOrRate(burst: Int, rate: Int): Int = burst.takeIf { it > 0 } ?: rate
