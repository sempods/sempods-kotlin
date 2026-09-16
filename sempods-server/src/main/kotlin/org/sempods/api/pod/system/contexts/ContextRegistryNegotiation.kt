package org.sempods.api.pod.system.contexts

import jakarta.ws.rs.core.MediaType
import org.sempods.commons.jaxrs.AcceptNegotiation

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
 * Which representation a registry request gets, and in which order this route prefers them.
 *
 * The rule is RFC 9110 §12.5.1 and lives in [AcceptNegotiation], where it is tested on its own: the
 * most specific range that names a representation decides its quality, the highest quality wins,
 * `q=0` excludes, and a parameter written after `q` is an accept extension that names nothing.
 *
 * `null` means the caller accepts none of these, and the route answers `406` — on `PUT` before
 * anything is written, which is what `SPS-CTX-037` needs. Jersey usually refuses such a request
 * while it matches, before any method runs.
 *
 * Deliberately not `GraphResultNegotiation`: that one serves `_system/find` and the SPARQL graph
 * results, where `application/json` is *not* accepted and the refusal is documented behaviour
 * (`docs/concepts/graph-retrieval.md`). Teaching it this alias would change that surface.
 */
internal object ContextRegistryNegotiation {

  /** In the order this route prefers them, each with what its body carries: UTF-8. */
  private val REPRESENTATIONS: List<MediaType> =
    RegistryFormat.entries.map { MediaType.valueOf("${it.contentType};charset=utf-8") }

  fun select(accept: String?): RegistryFormat? {
    val chosen = AcceptNegotiation.select(accept, REPRESENTATIONS) ?: return null
    return RegistryFormat.entries.first { it.contentType.equals("${chosen.type}/${chosen.subtype}", ignoreCase = true) }
  }
}
