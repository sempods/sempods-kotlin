package org.sempods.deployment

import org.sempods.commons.logging.LoggingAssertions
import kotlin.test.Test

/**
 * The pod server image owns a `main`, so it owns its logging configuration. See `docs/logging.md`.
 */
class SempodsServerLoggingConfigTest {

  @Test
  fun `this artifact ships a logging configuration`() = LoggingAssertions.assertAppLoggingConfigured()
}
