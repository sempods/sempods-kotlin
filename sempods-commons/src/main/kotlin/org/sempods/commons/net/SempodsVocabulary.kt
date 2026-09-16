package org.sempods.commons.net

/**
 * The sempods terms that go on the wire, as absolute IRIs — the vocabulary half of what
 * [SempodsPodRoutes] does for paths.
 *
 * **Why the strings live here.** A published term IRI is a contract with whoever cited it, so the
 * modules that write it must spell it identically: the server builds RDF from it with RDF4J, and
 * `:sempods-mcp-core` reads the same IRIs as JSON keys. `:sempods-commons` is the module both
 * already have. They are `String`s, so this module stays free of an RDF library, and they live here
 * because `:sempods-model`'s `Ontologies` holds the standards.
 *
 * The registry terms are specified in sempods-spec `spec/core/contexts.md`: `SPS-CTX-032` for
 * [PUBLIC], `SPS-CTX-033` for the three access relations.
 */
object SempodsVocabulary {

  /** The namespace every term below is built from. */
  const val NAMESPACE: String = "https://schema.sempods.org/"

  /** Whether a context is anonymously readable, as the registry holds it. */
  const val PUBLIC: String = NAMESPACE + "public"

  /** A context the requesting caller may read. */
  const val READABLE_CONTEXT: String = NAMESPACE + "readableContext"

  /** A context the requesting caller may write. */
  const val WRITABLE_CONTEXT: String = NAMESPACE + "writableContext"

  /** A context the requesting caller may manage. */
  const val MANAGEABLE_CONTEXT: String = NAMESPACE + "manageableContext"
}
