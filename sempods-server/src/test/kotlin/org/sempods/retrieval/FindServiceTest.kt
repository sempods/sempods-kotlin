package org.sempods.retrieval

import org.sempods.commons.net.SempodsVocabulary
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.spec.PodRef
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [FindService]'s central merge + cap logic, exercised with fake adapters /
 * expander (no pod store needed). Focus: the cap that only bites once several adapters overflow
 * the request limit, and the pruning that keeps each kept hit's blank nodes with it — the
 * single-adapter path is covered end-to-end by `FindEndpointHttpTest`.
 */
class FindServiceTest {

  private val vf = SimpleValueFactory.getInstance()
  private val name = vf.createIRI("https://schema.org/name")
  private val position = vf.createIRI("https://schema.org/position")
  private val text = vf.createIRI("https://schema.org/text")
  private val findResult = vf.createIRI(SempodsVocabulary.FIND_RESULT)

  private fun modelOf(vararg iris: String): Model =
    LinkedHashModel().apply { iris.forEach { add(vf.createIRI(it), name, vf.createLiteral("x")) } }

  /** One hit per IRI, each with a `sps:findResult` node carrying its 1-based rank in argument order. */
  private fun rankedModelOf(vararg iris: String): Model = LinkedHashModel().apply {
    iris.forEachIndexed { i, iri ->
      val hit = vf.createIRI(iri)
      val node = vf.createBNode()
      add(hit, name, vf.createLiteral("x"))
      add(hit, findResult, node)
      add(node, position, vf.createLiteral(i + 1))
    }
  }

  private fun adapter(model: Model) = object : FindAdapter {
    override fun find(request: FindRequest, sandbox: FindSandbox): Model = model
  }

  /** Records the hits handed to it; returns no expansion triples. */
  private class CapturingExpander : ResourceExpander {
    var lastHits: Set<String>? = null
    override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model {
      lastHits = hits.map { it.stringValue() }.toSet()
      return LinkedHashModel()
    }
  }

  private val sandbox = FindSandbox(
    podUri = URI("https://example.org/t"),
    credentials = SempodsCredentials(pod = PodRef(uri = URI("https://example.org/t"), name = "t", owner = "o"), restrictedContexts = null),
    visibleContexts = null,
  )

  private fun find(limit: Int, vararg models: Model, expander: ResourceExpander = CapturingExpander()): Model =
    FindService(adapters = models.map { adapter(it) }.toSet(), expander = expander)
      .find(FindRequest(tokens = listOf("x"), types = emptySet(), limit = limit), sandbox)

  private fun subjectsOf(model: Model): Set<String> =
    model.subjects().filterIsInstance<IRI>().map { it.stringValue() }.toSet()

  private fun nodesOf(model: Model, hit: String): Set<Resource> =
    model.filter(vf.createIRI(hit), findResult, null).objects().filterIsInstance<Resource>().toSet()

  /** Every blank-node subject is reachable from an IRI subject: nothing is left dangling. */
  private fun assertNoOrphanedBlankNodes(model: Model) {
    val linked = model.filter(null, null, null).objects().filter { it.isBNode }.toSet()
    model.subjects().filter { it.isBNode }.forEach { node ->
      assertTrue(node in linked, "blank node $node is left without a statement pointing at it")
    }
  }

  @Test
  fun `caps merged hits to the limit deterministically and prunes dropped hits' edges`() {
    val expander = CapturingExpander()
    val result = find(
      2,
      modelOf("https://e/a", "https://e/b"),
      modelOf("https://e/c", "https://e/d"),
      expander = expander,
    )

    // 4 distinct unranked hits across two adapters, limit 2 → IRI-order keep = {a, b}.
    assertEquals(setOf("https://e/a", "https://e/b"), subjectsOf(result))
    // Expansion ran over exactly the kept set — dropped hits c/d are gone, not expanded.
    assertEquals(setOf("https://e/a", "https://e/b"), expander.lastHits)
  }

  @Test
  fun `keeps all hits when the union is within the limit`() {
    val expander = CapturingExpander()
    val result = find(
      10,
      modelOf("https://e/a", "https://e/b"),
      modelOf("https://e/c", "https://e/d"),
      expander = expander,
    )

    val all = setOf("https://e/a", "https://e/b", "https://e/c", "https://e/d")
    assertEquals(all, subjectsOf(result))
    assertEquals(all, expander.lastHits)
  }

  @Test
  fun `the cap keeps the best-ranked hits, not the lexicographically smallest`() {
    // Ranked in descending IRI order: c is position 1, a is position 3.
    val result = find(2, rankedModelOf("https://e/c", "https://e/b", "https://e/a"))

    assertEquals(setOf("https://e/c", "https://e/b"), subjectsOf(result))
  }

  @Test
  fun `the best position across adapters ranks a hit, and ranked hits beat unranked ones`() {
    val result = find(
      2,
      // a is position 2 here and position 1 below; b is position 1 here.
      rankedModelOf("https://e/b", "https://e/a"),
      rankedModelOf("https://e/a"),
      // Unranked and lexicographically first, so only the rank can put it last.
      modelOf("https://e/0"),
    )

    assertEquals(setOf("https://e/a", "https://e/b"), subjectsOf(result))
  }

  @Test
  fun `a kept hit keeps its blank nodes, nested ones included, when the cap drops others`() {
    val model = rankedModelOf("https://e/a", "https://e/b", "https://e/c")
    val nested = vf.createBNode()
    val aNode = model.filter(vf.createIRI("https://e/a"), findResult, null).objects().single() as Resource
    model.add(aNode, text, nested)
    model.add(nested, name, vf.createLiteral("excerpt"))

    val result = find(2, model)

    assertEquals(setOf("https://e/a", "https://e/b"), subjectsOf(result))
    val node = nodesOf(result, "https://e/a").single()
    assertTrue(result.contains(node, position, vf.createLiteral(1)), "the kept hit's node keeps its statements")
    assertTrue(result.contains(nested, name, vf.createLiteral("excerpt")), "a nested blank node is kept too")
    assertTrue(nodesOf(result, "https://e/b").isNotEmpty())
    assertTrue(result.filter(null, position, vf.createLiteral(3)).isEmpty(), "the dropped hit's node is gone")
    assertNoOrphanedBlankNodes(result)
  }

  @Test
  fun `only IRI subjects are hits, so blank nodes never count against the limit`() {
    val result = find(1, rankedModelOf("https://e/a"))

    assertEquals(setOf("https://e/a"), subjectsOf(result))
    assertEquals(1, nodesOf(result, "https://e/a").size)
  }

  @Test
  fun `blank nodes that no hit reaches are not returned`() {
    val stray = LinkedHashModel().apply { add(vf.createBNode(), name, vf.createLiteral("stray")) }

    assertTrue(find(10, stray).isEmpty())
    assertEquals(setOf("https://e/a"), subjectsOf(find(10, modelOf("https://e/a"), stray)))
    assertTrue(find(10, modelOf("https://e/a"), stray).filter(null, null, vf.createLiteral("stray")).isEmpty())
  }
}
