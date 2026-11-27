package org.sempods.testsupport

import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.slf4j.event.Level

/**
 * One MockServer standing in for the pods of a test class, and one client to reach it with.
 *
 * The server starts before the class on a fresh port, is reset before each test and stops after the
 * class. [client] is built on first use and shut down after the class. Why the stub belongs to the
 * class and listens on loopback: `docs/testing.md` §"Stubbing an external API".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MockPodTest {

  protected lateinit var server: ClientAndServer
    private set

  /** Where the server answers, `http://localhost:<port>`. */
  protected lateinit var origin: String
    private set

  private val lazyClient = lazy { newClient() }

  /** The class's client, from [newClient]. */
  protected val client: OkHttpClient by lazyClient

  /** Builds [client]. By default a client as a consumer configures one; see [sempodsClient]. */
  protected open fun newClient(): OkHttpClient = sempodsClient()

  /**
   * The server's configuration. By default it listens on `127.0.0.1`, the address the build sets; an
   * override that chooses another says why the test needs it.
   */
  protected open fun configuration(): Configuration = Configuration.configuration().logLevel(Level.WARN)

  @BeforeAll
  fun startMockPod() {
    server = ClientAndServer.startClientAndServer(configuration())
    origin = "http://localhost:${server.port}"
  }

  @BeforeEach
  fun resetMockPod() {
    server.reset()
  }

  @AfterAll
  fun releaseMockPod() {
    try {
      if (::server.isInitialized) server.stop()
    } finally {
      if (lazyClient.isInitialized()) client.shutDown()
    }
  }
}
