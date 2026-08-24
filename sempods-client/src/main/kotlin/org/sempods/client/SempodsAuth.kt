package org.sempods.client

/**
 * The credential of one pod: what bearer to send, and how to throw it away.
 *
 * **`null` is anonymous, and that is a supported mode rather than a degraded one.** A pod serves
 * its public contexts without a bearer, and reading public data is what a consumer that owns no
 * pod does. What such a caller may see is the server's decision, expressed as a filtered answer or
 * a 401.
 *
 * **A functional interface, because the two ordinary shapes are one expression each:**
 * `SempodsAuth { myBearer }` for a credential the caller already holds, [anonymous] for none at
 * all. The third is written by a consumer serving many pods: one of these per pod, over its own
 * token cache. This module deliberately supplies no such adapter — where the pods come from is the
 * consumer's question, and an interface for it here would be a registry in a library about a pod
 * addressed by its own URL.
 */
fun interface SempodsAuth {

  /**
   * The bearer for the next request, or `null` for an anonymous one.
   *
   * **Asked once per operation and never cached by the client**, so an implementation owns its own
   * expiry policy — a token provider typically caches until `expires_in − 60 s`, while the
   * pod server's test seeding re-derives per call because the scope set it needs grows as a test
   * registers contexts. Neither would survive a client that captured a token when it was bound.
   */
  fun token(): String?

  /**
   * Drop whatever [token] would return, so the next call re-acquires it. [SempodsPodClient] calls
   * this after a 401 and before its single retry: a refresh margin narrows the expiry race but
   * cannot close it, because a token can be rotated or revoked mid-flight.
   *
   * **No-op by default, and what that costs is one wasted request.** With a fixed bearer the retry
   * re-sends exactly what the pod just refused and the 401 propagates — correct, but paid for
   * twice. An implementation that can re-acquire should override; one that cannot has nothing
   * better to do than nothing.
   */
  fun invalidate() {}

  companion object {

    /**
     * No credential at all — the reader's profile: a consumer pointed at a stranger's pod starts
     * here, and a purely public aggregator never leaves it.
     *
     * A 401 is deliberately **not** retried for it. There is nothing to re-mint, so a second
     * attempt would only double the latency of a failure that was already final.
     */
    val anonymous: SempodsAuth = SempodsAuth { null }
  }
}
