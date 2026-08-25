package org.sempods.commons.jaxrs.errors

import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.regex.Pattern

/**
 * An error the API answers with, carrying the status code and the machine-readable error list
 * that becomes the response body.
 *
 * Shared rather than application-owned: it names no domain type, and its whole job is to turn a
 * failure into a JAX-RS [Response] in the shape [ApiErrorResponse] pins.
 */
open class ApiException constructor(

  val statusCode: Int = 500,

  val errors: List<ApiErrorDto>,

  cause: Throwable? = null,
  message: String? = null,
  val logAsError: Boolean = false,

) : RuntimeException(message, cause) {

  constructor(
    errorId: String,
    errorMessage: String? = null,
    errorParameters: Map<String, Any> = emptyMap(),
    statusCode: Int = 500,
    logMessage: String? = null,
    logAsError: Boolean = false,
  ) : this(
    statusCode = statusCode,
    errors = listOf(
      ApiErrorDto(
        code = errorId,
        message = errorMessage,
        parameters = errorParameters,
      )
    ),
    message = logMessage,
    logAsError = logAsError,
  )

  fun buildResponse(): Response {

    val renderedErrors = errors.map { error ->

      if (error.parameters.isEmpty()) {
        return@map error
      }

      var renderedMessage = error.message ?: return@map error

      getVariables(renderedMessage)
        .forEach { templateVar ->
          val value = error.parameters[templateVar] ?: return@forEach
          renderedMessage = renderedMessage.replace(Pattern.quote("{{$templateVar}}").toRegex(), value.toString())
        }

      return@map error.copy(message = renderedMessage)
    }

    val bodyPayload = ApiErrorResponse(errors = renderedErrors)
    return Response
      .status(statusCode)
      .entity(JsonUtil.write(JsonMappers.default(), bodyPayload))
      .type(MediaType.APPLICATION_JSON).build()
  }

  companion object {

    private const val serialVersionUID = 1L

//    fun throwUnchecked(t: Throwable?): RuntimeException {
//      Throwables.throwIfUnchecked(t)
//      throw RuntimeException(t)
//    }
//
//    @JvmStatic
//    protected fun buildArguments(arg1: String, val1: Any): Map<String, Any> {
//      val m: MutableMap<String, Any> = HashMap()
//      m[arg1] = val1
//      return m
//    }
//
//    @JvmStatic
//    protected fun buildArguments(
//      arg1: String, val1: Any, arg2: String,
//      val2: Any
//    ): Map<String, Any> {
//      val m: MutableMap<String, Any> = HashMap()
//      m[arg1] = val1
//      m[arg2] = val2
//      return m
//    }

    private fun getVariables(localizationPattern: String): Collection<String> {
      val s: MutableSet<String> = HashSet()
      var idx1 = 0
      while (idx1 > -1) {
        idx1 = localizationPattern.indexOf("{{", idx1)
        if (idx1 > -1) {
          val idx2 = localizationPattern.indexOf("}}", idx1 + 2)
          idx1 = if (idx2 > -1) {
            val v = localizationPattern.substring(idx1 + 2, idx2)
            if (v.trim { it <= ' ' }.isNotEmpty()) s.add(v)
            idx2 + 2
          } else return s // reached end
        }
      }
      return s
    }
  }
}
