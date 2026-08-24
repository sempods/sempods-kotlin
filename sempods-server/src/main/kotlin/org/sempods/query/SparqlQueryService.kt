package org.sempods.query

import com.google.inject.Inject
import org.sempods.commons.jaxrs.errors.ApiErrors
import org.sempods.pods.PodRepositoryCache
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.query.Dataset
import org.eclipse.rdf4j.query.MalformedQueryException
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.query.algebra.Service
import org.eclipse.rdf4j.query.algebra.TupleExpr
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor
import org.eclipse.rdf4j.query.parser.ParsedBooleanQuery
import org.eclipse.rdf4j.query.parser.ParsedGraphQuery
import org.eclipse.rdf4j.query.parser.ParsedQuery
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery
import org.eclipse.rdf4j.query.parser.QueryParserUtil
import org.eclipse.rdf4j.query.resultio.QueryResultIO
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.sail.memory.MemoryStore
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Single source of truth for SPARQL read-only execution against a pod. Owns parser-based
 * validation (Update- and SERVICE-rejection), query classification, and dispatch into the
 * pod's RDF4J repository.
 *
 * Two consumers share this service:
 * - [org.sempods.api.system.sparql.SparqlEndpoint] — REST `_system/sparql/query`
 * - [org.sempods.api.pod.system.mcp.McpEndpoint]  — MCP `sparql_select` / `sparql_graph`
 *
 * The endpoints stay thin (HTTP/JSON-RPC adaption, content negotiation, error format).
 * All read-only enforcement and execution flows through here so the two surfaces cannot
 * drift apart.
 */
