package org.sempods.client.core

/** The formats a pod answers a CONSTRUCT or DESCRIBE query in (SPS-SPARQL-016), sent as `Accept`. */
enum class SempodsGraphFormat(
  /** The media type asked for. */
  val mediaType: String,
) {
  JSON_LD("application/ld+json"),
  N_QUADS("application/n-quads"),
}
