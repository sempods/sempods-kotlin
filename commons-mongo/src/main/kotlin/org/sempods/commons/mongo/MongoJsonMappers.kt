package org.sempods.commons.mongo

import com.fasterxml.jackson.databind.ObjectMapper
import org.sempods.commons.json.JsonMappers

/**
 * [JsonMappers.default] plus the BSON [org.bson.types.ObjectId] codecs.
 *
 * Written as an extension so the call reads `JsonMappers.withMongo()`: there is one place the
 * project's mappers come from, and the Mongo flavour is simply the variant that exists where the
 * driver is on the classpath. That is the layering statement made into an import — a module
 * without `commons-mongo` cannot name this at all.
 *
 * Whoever persists with Mongo *and* speaks JSON takes this one, because a document id then has to
 * survive the round trip through an API response or a search index. `:sempods-server` does not: its
 * `ObjectId`s never leave the DAO layer, so it uses the plain default and keeps `org.bson` out of
 * its JSON path entirely.
 */
fun JsonMappers.withMongo(): ObjectMapper = WITH_MONGO

/** A fresh Mongo-flavoured mapper, for callers that need to change it. */
fun JsonMappers.newWithMongo(): ObjectMapper = JsonMappers.newDefault().registerModule(ObjectIdModule())

/**
 * The shared Mongo-flavoured mapper. Shared for the same reason as [JsonMappers.default] — take
 * [ObjectMapper.copy] or [newWithMongo] before changing anything on it.
 */
private val WITH_MONGO: ObjectMapper = JsonMappers.newDefault().registerModule(ObjectIdModule())
