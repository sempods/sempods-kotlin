package org.sempods.pods.media

import java.time.Duration

/**
 * What a deployment decides about media, as a type rather than as environment lookups scattered
 * through the code.
 *
 * `:sempods` reads no environment for media at all — `SempodsMediaModule` in
 * `:deployments:sempods:image` is the single place that does, and hands the answer down. That is
 * not merely tidy: the store implementation lives in a *sibling* module that `:sempods` cannot name,
 * so the composition is where the configuration has to be read anyway. `Env`'s own standing note
 * asks for exactly this shape — a typed object built at the entry point.
 *
 * @property maxUploadBytes the largest object this pod server accepts. Enforced where the bytes are
 *   first counted, so an oversized upload is refused after being buffered but before it reaches the
 *   store or the registry. Applies to both ingestion paths — the raw body and the copy-from-URL
 *   descriptor — and on the second one it is enforced *while* streaming, because the source's
 *   `Content-Length` is a claim by a server the caller chose.
 * @property sourceReadTimeout how long `MediaSourceFetcher` waits for the next chunk from a source
 *   before giving up. Guards against a server that accepts the connection and then stalls, which
 *   would otherwise hold a request thread for the whole request timeout.
 * @property sourceRequestTimeout the total budget for one hop of a copy-from-URL fetch. Generous
 *   against [maxUploadBytes] on purpose: a legitimate 25 MiB image over a slow link must not be cut
 *   off, and the read timeout is what catches the actual failure mode.
 * @property sweepGracePeriod how long a media stays reclaimable after its last context assignment
 *   went away. The window in which an accidental unassign — or an accidental pod deletion, which
 *   stamps every one of its rows — can still be undone by re-assigning; the sweep deletes nothing
 *   inside it. Zero is a legal *request-level* override for an operator who means it, not a legal
 *   default.
 */
data class PodMediaConfig(
  val maxUploadBytes: Long,
  val sourceReadTimeout: Duration = DEFAULT_SOURCE_READ_TIMEOUT,
  val sourceRequestTimeout: Duration = DEFAULT_SOURCE_REQUEST_TIMEOUT,
  val sweepGracePeriod: Duration = DEFAULT_SWEEP_GRACE_PERIOD,
) {

  init {
    require(maxUploadBytes > 0) { "maxUploadBytes must be positive, got $maxUploadBytes" }
    require(!sourceReadTimeout.isZero && !sourceReadTimeout.isNegative) {
      "sourceReadTimeout must be positive, got $sourceReadTimeout"
    }
    require(!sourceRequestTimeout.isZero && !sourceRequestTimeout.isNegative) {
      "sourceRequestTimeout must be positive, got $sourceRequestTimeout"
    }
    // Positive, not merely non-negative: a deployment whose *default* is zero deletes bytes the
    // moment their last assignment goes, which is the grace period configured away by accident.
    require(!sweepGracePeriod.isZero && !sweepGracePeriod.isNegative) {
      "sweepGracePeriod must be positive, got $sweepGracePeriod"
    }
  }

  companion object {

    /**
     * 25 MiB. Large enough for the photographs this exists for — an event image out of Google Drive
     * is single-digit megabytes — and small enough that buffering one to a temporary file is
     * unremarkable. A deployment that means something else says so.
     */
    const val DEFAULT_MAX_UPLOAD_BYTES: Long = 25L * 1024 * 1024

    /**
     * Ten seconds without a byte is a source that has stopped answering, not a slow one — TCP keeps
     * delivering long before that on any link worth downloading over.
     */
    val DEFAULT_SOURCE_READ_TIMEOUT: Duration = Duration.ofSeconds(10)

    /**
     * One minute for a whole hop, which covers [DEFAULT_MAX_UPLOAD_BYTES] down to well under a
     * megabyte per second.
     */
    val DEFAULT_SOURCE_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)

    /**
     * 30 days. Long enough that a deletion noticed a week later is still recoverable, short enough
     * that abandoned bytes are not paid for indefinitely.
     *
     * **No environment variable**, the same call M3 made for the source timeouts: a deployment that
     * means something else says so where the config is built, and the sweep route takes a
     * per-request override — which is what an operator actually reaches for, since the two occasions
     * to deviate (reclaim now, or hold everything while investigating) are both one-off.
     */
    val DEFAULT_SWEEP_GRACE_PERIOD: Duration = Duration.ofDays(30)
  }
}
