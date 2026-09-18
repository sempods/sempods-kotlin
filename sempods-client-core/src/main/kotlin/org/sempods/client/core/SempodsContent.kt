package org.sempods.client.core

import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a write sends: bytes, text or a stream, sent as they are. The operation decides the media type.
 *
 * ```java
 * SempodsContent.of(jsonLd);                         // a String, as UTF-8
 * SempodsContent.of(bytes);                          // copied when handed over
 * SempodsContent.of(Files.newInputStream(dump));     // read once, while the request is written
 * SempodsContent.of(() -> Files.newInputStream(dump), size);   // opened again for every attempt
 * ```
 *
 * **A stream is sent once.** It has no length, so it goes out chunked, and what was read from it cannot
 * be read again: a write with stream content is not sent a second time, after a lost connection or a
 * refused credential, and one stream content serves one request. The caller keeps the stream and
 * closes it.
 *
 * **A [SempodsContentSource] is sent as often as the request is.** It is asked for a fresh stream per
 * attempt and closes each one, so a write that carries one is resent after a connection lost before
 * any answer, and reauthenticated after a refusal, as a write with bytes is. That is what a body too
 * large to hold in memory needs in order to be an ordinary request.
 */
sealed class SempodsContent {

  /**
   * This content as the body of one request, sent as [mediaType].
   *
   * A protocol module builds its own request through [SempodsSession.newRequest] and needs a body for
   * it; this is where one comes from, so the resend rules above hold for its route as they do for the
   * core's own. Calling it twice on stream content is refused — a stream is sent once.
   */
  abstract fun requestBody(mediaType: MediaType): RequestBody

  private class Bytes(private val bytes: ByteArray) : SempodsContent() {
    override fun requestBody(mediaType: MediaType): RequestBody = bytes.toRequestBody(mediaType)
  }

  private class Stream(private val stream: InputStream) : SempodsContent() {

    private val taken = AtomicBoolean()

    override fun requestBody(mediaType: MediaType): RequestBody {
      check(taken.compareAndSet(false, true)) { "This stream content was already sent; a stream can be sent once." }
      return object : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
          sink.writeAll(stream.source())
        }
      }
    }
  }

  private class Supplied(private val source: SempodsContentSource, private val length: Long) : SempodsContent() {

    override fun requestBody(mediaType: MediaType): RequestBody = object : RequestBody() {
      override fun contentType(): MediaType = mediaType

      override fun contentLength(): Long = length

      override fun isOneShot(): Boolean = false

      override fun writeTo(sink: BufferedSink) {
        source.open().use { sink.writeAll(it.source()) }
      }
    }
  }

  companion object {

    /** [bytes], copied now: changing the array afterwards changes nothing that is sent. */
    @JvmStatic
    fun of(bytes: ByteArray): SempodsContent = Bytes(bytes.copyOf())

    /** [text] as UTF-8. */
    @JvmStatic
    fun of(text: String): SempodsContent = Bytes(text.toByteArray(Charsets.UTF_8))

    /** [stream], read once while the request is written, and left open. */
    @JvmStatic
    fun of(stream: InputStream): SempodsContent = Stream(stream)

    /**
     * What [source] opens, for every attempt this request makes, each stream closed when it has been
     * written.
     *
     * [length] is the exact number of bytes [source] yields, or `-1` when the caller does not know:
     * with it the request carries a definite `Content-Length`, without it a chunked body. It is
     * framing rather than a promise the server trusts — a pod enforces its own limit while reading —
     * but a wrong value breaks the request, so `-1` is the honest answer where the size is unknown.
     */
    @JvmStatic
    @JvmOverloads
    fun of(source: SempodsContentSource, length: Long = -1): SempodsContent = Supplied(source, length)
  }
}

/**
 * Opens what a request writes, once per attempt.
 *
 * Every call must yield a **fresh** stream positioned at the first byte. Handed the same stream
 * twice, a second attempt writes what is left of it — and since the request itself still succeeds,
 * the server stores a truncated body and answers as though nothing were wrong.
 */
fun interface SempodsContentSource {

  @Throws(IOException::class)
  fun open(): InputStream
}
