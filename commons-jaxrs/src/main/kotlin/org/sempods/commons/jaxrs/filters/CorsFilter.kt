package org.sempods.commons.jaxrs.filters

import org.sempods.commons.net.BearerAuth
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import java.net.URI

/**
 * The CORS policy: which origins may send credentialed requests, and what a browser is allowed
 * to send and read on the ones that may not.
 *
 * [allowedOrigins] is the credentialed allowlist and arrives as a constructor parameter. It used
 * to be derived here from a registry of registered applications, which is what tied a plain
 * response filter to an application framework's configuration model. A caller that keeps such a
 * registry derives the origins from it and passes the result; a caller with a typed config and an
 * explicit origin list passes that. Values are normalised on the way in, so `https://example.org/`
 * and `https://example.org` are the same entry.
 */
open class CorsFilter(
  allowedOrigins: Set<String>,
) : ContainerResponseFilter {

  private val allowedOrigins: Set<String> = allowedOrigins.map(::normalizeOriginValue).toSet()

  override fun filter(
    requestContext: ContainerRequestContext,
    responseContext: ContainerResponseContext,
  ) {

    val origin = requestContext.getHeaderString("Origin") ?: return
    val normalizedOrigin = normalizeOriginValue(origin)
    val allowCredentialedOrigin = normalizedOrigin in allowedOrigins
    val publicFallback = !allowCredentialedOrigin && (
        isPublicRequestWithoutCredentials(requestContext) ||
            isBearerRequestWithoutCookies(requestContext) ||
            isAuthorizationPreflightWithoutCookies(requestContext)
        )
    if (!allowCredentialedOrigin && !publicFallback) return

    // see: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Sec-Fetch-Mode
    val secFetchMode = requestContext.getHeaderString("Sec-Fetch-Mode")
    if (secFetchMode != null && secFetchMode != "cors") {
      return
    }
    val secFetchSite = requestContext.getHeaderString("Sec-Fetch-Site")
    if (secFetchSite == "same-origin") {
      return
    }

    // Needed by any CORS request (not only for the OPTIONS preflight request)
    if (allowCredentialedOrigin) {
      responseContext.headers.putSingle("Access-Control-Allow-Origin", normalizedOrigin)
      responseContext.headers.putSingle("Access-Control-Allow-Credentials", "true")
    } else {
      responseContext.headers.putSingle("Access-Control-Allow-Origin", "*")
    }
    VaryHeaderUtil.addCommaSeparatedHeaderValue(responseContext, "Vary", "Origin")

    // Browser CORS hides all response headers except a short safelist. Expose the ones MCP
    // clients and OAuth re-auth flows need to read on the client side.
    responseContext.headers.putSingle("Access-Control-Expose-Headers", exposedHeaders)

    if (requestContext.method.equals("OPTIONS", ignoreCase = true)) {

      responseContext.headers.add("Access-Control-Allow-Headers", allowedHeaders)
      responseContext.headers.add("Access-Control-Allow-Methods", allowedMethods)

      // If we are in an option call, but the underlying resource was not found,
      // we need to set the status code to 200, otherwise the browser will fail with an invalid CORS response.
      if (responseContext.status != Response.Status.OK.statusCode) {
        responseContext.status = Response.Status.OK.statusCode
      }

      // Save this preflight response for 1 hour
      responseContext.headers.add("Access-Control-Max-Age", "3600")
    }
  }

  companion object {

    val allowedHeaders =
      listOf(
        "Accept",
        "Authorization",
        "Content-Type",

        // Conditional writes per lod-layer.md: `If-Match` for race-safe update/delete,
        // `If-None-Match: *` for create-only PUT. Without these in the preflight allow-list
        // the browser blocks the actual request with a TypeError before it ever hits the server.
        "If-Match",
        "If-None-Match",

        // W3C Trace Context. Not safelisted, so a browser client that joins the trace — an
        // OpenTelemetry SDK does this by itself — would be preflighted away before
        // `TraceContextFilter` ever saw the header, and its journey would restart here.
        "traceparent",

        HEADER_X_CLIENT_VERSION,

        // MCP (Streamable HTTP transport, spec 2025-06+) sends these on every request/response.
        "MCP-Protocol-Version",
        "MCP-Session-Id",
        "Last-Event-ID",
      )
        .joinToString(", ")

    // Headers the browser client is allowed to READ from responses. `WWW-Authenticate`
    // is needed so MCP/OAuth clients can reach `resource_metadata=` on 401; `MCP-Session-Id`
    // is echoed by the client on subsequent requests. `ETag` and `Location` are required
    // by the WritePolicy flow in apps/chat (and any other SDK consumer of conditional
    // writes): without `ETag` the SDK cannot capture a snapshot validator to send back as
    // `If-Match`, so every update/delete/clear/set against a concurrent-safe pod would be
    // refused as `etag_unavailable`. `Location` lets POST callers distinguish
    // `created` (Location header present, 201) from `already_present` (no Location, 200)
    // per `system-layer.md` §"Acknowledged deviations" #3.
    val exposedHeaders =
      listOf(
        "WWW-Authenticate",
        "MCP-Session-Id",
        "ETag",
        "Location",
        // `TraceContextFilter` echoes the trace it used. Without exposure a browser client
        // cannot read it back, which is the one case where the echo is worth anything: the
        // caller learns which trace to quote in a bug report.
        "traceparent",
      ).joinToString(", ")

    val allowedMethods =
      listOf(
        "DELETE",
        "GET",
        "HEAD",
        "OPTIONS",
        "PATCH",
        "POST",
        "PUT",
      ).joinToString(", ")

    const val HEADER_X_CLIENT_VERSION = "X-Client-Version"
  }
}

