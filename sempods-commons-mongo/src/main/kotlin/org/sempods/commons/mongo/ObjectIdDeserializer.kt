package org.sempods.commons.mongo

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import org.bson.types.ObjectId

class ObjectIdDeserializer: JsonDeserializer<ObjectId>() {

  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ObjectId {
    return ObjectId(p.text)
  }
}
