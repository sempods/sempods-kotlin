package org.sempods.mcp

/**
 * Local development entry point for sempods-mcp.
 * Hardcodes local URLs — no environment variables needed.
 *
 * Run via IntelliJ, or point the user identity at a local sempods-auth on :8091.
 */
fun main() = startSempodsMcp(
  SempodsMcpConfig(
    port = 8092,
    mongoUrl = "mongodb://localhost:27018",
    mongoDbName = "sempods-mcp",
    mcpBaseUrl = "http://localhost:8092",
    authIssuers = listOf("http://localhost:8091"),
    allowLocalPods = true,
  )
)
