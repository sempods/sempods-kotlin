package org.sempods.commons.jaxrs.errors

object ApiErrors {

  const val UNAUTHORIZED_ERROR_ID = "common.request.unauthorized"
  const val FORBIDDEN_ERROR_ID = "common.request.forbidden"
  const val NOT_FOUND_ERROR_ID = "common.not-found"
  const val ALREADY_EXISTS_ERROR_ID = "common.already-exists"
  const val INVALID_JSON_REQUEST_BODY_ERROR_ID = "common.request.invalid-json-body"
  const val INVALID_PARAMETER_ERROR_ID = "common.request.invalid-parameter"
  const val MISSING_PARAMETERS_ERROR_ID = "common.request.missing-parameters"
  const val SERVICE_UNAVAILABLE_ERROR_ID = "common.service-unavailable"

  fun throwUnauthorizedError(): ApiException {
    throw ApiException(
      statusCode = 401,
      errorId = UNAUTHORIZED_ERROR_ID,
    )
  }

  fun throwForbiddenError(type: String? = null, id: String? = null): ApiException {
    val args = mutableMapOf<String, String>()
    type?.let { args["type"] = it }
    id?.let { args["id"] = it }
    throw ApiException(
      statusCode = 403,
      errorId = FORBIDDEN_ERROR_ID,
      errorParameters = args,
    )
  }

  /**
   * The request was refused because *this deployment* cannot serve it — a credential the operator
   * never configured, a backing service that is not wired up. Distinct from 4xx on purpose: the
   * caller did nothing wrong and retrying with different input will not help.
   *
   * Logged as an error ([ApiException.logAsError]), because it is the operator who has to see it
   * and nothing else in the request will say so.
   */
  fun throwServiceUnavailableError(message: String? = null, logMessage: String? = null): ApiException {
    throw ApiException(
      statusCode = 503,
      errorId = SERVICE_UNAVAILABLE_ERROR_ID,
      errorMessage = message,
      logMessage = logMessage,
      logAsError = true,
    )
  }

  fun throwNotFoundError(type: String? = null, id: String? = null): ApiException {
    val args = mutableMapOf<String, String>()
    type?.let { args["type"] = it }
    id?.let { args["id"] = it }
    throw ApiException(
      statusCode = 404,
      errorId = NOT_FOUND_ERROR_ID,
      errorParameters = args,
    )
  }

  fun throwAlreadyExistsError(type: String? = null, id: String? = null, message: String? = null): ApiException {
    val args = mutableMapOf<String, String>()
    type?.let { args["type"] = it }
    id?.let { args["id"] = it }
    throw ApiException(
      statusCode = 409,
      errorId = ALREADY_EXISTS_ERROR_ID,
      errorParameters = args,
      errorMessage = message,
    )
  }

  fun throwInvalidJsonRequestBody(): ApiException {
    throw ApiException(
      statusCode = 400,
      errorId = INVALID_JSON_REQUEST_BODY_ERROR_ID,
    )
  }

  fun invalidParameterError(
    parameterName: String,
    message: String? = null,
  ): ApiErrorDto {
    return ApiErrorDto(
      code = INVALID_PARAMETER_ERROR_ID,
      parameters = mapOf("name" to parameterName),
      message = message ?: "Der Parameter '$parameterName' ist ungültig.",
    )
  }

  fun throwInvalidParameterError(parameterName: String, message: String? = null): ApiException {
    throw ApiException(
      statusCode = 400,
      errors = listOf(invalidParameterError(parameterName = parameterName, message = message)),
    )
  }

  fun throwInvalidRequestMissingParameters(parameterNames: List<String>): ApiException {
    throw ApiException(
      statusCode = 400,
      errorId = MISSING_PARAMETERS_ERROR_ID,
      errorMessage = "Es fehlen folgende Parameter: ${parameterNames.joinToString(", ")}",
      errorParameters = mapOf("parameters" to parameterNames),
    )
  }
}
