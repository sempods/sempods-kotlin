package org.sempods.client

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/** A body that may be written once, for the cases where no further attempt may send it. */
internal fun oneShotBody(content: String): RequestBody = object : RequestBody() {
  override fun contentType(): MediaType? = null

  override fun isOneShot() = true

  override fun writeTo(sink: BufferedSink) {
    sink.writeUtf8(content)
  }
}

/** Puts each attempt's number in [header], spelled by [value], and keeps the status of every answer it is shown. */
internal class Numbered(
  private val header: String,
  private val value: (Int) -> String = Int::toString,
) : SempodsRequestAuth {

  val told = CopyOnWriteArrayList<Int>()

  override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) {
    request.header(header, value(attempt.number))
  }

  override fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) {
    told += facts.status
  }
}
