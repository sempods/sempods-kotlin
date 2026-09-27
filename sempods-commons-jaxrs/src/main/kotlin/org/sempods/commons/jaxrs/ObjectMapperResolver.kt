package org.sempods.commons.jaxrs

import jakarta.ws.rs.ext.ContextResolver
import tools.jackson.databind.json.JsonMapper

/**
 * Hands the JAX-RS JSON provider the [JsonMapper] an application serialises with.
 *
 * A seam rather than a fixed instance: the mapper used to be built inside [JaxRsServerModule] as
 * `JsonMappers.withMongo()`, which put a database driver on the critical path of every module
 * that wanted an HTTP server. Each application now registers its own subclass as a JAX-RS
 * provider — the Mongo-flavoured mapper where the DTOs carry `ObjectId`s; a module whose wire
 * format has none registers `JsonMappers.default()` and keeps `org.bson` off its JSON path
 * entirely.
 *
 * Registering *nothing* is not the same as registering the default: Jersey would then fall back
 * to a stock `JsonMapper`, which reads getters rather than fields and would change the wire
 * format of every response. The provider asks for a `ContextResolver<JsonMapper>` by that exact
 * type, so a resolver for `ObjectMapper` would go unused just as silently.
 */
open class ObjectMapperResolver(
  private val objectMapper: JsonMapper,
) : ContextResolver<JsonMapper> {

  final override fun getContext(type: Class<*>?): JsonMapper = objectMapper
}
