package org.sempods.client.rdf4j

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.sempods.client.SempodsAdmission
import org.sempods.client.SempodsOkHttp
import org.slf4j.event.Level

/** One MockServer standing in for the pods of a test class, reset before each test. The core's twin is internal to its tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MockPodTest {

  protected lateinit var server: ClientAndServer
  protected lateinit var origin: String

  @BeforeAll
  fun startServer() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    origin = "http://localhost:${server.port}"
  }

  @AfterAll
  fun stopServer() {
    server.stop()
  }

  @BeforeEach
  fun resetServer() {
    server.reset()
  }
}

/** A client as a consumer configures one: OkHttp's builder, with the sempods interceptors installed. */
internal fun sempodsClient(
  admission: SempodsAdmission = SempodsAdmission(),
  configure: OkHttpClient.Builder.() -> Unit = {},
): OkHttpClient = SempodsOkHttp.install(OkHttpClient.Builder().apply(configure), admission = admission).build()

/** A request as OkHttp wrote it: MockServer decodes the query, and may re-read a body it records. */
internal class Sent(val method: String, val url: HttpUrl, val headers: Headers, val body: ByteArray?)

/** A client that records every request it writes in [sent]. */
internal fun recordingClient(sent: MutableList<Sent>): OkHttpClient = sempodsClient {
  addNetworkInterceptor { chain ->
    val request = chain.request()
    sent += Sent(request.method, request.url, request.headers, request.body?.let { Buffer().also(it::writeTo).readByteArray() })
    chain.proceed(request)
  }
}

/** OkHttp's own shutdown: the dispatcher's threads and the pooled connections. */
internal fun OkHttpClient.shutDown() {
  dispatcher.executorService.shutdown()
  connectionPool.evictAll()
}
