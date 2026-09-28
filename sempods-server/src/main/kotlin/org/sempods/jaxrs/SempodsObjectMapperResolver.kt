package org.sempods.jaxrs

import org.sempods.commons.jaxrs.ObjectMapperResolver
import org.sempods.commons.json.JsonMappers

/**
 * The mapper Jersey serialises the pod server's responses with.
 *
 * [JsonMappers.default] rather than the Mongo-flavoured variant: no `ObjectId` reaches this
 * server's wire — they never leave the DAO layer — so `org.bson` stays off the JSON path entirely.
 *
 * Registered by [org.sempods.SempodsModule]. Leaving it out fails silently, as
 * [ObjectMapperResolver] says.
 */
class SempodsObjectMapperResolver : ObjectMapperResolver(JsonMappers.default())