/**
 * Reduce an origin to `scheme://authority`, so that a trailing slash or a stray path does not
 * make two spellings of the same origin different entries. Shared with callers that build the
 * allowlist, hence not private.
 */
fun normalizeOriginValue(origin: String): String {
  return try {
    val uri = URI(origin.trim())
    "${uri.scheme}://${uri.authority}".trimEnd('/')
  } catch (_: Exception) {
    origin.trim().trimEnd('/')
  }
}

private fun isPublicRequestWithoutCredentials(requestContext: ContainerRequestContext): Boolean {
  // Credentialed calls must stay on allowlist.
  if (!requestContext.getHeaderString(HttpHeaders.AUTHORIZATION).isNullOrBlank()) return false
  if (!requestContext.getHeaderString(HttpHeaders.COOKIE).isNullOrBlank()) return false

  val requestedHeaders = requestContext
    .getHeaderString("Access-Control-Request-Headers")
    ?.lowercase()
    .orEmpty()
  if ("authorization" in requestedHeaders || "cookie" in requestedHeaders) return false

  return true
}

private fun isBearerRequestWithoutCookies(requestContext: ContainerRequestContext): Boolean {
  // Same well-formedness rule as every other Bearer call site (RFC 7235: scheme is
  // case-insensitive) — see BearerAuth.
  if (BearerAuth.parse(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)) == null) return false
  if (!requestContext.getHeaderString(HttpHeaders.COOKIE).isNullOrBlank()) return false
  return true
}

private fun isAuthorizationPreflightWithoutCookies(requestContext: ContainerRequestContext): Boolean {
  if (!requestContext.method.equals("OPTIONS", ignoreCase = true)) return false
  if (!requestContext.getHeaderString(HttpHeaders.COOKIE).isNullOrBlank()) return false

  val requestedHeaders = requestContext
    .getHeaderString("Access-Control-Request-Headers")
    ?.lowercase()
    ?.split(',')
    ?.map { it.trim() }
    ?.toSet()
    ?: emptySet()
  return "authorization" in requestedHeaders && "cookie" !in requestedHeaders
}
