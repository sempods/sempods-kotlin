package org.sempods.client.core

import java.util.Locale
import java.util.TreeMap

/**
 * The headers of one message: case-insensitive by field name, and **multi-valued**.
 *
 * Both halves are the contract rather than a convenience. RFC 9110 §5.1 makes field names
 * case-insensitive, and a lookup that compares them literally works against every server anyone
 * tests with and fails against the one that spells `ETag` as `Etag`. Repetition is the sharper one:
 * `Vary`, `Link`, `Set-Cookie` and `WWW-Authenticate` all arrive more than once, and a response
 * type that answers with the first value silently discards the rest — which is how a client comes
 * to believe a server offered one authentication scheme when it offered two.
 *
 * Iteration order of [names] is the order the fields arrived in, so a caller that forwards a
 * response onward sends what it received.
 */
class SempodsHeaders internal constructor(entries: List<Pair<String, String>>) {

  private val byName: Map<String, List<String>> =
    TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER).apply {
      entries.forEach { (name, value) ->
        merge(name, listOf(value)) { existing, added -> existing + added }
      }
    }

  private val declaredNames: List<String> = entries.map { it.first }.distinctBy { it.lowercase(Locale.ROOT) }

  /** The first value of [name], or `null` when the field is absent. */
  fun first(name: String): String? = byName[name]?.firstOrNull()

  /** Every value of [name] in arrival order; empty when the field is absent. */
  fun all(name: String): List<String> = byName[name] ?: emptyList()

  /** Whether [name] is present at all — distinct from a field present with an empty value. */
  operator fun contains(name: String): Boolean = byName.containsKey(name)

  /** Every field name, once each, spelled as it arrived and in arrival order. */
  fun names(): List<String> = declaredNames

  override fun toString(): String =
    declaredNames.joinToString(", ") { "$it: ${all(it).joinToString(", ")}" }

  companion object {

    @JvmField
    val EMPTY: SempodsHeaders = SempodsHeaders(emptyList())

    /**
     * Headers a caller assembles — for a test, or for an adapter that reconstructs a response it
     * received elsewhere. The transport builds its own from the wire.
     */
    @JvmStatic
    fun of(values: Map<String, List<String>>): SempodsHeaders =
      SempodsHeaders(values.flatMap { (name, list) -> list.map { name to it } })
  }
}
