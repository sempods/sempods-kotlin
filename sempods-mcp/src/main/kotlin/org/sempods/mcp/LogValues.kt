package org.sempods.mcp

import org.sempods.commons.logging.LogSafeText

/**
 * A value this service did not author, made safe to put in a log line.
 *
 * The escaping is [LogSafeText]'s, which is where the rule and its reasoning live. What this adds
 * is the part that is this service's own: a null reads as `none`, so a caller never has to choose
 * between a safe log and a complete one, and the value is capped — a diagnostic value is worth a
 * line, not a screen. Capped before escaping, so the limit counts what arrived rather than how
 * much of it needed an escape.
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
