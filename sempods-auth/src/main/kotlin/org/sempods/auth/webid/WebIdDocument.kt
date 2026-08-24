package org.sempods.auth.webid

import org.sempods.auth.persist.WebIdProfile
import org.sempods.commons.utils.escapeHtml

/**
 * Renders a WebIdProfile as Turtle, JSON-LD, or HTML.
 *
 * v0: profile data only (no public key — key registration comes in v1/DPoP).
 *
 * The email address and OIDC identifiers are never included in the WebID document —
 * they exist only as opaque SHA-256 hashes in the URI path. This prevents
 * reverse-lookup of emails from WebID URIs.
 */
object WebIdDocument {

  fun toTurtle(profile: WebIdProfile): String = buildString {
    appendLine("@prefix foaf: <http://xmlns.com/foaf/0.1/> .")
    appendLine("@prefix sempods: <https://sempods.org/ontology#> .")
    appendLine()
    appendLine("<${profile.id}>")
    append("    a foaf:Person")
    if (profile.displayName.isNotBlank()) {
      appendLine(" ;")
      append("    foaf:name ${profile.displayName.toTurtleLiteral()}")
    }
    for (linked in profile.linkedIdentities) {
      appendLine(" ;")
      append("    sempods:authLookupKey <$linked>")
    }
    appendLine(" .")
  }

  fun toJsonLd(profile: WebIdProfile): String = buildString {
    appendLine("{")
    appendLine("""  "@context": {""")
    appendLine("""    "foaf": "http://xmlns.com/foaf/0.1/",""")
    appendLine("""    "sempods": "https://sempods.org/ontology#"""")
    appendLine("""  },""")
    appendLine("""  "@id": "${profile.id}",""")
    append("""  "@type": "foaf:Person"""")
    if (profile.displayName.isNotBlank()) {
      appendLine(",")
      append("""  "foaf:name": "${profile.displayName.escapeJson()}"""")
    }
    if (profile.linkedIdentities.isNotEmpty()) {
      appendLine(",")
      val links = profile.linkedIdentities.joinToString(", ") { """{"@id": "${it.escapeJson()}"}""" }
      append("""  "sempods:authLookupKey": [$links]""")
    }
    appendLine()
    appendLine("}")
  }

  fun toHtml(profile: WebIdProfile): String {
    val name = profile.displayName.ifBlank { profile.id }
    return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>${name.escapeHtml()}</title>
</head>
<body>
  <h1>${name.escapeHtml()}</h1>
  <p>WebID: <a href="${profile.id.escapeHtml()}">${profile.id.escapeHtml()}</a></p>
</body>
</html>"""
  }

  private fun String.toTurtleLiteral(): String {
    val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
  }

  private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
}
