package org.sempods.commons.mongo

import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.sempods.commons.json.JsonMappers
import kotlin.test.assertEquals

class MongoJsonMappersTest {

  private data class Row(val id: ObjectId)

  @Test
  fun `an ObjectId is written as its hex string and read back`() {
    val id = ObjectId("65f0c0ffee0000000000abcd")
    val json = JsonMappers.withMongo().writeValueAsString(Row(id))

    assertEquals("""{"id":"65f0c0ffee0000000000abcd"}""", json)
    assertEquals(Row(id), JsonMappers.withMongo().readValue(json, Row::class.java))
  }
}
