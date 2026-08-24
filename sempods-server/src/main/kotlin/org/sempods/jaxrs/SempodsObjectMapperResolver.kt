package org.sempods.jaxrs

import org.sempods.commons.jaxrs.ObjectMapperResolver
import org.sempods.commons.json.JsonMappers

/**
 * The mapper Jersey serialises the pod server's responses with.
 *
 * [JsonMappers.default] rather than the Mongo-flavoured variant: no `ObjectId` reaches this
 * server's wire — they never leave the DAO layer — so `org.bson` stays off the JSON path entirely.
 *
 * **Registering nothing would not be the same as registering the default.** Without a
 * `ContextResolver<ObjectMapper>` Jersey falls back to a stock `ObjectMapper`, which reads getters
 * instead of fields and would silently change the wire format of every response. This resolver used
 * to be registered by an application framework's endpoint binding; it is registered by
 * [org.sempods.SempodsModule] now.
 */
class SempodsObjectMapperResolver : ObjectMapperResolver(JsonMappers.default())
