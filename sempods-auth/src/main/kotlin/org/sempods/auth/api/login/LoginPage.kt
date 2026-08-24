package org.sempods.auth.api.login

import org.sempods.auth.oidc.OidcProviderClient
import org.sempods.commons.utils.appendEscapedHtml

/**
 * The provider chooser shown by `GET /login` when more than one login provider is configured.
 *
 * Rendered here rather than through a template engine: this module has neither one nor static
 * content serving, and a single page is not worth a dependency. Design tokens are the ones the
 * sibling Ktor service already uses for its consent and dashboard screens
 * (`sempods-mcp`'s `WebUiEndpoint` / `AuthEndpoint`) so the two services do not drift into
 * different-looking halves of one login journey. Self-contained inline CSS — no external assets,
 * which also means no third party learns that someone is signing in.
 */
object LoginPage {

  /**
   * @param providers in display order; each renders as a link back to `/login` carrying the same
   *   parameters plus `provider`. Keeping the step a plain GET is what lets the `return_to` cookie,
   *   the state store and the callback routes stay exactly as they are.
   */
  fun render(providers: List<OidcProviderClient>, hrefFor: (OidcProviderClient) -> String): String =
    buildString {
      append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
      append("<meta charset=\"UTF-8\">\n")
      append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
      append("<title>Sign in</title>\n")
      append("<style>")
      append(":root{--fg:#1a1a1a;--muted:#6b7280;--line:#e2e5ea;--card:#fbfcfe;--accent:#2d6cdf}")
      append("body{font-family:system-ui,-apple-system,sans-serif;color:var(--fg);")
      append("max-width:22rem;margin:4rem auto;padding:0 1.1rem;line-height:1.55}")
      append("h1{font-size:1.25rem;margin:0 0 .3rem}")
      append("p{color:var(--muted);font-size:.92rem;margin:0 0 1.6rem}")
      append(".providers{display:flex;flex-direction:column;gap:.6rem}")
      append("a.provider{display:block;text-align:center;text-decoration:none;font-size:.95rem;")
      append("padding:.7rem 1.1rem;border:1px solid var(--line);border-radius:.45rem;")
      append("background:var(--card);color:var(--fg)}")
      append("a.provider:hover{border-color:var(--accent);color:var(--accent)}")
      append("</style>\n</head>\n<body>\n")
      append("<h1>Sign in</h1>\n")
      append("<p>Choose how you want to identify yourself.</p>\n")
      append("<div class=\"providers\">\n")
      for (provider in providers) {
        append("<a class=\"provider\" href=\"")
        appendEscapedHtml(hrefFor(provider))
        append("\">Continue with ")
        appendEscapedHtml(provider.displayName)
        append("</a>\n")
      }
      append("</div>\n</body>\n</html>")
    }
}
