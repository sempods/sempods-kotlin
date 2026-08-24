package org.sempods.retrieval.impls.sparql

import org.sempods.retrieval.FindRequest
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.query.parser.QueryParserUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Injection / robustness tests for [FindQueryBuilder]. Caller-supplied type IRIs are rejected at
 * the input boundary by `FindRequestParser` (see `FindRequestParserTest`); these prove the builder
 * itself (a) emits structurally valid SPARQL for clean IRIs and (b) refuses, rather than escapes, an
 * IRI carrying SPARQL-`IRIREF` break-out characters — so it can never emit injectable SPARQL.
 */
class FindQueryBuilderTest {

  private val vf = SimpleValueFactory.getInstance()
  private val injection = vf.createIRI("https://evil/a> } UNION { ?s ?p ?o")

  @Test
  fun `textMatch with clean type IRI produces parseable SPARQL`() {
    val query = FindQueryBuilder.textMatch(
      FindRequest(tokens = listOf("alice"), types = setOf(vf.createIRI("https://schema.org/Event")), limit = 10),
    )
    QueryParserUtil.parseQuery(QueryLanguage.SPARQL, query.sparql, null) // throws if malformed
  }

  @Test
  fun `expansion with clean hit IRIs produces parseable SPARQL`() {
    val query = FindQueryBuilder.expansion(listOf(vf.createIRI("https://e/a"), vf.createIRI("https://e/b")))
    QueryParserUtil.parseQuery(QueryLanguage.SPARQL, query.sparql, null)
  }

  @Test
  fun `textMatch refuses a type IRI that would break out of an IRIREF`() {
    assertFailsWith<IllegalArgumentException> {
      FindQueryBuilder.textMatch(FindRequest(tokens = listOf("x"), types = setOf(injection), limit = 10))
    }
  }

  @Test
  fun `expansion refuses a hit IRI that would break out of an IRIREF`() {
    assertFailsWith<IllegalArgumentException> {
      FindQueryBuilder.expansion(listOf(injection))
    }
  }
}
