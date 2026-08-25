package org.sempods.commons.utils

/**
 * The five characters that turn interpolated text into markup, escaped once for the whole tree.
 *
 * Server-rendered HTML is a small surface here — a login page, a consent screen, a connection UI,
 * a WebID profile — but it existed four times over, in four files, with one copy escaping only
 * four of the five and letting `'` through. Which copy a page reached for decided whether a
 * single-quoted attribute could be broken out of, and nothing about the page said so.
 *
 * `'` as `&#39;` rather than `&apos;` because the named form is XML/HTML5 and not HTML 4.
 */
fun StringBuilder.appendEscapedHtml(value: String): StringBuilder {
  for (char in value) {
    when (char) {
      '&' -> append("&amp;")
      '<' -> append("&lt;")
      '>' -> append("&gt;")
      '"' -> append("&quot;")
      '\'' -> append("&#39;")
      else -> append(char)
    }
  }
  return this
}

/** [appendEscapedHtml] for a caller that wants the string rather than the builder. */
fun String.escapeHtml(): String = StringBuilder(length).appendEscapedHtml(this).toString()
