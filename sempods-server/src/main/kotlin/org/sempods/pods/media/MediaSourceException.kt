package org.sempods.pods.media

/**
 * A copy-from-URL request that will not be carried out, with the reason it was refused.
 *
 * **The reason is for the log, not for the caller.** `PodMediaEndpoint` answers `400` (or `413` for
 * [tooLarge]) without the message: distinguishing "that host resolves to a private address" from
 * "that host does not exist" or "that server answered 500" turns the route into a probe of whatever
 * the pod server can reach, which is the same network map the guard exists to withhold.
 *
 * @property tooLarge the source exceeded the configured upload limit — the one refusal that has a
 *   status of its own, because it is the caller's own doing and says nothing about the network.
 */
class MediaSourceException(
  message: String,
  val tooLarge: Boolean = false,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
