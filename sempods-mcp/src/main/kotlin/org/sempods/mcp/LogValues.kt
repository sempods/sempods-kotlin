package org.sempods.mcp

/**
 * A value this service did not author, made safe to put in a log line.
 *
 * The console pattern ends each event with `%msg%n` and writes the message through unchanged
 * (`commons/src/main/resources/org/sempods/commons/logging/logback-base.xml`), so a newline inside
 * an interpolated value produces what reads as a second log entry — with a timestamp, a level and
 * a message the value's author chose. Forged operational history is worth more to an attacker than
 * it looks: it is what an incident gets reconstructed from.
 *
 * Two sources reach the log this way and neither is trustworthy. **Request parameters**: anyone who
 * can register a client can drive `/authorize` and the OIDC callback with values of their choosing.
 * **Pod responses**: a connected pod's metadata, error bodies and the URIs it advertises are all
 * text from another host — and a rejected value often travels inside an exception message, which is
 * the same problem one indirection further out.
 *
 * Control characters become `.` rather than being dropped, so the length still reflects what
 * arrived, and the result is capped: a diagnostic value is worth a line, not a screen. A null
 * reads as `none`, so a caller never has to choose between a safe log and a complete one.
 *
 * `internal` so both surfaces can reach it, and so a test can exercise it directly — driving it
 * through HTTP would only show that the request survived, not what reached the log.
 */
internal fun forLog(value: String?): String {
  if (value == null) return "none"
  val capped = value.take(LOG_VALUE_MAX).map { if (it.isISOControl()) '.' else it }.joinToString("")
  return if (value.length > LOG_VALUE_MAX) "$capped…" else capped
}

/** Long enough for any real client id, OAuth error code or endpoint URI; short enough not to flood. */
private const val LOG_VALUE_MAX = 200
