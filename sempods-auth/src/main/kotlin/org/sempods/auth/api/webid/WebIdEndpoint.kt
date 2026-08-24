package org.sempods.auth.api.webid

import org.sempods.auth.SempodsAuthConfig
import org.sempods.auth.persist.WebIdProfileDao
import org.sempods.auth.webid.WebIdDocument
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.webIdEndpoint(
  webIdProfileDao: WebIdProfileDao,
  config: SempodsAuthConfig,
) {
  routing {

    // EMAIL namespace: id.sempods.org/e/<sha256(normalize(email))>
    get("/e/{hash}") {
      val hash = call.parameters["hash"]
        ?: return@get call.respondText("missing hash", status = HttpStatusCode.BadRequest)
      serveWebId("${config.idBaseUrl}/e/$hash", webIdProfileDao, call)
    }

    // OIDC namespace: id.sempods.org/oidc/<sha256(normalize(iss+":"+sub))>
    get("/oidc/{hash}") {
      val hash = call.parameters["hash"]
        ?: return@get call.respondText("missing hash", status = HttpStatusCode.BadRequest)
      serveWebId("${config.idBaseUrl}/oidc/$hash", webIdProfileDao, call)
    }
  }
}

private suspend fun serveWebId(uri: String, dao: WebIdProfileDao, call: ApplicationCall) {
  val profile = dao.findByUri(uri)
    ?: return call.respondText("not found", status = HttpStatusCode.NotFound)

  val accept = call.request.header("Accept") ?: ""
  when {
    "text/turtle" in accept ->
      call.respondText(WebIdDocument.toTurtle(profile), ContentType.parse("text/turtle; charset=UTF-8"))

    "application/ld+json" in accept ->
      call.respondText(WebIdDocument.toJsonLd(profile), ContentType.parse("application/ld+json; charset=UTF-8"))

    else ->
      call.respondText(WebIdDocument.toHtml(profile), ContentType.Text.Html)
  }
}
