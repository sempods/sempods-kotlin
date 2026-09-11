package org.sempods.client.core

import java.net.URI
import java.time.Duration

/**
 * One request to send: a method, an absolute target, headers and an optional body.
 *
 * Built through [SempodsSession.newRequest], which resolves the path against the session's pod base
 * and refuses one that would leave it. A request is immutable and carries no credential — the
 * session's [SempodsRequestAuth] applies one per attempt, after assembly, so a header bound to the
 * request can be regenerated for each try.
 */
class SempodsRequest internal constructor(
  val uri: URI,
  val method: String,
  internal val headers: List<Pair<String, String>>,
  internal val body: SempodsBody?,
  internal val operationTimeout: Duration?,
) {

  /** Whether every attempt can send the same bytes — `false` for a one-shot body. */
  internal val replayable: Boolean get() = body?.replayable ?: true

  /**
   * Assembles a request. Header methods distinguish replacement from repetition, because both are
   * real: `Accept` is set once and a second one is a bug, while `Link` may legitimately repeat.
   */
  class Builder internal constructor(
    private val uri: URI,
    private var method: String,
    private var operationTimeout: Duration?,
  ) {
    private val headers = mutableListOf<Pair<String, String>>()
    private var body: SempodsBody? = null

    /** Sets [name] to [value], removing any value this builder already holds for it. */
    fun setHeader(name: String, value: String): Builder = apply {
      headers.removeAll { it.first.equals(name, ignoreCase = true) }
      headers += name to value
    }

    /** Adds another value for [name], keeping the ones already there. */
    fun addHeader(name: String, value: String): Builder = apply { headers += name to value }

    fun removeHeader(name: String): Builder = apply {
      headers.removeAll { it.first.equals(name, ignoreCase = true) }
    }

    /**
     * The method and body of this request. Any token is accepted, so a protocol extension needs no
     * change here; [SempodsSession] exposes the ordinary verbs as named methods.
     */
    @JvmOverloads
    fun method(method: String, body: SempodsBody? = null): Builder = apply {
      this.method = method
      this.body = body
    }

    fun body(body: SempodsBody): Builder = apply { this.body = body }

    fun get(): Builder = method("GET")

    fun head(): Builder = method("HEAD")

    fun options(): Builder = method("OPTIONS")

    fun delete(): Builder = method("DELETE")

    fun put(body: SempodsBody): Builder = method("PUT", body)

    fun post(body: SempodsBody): Builder = method("POST", body)

    fun patch(body: SempodsBody): Builder = method("PATCH", body)

    /**
     * Overrides the session's whole-operation deadline for this one request.
     *
     * [Duration.ZERO] means **no** deadline — what a streaming read of an unbounded body needs,
     * since a deadline covering body processing would cut it off mid-stream.
     */
    fun operationTimeout(timeout: Duration): Builder = apply { this.operationTimeout = timeout }

    fun build(): SempodsRequest = SempodsRequest(uri, method, headers.toList(), body, operationTimeout)
  }

  companion object {

    /**
     * A request against an absolute target, for the paths that have no pod to be relative to:
     * dereferencing a foreign URI, and acquiring a token from an endpoint that is not under a pod.
     *
     * Such a request carries **no** session credential — [SempodsTransport.execute] takes the
     * authentication for its one target explicitly, and nothing inherits a pod's. Use
     * [SempodsSession.newRequest] for anything addressed under a pod, where the base is validated
     * and the target confined.
     */
    @JvmStatic
    @JvmOverloads
    fun to(uri: URI, method: String = "GET"): Builder {
      require(uri.isAbsolute) { "A request target must be an absolute URI, not '$uri'" }
      return Builder(uri, method, null)
    }
  }
}
