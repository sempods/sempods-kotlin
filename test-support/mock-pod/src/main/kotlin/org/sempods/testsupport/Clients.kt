package org.sempods.testsupport

import okhttp3.OkHttpClient
import org.sempods.client.SempodsAdmission
import org.sempods.client.SempodsOkHttp
import org.sempods.client.net.SempodsOutboundGuard

/** A client as a consumer configures one: OkHttp's builder, with the sempods interceptors installed. */
fun sempodsClient(
  admission: SempodsAdmission? = SempodsAdmission(),
  guard: SempodsOutboundGuard? = null,
  configure: OkHttpClient.Builder.() -> Unit = {},
): OkHttpClient = SempodsOkHttp.install(OkHttpClient.Builder().apply(configure), guard, admission).build()

/** OkHttp's own shutdown: the dispatcher's threads and the pooled connections. */
fun OkHttpClient.shutDown() {
  dispatcher.executorService.shutdown()
  connectionPool.evictAll()
}

/** Runs [block] with this client and shuts it down afterwards, for a client one test builds for itself. */
inline fun <T> OkHttpClient.closing(block: (OkHttpClient) -> T): T =
  try {
    block(this)
  } finally {
    shutDown()
  }
