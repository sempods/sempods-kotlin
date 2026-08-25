package org.sempods.probe.mcp

import com.google.inject.Guice
import com.google.inject.Injector
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.SempodsMcpModule

/**
 * The embedding contract of `:sempods-mcp`, compiled the way a stranger would compile it. See
 * `org.sempods.probe.auth.embedSempodsAuth` for why this check exists and what it catches that
 * `buildHealth` cannot.
 */
@Suppress("unused")
internal fun embedSempodsMcp(config: SempodsMcpConfig): Injector =
  Guice.createInjector(SempodsMcpModule(config))
