package org.sempods.commons.mongo

import org.bson.types.ObjectId
import tools.jackson.databind.module.SimpleModule

/** Serialises a BSON [ObjectId] as its hex string, and reads one back from it. */
class ObjectIdModule : SimpleModule() {

  init {
    addSerializer(ObjectId::class.java, ObjectIdSerializer())
    addDeserializer(ObjectId::class.java, ObjectIdDeserializer())
  }
}
