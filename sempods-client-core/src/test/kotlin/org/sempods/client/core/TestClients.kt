package org.sempods.client.core

import okhttp3.OkHttpClient

/** A client as a consumer configures one: OkHttp's builder, with the sempods interceptors installed. */
internal fun sempodsClient(
  admission: SempodsAdmission? = SempodsAdmission(),
  configure: OkHttpClient.Builder.() -> Unit = {},
): OkHttpClient = SempodsOkHttp.install(OkHttpClient.Builder().apply(configure), admission = admission).build()

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
