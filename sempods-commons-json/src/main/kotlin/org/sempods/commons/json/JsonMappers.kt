package org.sempods.commons.json

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * The project's JSON configuration, in one place.
 *
 * The BSON `ObjectId` codecs the project's earlier mapper also registered are **not** here: they
 * need `org.bson` and live in the Mongo-flavoured mapper instead, so that a consumer serialising
 * plain JSON does not inherit a database driver.
 *
 * The configuration is the contract, and each part of it is load-bearing:
 *
 * - **fields only, no getters/setters/creators.** What is serialised is the object's state, not
 *   whatever its accessors happen to compute. Renaming a private field is therefore a wire change.
 * - **unknown properties ignored on read.** A newer client may send fields an older server does
 *   not know. Where that is the wrong trade — an authorization-relevant body, where a typo'd field
 *   silently becoming "not given" is a fail-open — the call site takes a [copy] and enables
 *   [DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES] on it.
 * - **dates as ISO-8601, not timestamps**, via [JavaTimeModule].
 * - **the Kotlin module**, so that `data class` constructors, nullability and defaults survive
 *   deserialisation.
 */
object JsonMappers {

  /**
   * The shared default mapper.
   *
   * One instance per process: `ObjectMapper` is thread-safe for reading and writing and caches its
   * serialisers, so handing out copies would only lose that cache. It is shared, which means it
   * must not be reconfigured — a caller that needs a different setting takes
   * [ObjectMapper.copy] or [newDefault] and changes that.
   */
  fun default(): ObjectMapper = DEFAULT

  /** A fresh mapper with the default configuration, for callers that need to change it. */
  fun newDefault(): ObjectMapper = ObjectMapper()
    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
    .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.NONE)
    .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
    .setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE)
    .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .registerModule(JavaTimeModule())
    .registerKotlinModule()

  private val DEFAULT: ObjectMapper = newDefault()
}
