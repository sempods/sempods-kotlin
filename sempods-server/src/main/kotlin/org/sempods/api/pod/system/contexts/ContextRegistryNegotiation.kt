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
 * Which representation a registry request gets.
 *
 * Jersey has already answered a request that accepts none of the three with `406` during matching,
 * which is what `SPS-CTX-037` asks for on `PUT`: the method never runs, so nothing is created. This
 * only picks the writer, and a request that names nothing gets JSON-LD.
 *
 * Deliberately not `GraphResultNegotiation`: that one serves `_system/find` and the SPARQL graph
 * results, where `application/json` is *not* accepted and the refusal is documented behaviour
 * (`docs/concepts/graph-retrieval.md`). Teaching it this alias would change that surface.
 */
internal object ContextRegistryNegotiation {

  fun select(acceptable: List<MediaType>): RegistryFormat {
    acceptable.forEach { accepted ->
      RegistryFormat.entries.forEach { format ->
        if (accepted.isCompatible(MediaType.valueOf(format.contentType))) return format
      }
    }
    return RegistryFormat.JSON_LD
  }
}
