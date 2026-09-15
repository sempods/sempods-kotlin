package org.sempods.client.core

import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a write sends: bytes, text or a stream, sent as they are. The operation decides the media type.
 *
 * ```java
 * SempodsContent.of(jsonLd);                         // a String, as UTF-8
 * SempodsContent.of(bytes);                          // copied when handed over
 * SempodsContent.of(Files.newInputStream(dump));     // read once, while the request is written
 * ```
 *
 * **A stream is sent once.** It has no length, so it goes out chunked, and what was read from it cannot
 * be read again: a write with stream content is not sent a second time, after a lost connection or a
 * refused credential, and one stream content serves one request. The caller keeps the stream and
 * closes it.
 */
sealed class SempodsContent {

  /** The body of one request. */
  internal abstract fun requestBody(mediaType: MediaType): RequestBody

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
  }
}
