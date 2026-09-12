package org.sempods.client

/**
 * What a refused request looks like to the clients on this surface.
 *
 * A subclass of [org.sempods.client.core.SempodsClientException] rather than a second hierarchy, so
 * a caller catching the core type catches this too. It keeps everything on one class, which is the
 * shape `SempodsClient` and `PodWireClient` classify on; the core splits the same information into
 * [org.sempods.client.core.SempodsHttpException] and
 * [org.sempods.client.core.SempodsTransportException], where a server that answered and a network
 * that did not are different types rather than a nullable field.
 */
class SempodsClientException @JvmOverloads constructor(
  message: String,
  /** HTTP status of the failed response, when the failure was a non-2xx answer (else null). */
  val statusCode: Int? = null,
  /**
   * What the server wrote in the failed response, on its own — the same excerpt [message] embeds,
   * without the method and URL in front of it.
   *
   * Separate because a caller that forwards a failure onward must not forward the URL with it.
   * [message] is written for a log line, where knowing which call failed is the point; an MCP tool
   * error goes to a language model, which has no use for `http://localhost:8090/pod/_system/…` and
   * some tendency to repeat it back to the user as if it were an address they should visit.
   * Recovering the detail by cutting [message] at a separator would be the kind of string surgery
   * that breaks silently the first time the format changes.
   *
   * Null when there was no response to read — a refused connection, or a body that could not be
   * parsed as the route's contract requires.
   */
  val responseBody: String? = null,
  /**
   * The failure underneath, when there is one — a transport refusing to connect at all rather than
   * a server answering. Callers classify on it: a blocked address and a dead credential both surface
   * as this exception, and only the cause chain tells them apart. Dropping it makes the two
   * indistinguishable, which is how a refused connection gets reported as an expired token.
   */
  cause: Throwable? = null,
) : org.sempods.client.core.SempodsClientException(message, cause)
