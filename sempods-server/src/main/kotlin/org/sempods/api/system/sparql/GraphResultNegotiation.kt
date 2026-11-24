package org.sempods.api.system.sparql

import jakarta.ws.rs.core.MediaType
import org.sempods.commons.jaxrs.AcceptNegotiation
import org.eclipse.rdf4j.rio.RDFFormat

/**
 * The RDF graph serializations a pod offers for CONSTRUCT/DESCRIBE-shaped results, with their
 * content type and RDF4J writer format. Shared so the `_system/sparql` endpoint and the
 * `_system/find` endpoint negotiate identically and cannot drift.
 */
enum class GraphResultFormat(val contentType: String, val rdfFormat: RDFFormat) {
  JSON_LD("application/ld+json", RDFFormat.JSONLD),
  N_QUADS("application/n-quads", RDFFormat.NQUADS),
}

/**
 * Which graph serialization a `find` or SPARQL graph result is answered in.
 *
 * The rule is RFC 9110 §12.5.1 and lives in [AcceptNegotiation], where it is tested on its own: the
 * most specific range that names a representation decides its quality, the highest quality wins,
 * `q=0` excludes, also beside a wildcard, and a tie falls to JSON-LD, which is also what a caller
 * gets who sends no `Accept` (`SPS-FIND-020`).
 *
 * `null` means the caller accepts neither, and the route answers `406`. `application/json` is one
 * such caller: these routes do not treat it as an alias of JSON-LD, unlike the context registry
 * (`ContextRegistryNegotiation`), and a range with a parameter the representation does not carry,
 * such as a JSON-LD `profile`, names nothing here.
 */
object GraphResultNegotiation {

  /** In the order these routes prefer them, each with what its body carries: UTF-8. */
  private val REPRESENTATIONS: List<MediaType> =
    GraphResultFormat.entries.map { MediaType.valueOf("${it.contentType};charset=utf-8") }

  /** The format for the raw `Accept` header [accept], or `null` when it accepts neither. */
  fun select(accept: String?): GraphResultFormat? {
    val chosen = AcceptNegotiation.select(accept, REPRESENTATIONS) ?: return null
    return GraphResultFormat.entries.first { it.contentType.equals("${chosen.type}/${chosen.subtype}", ignoreCase = true) }
  }

  /**
   * The 0.2 rule, kept so a binary compiled against it still links: JSON-LD for an empty list,
   * otherwise the first range in [acceptableMediaTypes] compatible with JSON-LD or N-Quads, with no
   * regard for quality values. The routes no longer call it.
   */
  @Deprecated("Ignores quality values. Pass the raw Accept header to select(String).")
  fun select(acceptableMediaTypes: List<MediaType>): GraphResultFormat? {
    if (acceptableMediaTypes.isEmpty()) return GraphResultFormat.JSON_LD
    for (mediaType in acceptableMediaTypes) {
      GraphResultFormat.entries.firstOrNull { mediaType.isCompatible(MediaType.valueOf(it.contentType)) }?.let { return it }
    }
    return null
  }
}
