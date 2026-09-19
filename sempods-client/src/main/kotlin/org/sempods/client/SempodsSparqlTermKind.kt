package org.sempods.client

/** What an RDF term in a SELECT result is: the `uri`, `literal` and `bnode` of the SPARQL 1.1 results format. */
enum class SempodsSparqlTermKind {
  IRI,
  LITERAL,
  BLANK_NODE,
}
