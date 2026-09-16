package org.sempods.api.pod.system.contexts

import jakarta.ws.rs.core.MediaType

/** What a registry route answers with, and the media type it says so with. */
internal enum class RegistryFormat(val contentType: String) {

  /** Canonical JSON-LD, and what a caller gets who asks for nothing in particular (`SPS-CTX-031`). */
  JSON_LD("application/ld+json"),

  /** The same RDF, in the default graph (`SPS-CTX-031`). */
  N_QUADS("application/n-quads"),

  /**
   * The JSON envelopes this route answered before the registry became RDF.
   *
   * `SPS-CTX-031` makes `application/json` an alias of JSON-LD and rules these envelopes out, so a
   * pod answering them is not conformant on that media type. They are kept for callers that have
   * not migrated — a client published as `v0.1.0` reads them — and go with
   * [#184](https://github.com/sempods/sempods-kotlin/issues/184), which is also what the
   * `Deprecation` header on such a response points at.
   */
  LEGACY_JSON("application/json"),
}

/**
 * Which representation a registry request gets, by RFC 9110 §12.5.1: the most specific range that
 * matches a representation decides its quality, the highest quality wins, and `q=0` excludes.
 *
 * **Why this is not `Request.selectVariant`.** An `Accept` that pairs a wildcard range with
 * `application/ld+json;q=0` gets JSON-LD out of the container's own selection: the wildcard matches
 * and the exclusion beside it goes unapplied, so the caller is handed the one representation they
 * refused. `PodContextsEndpointHttpTest` pins both halves of the rule.
 *
 * `null` means nothing this route produces is acceptable, and the caller answers `406`. Jersey
 * usually refuses such a request while it matches, before any method runs, which is what
 * `SPS-CTX-037` needs on `PUT`; this covers the request it admits anyway.
 *
 * Deliberately not `GraphResultNegotiation`: that one serves `_system/find` and the SPARQL graph
 * results, where `application/json` is *not* accepted and the refusal is documented behaviour
 * (`docs/concepts/graph-retrieval.md`). Teaching it this alias would change that surface.
 */
internal object ContextRegistryNegotiation {

  /** In the order this route prefers them, which is what a caller expressing no preference gets. */
  fun select(acceptable: List<MediaType>): RegistryFormat? {
    if (acceptable.isEmpty()) return RegistryFormat.JSON_LD
    return RegistryFormat.entries
      .map { format -> format to quality(acceptable, MediaType.valueOf(format.contentType)) }
      .filter { (_, quality) -> quality > 0.0 }
      // `maxByOrNull` keeps the first of equal values, so a tie falls to this route's own order.
      .maxByOrNull { (_, quality) -> quality }
      ?.first
  }

  /** The quality of the most specific range that matches [type], or `0.0` when none does. */
  private fun quality(acceptable: List<MediaType>, type: MediaType): Double {
    val range = acceptable.filter { it.isCompatible(type) }.minByOrNull { specificity(it) } ?: return 0.0
    return range.parameters["q"]?.toDoubleOrNull() ?: 1.0
  }

  /** How narrowly a range names a type: an exact type beats a subtype wildcard, which beats a full wildcard. */
  private fun specificity(range: MediaType): Int = when {
    range.isWildcardType -> 2
    range.isWildcardSubtype -> 1
    else -> 0
  }
}
