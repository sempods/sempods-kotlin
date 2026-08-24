package org.sempods.commons.datetime

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals

class DateTimeUtilTest {

  /**
   * A wall-clock time read off a German source is the same wall-clock time on both sides of the
   * daylight-saving switch — what moves is the UTC instant behind it.
   *
   * Asserting the instant as well as the local hour is the point: a zone-less implementation would
   * still round-trip to hour 18, and only the instant tells summer (UTC+2) from winter (UTC+1).
   */
  @Test
  fun `toInstant should follow the daylight-saving shift of the given zone`() {

    val summer = DateTimeUtil.toInstant(
      date = LocalDate.of(2023, 8, 1),
      time = LocalTime.of(18, 0),
    )
    assertEquals(18, summer.atZone(DateTimeUtil.cetZone).hour)
    assertEquals(Instant.parse("2023-08-01T16:00:00Z"), summer)

    val winter = DateTimeUtil.toInstant(
      date = LocalDate.of(2023, 11, 3),
      time = LocalTime.of(18, 0),
    )
    assertEquals(18, winter.atZone(DateTimeUtil.cetZone).hour)
    assertEquals(Instant.parse("2023-11-03T17:00:00Z"), winter)
  }

  @Test
  fun `toInstant should honour an explicit zone`() {
    assertEquals(
      Instant.parse("2023-08-01T18:00:00Z"),
      DateTimeUtil.toInstant(
        date = LocalDate.of(2023, 8, 1),
        time = LocalTime.of(18, 0),
        zone = DateTimeUtil.utcZone,
      ),
    )
  }
}
