package org.sempods.commons.jaxrs

import org.sempods.commons.logging.LogSafeText

/**
 * A request path, made fit for a log line: declared credentials replaced, control characters
 * escaped.
 *
 * Both halves exist because the path of a request refused *during* matching is attacker-controlled
 * and arrives **decoded** — `UriInfo.getPath()` is `getPath(true)`, so `%E2%80%A8` is a real
 * U+2028 by the time anything logs it.
 *
 * - **Escaping** is [LogSafeText]'s, which is where the rule and its reasoning live: a forged log
 *   line is not something to hold on someone else's URI compliance, and a path is not the only
 *   caller-supplied thing this tree logs.
 * - **Redaction** replaces the segment after each declared marker — see [SecretPathSegment].
 *
 * Escaping runs last, so a `<redacted>` marker cannot be forged by a path that contains the word.
 */
object RequestPathForLog {

  private const val REDACTED = "<redacted>"

  /**
   * @param secretAfter the literal segments whose successor is a credential. Empty for a caller
   *   that has no such route, which is every caller but one.
   */
  fun of(path: String, secretAfter: Set<String> = emptySet()): String =
    LogSafeText.of(redactSecretSegments(path, secretAfter))

  private fun redactSecretSegments(path: String, secretAfter: Set<String>): String {
    if (secretAfter.isEmpty()) return path
    val segments = path.split('/')
    if (segments.none { it in secretAfter }) return path
    return segments
      .mapIndexed { index, segment -> if (index > 0 && segments[index - 1] in secretAfter) REDACTED else segment }
      .joinToString("/")
  }
}
