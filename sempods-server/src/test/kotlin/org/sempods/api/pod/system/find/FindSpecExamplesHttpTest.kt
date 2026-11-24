package org.sempods.api.pod.system.find

import com.google.inject.Inject
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.net.SempodsVocabulary
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.tests.TestUtil
import org.sempods.pods.contexts.persist.PodContextsDao
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.StringReader
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * sempods-spec's find examples (`src/test/resources/spec/find/`), served through `_system/find` by
 * an engine that annotates its hits (the setup's `findAdapter`) beside the built-in SPARQL adapter.
 *
 * Each case seeds the examples' pod data into a fresh pod, scripts the engine with the case's grouped
 * answer, and asks for it by GET and POST, flat and grouped, as JSON-LD and as N-Quads. The answer
 * must be the example's dataset up to blank-node renaming. The built-in expansion may add a stored
 * `rdf:type`, `rdfs:label` or `schema:name` of a hit, which a pod may return (`SPS-FIND-032`); every
 * such addition must be about one of the example's hits and stored in a context of the caller's
 * selection, in that context when grouped (`SPS-FIND-010`, `SPS-FIND-030`).
 */
class FindSpecExamplesHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  /** One pod holding the examples' data, with the IRIs the examples spell under `https://pod.example/` moved under it. */
  private inner class ExamplePod {
    val pod = sempodsTestFactory.newPod()
    val base = "${SempodsModule.config.apiBaseUrl}${pod.name}"
    val data: Model = nQuads(EXAMPLES["pod"].stringValue())

    init {
      listOf("A", "B").forEach { path ->
        val context = sempodsUriBuilder.buildContext(pod.name, path)
        assertEquals("$base/_system/contexts/$path", context.toString())
        podContextsDao.create(podId = checkNotNull(pod.id), contextUri = context.toString(), label = null, description = null, createdBy = "test")
      }
      data.groupBy { it.subject to it.context }.forEach { (key, statements) ->
        podAccess.seed(pod.name, URI(key.first.stringValue()), URI(checkNotNull(key.second).stringValue()), LinkedHashModel(statements))
      }
    }

    fun local(text: String): String = text.replace(EXAMPLE_BASE, "$base/")

    fun nQuads(text: String): Model = Rio.parse(StringReader(local(text)), "", RDFFormat.NQUADS)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "plain", "plain-array", "bare", "known-match", "article-via-topic", "article-and-topic", "repeated",
      "split", "independent", "match-only-in-B", "match-also-in-A", "empty-selection", "stored-vocabulary",
      "optional-description",
    ],
  )
  fun `an example is served as the specification writes it, in every form`(name: String) = withSetup {
    val example = ExamplePod()
    val case = EXAMPLES[name]
    val text = "specexample${TestUtil.randomId()}"
    findAdapter.answerWith(example.nQuads(case["grouped"]["nquads"].stringValue()))

    forms(example, case, text).forEach { (form, answer) ->
      val expected = example.nQuads(case[if (form.grouped) "grouped" else "flat"]["nquads"].stringValue())
      assertAnswers(example, expected, answer, form, selectionOf(example, case))
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["bare", "known-match", "repeated", "split", "independent", "match-also-in-A"])
  fun `an observation the engine did not place goes into a context that holds its hit`(name: String) = withSetup {
    val example = ExamplePod()
    val case = EXAMPLES[name]
    val text = "specexample${TestUtil.randomId()}"
    val unplaced = example.nQuads(case["grouped"]["nquads"].stringValue())
      .mapTo(LinkedHashModel()) { st -> if (generated(st)) Values.getValueFactory().createStatement(st.subject, st.predicate, st.`object`) else st }
    findAdapter.answerWith(unplaced)

    forms(example, case, text).forEach { (form, answer) ->
      if (!form.grouped) {
        assertAnswers(example, example.nQuads(case["flat"]["nquads"].stringValue()), answer, form, selectionOf(example, case))
        return@forEach
      }
      val selection = selectionOf(example, case)
      val holding = { hit: Resource -> example.data.filter(hit, null, null).contexts().filter { it!!.stringValue() in selection }.toSet() }
      answer.filter(::generated).forEach { st ->
        val hits = if (st.subject is IRI) setOf(st.subject) else answer.filter(null, FIND_RESULT, st.subject).subjects()
        assertTrue(hits.any { st.context in holding(it) }, "$form: $st is in no context that holds its hit")
      }
      assertTrue(answer.none { it.context == null }, "$form: a grouped answer has no default graph")
    }
  }

  private data class Form(val method: String, val grouped: Boolean, val format: RDFFormat) {
    override fun toString() = "$method ${if (grouped) "grouped" else "flat"} ${format.name}"
  }

  /** The answer to [case]'s request in every form, each checked for the status and, grouped JSON-LD, the shape. */
  private fun forms(example: ExamplePod, case: JsonNode, text: String): List<Pair<Form, Model>> {
    val request = case["request"]
    val readable = case["readable"]?.items()?.map { example.local(it.stringValue()) }
      ?: listOf("A", "B").map { "${example.base}/_system/contexts/$it" }
    val token = mintScopedToken(example.pod.name, readable.map { "$it#read" })
    val types = request["type"]?.items()?.map { it.stringValue() }.orEmpty()
    val contexts = request["contexts"]?.items()?.map { example.local(it.stringValue()) }
    val limit = request["limit"]?.intValue()

    return listOf("GET", "POST").flatMap { method ->
      listOf(false, true).flatMap { grouped ->
        listOf(RDFFormat.JSONLD, RDFFormat.NQUADS).map { format ->
          val form = Form(method, grouped, format)
          val prepared = if (method == "GET") {
            http.prepareGet(findUrl(example)).addQueryParam("text", text).also { get ->
              types.forEach { get.addQueryParam("type", it) }
              contexts?.forEach { get.addQueryParam("context", it) }
              limit?.let { get.addQueryParam("limit", it.toString()) }
              if (grouped) get.addQueryParam("include_contexts", "true")
            }
          } else {
            val body = JsonNodeFactory.instance.objectNode().put("text", text)
            if (types.isNotEmpty()) body.putArray("type").also { array -> types.forEach { array.add(it) } }
            contexts?.let { list -> body.putArray("contexts").also { array -> list.forEach { array.add(it) } } }
            limit?.let { body.put("limit", it) }
            if (grouped) body.put("include_contexts", true)
            http.preparePost(findUrl(example)).addHeader("Content-Type", "application/json").setBody(JSON.writeValueAsString(body))
          }
          val response = prepared
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", format.defaultMIMEType)
            .execute()
          assertEquals(200, response.statusCode, "$form: ${response.responseBody}")
          if (grouped && format == RDFFormat.JSONLD) assertEveryGroupNamed(form, response.responseBody)
          form to Rio.parse(StringReader(response.responseBody), "", format)
        }
      }
    }
  }

  /**
   * [answer] is [expected] up to blank-node renaming, once the expansion's additions are set aside.
   * An addition must be about a hit of [expected] and stored in a context of [selection].
   */
  private fun assertAnswers(example: ExamplePod, expected: Model, answer: Model, form: Form, selection: Set<String>) {
    val hits = expected.subjects().filterIsInstance<IRI>().toSet()
    val added = answer.filter { st ->
      st.subject is IRI && st.predicate in EXPANSION &&
        !(if (st.context == null) expected.contains(st.subject, st.predicate, st.`object`) else expected.contains(st.subject, st.predicate, st.`object`, st.context))
    }
    added.forEach { st ->
      assertTrue(st.subject in hits, "$form: $st is about no hit of the example")
      val sources = example.data.filter(st.subject, st.predicate, st.`object`).contexts().mapNotNull { it?.stringValue() }
      val allowed = if (form.grouped) sources.filter { it == st.context?.stringValue() } else sources
      assertTrue(allowed.any { it in selection }, "$form: $st is stored in no context of the selection $selection")
    }
    val compared = LinkedHashModel(answer).apply { removeAll(added) }
    assertTrue(Models.isomorphic(expected, compared), "$form:\nexpected $expected\nanswered $compared")
  }

  /** Case 8: every group of a grouped JSON-LD answer names a context, so no statement is in a default or blank-named graph. */
  private fun assertEveryGroupNamed(form: Form, body: String) {
    val root = JSON.readTree(body)
    val groups = if (root.isArray) root.items() else listOf(root)
    groups.forEach { group ->
      assertTrue(group is ObjectNode && group["@id"]?.stringValue()?.startsWith("http") == true && group.has("@graph"), "$form: unnamed group $group")
    }
  }

  /** The contexts the case's caller searches: what it asked for, within what it may read. */
  private fun selectionOf(example: ExamplePod, case: JsonNode): Set<String> {
    val readable = case["readable"]?.items()?.map { example.local(it.stringValue()) }
      ?: listOf("A", "B").map { "${example.base}/_system/contexts/$it" }
    val requested = case["request"]["contexts"]?.items()?.map { example.local(it.stringValue()) }
    return requested?.filterTo(HashSet()) { it in readable } ?: readable.toHashSet()
  }

  private fun findUrl(example: ExamplePod) = "${example.base}/_system/find"

  private companion object {

    const val EXAMPLE_BASE = "https://pod.example/"

    val JSON: JsonMapper = JsonMapper.builder().build()

    val EXAMPLES: JsonNode = YAMLMapper.builder().build().readTree(
      checkNotNull(FindSpecExamplesHttpTest::class.java.getResourceAsStream("/spec/find/responses.yaml")),
    )

    val FIND_RESULT: IRI = Values.iri(SempodsVocabulary.FIND_RESULT)

    val EXPANSION: Set<IRI> = setOf(RDF.TYPE, RDFS.LABEL, Values.iri("https://schema.org/name"))

    fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    fun generated(st: Statement): Boolean = st.subject is BNode || (st.predicate == FIND_RESULT && st.`object` is BNode)
  }
}
