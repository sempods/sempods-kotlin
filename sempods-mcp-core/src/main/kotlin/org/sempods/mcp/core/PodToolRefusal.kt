package org.sempods.mcp.core

import org.sempods.client.core.SempodsClientException
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
 * [podBody] travels separately from [message] because only [message] carries the URL that was
 * dialled. A tool error goes to a language model, which has no use for
 * `http://localhost:8090/pod/_system/…` and some tendency to repeat it back to the user as if it
 * were an address to visit. It is empty for a status an endpoint group lists, whose body the core
 * closes unread; [PodToolFailure] says what a caller shows instead.
 */
class PodToolRefusal internal constructor(
  message: String,
  /** The status the pod answered. */
  val status: Int,
  /** What the pod wrote, on its own, or empty when the core did not keep it. */
  val podBody: String,
  cause: Throwable?,
) : SempodsClientException(message, cause) {

  internal companion object {

    /** The refusal for an answer the core raised, keeping what it kept of the body. */
    @JvmSynthetic
    internal fun of(refused: SempodsResponseException): PodToolRefusal =
      PodToolRefusal(
        refused.message.orEmpty(),
        refused.status,
        (refused as? SempodsStatusException)?.bodyExcerpt.orEmpty(),
        refused,
      )

    /** The refusal for an answer the core handed over as a result — a status a group lists, or a body it cannot read. */
    @JvmSynthetic
    internal fun at(message: String, status: Int): PodToolRefusal =
      PodToolRefusal(message, status, podBody = "", cause = null)
  }
}
