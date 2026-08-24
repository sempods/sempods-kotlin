package org.sempods.commons.mongo

import org.bson.Document
import java.time.Instant
import java.util.Date

/**
 * Mapping between a domain object and a BSON [Document], for a DAO that talks to the driver
 * directly instead of going through an object mapper.
 *
 * Two conventions run through all of it, and both are load-bearing rather than stylistic:
 *
 * **A field that carries no information is not written.** `null` is omitted, and so is an empty
 * collection — not stored as BSON `null` or as `[]`. A reader cannot tell the three apart anyway
 * (`Filters.eq(field, null)` matches a missing field, and an absent array reads back as an empty
 * one), so writing them only grows every document by fields that say nothing.
 *
 * **Instants carry milliseconds, not nanoseconds.** BSON's date type is a 64-bit millisecond
 * count; there is nowhere to put the rest. [putInstant] therefore truncates, and it does so
 * visibly here rather than silently inside the driver, because a round trip is *not* the identity
 * function: `10:15:30.123456789Z` comes back as `10:15:30.123Z`. Anything comparing a stored
 * instant to an in-memory one has to compare truncated values.
 *
 * Both conventions are also, exactly, what Morphia's `PojoCodec` writes today — measured against
 * it by a wire-format test on the mapping side, which is what lets a collection be moved off
 * Morphia onto these helpers without rewriting a single stored document. That parity is the reason
 * the rules are what they are, but they stand on their own: a consumer with no Morphia history gets
 * the smaller document either way.
 */

/** Puts [value] under [key], or omits the field when it is `null`. */
fun Document.putNotNull(key: String, value: Any?): Document = apply {
  if (value != null) put(key, value)
}

/**
 * Puts [value] as a BSON date, truncated to milliseconds; omits the field when it is `null`.
 *
 * Read it back with [getInstant].
 */
fun Document.putInstant(key: String, value: Instant?): Document = apply {
  if (value != null) put(key, Date.from(value))
}

/**
 * Puts [value] as a BSON array, preserving its iteration order; omits the field when it is `null`
 * **or empty**.
 *
 * Order is preserved on the way in because a `LinkedHashSet` or a `List` may mean it. It is not
 * preserved on the way out — [getStringSet] returns a set, and [getStringList] the array's own
 * order.
 */
fun Document.putStrings(key: String, value: Collection<String>?): Document = apply {
  if (!value.isNullOrEmpty()) put(key, ArrayList(value))
}

/** The instant stored under [key], or `null` when the field is absent or BSON `null`. */
fun Document.getInstant(key: String): Instant? = getDate(key)?.toInstant()

/** The strings stored under [key] in the array's own order — empty when the field is absent. */
fun Document.getStringList(key: String): List<String> {
  val raw = get(key) as? List<*> ?: return emptyList()
  return raw.filterIsInstance<String>()
}

/** The strings stored under [key] — empty when the field is absent. */
fun Document.getStringSet(key: String): Set<String> = getStringList(key).toSet()
