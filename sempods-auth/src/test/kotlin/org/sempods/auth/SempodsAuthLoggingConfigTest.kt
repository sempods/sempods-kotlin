package org.sempods.auth

import org.sempods.commons.logging.LoggingAssertions
import kotlin.test.Test

/**
 * sempods-auth owns a `main`, so it owns its logging configuration. See `docs/logging.md`.
 */
class SempodsAuthLoggingConfigTest {

  @Test
  fun `this artifact ships a logging configuration`() = LoggingAssertions.assertAppLoggingConfigured()
}
