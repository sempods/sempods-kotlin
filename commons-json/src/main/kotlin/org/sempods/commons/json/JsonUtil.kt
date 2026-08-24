package org.sempods.commons.json

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Read and write JSON.
 *
 * Jackson's `IOException` family used to be caught here and rethrown wrapped, so it could travel
 * through a Java signature that declared no `throws`. Kotlin has no checked exceptions, so the
 * parse failure now propagates exactly as Jackson threw it — a malformed document is a bug or a
 * bad request, and both are handled far away from the parse itself:
 * `BaseEndpoint.parseJsonParameter` turns it into a 400, `ApiExceptionMapper` into a 500.
 *
 * The `Opt` variants add the second convention: `null` in, `null` out, which is what lets a
 * nullable database column or an absent request body flow through without a guard at every call
 * site.
 */
class JsonUtil(
  val objectMapper: ObjectMapper,
) {

  fun read(json: String): Map<String, Any?> = read(objectMapper, json)

  inline fun <reified T> read(json: String): T = read(json, T::class.java)

  fun <T> read(json: String, type: Class<T>?): T = objectMapper.readValue(json, type)

  fun <T> read(json: String, type: TypeReference<T>): T {
    return checkNotNull(readOpt(objectMapper, json, type))
  }

  fun write(input: Any): String {
    return Companion.write(objectMapper, input)
  }

  inline fun <reified T> appleToPear(input: Any): T = read<T>(write(input))

  companion object {

    val dynamicTypeRef: TypeReference<Map<String, Any?>> = object : TypeReference<Map<String, Any?>>() {}

    val dynamicTypeListRef = object : TypeReference<List<Map<String, Any?>>>() {}

    val listTypeRef = object : TypeReference<List<Any?>>() {}

    val stringType: TypeReference<String> = object : TypeReference<String>() {}

    fun read(objectMapper: ObjectMapper, json: String): Map<String, Any?> {
      return checkNotNull(readOpt(objectMapper, json))
    }

    fun readOpt(objectMapper: ObjectMapper, json: String?): Map<String, Any?>? {
      json ?: return null
      return readOpt(objectMapper, json, dynamicTypeRef)
    }

    fun <T> read(objectMapper: ObjectMapper, json: String?, type: Class<T>?): T? {
      return if (json == null) null else objectMapper.readValue(json, type)
    }

    fun <T> read(objectMapper: ObjectMapper, json: String, type: TypeReference<T>): T {
      return checkNotNull(readOpt(objectMapper, json, type))
    }

    fun <T> readOpt(objectMapper: ObjectMapper, json: String?, type: TypeReference<T>): T? {
      if (json == null) {
        return null
      }
      return if (json.length == 0) {
        if (type == stringType) {
          json as T
        } else {
          null
        }
      } else {
        objectMapper.readValue(json, type)
      }
    }

    fun write(objectMapper: ObjectMapper, input: Any): String {
      return checkNotNull(writeOpt(objectMapper, input))
    }

    fun writeOpt(objectMapper: ObjectMapper, input: Any?): String? {
      input ?: return null
      return objectMapper.writeValueAsString(input)
    }

    fun <T> copy(objectMapper: ObjectMapper, input: T?): T? {
      return if (input == null) null else castOpt(objectMapper, input, object : TypeReference<T>() {})
    }

    fun <T> castOpt(objectMapper: ObjectMapper, input: Any?, type: TypeReference<T>): T? {
      return if (input == null) null else read(objectMapper, write(objectMapper, input), type)
    }

    fun <T> castOpt(objectMapper: ObjectMapper, input: Any?, type: Class<T>): T? {
      return if (input == null) null else read(objectMapper, write(objectMapper, input), type)
    }

    fun toMap(objectMapper: ObjectMapper, input: Any): Map<String, Any?> {
      return read(objectMapper, write(objectMapper, input), dynamicTypeRef)
    }
  }
}
