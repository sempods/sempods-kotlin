package org.sempods.retrieval

import org.eclipse.rdf4j.model.IRI

/**
 * Minimal IRI syntax guard. `SimpleValueFactory.createIRI` validates permissively (it only
 * requires a scheme), so a caller-supplied IRI can carry characters that no RFC 3987 IRI may
 * contain — spaces, control chars, and ``<>"{}|^`\`` — which are also exactly the characters that
 * would break out of a SPARQL `IRIREF` (and which RDF4J rejects even when `\u`-escaped). Rejecting
 * them at the input boundary is the injection guard for caller-supplied IRIs.
 */
internal object IriSyntax {

  // '<' '>' '"' '{' '}' '|' '^' '`' '\\'
  private val ILLEGAL = setOf(0x3C, 0x3E, 0x22, 0x7B, 0x7D, 0x7C, 0x5E, 0x60, 0x5C)

  fun isWellFormed(iri: IRI): Boolean =
    iri.stringValue().none { ch ->
      val c = ch.code
      c <= 0x20 || c == 0x7F || (c in 0x80..0x9F) || (c in ILLEGAL)
    }
}
