package org.sempods.auth

import com.google.inject.Guice
import com.google.inject.Injector
import org.sempods.auth.api.webid.webIdEndpoint
import org.sempods.auth.persist.WebIdProfileDao
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.testing.*
import java.util.*

/**
 * Base class for sempods-auth integration tests.
 *
 * Uses a real Guice injector, real MongoDB (sempods-auth-test database),
 * and a real Ktor application — no mocking.
 *
 * Tests are designed to run in parallel: each test generates unique hashes
 * via [uniqueHash] so test data never collides. No global state reset.
 */
abstract class SempodsAuthIntegrationTest {

  companion object {

    val testConfig = SempodsAuthConfig(
      port = 8091,
      mongoUrl = "mongodb://localhost:27018",
      mongoDbName = "sempods-auth-test",
      idBaseUrl = "https://id.sempods.org",
    )

    val injector: Injector by lazy {
      Guice.createInjector(SempodsAuthModule(testConfig))
    }

    val webIdProfileDao: WebIdProfileDao by lazy {
      injector.getInstance(WebIdProfileDao::class.java)
    }
  }

  /**
   * Returns a unique hash for each call — ensures parallel tests never share data.
   */
  fun uniqueHash(): String = UUID.randomUUID().toString().replace("-", "").take(16)

  /**
   * Runs a test with a full Ktor application backed by real dependencies.
   * Usage:
   *   @Test fun `my test`() = withApp { client.get(...) }
   */
  fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      install(CallLogging)
      webIdEndpoint(webIdProfileDao, testConfig)
    }
    block()
  }
}
