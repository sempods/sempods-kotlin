package org.sempods.client.core

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Opens the bytes of a request body.
 *
 * An interface rather than a Kotlin function type because this is a published Java API: a
 * `() -> InputStream` reaches a Java caller as `kotlin.jvm.functions.Function0`, which they have to
 * name and which cannot declare [IOException]. Opening a file is I/O, and a body source that has to
 * wrap its own failure in an unchecked exception tells the transport less than it knows.
 */
fun interface SempodsBodySource {
  @Throws(IOException::class)
  fun open(): InputStream
}

/**
 * The bytes a request carries, deliberately naming no HTTP engine.
 *
 * **Why these exist rather than the engine's own types.** Handing out an engine's `RequestBody`
 * would make its major version part of this library's ABI, and a consumer of a Maven Central
 * artifact would compile against it. Everything engine-specific therefore stops at
 * [SempodsTransport]; what travels above it is this.
 *
 * **Replayability is stated, not guessed.** A body is sent again whenever an attempt is repeated —
 * an authentication recovery, or a connection that turned out to be stale — and only the caller
 * knows whether its source can produce the same bytes twice. [stream] says yes; [oneShotStream]
 * says no, and an operation carrying one gets exactly one attempt. Guessing wrong in the permissive
 * direction uploads zero bytes on the second attempt and reports success.
 */
sealed class SempodsBody {

  /** Whether this body can be written more than once, and therefore whether a retry is possible. */
  abstract val replayable: Boolean

  internal class Bytes(val value: ByteArray) : SempodsBody() {
    override val replayable: Boolean get() = true
  }

  /**
   * A body supplied on demand. [source] is invoked **once per attempt** and, when [replayable],
   * must yield a fresh stream positioned at the first byte every time.
   *
   * [size] decides the framing only: with it the request carries a definite `Content-Length`,
   * without it a chunked body.
   */
  internal class Stream(
    val size: Long?,
    val source: SempodsBodySource,
    override val replayable: Boolean,
  ) : SempodsBody()

  internal object Empty : SempodsBody() {
    override val replayable: Boolean get() = true
  }

  companion object {

    @JvmStatic
    fun bytes(value: ByteArray): SempodsBody = Bytes(value)

    /**
     * UTF-8 bytes of [value], and **only** the bytes: no charset is attached to the body, so the
     * `Content-Type` a caller sets with [SempodsRequest.Builder.setHeader] is what travels. Engines
     * that would otherwise append `; charset=utf-8` to a string body cannot do it here.
     */
    @JvmStatic
    fun text(value: String): SempodsBody = Bytes(value.toByteArray(StandardCharsets.UTF_8))

    /**
     * A replayable stream: [source] yields a fresh stream over the same bytes on every attempt.
     * A file, a byte array, a regenerable serialization.
     */
    @JvmStatic
    @JvmOverloads
    fun stream(source: SempodsBodySource, size: Long? = null): SempodsBody = Stream(size, source, replayable = true)

    /**
     * A stream that can be read once — a socket, a pipe, a consumer's own `InputStream` parameter.
     *
     * An operation carrying one gets a single attempt: no authentication recovery, and no
     * transparent repeat after a stale connection. That is the honest trade, because the
     * alternative is a second attempt that sends nothing and a server that answers 200 to it.
     */
    @JvmStatic
    @JvmOverloads
    fun oneShotStream(source: SempodsBodySource, size: Long? = null): SempodsBody =
      Stream(size, source, replayable = false)

    @JvmStatic
    fun empty(): SempodsBody = Empty
  }
}
