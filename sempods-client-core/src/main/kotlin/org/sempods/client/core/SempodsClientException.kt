package org.sempods.client.core

import java.io.IOException

/**
 * What this library refuses, as opposed to what the network does.
 *
 * An [IOException], because that is what every caller of an OkHttp `Call` already handles and what
 * the engine itself throws — a second hierarchy beside it would only make a `catch` clause longer.
 * What it marks is a refusal that came from *here*: a target outside the session's pod, an address
 * the outbound guard rejected, an admission budget that was spent, an authentication mechanism that
 * tried to move the request.
 *
 * A server that answered is not an exception at all. `Response.isSuccessful`, the status and the
 * headers are OkHttp's, and 304, 404 and 412 are answers on the routes above this rather than
 * failures — a core that threw would force every caller to read them out of a `catch`.
 */
open class SempodsClientException @JvmOverloads constructor(
  message: String,
  cause: Throwable? = null,
) : IOException(message, cause)
