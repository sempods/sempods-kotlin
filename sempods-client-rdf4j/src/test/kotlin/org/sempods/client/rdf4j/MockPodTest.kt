package org.sempods.client.rdf4j

import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.sempods.client.core.SempodsAdmission
import org.sempods.client.core.SempodsOkHttp
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

/** OkHttp's own shutdown: the dispatcher's threads and the pooled connections. */
internal fun OkHttpClient.shutDown() {
  dispatcher.executorService.shutdown()
  connectionPool.evictAll()
}
