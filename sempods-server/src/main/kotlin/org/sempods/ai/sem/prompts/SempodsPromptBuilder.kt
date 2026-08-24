package org.sempods.ai.sem.prompts

import com.google.inject.Inject
import java.time.InstantSource
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.time.ZoneOffset
import java.time.format.TextStyle

class SempodsPromptBuilder {

  @Inject
  private lateinit var instantSource: InstantSource

  private val sb = StringBuilder()

  fun appendLine(line: String) {
    sb.appendLine(line)
  }

  fun appendTimestampBlock() {
    val timestamp = instantSource.instant().truncatedTo(ChronoUnit.SECONDS)
    val currentTimestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp)
    val utcDateTime = timestamp.atOffset(ZoneOffset.UTC)
    val currentDate = utcDateTime.toLocalDate().toString()
    val currentWeekday = utcDateTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val currentWeekdayIso = utcDateTime.dayOfWeek.value
    appendLine(
      """
        Use the server-provided current timestamp as authoritative reference time.
        Current timestamp (UTC): $currentTimestamp
        Current date (UTC): $currentDate
        Current weekday (UTC): $currentWeekday
        Current weekday index (ISO-8601): $currentWeekdayIso (Monday=1, Sunday=7)
        For weekday-relative expressions (for example: "Sunday", "Sonntag"), resolve against the current date/weekday above and choose the next occurrence unless the user explicitly requests a past date.
        Timestamp datatype: xsd:dateTime lexical form (ISO-8601 UTC with Z suffix).
      """.trimIndent()
    )
  }

  override fun toString(): String {
    return sb.toString()
  }
}
