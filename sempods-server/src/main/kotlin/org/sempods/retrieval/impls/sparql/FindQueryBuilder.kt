package org.sempods.retrieval.impls.sparql

import org.sempods.retrieval.FindRequest
import org.sempods.retrieval.IriSyntax
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory

/**
 * Builds the injection-safe SPARQL for the SPARQL find engine — the text-match query
 * ([SparqlTextFindAdapter]) and the fixed expansion ([SparqlResourceExpander]).
 *
 * No caller input is ever raw-string-concatenated into the query:
 * - **Tokens** are passed as bound variables (`?tok0`, …) via `Query.setBinding`.
 * - **Type IRIs** and **hit IRIs** go into `VALUES` clauses via [iriRef], which requires a
 *   well-formed IRI ([IriSyntax]) — one that cannot contain a SPARQL `IRIREF` break-out character
 *   — and wraps it in `<…>`. They are *not* bound, because RDF4J's `BindingAssignerOptimizer`
 *   corrupts a bound variable that sits inside an `IN` / `VALUES` list of two or more members.
 *   (`\u`-escaping is not an option: RDF4J decodes UCHARs inside an IRIREF and re-rejects the
 *   decoded character, so the illegal char must be excluded, not escaped.) Caller-supplied type
 *   IRIs are already rejected at the input boundary by `FindRequestParser`; this is the
 *   defense-in-depth that keeps the builder unable to emit injectable SPARQL.
 *
 * Only safe, fixed structure (variable names, the integer `LIMIT`, the number of conjuncts)
 * is interpolated.
 */
object FindQueryBuilder {

  private val vf = SimpleValueFactory.getInstance()

  data class BoundQuery(val sparql: String, val bindings: Map<String, Value>)

  /** Render a well-formed IRI as a SPARQL `IRIREF` (`<…>`). Throws if the IRI carries a break-out character. */
  private fun iriRef(iri: IRI): String {
    require(IriSyntax.isWellFormed(iri)) { "IRI is not representable as a SPARQL IRIREF: ${iri.stringValue()}" }
    return "<${iri.stringValue()}>"
  }

  /**
   * Text-match `SELECT ?s ?p ?o ?g`: a resource is a hit iff **one of its literals contains all
   * tokens** (case-insensitive). Returns the matched literal edges with their **source context**
   * `?g`; the subjects are the hits. Optionally constrains the hit's `rdf:type` to
   * [FindRequest.types] (OR-combined).
   *
   * **Match vs. provenance:** the inner `SELECT DISTINCT ?s` matches against the *default-graph
   * union* (every visible context, registered by [org.sempods.query.SparqlSandbox] both as default
   * and named graph), so a hit may match cross-context exactly as before. Only the *returned* edges
   * are wrapped in `GRAPH ?g { ?s ?p ?o }` to tag each with the named graph it actually lives in —
   * wrapping the match itself would wrongly force per-context matching. `isIRI(?s)` keeps blank-node
   * subjects out; `ORDER BY STR(?s)` makes the `LIMIT` deterministic.
   */
  fun textMatch(request: FindRequest): BoundQuery {
    require(request.tokens.isNotEmpty()) { "textMatch requires at least one token" }
    val bindings = LinkedHashMap<String, Value>()

    val containsConjunction = request.tokens.mapIndexed { i, token ->
      val varName = "tok$i"
      bindings[varName] = vf.createLiteral(token)
      "CONTAINS(LCASE(STR(?o)), ?$varName)"
    }.joinToString(" && ")

    val typeClause = if (request.types.isEmpty()) "" else {
      val values = request.types.joinToString(" ") { iriRef(it) }
      "?s a ?type . VALUES ?type { $values }"
    }

    val sparql = """
      SELECT ?s ?p ?o ?g
      WHERE {
        {
          SELECT DISTINCT ?s WHERE {
            ?s ?p ?o . FILTER(isIRI(?s) && isLiteral(?o))
            FILTER( $containsConjunction )
            $typeClause
          } ORDER BY STR(?s) LIMIT ${request.limit}
        }
        GRAPH ?g { ?s ?p ?o }
        FILTER(isLiteral(?o))
        FILTER( $containsConjunction )
      }
    """.trimIndent()

    return BoundQuery(sparql, bindings)
  }

  /**
   * Fixed expansion `SELECT ?s ?p ?o ?g` over the given hit IRIs: returns `rdf:type`, `rdfs:label`
   * and `schema:name` where present, each tagged with its **source context** `?g`. Hit IRIs go into
   * an inline `VALUES` clause (IRIREF-escaped), never concatenated.
   */
  fun expansion(hits: Collection<IRI>): BoundQuery {
    require(hits.isNotEmpty()) { "expansion requires at least one hit" }
    val values = hits.joinToString(" ") { iriRef(it) }

    val sparql = """
      PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      PREFIX schema: <https://schema.org/>
      SELECT ?s ?p ?o ?g
      WHERE {
        VALUES ?s { $values }
        GRAPH ?g { ?s ?p ?o }
        FILTER(?p IN (rdf:type, rdfs:label, schema:name))
      }
    """.trimIndent()

    return BoundQuery(sparql, emptyMap())
  }
}
