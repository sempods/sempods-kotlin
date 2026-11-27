package org.sempods.client.rdf4j

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.sempods.testsupport.sempodsClient

/** A request as OkHttp wrote it: MockServer decodes the query, and may re-read a body it records. */
internal class Sent(val method: String, val url: HttpUrl, val headers: Headers, val body: ByteArray?)

/** A client that records every request it writes in [sent], below the sempods interceptors, credential included. */
internal fun recordingClient(sent: MutableList<Sent>): OkHttpClient = sempodsClient().newBuilder()
  .addNetworkInterceptor { chain ->
    val request = chain.request()
    sent += Sent(request.method, request.url, request.headers, request.body?.let { Buffer().also(it::writeTo).readByteArray() })
    chain.proceed(request)
  }
  .build()
