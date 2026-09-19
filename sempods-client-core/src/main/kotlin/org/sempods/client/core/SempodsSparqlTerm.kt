package org.sempods.client.core

/** One RDF term bound in a SELECT result, as the SPARQL 1.1 Query Results JSON Format writes it. */
class SempodsSparqlTerm private constructor(
  val kind: SempodsSparqlTermKind,
  /** The IRI, the literal's lexical form or the blank node's label, exactly as the pod sent it. */
  val value: String,
  /** A literal's language tag; null for any other term and for a literal without one. */
  val language: String?,
  /**
   * A literal's datatype IRI; null for any other term, for a language-tagged literal, and for a literal
   * the pod sent without one, which is an `xsd:string`.
   */
  val datatype: String?,
) {

  override fun equals(other: Any?): Boolean =
    other is SempodsSparqlTerm && other.kind == kind && other.value == value &&
      other.language == language && other.datatype == datatype

  override fun hashCode(): Int = listOf(kind, value, language, datatype).hashCode()

  override fun toString(): String = "SempodsSparqlTerm(kind=$kind, value=$value, language=$language, datatype=$datatype)"

  internal companion object {

    @JvmSynthetic
    internal fun of(
      kind: SempodsSparqlTermKind,
      value: String,
      language: String?,
      datatype: String?,
    ): SempodsSparqlTerm = SempodsSparqlTerm(kind, value, language, datatype)
  }
}
