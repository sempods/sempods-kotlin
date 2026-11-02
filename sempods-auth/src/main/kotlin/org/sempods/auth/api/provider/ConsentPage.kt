package org.sempods.auth.api.provider

import org.sempods.commons.utils.appendEscapedHtml

/**
 * The agreement screen: which client asks for which claims about the signed-in person.
 *
 * Same construction and design tokens as `LoginPage`, so the two read as one journey. The form
 * posts to [OpenIdConfiguration.CONSENT_PATH]; its hidden field is the key the request is parked
 * under, which doubles as the form's CSRF token (`ConsentTransactionStore`).
 */
internal object ConsentPage {

  /** What each releasable scope means to the person, in the order the screen lists them. */
  private val DESCRIPTIONS = linkedMapOf(
    "profile" to "Your name and picture",
    "email" to "Your email address",
  )

  /**
   * @param clientHost the host of the client's verified redirect address.
   * @param asked the scopes beyond `openid` the client requested.
   * @param signedInAs the person's display name, or their WebID when they have none.
   */
  fun render(clientHost: String, asked: Set<String>, signedInAs: String, consentKey: String): String =
    buildString {
      append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
      append("<meta charset=\"UTF-8\">\n")
      append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
      append("<title>Share your details?</title>\n")
      append("<style>")
      append(":root{--fg:#1a1a1a;--muted:#6b7280;--line:#e2e5ea;--card:#fbfcfe;--accent:#2d6cdf}")
      append("body{font-family:system-ui,-apple-system,sans-serif;color:var(--fg);")
      append("max-width:22rem;margin:4rem auto;padding:0 1.1rem;line-height:1.55}")
      append("h1{font-size:1.25rem;margin:0 0 .3rem;overflow-wrap:anywhere}")
      append("p{color:var(--muted);font-size:.92rem;margin:0 0 1rem;overflow-wrap:anywhere}")
      append("ul{margin:0 0 1.6rem;padding-left:1.2rem}")
      append(".actions{display:flex;gap:.6rem}")
      append("button{flex:1;font:inherit;font-size:.95rem;padding:.7rem 1.1rem;border-radius:.45rem;")
      append("border:1px solid var(--line);background:var(--card);color:var(--fg);cursor:pointer}")
      append("button.allow{background:var(--accent);border-color:var(--accent);color:#fff}")
      append("</style>\n</head>\n<body>\n")
      append("<h1>")
      appendEscapedHtml(clientHost)
      append(" asks for</h1>\n<ul>\n")
      for ((scope, description) in DESCRIPTIONS) {
        if (scope in asked) append("<li>").append(description).append("</li>\n")
      }
      append("</ul>\n<p>Signed in as ")
      appendEscapedHtml(signedInAs)
      append(". The app learns your WebID either way.</p>\n")
      append("<form method=\"post\" action=\"").append(OpenIdConfiguration.CONSENT_PATH).append("\">\n")
      append("<input type=\"hidden\" name=\"consent\" value=\"")
      appendEscapedHtml(consentKey)
      append("\">\n<div class=\"actions\">\n")
      append("<button type=\"submit\" name=\"decision\" value=\"deny\">Deny</button>\n")
      append("<button type=\"submit\" name=\"decision\" value=\"allow\" class=\"allow\">Allow</button>\n")
      append("</div>\n</form>\n</body>\n</html>")
    }
}
