package org.sempods.commons.logging

/**
 * Caller-supplied text, made fit for a log line: every control character rendered as its escape, so
 * a line stays one line and stays what it says it is.
 *
 * **The rule this serves.** A log line is not a place to interpolate what somebody else wrote —
 * a value carrying a newline ends the line early and writes the next one itself, and a forged line
 * is indistinguishable from a real one afterwards. `docs/logging.md` §"Three rules" states it for
 * the request path, and says the same is owed by anything else that logs caller-supplied text: a
 * form parameter, a header value, a client identifier.
 *
 * **Not conditional on a container being strict.** Jetty answers 400 to an ASCII control character
 * in a URI, so a path never carries one behind it — but a form body carries whatever it likes, and
 * the Unicode line and paragraph separators pass every URI check there is while plenty of log
 * viewers and JSON tooling treat U+2028 as a line break. These modules are libraries; the next
 * container may be stricter or looser, and this must not depend on which.
 *
 * `isISOControl` covers C0 and C1, U+0085 (NEL) with them; the two Unicode separators are named
 * because they are neither control characters nor whitespace to `Character`.
 */
object LogSafeText {

  fun of(text: String): String {
    if (text.none(::mustEscape)) return text
    return buildString(text.length) {
      text.forEach { character ->
        if (mustEscape(character)) append("\\u%04x".format(character.code)) else append(character)
      }
    }
  }

  private fun mustEscape(character: Char): Boolean =
    character.isISOControl() || character == LINE_SEPARATOR || character == PARAGRAPH_SEPARATOR

  private const val LINE_SEPARATOR = '\u2028'
  private const val PARAGRAPH_SEPARATOR = '\u2029'
}
