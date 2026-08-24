package org.sempods.mcp.api

import org.sempods.mcp.persist.ProfilePath
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/**
 * Resolve the `{profile}` path segment for a profile-scoped route, or answer a uniform 404 (and
 * return null) for a reserved/malformed segment. Shared by the MCP, DCR/authorize/token, and
 * OAuth-discovery routes so the rejection contract stays identical across every surface.
 */
suspend fun ApplicationCall.resolveProfileOr404(): String? {
  val profile = ProfilePath.normalize(parameters["profile"])
  if (profile == null) respondText("unknown profile", status = HttpStatusCode.NotFound)
  return profile
}
