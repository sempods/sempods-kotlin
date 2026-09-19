package org.sempods.client

/**
 * The graph formats a pod answers in — a CONSTRUCT or DESCRIBE query (SPS-SPARQL-016), a resource
 * (SPS-CRUD-026), the context registry (SPS-CTX-031) — sent as `Accept`.
 */
enum class SempodsGraphFormat(
  /** The media type asked for. */
  val mediaType: String,
) {
  JSON_LD("application/ld+json"),
  N_QUADS("application/n-quads"),
}
