package org.sempods.commons.datetime

import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal
import java.util.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.commons.logging.LogSafeText

object DateTimeUtil {

  private val logger = KotlinLogging.logger {}

  private val utcTimeZone = TimeZone.getTimeZone("UTC")

  val utcZone: ZoneId = ZoneId.of("UTC")
  val cetZone: ZoneId = ZoneId.of("CET") // germany

  init {
    TimeZone.setDefault(utcTimeZone)
  }

  private fun buildDateFormat(pattern: String): SimpleDateFormat {
    val dateFormat = SimpleDateFormat(pattern)
    dateFormat.timeZone = utcTimeZone
    return dateFormat
  }

  private val iso8601DateFormatRef: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat {
      return buildDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    }
  }

  private val iso8601DateFormat_secondsRef: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { buildDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'") }

  private val iso8601DateFormat_minutesRef: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat {
      return buildDateFormat("yyyy-MM-dd'T'HH:mm'Z'")
    }
  }

  private val dateTimeParsersRef: ThreadLocal<List<DateFormat>> = object : ThreadLocal<List<DateFormat>>() {
    override fun initialValue(): List<DateFormat> {
      val list = LinkedList<DateFormat>()
      list.add(iso8601DateFormatRef.get())
      list.add(iso8601DateFormat_secondsRef.get())
      list.add(iso8601DateFormat_minutesRef.get())
      return list
    }
  }

  fun parseDate(value: Any?): Instant? {
    value ?: return null

    if (value is Instant) {
      return value
    }

    try {
      parseIsoZonedDatetime(value.toString()).let { return it }
    } catch (e: DateTimeParseException) {
    }

    // maybe its an utc timestamp
    try {
      val timestampUtc = value.toString().toLong()
      return Instant.ofEpochMilli(timestampUtc)
    } catch (e: NumberFormatException) {
    }

    logger.warn { "Could not parse date time: '${LogSafeText.of(value.toString())}'" }

    // parse with some iso8601 date formats
    // TODO: still needed?
    for (dateFormat in dateTimeParsersRef.get()) {
      try {
        return dateFormat.parse(value.toString()).toInstant()
      } catch (e: ParseException) {
      }
    }

    throw IllegalArgumentException("Could not parse date time: '$value'")
  }

  fun parseIsoZonedDatetime(datetime: String): Instant {
    return Instant.parse(datetime)
  }

  fun toUtcLocalDateTime(date: Instant): LocalDateTime {
    return LocalDateTime.ofInstant(date, utcZone)
  }

  fun toCetLocalDateTime(date: Instant): LocalDateTime {
    return LocalDateTime.ofInstant(date, cetZone)
  }

  fun parseLocalDate(isoDate: String, zone: ZoneId = utcZone): LocalDate {
    return if ('T' in isoDate) {
      parseIsoZonedDatetime(isoDate).atZone(zone).toLocalDate()
    } else {
      LocalDate.parse(isoDate)
    }
  }

  fun parseTemporal(isoDateStr: String?): Temporal? {
    isoDateStr ?: return null
    return if ('T' in isoDateStr) {
      parseIsoZonedDatetime(isoDateStr)
    } else {
      parseLocalDate(isoDateStr)
    }
  }

  fun isoNow(): Instant {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS)
  }

  fun toInstant(temporal: Temporal?, zone: ZoneId = utcZone): Instant? {
    temporal ?: return null
    if (temporal is Instant) {
      return temporal
    }
    if (temporal is LocalDate) {
      return toInstant(temporal, LocalTime.MIDNIGHT, zone)
    }
    if (temporal is LocalDateTime) {
      return temporal.atZone(zone).toInstant()
    }
    return Instant.from(temporal)
  }

  fun toInstant(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId = cetZone,
  ): Instant {
    return ZonedDateTime.of(LocalDateTime.of(date, time), zone).toInstant()
  }
}
