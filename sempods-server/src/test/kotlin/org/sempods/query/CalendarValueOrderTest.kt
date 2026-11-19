package org.sempods.query

import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.eclipse.rdf4j.query.algebra.evaluation.util.ValueComparator
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Collections
import java.util.Random
import kotlin.math.sign
import kotlin.test.assertEquals

class CalendarValueOrderTest {

  @Test
  fun `a mixed calendar sorts every day and timestamp in chronological order`() {
    val quarter = LocalDate.of(2026, 1, 1).datesUntil(LocalDate.of(2026, 4, 1)).toList()
    val calendar = quarter.flatMap { day ->
      listOf(
        Values.literal(day.toString(), XSD.DATE),
        Values.literal("${day}T08:00:00Z", XSD.DATETIME),
        Values.literal("${day}T12:00:00Z", XSD.DATETIME),
      )
    }
    val rows = ArrayList(calendar)
    Collections.shuffle(rows, Random(42))
    rows.sortWith(ValueComparator())

    for (type in listOf(XSD.DATE, XSD.DATETIME)) {
      assertEquals(calendar.filter { it.datatype == type }, rows.filter { it.datatype == type })
    }
  }

  @Test
  fun `reversing the date and timestamp in an ORDER BY comparison reverses its sign`() {
    val day = Values.literal("2026-04-08", XSD.DATE)
    val timestamp = Values.literal("2026-04-08T12:00:00Z", XSD.DATETIME)
    val order = ValueComparator()
    val forward = order.compare(day, timestamp).sign
    val backward = order.compare(timestamp, day).sign

    assertEquals(-forward, backward, "date vs dateTime: $forward, dateTime vs date: $backward")
  }
}
