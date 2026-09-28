package org.sempods.commons.jaxrs

import jakarta.ws.rs.ext.ContextResolver
import tools.jackson.databind.json.JsonMapper

/**
 * Hands the JAX-RS JSON provider the [JsonMapper] an application serialises with.
 *
 * A seam, so that [JaxRsServerModule] picks no mapper and puts no database driver on the path of
 * every module that wants an HTTP server. Each application registers its own subclass as a JAX-RS
 * provider: the Mongo-flavoured mapper where the DTOs carry `ObjectId`s, and
 * `JsonMappers.default()` where the wire format has none, which keeps `org.bson` off the JSON path.
 *
 * Registering *nothing* is not the same as registering the default: Jersey would then fall back
 * to a stock `JsonMapper`, which serialises getters and would change the wire format of every
 * response. The provider asks for a `ContextResolver<JsonMapper>` by that exact type, so a resolver
 * for `ObjectMapper` would go unused just as silently.
 */
open class ObjectMapperResolver(
  private val objectMapper: JsonMapper,
) : ContextResolver<JsonMapper> {

  final override fun getContext(type: Class<*>?): JsonMapper = objectMapper
}
