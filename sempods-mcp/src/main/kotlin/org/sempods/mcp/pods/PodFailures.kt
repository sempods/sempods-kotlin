package org.sempods.mcp.pods

import org.sempods.client.net.SempodsRateLimitedException
import org.sempods.client.net.SsrfBlockedException

/**
 * True when the attempt failed but the connection did not — so it is worth trying again.
 *
 * The distinction the tool surfaces need: everything else maps to `no_token` and tells the user to
 * reconnect the pod, which needlessly rotates the token family and, when the pod was merely
 * unwell, asks them to fix something that was never broken.
 *
 * Three cases:
 * - the transport's own throttle and SSRF block;
 * - an OAuth refusal from the pod that is not `invalid_grant` — RFC 6749 §5.2 gives exactly one
 *   code meaning the grant is finished, and `server_error`, `temporarily_unavailable` or a bare
 *   5xx are not it;
 * - anything wrapping one of those.
 *
 * **The single home for this classification.** The token provider and both tool surfaces ask this
 * question, and a second answer somewhere else is how a rethrown exception ends up reported as a
 * dead token anyway — which is exactly what happened when the OAuth case was first classified in
 * `PodTokenProvider` alone.
 *
 * Walks the cause chain: the engine can wrap the original throwable, and the client deliberately
 * wraps a blocked address in its own exception type while keeping the cause — so a top-level `is`
 * check alone would miss it and fall through to the misleading `no_token` path.
 */
fun Throwable.isRetryablePodFailure(): Boolean =
  generateSequence(this) { it.cause }.any {
    it is SempodsRateLimitedException ||
      it is SsrfBlockedException ||
      (it is PodOAuthException && it.oauthErrorCode != null && !it.isDeadGrant)
  }

/**
 * True when a pod said this connection's grant is finished — RFC 6749 §5.2's one code for it.
 *
 * The counterpart of [isRetryablePodFailure], and it walks the cause chain for the same reason:
 * the engine wraps what it throws, so a top-level `is` check would miss exactly the cases the
 * sibling catches. Kept beside it so the two questions cannot drift apart.
 */
fun Throwable.isDeadPodGrant(): Boolean =
  generateSequence(this) { it.cause }.any { it is PodOAuthException && it.isDeadGrant }
