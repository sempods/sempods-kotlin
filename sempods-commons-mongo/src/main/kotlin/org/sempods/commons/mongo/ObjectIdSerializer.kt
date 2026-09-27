package org.sempods.commons.mongo

import org.bson.types.ObjectId
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer

class ObjectIdSerializer: ValueSerializer<ObjectId>() {

  override fun serialize(value: ObjectId, gen: JsonGenerator, ctxt: SerializationContext) {
    gen.writeString(value.toString())
  }
}
