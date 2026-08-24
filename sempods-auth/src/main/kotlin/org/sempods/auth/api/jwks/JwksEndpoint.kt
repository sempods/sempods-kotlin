package org.sempods.auth.api.jwks

import org.sempods.auth.login.JwtIssuer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.jwksEndpoint(jwtIssuer: JwtIssuer) {
  routing {
    // TODO: add Cache-Control headers (e.g. max-age=3600) so pods don't fetch the JWKS
    //   on every request. Requires coordination with key rotation: the cache TTL must be
    //   shorter than the key rotation overlap window.
    get("/.well-known/jwks.json") {
      call.respondText(jwtIssuer.jwksJson, ContentType.Application.Json)
    }
  }
}
