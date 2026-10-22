package org.sempods.api

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * The `include_contexts` query parameter of the read routes. Kept apart from [SempodsBaseEndpoint]
 * so a handler that authenticates nothing itself, such as `FindHttpHandler`, parses it the same way.
 */
internal object IncludeContextsParameter {

  /**
   * Absent or blank is `false`; otherwise `true` or `false`, case-insensitive. Anything else throws
   * a [WebApplicationException] carrying a `400`.
   */
  fun parseOrThrow(raw: String?): Boolean {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return when (value.lowercase()) {
      "true" -> true
      "false" -> false
      else -> throw WebApplicationException(
        Response.status(400)
          .entity("include_contexts must be 'true' or 'false'")
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }
  }
}
