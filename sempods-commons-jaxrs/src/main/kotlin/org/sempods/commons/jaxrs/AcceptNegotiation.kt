package org.sempods.commons.jaxrs

import jakarta.ws.rs.core.MediaType

/** One media range of an `Accept` header, as the client wrote it. */
data class MediaRange(
  /** Its type and subtype, wildcards included, with the parameters written before `q`. */
  val mediaType: MediaType,
  /** How much the client wants it: `1.0` where it named no weight, `0.0` where it excluded it. */
  val quality: Double,
)

/**
 * Which representation an `Accept` header asks for, by
 * [RFC 9110 §12.5.1](https://www.rfc-editor.org/rfc/rfc9110#section-12.5.1): the most specific range
 * that names a representation decides its quality, the highest quality wins, and `q=0` excludes.
 *
 * **Why this reads the raw header.** `HttpHeaders.getAcceptableMediaTypes()` hands back parsed media
 * types whose parameter map holds everything the range carried — `q` and the accept extensions after
 * it included, in no particular order. The order is what separates a media type's parameter
 * (`charset`, a JSON-LD `profile`) from an extension, and `application/ld+json;q=1;foo=bar` must not
 * read as a request for a representation carrying `foo`.
 *
 * **A representation is a media type with the parameters it carries.** A caller lists them in the
 * order it prefers them, which is what decides a tie and what a client expressing no preference
 * gets. `null` means nothing on that list is acceptable, and the caller answers `406`.
 *
 * Jersey usually refuses such a request while it matches a method, so this mostly decides between
 * representations one endpoint produces. It does not know about `Accept-Charset` or
 * `Accept-Language`; a route needing those negotiates them itself.
 */
object AcceptNegotiation {

  /** The best of [available] for [header], or `null` when the client accepts none of them. */
  fun select(header: String?, available: List<MediaType>): MediaType? {
    val ranges = parse(header)
    if (ranges.isEmpty()) return available.firstOrNull()
    return available
      .map { representation -> representation to quality(ranges, representation) }
      .filter { (_, quality) -> quality > 0.0 }
      // `maxByOrNull` keeps the first of equal values, so a tie falls to the caller's own order.
      .maxByOrNull { (_, quality) -> quality }
      ?.first
  }

  /** The ranges of [header], in the order they were written; one that names no media type is dropped. */
  fun parse(header: String?): List<MediaRange> = split(header.orEmpty(), ',').mapNotNull { range(it) }

  private fun range(text: String): MediaRange? {
    val parts = split(text, ';')
    val name = parts.firstOrNull().orEmpty()
    val type = name.substringBefore('/').trim()
    val subtype = name.substringAfter('/', "").trim()
    if (type.isEmpty() || subtype.isEmpty()) return null

    var quality = 1.0
    val parameters = linkedMapOf<String, String>()
    for (parameter in parts.drop(1)) {
      val key = parameter.substringBefore('=').trim()
      val value = unquote(parameter.substringAfter('=', "").trim())
      if (key.equals(QUALITY, ignoreCase = true)) {
        quality = value.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0
        // Everything past `q` is an accept extension: it weights the range, and names no
        // representation (RFC 9110 §12.4.2).
        break
      }
      if (key.isNotEmpty()) parameters[key.lowercase()] = value
    }
    return MediaRange(MediaType(type, subtype, parameters), quality)
  }

  /** The quality of the most specific range that names [representation], or `0.0` when none does. */
  private fun quality(ranges: List<MediaRange>, representation: MediaType): Double =
    ranges
      .filter { names(it.mediaType, representation) }
      .minByOrNull { specificity(it.mediaType) }
      ?.quality
      ?: 0.0

  /** Whether [range] names [representation]: its type and subtype, and every parameter it carries. */
  private fun names(range: MediaType, representation: MediaType): Boolean {
    if (!range.isCompatible(representation)) return false
    val carried = representation.parameters.mapKeys { it.key.lowercase() }
    return range.parameters.all { (name, value) -> value.equals(carried[name.lowercase()], ignoreCase = true) }
  }

  /** How narrowly a range names a type: wildcards widen it, parameters narrow it. */
  private fun specificity(range: MediaType): Int {
    val wildcard = when {
      range.isWildcardType -> 2
      range.isWildcardSubtype -> 1
      else -> 0
    }
    return wildcard * PARAMETER_HEADROOM - range.parameters.size
  }

  /** [text] split on [separator], leaving one inside a quoted string alone. */
  private fun split(text: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    text.forEach { character ->
      when {
        escaped -> {
          current.append(character)
          escaped = false
        }
        quoted && character == '\\' -> {
          current.append(character)
          escaped = true
        }
        character == '"' -> {
          current.append(character)
          quoted = !quoted
        }
        character == separator && !quoted -> {
          parts += current.toString()
          current.setLength(0)
        }
        else -> current.append(character)
      }
    }
    parts += current.toString()
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
  }

  private fun unquote(value: String): String =
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
      value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    } else {
      value
    }

  private const val QUALITY = "q"

  /** More parameters than this on one range would have to widen it, which no `Accept` does. */
  private const val PARAMETER_HEADROOM = 8
}
