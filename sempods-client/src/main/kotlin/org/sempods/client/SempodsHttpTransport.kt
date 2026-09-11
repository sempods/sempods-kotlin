package org.sempods.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import org.sempods.client.core.SempodsBodyHandler
import org.sempods.client.core.SempodsHttpTimeouts
import org.sempods.client.core.SempodsRequest
import org.sempods.client.core.SempodsResponse
import org.sempods.client.core.SempodsStreamedResponse
import org.sempods.client.core.SempodsTransport
import org.sempods.client.core.SempodsTransportException
import org.sempods.client.core.SempodsUrlEncoding
import org.sempods.client.core.net.SempodsOutboundGuard
import org.sempods.client.core.net.SempodsRateLimitedException
import org.sempods.client.core.net.SsrfBlockedException
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder

/**
 * The transport the existing clients were written against, now a thin layer over
 * [SempodsTransport].
 *
 * **This is the legacy surface, and it is where the JSON helpers stayed.** [objectMapper] and
 * [requiredText] name Jackson types, which is exactly why the core does not have them: a consumer
 * that wants HTTP against a pod should not resolve an object mapper to get it. They remain here
 * because `SempodsClient`, `SempodsPodClient`, `PodWireClient` and `SempodsControlPlaneClient` use
 * them today, and moving those onto the core is
 * [#150](https://github.com/sempods/sempods-kotlin/issues/150) and
 * [#152](https://github.com/sempods/sempods-kotlin/issues/152), not this iteration.
 *
 * Everything below it — one connection pool, the outbound guard, the deadlines, cancellation,
 * admission and the body lifetime — is [SempodsTransport]'s, so there is one implementation of
 * those and not two.
 *
 * **A token per request rather than a credential per session.** That is this surface's model, and
 * it is why the core sees [org.sempods.client.core.SempodsRequestAuth.anonymous] here: the bearer
 * is already a header on the request by the time it is executed. A consumer wanting a credential
 * that can be replaced, decorated or refreshed builds a
 * [org.sempods.client.core.SempodsSession] instead.
 */
class SempodsHttpTransport @JvmOverloads constructor(
  timeouts: SempodsHttpTimeouts = SempodsHttpTimeouts(),
  guard: SempodsOutboundGuard? = null,
) {

  private val transport: SempodsTransport = SempodsTransport.builder()
    .timeouts(timeouts)
    .guard(guard)
    .build()

  /** Shared by the clients above so a response is parsed the same way wherever it is read. */
  val objectMapper: ObjectMapper = ObjectMapper()

  /**
   * Starts a request: the bearer when there is one, and the caller's W3C trace when one is bound.
   * Each request gets its own span id; the trace id is what carries.
   *
   * **[token] is nullable on purpose.** A pod serves its public contexts to an unauthenticated
   * caller (`SempodsBaseEndpoint.authenticate` treats a missing bearer as anonymous), and reading
   * public data is the first thing anyone does with a pod they do not own. Omitting the header is
   * therefore a supported mode, not a degraded one; what a caller may do without a token is the
   * server's decision, expressed as a 401 or a filtered answer.
   *
   * Outside a request no trace is bound and no trace header is set.
   */
  @JvmOverloads
  fun newRequest(uri: URI, token: String? = null): SempodsRequest.Builder {
    val builder = SempodsRequest.to(uri)
    token?.let { builder.setHeader("Authorization", "Bearer $it") }
    TraceContextHolder.get()?.let { traceContext ->
      builder.setHeader(TraceContext.TRACEPARENT, traceContext.newChild().toHeader())
    }
    return builder
  }

  fun send(request: SempodsRequest): SempodsResponse<String> =
    asLegacyFailure { transport.execute(request, SempodsBodyHandler { it.bodyText() }) }

  fun sendBytes(request: SempodsRequest): SempodsResponse<ByteArray> =
    asLegacyFailure { transport.execute(request, SempodsBodyHandler { it.bodyBytes() }) }

  /**
   * Streams a response body through [read] and closes it afterwards, whatever [read] does.
   *
   * Scoped rather than returned so the body cannot outlive the call that owns it: an unclosed
   * response strands a pooled connection, and a signature that hands one out makes every call site
   * responsible for remembering. The failure path is served by
   * [SempodsStreamedResponse.bodyTextCapped].
   */
  fun <T> sendStreaming(request: SempodsRequest, read: (SempodsStreamedResponse) -> T): T =
    asLegacyFailure { transport.execute(request, SempodsBodyHandler { read(it) }).body }

  /**
   * Unwraps the core's one transport failure back into the several shapes this surface's callers
   * classify on.
   *
   * The core made them one type on purpose — a refused address, an exhausted budget and a timeout
   * used to arrive as three unrelated things here, and telling them apart meant matching on message
   * text. But the callers above were written against those three, and two of them decide real
   * behaviour on the distinction: `PodFailures.isRetryablePodFailure` walks for a rate-limit or SSRF
   * cause, and `McpEndpoint.runTool` catches [SempodsClientException] to keep this process's URLs
   * out of a message that goes to a language model. Migrating them onto the core's hierarchy is
   * [#152](https://github.com/sempods/sempods-kotlin/issues/152); until then this hands each caller
   * back what it was written for.
   */
  private fun <T> asLegacyFailure(block: () -> T): T =
    try {
      block()
    } catch (e: SempodsTransportException) {
      throw when (val cause = e.cause) {
        // Before `SsrfBlockedException`, which is an `IOException` too and must not fall through.
        is SsrfBlockedException -> SempodsClientException(e.message.orEmpty(), cause = cause)
        is SempodsRateLimitedException -> cause
        is IOException -> cause
        else -> SempodsClientException(e.message.orEmpty(), cause = cause)
      }
    }

  /**
   * The one shape a refused request takes on this surface. The server's own body travels with it:
   * a pod answers scope refusals and precondition failures with a reason, and swallowing it turns
   * every diagnosis into a server-log expedition.
   *
   * It travels twice — inside the message, which is written for a log line and therefore says
   * which call failed, and on its own in [SempodsClientException.responseBody], for a caller that
   * forwards the reason onward and must not forward this process's URLs with it.
   */
  fun failure(method: String, url: URI, statusCode: Int, body: String?): SempodsClientException =
    SempodsClientException(
      "$method $url failed: HTTP $statusCode — $body",
      statusCode = statusCode,
      responseBody = body,
    )

  /**
   * Reads a field the route's contract guarantees. A missing one is a broken contract rather than
   * an empty answer, so it fails here instead of becoming a null further up.
   */
  fun requiredText(root: JsonNode, field: String, url: URI, body: String): String =
    root.path(field).takeIf { it.isTextual }?.asText()
      ?: throw SempodsClientException("Response from $url missing '$field': ${body.take(200)}")

  /** Path-segment encoding: `URLEncoder` plus the `+`→`%20` correction it does not make. */
  fun urlPathSegment(value: String): String = SempodsUrlEncoding.pathSegment(value)

  /** A base URL with a guaranteed trailing slash, so [URI.resolve] appends instead of replacing. */
  fun baseWithTrailingSlash(baseUrl: URI): URI = URI(baseUrl.toString().trimEnd('/') + "/")
}
