package org.sempods.mcp

import org.sempods.commons.logging.LogSafeText

/**
 * A value this service did not author, made safe to put in a log line.
 *
 * The shared console pattern replaces line terminators in every message, but this module is
 * published: an embedder brings its own logging configuration, and under an ordinary `%msg`
 * pattern a newline inside an interpolated value produces what reads as a second log entry — with
 * a timestamp, a level and a message the value's author chose. Forged operational history is worth
 * more to an attacker than it looks: it is what an incident gets reconstructed from. `docs/logging.md`
 * §"Three rules" is where that division sits.
 *
 * The escaping is [LogSafeText]'s. What this adds is the part that is this service's own: a null
 * reads as `none`, so a caller never has to choose between a safe log and a complete one, and the
 * value is capped — a diagnostic value is worth a line, not a screen. Capped before escaping, so
 * the limit counts what arrived rather than how much of it needed an escape.
 *
 * Two sources reach the log this way and neither is trustworthy. **Request parameters**: anyone who
 * can register a client can drive `/authorize` and the OIDC callback with values of their choosing.
 * **Pod responses**: a connected pod's metadata, error bodies and the URIs it advertises are all
 * text from another host — and a rejected value often travels inside an exception message, which is
 * the same problem one indirection further out.
 *
 * `internal` so both surfaces can reach it, and so a test can exercise it directly — driving it
 * through HTTP would only show that the request survived, not what reached the log.
 */
internal fun forLog(value: String?): String {
  if (value == null) return "none"
  val capped = LogSafeText.of(value.take(LOG_VALUE_MAX))
  return if (value.length > LOG_VALUE_MAX) "$capped…" else capped
}

/** Long enough for any real client id, OAuth error code or endpoint URI; short enough not to flood. */
private const val LOG_VALUE_MAX = 200
