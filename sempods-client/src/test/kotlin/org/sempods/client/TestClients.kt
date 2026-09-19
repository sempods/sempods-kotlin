package org.sempods.client

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.sempods.client.net.SempodsOutboundGuard
import okio.BufferedSink

/** A client as a consumer configures one: OkHttp's builder, with the sempods interceptors installed. */
internal fun sempodsClient(
  admission: SempodsAdmission? = SempodsAdmission(),
  guard: SempodsOutboundGuard? = null,
  configure: OkHttpClient.Builder.() -> Unit = {},
): OkHttpClient = SempodsOkHttp.install(OkHttpClient.Builder().apply(configure), guard, admission).build()

/** OkHttp's own shutdown: the dispatcher's threads and the pooled connections. */
internal fun OkHttpClient.shutDown() {
  dispatcher.executorService.shutdown()
  connectionPool.evictAll()
}

internal inline fun <T> OkHttpClient.closing(block: (OkHttpClient) -> T): T =
  try {
    block(this)
  } finally {
    shutDown()
  }

/** A body that may be written once, for the cases where no further attempt may send it. */
internal fun oneShotBody(content: String): RequestBody = object : RequestBody() {
  override fun contentType(): MediaType? = null

  override fun isOneShot() = true

  override fun writeTo(sink: BufferedSink) {
    sink.writeUtf8(content)
  }
}
