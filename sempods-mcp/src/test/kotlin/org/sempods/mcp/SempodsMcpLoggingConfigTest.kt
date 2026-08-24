package org.sempods.mcp

import org.sempods.commons.logging.LoggingAssertions
import kotlin.test.Test

/**
 * sempods-mcp owns a `main`, so it owns its logging configuration. See `docs/logging.md`.
 */
class SempodsMcpLoggingConfigTest {

  @Test
  fun `this artifact ships a logging configuration`() = LoggingAssertions.assertAppLoggingConfigured()
}
