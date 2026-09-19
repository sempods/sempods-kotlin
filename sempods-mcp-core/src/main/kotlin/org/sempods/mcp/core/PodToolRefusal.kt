package org.sempods.mcp.core

import org.sempods.client.core.SempodsClientException
import org.sempods.client.core.SempodsDecodingException
import org.sempods.client.core.SempodsResponseException
import org.sempods.client.core.SempodsStatusException

/**
 * The pod answered a tool call, and the answer is not one the tool can report as a result.
 *
 * **One type for every such answer**, whichever way the core produced it: a status an endpoint group
 * lists but the tool cannot use — a `404` on a merge-patch, a `412` on a conditional write — and a
 * status no group lists at all, which the core raises as a [SempodsStatusException]. Both surfaces
 * report the pod's status beside the message rather than inside it, and a caller that had to tell
 * two exception types apart to find that status would get it wrong for one of them.
 *
 * A failure the pod never answered — a blocked address, a spent budget, a connection that was
 * refused — is not this. It stays what the core threw, and the surfaces report it without a status,
 * as they did before.
 *
 * [reason] travels separately from [message] because only [message] carries the URL that was
 * dialled. A tool error goes to a language model, which has no use for
 * `http://localhost:8090/pod/_system/…` and some tendency to repeat it back to the user as if it
 * were an address to visit. It is empty for a status an endpoint group lists, whose body the core
 * closes unread; [PodToolFailure] then answers from the status alone.
 */
class PodToolRefusal internal constructor(
  message: String,
  /** The status the pod answered. */
  val status: Int,
  /**
   * The reason a caller shows a model: what the pod wrote where the core kept it, and where it kept
   * nothing, what this layer can say without naming the URL. Empty when neither has anything to add
   * to the status.
   */
  val reason: String,
  cause: Throwable?,
) : SempodsClientException(message, cause) {

  internal companion object {

    /** The refusal for an answer the core raised, keeping what it kept of the body. */
    @JvmSynthetic
    internal fun of(refused: SempodsResponseException): PodToolRefusal =
      PodToolRefusal(
        refused.message.orEmpty(),
        refused.status,
        when (refused) {
          is SempodsStatusException -> refused.bodyExcerpt
          // The only body these tools ask the core for is text, so the one way its decoding fails
          // is the size bound. Said again here because the core's own message names the URL, and
          // left unsaid a `200` would reach a model as "the pod refused the call". A tool that ever
          // asks the core for a typed result makes this too narrow.
          is SempodsDecodingException -> OVERSIZED
        },
        refused,
      )

    /** What a caller shows when the answer did not fit the client core's 16 MiB bound. */
    private const val OVERSIZED: String =
      "the pod's answer is larger than the 16 MiB this client reads — ask for less: fewer contexts, " +
        "a smaller limit, or a narrower query"

    /** The refusal for an answer the core handed over as a result — a status a group lists, or a body it cannot read. */
    @JvmSynthetic
    internal fun at(message: String, status: Int, reason: String = ""): PodToolRefusal =
      PodToolRefusal(message, status, reason, cause = null)
  }
}
