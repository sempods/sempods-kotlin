package org.sempods.auth.api.provider

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * `POST /consent` — the agreement screen's answer (`AuthorizationCompletion`).
 *
 * Without the form's key the request is refused as forged (403). A key that finds nothing has
 * expired or was answered already (400); the person starts the sign-in again at the client.
 */
fun Application.consentEndpoint(completion: AuthorizationCompletion) {
  routing {
    post(OpenIdConfiguration.CONSENT_PATH) {
      val form = call.receiveParameters()
      val key = form["consent"]?.takeIf { it.isNotBlank() }
        ?: return@post call.respondText("missing consent token", status = HttpStatusCode.Forbidden)
      val allowed = when (form["decision"]) {
        "allow" -> true
        "deny" -> false
        else -> return@post call.respondText("decision must be allow or deny", status = HttpStatusCode.BadRequest)
      }
      if (!completion.afterDecision(call, key, allowed)) {
        call.respondText(
          "This request has expired or was answered already. Start the sign-in again at the app.",
          status = HttpStatusCode.BadRequest,
        )
      }
    }
  }
}
