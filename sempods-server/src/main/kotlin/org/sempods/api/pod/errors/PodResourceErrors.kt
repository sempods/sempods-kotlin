package org.sempods.api.pod.errors

import org.sempods.commons.jaxrs.errors.ApiErrors
import org.sempods.commons.jaxrs.errors.ApiException
import java.net.URI

object PodResourceErrors {

  /**
   * 404 thrown when a write operation (merge-patch, delete, slot ops) targets a resource that
   * does not exist in [contextUri]. Carries the standard `type`/`id` parameters of
   * [ApiErrors.NOT_FOUND_ERROR_ID] plus a `context_iri` parameter so callers can react
   * programmatically without parsing the message.
   *
   * The optional [hint] is appended to the rendered message — e.g. for merge-patch, pass
   * `"use create_resource to create it"` so the MCP client knows which tool to call instead.
   */
  fun throwResourceNotFoundInContext(
    resourceUri: URI,
    contextUri: URI,
    hint: String? = null,
  ): ApiException {
    val baseMessage = "resource <$resourceUri> does not exist in context <$contextUri>"
    val fullMessage = if (hint != null) "$baseMessage — $hint" else baseMessage
    throw ApiException(
      statusCode = 404,
      errorId = ApiErrors.NOT_FOUND_ERROR_ID,
      errorMessage = fullMessage,
      errorParameters = mapOf(
        "type" to "resource",
        "id" to resourceUri.toString(),
        "context_iri" to contextUri.toString(),
      ),
    )
  }
}
