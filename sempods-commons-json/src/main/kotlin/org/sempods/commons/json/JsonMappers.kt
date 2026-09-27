package org.sempods.commons.json

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * The project's JSON configuration, in one place.
 *
 * The BSON `ObjectId` codecs the project's earlier mapper also registered are **not** here: they
 * need `org.bson` and live in the Mongo-flavoured mapper instead, so that a consumer serialising
 * plain JSON does not inherit a database driver.
 *
 * Jackson 3's defaults hold, except for what follows. Each part is load-bearing:
 *
 * - **fields only, no getters/setters/creators.** What is serialised is the object's state, not
 *   whatever its accessors happen to compute. Renaming a private field is therefore a wire change.
 *   A `final` field is written on read, as `val`s have to be.
 * - **unknown properties ignored on read.** A newer client may send fields an older server does
 *   not know. Where that is the wrong trade — an authorization-relevant body, where a typo'd field
 *   silently becoming "not given" is a fail-open — the call site takes [JsonMapper.rebuild] and
 *   enables [DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES] on it.
 * - **the Kotlin module**, so that `data class` constructors, nullability and defaults survive
 *   deserialisation.
 */
object JsonMappers {

  /**
   * The shared default mapper.
   *
   * One instance per process: a `JsonMapper` is immutable and thread-safe and caches its
   * serialisers. A caller that needs a different setting builds its own from [JsonMapper.rebuild].
   */
  fun default(): JsonMapper = DEFAULT

  private val DEFAULT: JsonMapper = JsonMapper.builder()
    .changeDefaultVisibility { checker ->
      checker
        .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        .withVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.NONE)
        .withVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
        .withVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE)
    }
    .enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .addModule(KotlinModule.Builder().build())
    .build()
}
