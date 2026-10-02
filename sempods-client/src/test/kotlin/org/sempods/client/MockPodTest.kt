package org.sempods.client

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest
import org.slf4j.event.Level

/** One MockServer standing in for the pods of a test class, reset before each test. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MockPodTest {

  protected lateinit var server: ClientAndServer
  protected lateinit var origin: String

  @BeforeAll
  fun startServer() {
    server = ClientAndServer.startClientAndServer(configuration())
    origin = "http://localhost:${server.port}"
  }

  /**
   * The server's configuration. It listens on `127.0.0.1`, which the build sets for every
   * MockServer — see `docs/testing.md` §"Stubbing an external API".
   */
  protected open fun configuration(): Configuration = Configuration.configuration().logLevel(Level.WARN)

  @AfterAll
  fun stopServer() {
    server.stop()
  }

  @BeforeEach
  fun resetServer() {
    server.reset()
  }

  /** The header names a request carries beyond those OkHttp adds to any request it frames. */
  protected fun HttpRequest.headersBeyondTransport(): Set<String> =
    headerList.map { it.name.value.lowercase() }.toSet() -
      setOf("host", "connection", "accept-encoding", "user-agent", "content-length")
}
