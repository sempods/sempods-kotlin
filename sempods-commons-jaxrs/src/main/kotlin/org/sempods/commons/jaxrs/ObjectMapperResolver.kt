package org.sempods.commons.jaxrs

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.ws.rs.ext.ContextResolver

/**
 * Hands Jersey's Jackson provider the [ObjectMapper] an application serialises with.
 *
 * A seam rather than a fixed instance: the mapper used to be built inside [JaxRsServerModule] as
 * `JsonMappers.withMongo()`, which put a database driver on the critical path of every module
 * that wanted an HTTP server. Each application now registers its own subclass as a JAX-RS
 * provider — the Mongo-flavoured mapper where the DTOs carry `ObjectId`s; a module whose wire
 * format has none registers `JsonMappers.default()` and keeps `org.bson` off its JSON path
 * entirely.
 *
 * Registering *nothing* is not the same as registering the default: Jersey would then fall back
 * to a stock `ObjectMapper`, which reads getters rather than fields and would change the wire
 * format of every response.
 */
open class ObjectMapperResolver(
  private val objectMapper: ObjectMapper,
) : ContextResolver<ObjectMapper> {

  final override fun getContext(type: Class<*>?): ObjectMapper = objectMapper
}
