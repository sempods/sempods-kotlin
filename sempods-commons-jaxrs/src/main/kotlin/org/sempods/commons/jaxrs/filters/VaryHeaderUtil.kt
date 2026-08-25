package org.sempods.commons.jaxrs.filters

import jakarta.ws.rs.container.ContainerResponseContext

/**
 * Utility for manipulating the Vary HTTP header.
 *
 * The Vary header uses comma-separated values to indicate which request headers
 * affect the response representation. This utility helps add values to the Vary
 * header while preventing duplicates.
 */
object VaryHeaderUtil {

  /**
   * Adds a value to a comma-separated header (like Vary) without creating duplicates.
   *
   * If the header doesn't exist, it will be created with the given value.
   * If the header exists and doesn't already contain the value (case-insensitive check),
   * the value will be appended to the existing comma-separated list.
   *
   * @param responseContext The JAX-RS response context
   * @param headerName The name of the header (e.g., "Vary")
   * @param headerValue The value to add (e.g., "Accept", "Origin")
   */
  fun addCommaSeparatedHeaderValue(
    responseContext: ContainerResponseContext,
    headerName: String,
    headerValue: String,
  ) {
    val existingHeaderValue = responseContext.getHeaderString(headerName)

    if (existingHeaderValue.isNullOrEmpty()) {
      // No existing header, just set the value
      responseContext.headers.putSingle(headerName, headerValue)
    } else {
      // Header already exists, check if value is already included
      val headerValues = existingHeaderValue.split(",").map { it.trim() }
      if (!headerValues.any { it.equals(headerValue, ignoreCase = true) }) {
        // Value not present: append to existing values
        responseContext.headers.putSingle(headerName, "$existingHeaderValue, $headerValue")
      }
    }
  }
}
