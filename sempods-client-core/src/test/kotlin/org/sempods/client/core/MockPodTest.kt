package org.sempods.client.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.slf4j.event.Level

/** One MockServer standing in for the pods of a test class, reset before each test. */
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
