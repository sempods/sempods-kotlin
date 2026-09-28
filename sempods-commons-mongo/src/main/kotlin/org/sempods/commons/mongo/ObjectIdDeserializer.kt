package org.sempods.commons.mongo

import org.bson.types.ObjectId
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

class ObjectIdDeserializer: ValueDeserializer<ObjectId>() {

  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ObjectId {
    return ObjectId(p.string)
  }
}
