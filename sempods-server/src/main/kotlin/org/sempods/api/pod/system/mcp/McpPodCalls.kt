package org.sempods.api.pod.system.mcp

import okhttp3.Call

/**
 * The calls the MCP tools make back into this pod server's own public HTTP surface.
 *
 * **A type of its own rather than the `OkHttpClient` the composition shares.** This client carries a
 * trusted-host exemption for the deployment's own address, and a binding a second consumer could
 * receive by type would take that exemption along to wherever it dials. Named here, it reaches the
 * one endpoint that may have it.
 */
class McpPodCalls(val calls: Call.Factory)
