package org.sempods.api.system.sparql

import jakarta.ws.rs.core.MediaType
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

object GraphResultNegotiation {

  private val JSON_LD_MEDIA_TYPE = MediaType.valueOf("application/ld+json")
  private val N_QUADS_MEDIA_TYPE = MediaType.valueOf("application/n-quads")

  /**
   * Pick the graph result format for an `Accept` header: JSON-LD is the default (no/empty
   * Accept), otherwise the first compatible of JSON-LD / N-Quads. Returns `null` when the
   * caller accepts neither — the endpoint answers `406`.
   */
  fun select(acceptableMediaTypes: List<MediaType>): GraphResultFormat? {
    if (acceptableMediaTypes.isEmpty()) return GraphResultFormat.JSON_LD
    for (mediaType in acceptableMediaTypes) {
      if (mediaType.isCompatible(JSON_LD_MEDIA_TYPE)) return GraphResultFormat.JSON_LD
      if (mediaType.isCompatible(N_QUADS_MEDIA_TYPE)) return GraphResultFormat.N_QUADS
    }
    return null
  }
}