class SparqlQueryService @Inject constructor(
  private val podRepositoryCache: PodRepositoryCache,
) {

  private val vf = SimpleValueFactory.getInstance()

  enum class ReadQueryType { TUPLE, BOOLEAN, GRAPH }

  data class ValidatedQuery(val parsed: ParsedQuery, val type: ReadQueryType)

  /**
   * Parse the query and reject Updates and SERVICE clauses.
   *
   * - SPARQL Update forms (INSERT/DELETE/LOAD/CLEAR/CREATE/DROP/COPY/MOVE/ADD) cannot
   *   be parsed as a Query production; [QueryParserUtil.parseQuery] throws
   *   [MalformedQueryException]. We translate that to [ForbiddenSparqlException].
   *   Bonus property: keyword strings inside literal values (`?p "create_resource"`)
   *   are correctly classified as `StringLiteral` tokens, not as SPARQL keywords.
   * - SERVICE clauses are walked over the algebra (anywhere in the tree) and rejected
   *   for SSRF reasons.
   *
   * @throws ForbiddenSparqlException if the query is an Update or contains SERVICE.
   */
  fun validateReadOnly(query: String): ValidatedQuery {
    val parsed = try {
      QueryParserUtil.parseQuery(QueryLanguage.SPARQL, query, null)
    } catch (e: MalformedQueryException) {
      // We can't reliably distinguish "is an Update" from "general syntax error" without
      // a second parse attempt against the Update grammar. Surface both as Forbidden so
      // a malicious INSERT cannot leak through as a 400 syntax error: a valid-looking
      // read query is the only path forward.
      throw ForbiddenSparqlException(
        "Write operations are not allowed or query is malformed: ${e.message}"
      )
    }
    if (containsServiceClause(parsed.tupleExpr)) {
      throw ForbiddenSparqlException("SERVICE keyword is not allowed for security reasons")
    }
    val type = when (parsed) {
      is ParsedTupleQuery -> ReadQueryType.TUPLE
      is ParsedBooleanQuery -> ReadQueryType.BOOLEAN
      is ParsedGraphQuery -> ReadQueryType.GRAPH
      else -> throw ForbiddenSparqlException("Unsupported read query type: ${parsed.javaClass.simpleName}")
    }
    return ValidatedQuery(parsed = parsed, type = type)
  }

  fun executeTuple(pod: String, query: String, dataset: Dataset?, out: OutputStream) {
    withPodConnection(pod) { conn ->
      val prepared = conn.prepareTupleQuery(QueryLanguage.SPARQL, query)
      prepared.maxExecutionTime = MAX_EXECUTION_TIME_SECONDS
      if (dataset != null) prepared.dataset = dataset
      prepared.evaluate().use { res ->
        QueryResultIO.writeTuple(res, TupleQueryResultFormat.JSON, out)
      }
    }
  }

  fun executeBoolean(pod: String, query: String, dataset: Dataset?): Boolean {
    return withPodConnection(pod) { conn ->
      val prepared = conn.prepareBooleanQuery(QueryLanguage.SPARQL, query)
      prepared.maxExecutionTime = MAX_EXECUTION_TIME_SECONDS
      if (dataset != null) prepared.dataset = dataset
      prepared.evaluate()
    }
  }

  fun executeGraph(
    pod: String,
    query: String,
    dataset: Dataset?,
    out: OutputStream,
    rdfFormat: RDFFormat,
  ) {
    withPodConnection(pod) { conn ->
      val prepared = conn.prepareGraphQuery(QueryLanguage.SPARQL, query)
      prepared.maxExecutionTime = MAX_EXECUTION_TIME_SECONDS
      if (dataset != null) prepared.dataset = dataset
      prepared.evaluate().use { res ->
        val writer = Rio.createWriter(rdfFormat, out)
        writer.startRDF()
        while (res.hasNext()) writer.handleStatement(res.next())
        writer.endRDF()
      }
    }
  }

  /**
   * Execute a CONSTRUCT/DESCRIBE query and collect the result into an in-memory [Model].
   *
   * Unlike [executeGraph] (which streams to an [OutputStream]) this returns a Model that
   * callers can merge / inspect — used by the retrieval layer ([org.sempods.retrieval.FindService]
   * and its adapters), which fan out several graph queries and join the results before serializing.
   *
   * [bindings] are applied via [org.eclipse.rdf4j.query.GraphQuery.setBinding] so callers can
   * parameterize a fixed query template (injection-safe — values never get string-concatenated
   * into the query text). The query is trusted/server-generated here; read-only validation lives
   * at the endpoint boundary for caller-supplied SPARQL ([validateReadOnly]).
   */
  fun executeGraphToModel(
    pod: String,
    query: String,
    dataset: Dataset?,
    bindings: Map<String, Value> = emptyMap(),
  ): Model {
    return withPodConnection(pod) { conn ->
      val prepared = conn.prepareGraphQuery(QueryLanguage.SPARQL, query)
      prepared.maxExecutionTime = MAX_EXECUTION_TIME_SECONDS
      if (dataset != null) prepared.dataset = dataset
      bindings.forEach { (name, value) -> prepared.setBinding(name, value) }
      val model = LinkedHashModel()
      prepared.evaluate().use { res ->
        while (res.hasNext()) model.add(res.next())
      }
      model
    }
  }

  /**
   * Execute a `SELECT ?s ?p ?o ?g` and assemble the rows into a **quad** [Model] — each statement
   * carries its source named graph `?g` as the RDF4J context. This is how `find` keeps per-edge
   * context provenance (a CONSTRUCT/[executeGraphToModel] can only emit context-less triples).
   * Rows missing any of the four bindings, or with the wrong term kind for subject/predicate/graph,
   * are skipped defensively. Same `dataset` sandbox + bound-variable handling as the graph path.
   */
  fun executeSelectToQuadModel(
    pod: String,
    query: String,
    dataset: Dataset?,
    bindings: Map<String, Value> = emptyMap(),
  ): Model {
    return withPodConnection(pod) { conn ->
      val prepared = conn.prepareTupleQuery(QueryLanguage.SPARQL, query)
      prepared.maxExecutionTime = MAX_EXECUTION_TIME_SECONDS
      if (dataset != null) prepared.dataset = dataset
      bindings.forEach { (name, value) -> prepared.setBinding(name, value) }
      val model = LinkedHashModel()
      prepared.evaluate().use { res ->
        while (res.hasNext()) {
          val row = res.next()
          val s = row.getValue("s") as? Resource
          val p = row.getValue("p") as? IRI
          val o = row.getValue("o")
          val g = row.getValue("g") as? Resource
          if (s != null && p != null && o != null && g != null) {
            model.add(vf.createStatement(s, p, o, g))
          }
        }
      }
      model
    }
  }

  fun executeTupleAsJson(pod: String, query: String, dataset: Dataset?): String {
    val out = ByteArrayOutputStream()
    executeTuple(pod = pod, query = query, dataset = dataset, out = out)
    return out.toString(Charsets.UTF_8)
  }

  fun executeTupleAsJsonOnEmptyStore(query: String): String {
    val out = ByteArrayOutputStream()
    withEmptyConnection { conn ->
      val prepared = conn.prepareTupleQuery(QueryLanguage.SPARQL, query)
      prepared.maxExecutionTime = MAX_EXECUTION_TIME_SECONDS
      prepared.evaluate().use { res ->
        QueryResultIO.writeTuple(res, TupleQueryResultFormat.JSON, out)
      }
    }
    return out.toString(Charsets.UTF_8)
  }

  /**
   * Execute an ASK query and serialize as SPARQL-Results-JSON
   * (https://www.w3.org/TR/sparql11-results-json/#boolean-results).
   */
  fun executeBooleanAsJson(pod: String, query: String, dataset: Dataset?): String {
    val value = executeBoolean(pod = pod, query = query, dataset = dataset)
    return """{"head":{},"boolean":$value}"""
  }

  fun executeBooleanAsJsonOnEmptyStore(query: String): String {
    val value = withEmptyConnection { conn ->
      val prepared = conn.prepareBooleanQuery(QueryLanguage.SPARQL, query)
      prepared.maxExecutionTime = MAX_EXECUTION_TIME_SECONDS
      prepared.evaluate()
    }
    return """{"head":{},"boolean":$value}"""
  }

  fun executeGraphAsString(
    pod: String,
    query: String,
    dataset: Dataset?,
    rdfFormat: RDFFormat = RDFFormat.JSONLD,
  ): String {
    val out = ByteArrayOutputStream()
    executeGraph(pod = pod, query = query, dataset = dataset, out = out, rdfFormat = rdfFormat)
    return out.toString(Charsets.UTF_8)
  }

  fun executeGraphAsStringOnEmptyStore(query: String, rdfFormat: RDFFormat = RDFFormat.JSONLD): String {
    val out = ByteArrayOutputStream()
    withEmptyConnection { conn ->
      val prepared = conn.prepareGraphQuery(QueryLanguage.SPARQL, query)
      prepared.maxExecutionTime = MAX_EXECUTION_TIME_SECONDS
      prepared.evaluate().use { res ->
        val writer = Rio.createWriter(rdfFormat, out)
        writer.startRDF()
        while (res.hasNext()) writer.handleStatement(res.next())
        writer.endRDF()
      }
    }
    return out.toString(Charsets.UTF_8)
  }

  private fun <T> withPodConnection(pod: String, block: (RepositoryConnection) -> T): T {
    val repo = podRepositoryCache.get(pod)
      ?: throw ApiErrors.throwNotFoundError("pod", pod)
    return repo.withConnection(block)
  }

  private fun <T> withEmptyConnection(block: (RepositoryConnection) -> T): T {
    val repo = SailRepository(MemoryStore())
    repo.init()
    return try {
      repo.connection.use(block)
    } finally {
      repo.shutDown()
    }
  }

  private fun containsServiceClause(expr: TupleExpr): Boolean {
    var found = false
    expr.visit(object : AbstractQueryModelVisitor<RuntimeException>() {
      override fun meet(node: Service) {
        found = true
      }
    })
    return found
  }

  companion object {
    private const val MAX_EXECUTION_TIME_SECONDS = 10
  }
}
