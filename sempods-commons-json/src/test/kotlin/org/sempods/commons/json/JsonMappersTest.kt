package org.sempods.commons.json

import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the mapper configuration.
 *
 * Every assertion here corresponds to a wire-format property something in the repo already relies
 * on, so a change to [JsonMappers.default] that breaks one of them is a change to what clients
 * and stored documents look like — not a refactoring.
 */
class JsonMappersTest {

  private data class Sample(
    val name: String,
    val at: Instant? = null,
    val tags: Set<String> = emptySet(),
  )

  private class WithPrivateField(private val secret: String = "s") {
    @Suppress("unused")
    fun getComputed(): String = "not serialised"
  }

  private class WithBodyVal {
    val name: String = "unset"
  }

  @Test
  fun `default is one shared instance`() {
    assertSame(JsonMappers.default(), JsonMappers.default())
  }

  @Test
  fun `a rebuilt mapper leaves the shared one as it was`() {
    val strict = JsonMappers.default().rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    assertTrue(strict !== JsonMappers.default())
    assertTrue(
      JsonMappers.default()
        .deserializationConfig()
        .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .not(),
      "rebuilding must not touch the shared mapper",
    )
  }

  @Test
  fun `unknown properties are ignored on read`() {
    val sample = JsonMappers.default()
      .readValue("""{"name":"a","somethingNew":42}""", Sample::class.java)
    assertEquals("a", sample.name)
  }

  @Test
  fun `unknown properties can be rejected on a rebuilt mapper`() {
    val strict = JsonMappers.default().rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    assertFailsWith<Exception> { strict.readValue("""{"name":"a","typo":1}""", Sample::class.java) }
  }

  @Test
  fun `instants are written as ISO-8601 rather than as timestamps`() {
    val json = JsonMappers.default().writeValueAsString(
      Sample(name = "a", at = Instant.parse("2026-08-09T10:15:30Z")),
    )
    assertTrue(json.contains(""""at":"2026-08-09T10:15:30Z""""), json)
  }

  @Test
  fun `instants round-trip`() {
    val mapper = JsonMappers.default()
    val original = Sample(name = "a", at = Instant.parse("2026-08-09T10:15:30.123Z"))
    assertEquals(original, mapper.readValue(mapper.writeValueAsString(original), Sample::class.java))
  }

  @Test
  fun `kotlin defaults apply to absent properties`() {
    val sample = JsonMappers.default().readValue("""{"name":"a"}""", Sample::class.java)
    assertNull(sample.at)
    assertEquals(emptySet(), sample.tags)
  }

  @Test
  fun `fields are serialised, getters are not`() {
    val json = JsonMappers.default().writeValueAsString(WithPrivateField())
    assertTrue(json.contains(""""secret":"s""""), json)
    assertTrue(!json.contains("computed"), json)
  }

  @Test
  fun `a val outside the constructor is read into its field`() {
    val read = JsonMappers.default().readValue("""{"name":"a"}""", WithBodyVal::class.java)
    assertEquals("a", read.name)
  }

  @Test
  fun `an object without any visible property serialises to an empty object`() {
    // FAIL_ON_EMPTY_BEANS off — the alternative is an exception deep inside an unrelated response.
    assertEquals("{}", JsonMappers.default().writeValueAsString(object {}))
  }
}
