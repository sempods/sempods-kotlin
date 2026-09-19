package org.sempods.client

import java.io.IOException

/**
 * What the sempods layer reports, as opposed to what the network does.
 *
 * An [IOException], because that is what every caller of an OkHttp `Call` already handles and what
 * the engine itself throws — a second hierarchy beside it would only make a `catch` clause longer.
 * It marks two things. A refusal that came from *here*: a target outside the session's pod, an
 * address the outbound guard rejected, a spent admission or outbound budget, an authentication
 * mechanism that tried to move the request. And, from an endpoint operation, an answer outside that
 * operation's contract ([SempodsResponseException]).
 *
 * A call never throws for a status; which statuses an endpoint operation accepts, [SempodsResponse]
 * says.
 */
open class SempodsClientException @JvmOverloads constructor(
  message: String,
  cause: Throwable? = null,
) : IOException(message, cause)
