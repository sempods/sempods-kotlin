package org.sempods.commons.mongo

import com.fasterxml.jackson.databind.module.SimpleModule
import org.bson.types.ObjectId

/** Serialises a BSON [ObjectId] as its hex string, and reads one back from it. */
class ObjectIdModule : SimpleModule() {

  init {
    addSerializer(ObjectId::class.java, ObjectIdSerializer())
    addDeserializer(ObjectId::class.java, ObjectIdDeserializer())
  }
}
