package org.sempods.auth.api.login

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * `GET /.well-known/apple-developer-domain-association.txt`
 *
 * A fallback, unused by the deployments this repository runs.
 *
 * Apple's portal used to withhold a Return URL until it had fetched this file from the domain the
 * URL points at. It no longer asks: `id.sempods.org` was configured in August 2026 without one,
 * and web sign-ins set up years earlier serve no such file either — so this is not a token that
 * merely stopped being re-checked. The route stays because the requirement is Apple's to
 * reinstate, and serving one string is a smaller thing than rediscovering where it went.
 *
 * Served by this service rather than by the reverse proxy because the edge forwards all of
 * `id.sempods.org` here; there is no other place the file could come from.
 *
 * @param content `null` when `APPLE_DOMAIN_ASSOCIATION` is unset — the normal case, and the route
 *   is then not registered at all, so the path 404s like any other unknown one instead of
 *   answering with an empty body that Apple would read as a failed verification.
 */
fun Application.appleDomainAssociationEndpoint(content: String?) {
  val body = content?.takeIf { it.isNotBlank() } ?: return
  routing {
    get("/.well-known/apple-developer-domain-association.txt") {
      call.respondText(body, ContentType.Text.Plain)
    }
  }
}
