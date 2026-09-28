package org.sempods.commons.json

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * The project's JSON configuration, in one place.
 *
 * The BSON `ObjectId` codecs are **not** here: they need `org.bson` and live in the
 * Mongo-flavoured mapper, `withMongo()` in `:sempods-commons-mongo`, so that a consumer serialising
 * plain JSON does not inherit a database driver.
 *
 * Jackson 3's defaults hold, except for what follows. Each part is load-bearing:
 *
 * - **fields only, no getters/setters/creators.** What is serialised is the object's state, not
 *   whatever its accessors happen to compute. Renaming a private field is therefore a wire change.
 *   A `final` field is written on read, as `val`s have to be.
 * - **the Kotlin module**, so that `data class` constructors, nullability and defaults survive
 *   deserialisation.
 *
 * Each mapper is one shared instance: a `JsonMapper` is immutable and thread-safe and caches its
 * serialisers. A caller that needs a different setting builds its own from [JsonMapper.rebuild].
 */
object JsonMappers {

  /**
   * The shared default mapper. It ignores unknown properties, so a newer client may send fields an
   * older server does not know.
   */
  fun default(): JsonMapper = DEFAULT

  /**
   * [default], refusing a property the target type does not declare.
   *
   * For a body whose fields decide what a request may do. There a typo'd field silently becoming
   * "not given" is a fail-open.
   */
  fun strict(): JsonMapper = STRICT

  private val DEFAULT: JsonMapper = jacksonMapperBuilder()
    .changeDefaultVisibility { checker ->
      checker
        .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        .withVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.NONE)
        .withVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
        .withVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE)
    }
    .enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
    .build()

  private val STRICT: JsonMapper = DEFAULT.rebuild()
    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .build()
}
