package org.sempods.commons.jaxrs.errors

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import java.util.concurrent.ExecutionException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import org.sempods.commons.jaxrs.ContainerRequestHolder
import org.sempods.commons.jaxrs.RequestPathForLog
import org.sempods.commons.jaxrs.SecretPathSegment
import org.sempods.commons.logging.LogSafeText

/**
 * The one place a failed request is logged, and therefore the one place that has to say which
 * request it was.
 *
 * Every line carries `<METHOD> <path>`, read from [ContainerRequestHolder] — including the
 * failures decided *during* matching (404, 405, 406), which is what
 * [org.sempods.commons.jaxrs.ContainerRequestHolderFilter]'s `@PreMatching` buys. The trace id
 * arrives on its own: `TraceContextFilter` puts it in the MDC and the shared pattern renders it.
 *
 * The query string is deliberately not logged — it carries parameters that are nobody's business
 * in a log file, and the path is what identifies the route. The path itself is attacker-controlled
 * and decoded, so it goes through [RequestPathForLog] first: a route with a secret in its URL
 * declares it ([SecretPathSegment]) because a 405 or a 406 leaves no matched template to log
 * instead, and control characters are escaped so a request cannot forge a second log line.
 *
 * The failure's own text goes through [LogSafeText] too: a parser quotes the body it choked on.
 *
 * @param secretPathSegments empty in every composition but the one that has such a route — the set
 *   binder in `JaxRsApplicationModule` supplies it either way, so there is no default here: a
 *   Kotlin default parameter compiles to a second `@Inject` constructor and Guice refuses the
 *   class outright. `jakarta.inject`, not Guice, because a consumer wiring Jersey by hand must not
 *   inherit a DI container to get an exception mapper — what `commons-jaxrs` keeps Guice
 *   `compileOnly` for.
 */
class ApiExceptionMapper @Inject constructor(
  private val secretPathSegments: @JvmSuppressWildcards Set<SecretPathSegment>,
) : ExceptionMapper<Throwable> {

  private val secretAfter: Set<String> = secretPathSegments.mapTo(HashSet()) { it.afterSegment }

  override fun toResponse(throwable: Throwable): Response {

    val failure = unwrapExecutionException(throwable)
    val request = requestDescription()

    // The project's own API exceptions
    if (failure is ApiException) {
      val logMsg = "${LogSafeText.of(failure.errors.toString())}$request"
      if (failure.logAsError) {
        logger.error(failure.cause) { logMsg }
      } else {
        logger.warn(failure.cause) { logMsg }
      }
      try {
        return failure.buildResponse()
      } catch (e: Exception) {
        throw RuntimeException("error on creating error response", e)
      }
    }

    // jax-rs exceptions
    if (failure is WebApplicationException) {
      if (failure.response?.status == 404) {
        logger.info { "Got 404 (not found): ${LogSafeText.of(failure.message.toString())}$request" }
      } else if (failure.response?.status == 405) {
        logger.info { "Got 405 (method not allowed): ${LogSafeText.of(failure.message.toString())}$request" }
      } else {
        logger.error(failure) { "${LogSafeText.of(failure.message.toString())}$request" }
      }
      return failure.response
    }

    // unknown exception, log as fatal
    logger.error(failure) { "${LogSafeText.of(failure.message.toString())}$request" }
    return Response.status(500).entity(failure.message).type("text/plain").build()
  }

  /**
   * `" [POST v1/pods/alice/_system/auth/token]"`, or the empty string when this thread is not
   * serving a request — a mapper invoked outside one still has to log rather than throw.
   */
  private fun requestDescription(): String {
    val requestContext = ContainerRequestHolder.get() ?: return ""
    return " [${requestContext.method} ${RequestPathForLog.of(requestContext.uriInfo.path, secretAfter)}]"
  }

  companion object {

    private val logger = KotlinLogging.logger {}

    /**
     * The cause of an [ExecutionException] — what a caller joining a task with `Future.get()`
     * throws in place of the failure the task raised. This type only, one layer: [ApiException]
     * and [WebApplicationException] are `RuntimeException`s themselves, so a wider rule would
     * strip the response off an exception that carries one.
     */
    private fun unwrapExecutionException(t: Throwable): Throwable =
      if (t is ExecutionException) t.cause ?: t else t
  }
}
