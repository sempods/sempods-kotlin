package org.sempods.retrieval

import org.sempods.commons.net.SempodsVocabulary
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.spec.PodRef
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [FindService]'s central merge, verification and cap logic, exercised with fake
 * adapters / expander (no pod store needed). Focus: the cap that only bites once several adapters
 * overflow the request limit, the pruning that keeps each kept hit's blank nodes with it, and the
 * verification of hits no self-scoped adapter vouches for — the single-adapter path is covered
 * end-to-end by `FindEndpointHttpTest`.
 */
class FindServiceTest {

  private val vf = SimpleValueFactory.getInstance()
  private val name = vf.createIRI("https://schema.org/name")
  private val position = vf.createIRI("https://schema.org/position")
  private val text = vf.createIRI("https://schema.org/text")
  private val findResult = vf.createIRI(SempodsVocabulary.FIND_RESULT)
  private val event = vf.createIRI("https://schema.org/Event")
  private val person = vf.createIRI("https://schema.org/Person")
  private val place = vf.createIRI("https://schema.org/Place")

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

  /** Adds a `sps:findResult` node carrying [snippet] to [hit], the way an external engine reports one. */
  private fun Model.withSnippet(hit: String, snippet: String): Model = apply {
    val node = vf.createBNode()
    add(vf.createIRI(hit), findResult, node)
    add(node, text, vf.createLiteral(snippet))
  }

  private fun adapter(model: Model, selfScoped: Boolean = true) = object : FindAdapter {
    override val selfScoped = selfScoped
    override fun find(request: FindRequest, sandbox: FindSandbox): Model = model
  }

  /** Records the hits handed to it; returns no expansion triples. */
  private open class CapturingExpander : ResourceExpander {
    var lastHits: Set<String>? = null
    override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model {
      lastHits = hits.map { it.stringValue() }.toSet()
      return LinkedHashModel()
    }
  }

  /**
   * Stands in for a store holding [types]: for every handed hit it has types for, returns those
   * `rdf:type` statements and a `schema:name`. Honours [FindSandbox.matchesNothing] as the contract
   * requires.
   */
  private inner class TypingExpander(private val types: Map<String, Set<IRI>>) : CapturingExpander() {
    override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model {
      super.expand(hits, sandbox)
      if (sandbox.matchesNothing()) return LinkedHashModel()
      return LinkedHashModel().apply {
        hits.forEach { hit ->
          val hitTypes = types[hit.stringValue()] ?: return@forEach
          hitTypes.forEach { add(hit, RDF.TYPE, it) }
          add(hit, name, vf.createLiteral("expanded ${hit.stringValue()}"))
        }
      }
    }
  }

  private val sandbox = FindSandbox(
    podUri = URI("https://example.org/t"),
    credentials = SempodsCredentials(pod = PodRef(uri = URI("https://example.org/t"), name = "t", owner = "o"), restrictedContexts = null),
    visibleContexts = null,
  )

  private fun find(limit: Int, vararg models: Model, expander: ResourceExpander = CapturingExpander()): Model =
    find(limit = limit, adapters = models.map { adapter(it) }, expander = expander)

  private fun find(
    limit: Int,
    adapters: List<FindAdapter>,
    expander: ResourceExpander,
    types: Set<IRI> = emptySet(),
    sandbox: FindSandbox = this.sandbox,
  ): Model =
    FindService(adapters = adapters.toSet(), expander = expander)
      .find(FindRequest(tokens = listOf("x"), types = types, limit = limit), sandbox)

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
    // The one expansion ran over every merged hit, because it verifies them before the cap.
    assertEquals(setOf("https://e/a", "https://e/b", "https://e/c", "https://e/d"), expander.lastHits)
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

  @Test
  fun `drops an unvouched hit the expansion does not type, together with its blank nodes`() {
    // b stands for a hit from a context the caller may not read: the scoped expansion has no type for it.
    val overReturning = modelOf("https://e/a", "https://e/b")
      .withSnippet("https://e/a", "readable")
      .withSnippet("https://e/b", "secret")
    val expander = TypingExpander(mapOf("https://e/a" to setOf(event)))

    val result = find(limit = 10, adapters = listOf(adapter(overReturning, selfScoped = false)), expander = expander)

    assertEquals(setOf("https://e/a"), subjectsOf(result))
    assertTrue(result.contains(null, text, vf.createLiteral("readable")), "the kept hit keeps its snippet")
    assertTrue(result.filter(null, text, vf.createLiteral("secret")).isEmpty(), "the dropped hit's snippet goes")
    assertNoOrphanedBlankNodes(result)
  }

  @Test
  fun `with a type constraint, drops an unvouched hit of another type`() {
    val overReturning = modelOf("https://e/a", "https://e/b").withSnippet("https://e/b", "a person")
    val expander = TypingExpander(mapOf("https://e/a" to setOf(event), "https://e/b" to setOf(person)))

    val result = find(
      limit = 10,
      adapters = listOf(adapter(overReturning, selfScoped = false)),
      expander = expander,
      types = setOf(event),
    )

    assertEquals(setOf("https://e/a"), subjectsOf(result))
    assertTrue(result.filter(null, text, null).isEmpty(), "the dropped hit's snippet goes")
  }

  @Test
  fun `the type constraint is OR-combined, and one matching type of several suffices`() {
    val overReturning = modelOf("https://e/a", "https://e/b", "https://e/c")
    val expander = TypingExpander(
      mapOf(
        "https://e/a" to setOf(event),
        "https://e/b" to setOf(person, place),
        "https://e/c" to setOf(place),
      ),
    )

    val result = find(
      limit = 10,
      adapters = listOf(adapter(overReturning, selfScoped = false)),
      expander = expander,
      types = setOf(event, person),
    )

    assertEquals(setOf("https://e/a", "https://e/b"), subjectsOf(result))
  }

  @Test
  fun `an expander that returns nothing drops every unvouched hit`() {
    val result = find(
      limit = 10,
      adapters = listOf(adapter(modelOf("https://e/a", "https://e/b").withSnippet("https://e/a", "s"), selfScoped = false)),
      expander = CapturingExpander(),
    )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `an empty effective scope yields nothing, whatever the engines return`() {
    val nothing = FindSandbox(
      podUri = sandbox.podUri,
      credentials = sandbox.credentials,
      visibleContexts = setOf(URI("https://example.org/t/public")),
      contextFilter = emptySet(),
    )
    // Neither engine honours the empty scope; the service must not depend on them doing so.
    val careless = object : ResourceExpander {
      override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model =
        LinkedHashModel().apply { hits.forEach { add(it, RDF.TYPE, event) } }
    }

    val result = find(
      limit = 10,
      adapters = listOf(adapter(modelOf("https://e/a")), adapter(modelOf("https://e/b"), selfScoped = false)),
      expander = careless,
      sandbox = nothing,
    )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `a hit vouched for by a self-scoped adapter is kept without an expanded type`() {
    val expander = TypingExpander(emptyMap())

    val result = find(
      limit = 10,
      adapters = listOf(
        adapter(modelOf("https://e/a").withSnippet("https://e/a", "vouched")),
        // Returns a as well, without vouching: the self-scoped adapter's word is enough.
        adapter(modelOf("https://e/a", "https://e/b"), selfScoped = false),
      ),
      expander = expander,
      types = setOf(event),
    )

    assertEquals(setOf("https://e/a"), subjectsOf(result))
    assertTrue(result.contains(null, text, vf.createLiteral("vouched")))
  }

  @Test
  fun `only present hits count against the limit`() {
    // a and b sort first and are best-ranked, but nothing vouches for them and the expansion has no type.
    val overReturning = rankedModelOf("https://e/a", "https://e/b", "https://e/c")
    val expander = TypingExpander(mapOf("https://e/c" to setOf(event)))

    val result = find(
      limit = 1,
      adapters = listOf(adapter(overReturning, selfScoped = false), adapter(modelOf("https://e/d"))),
      expander = expander,
    )

    assertEquals(setOf("https://e/c"), subjectsOf(result))
  }

  @Test
  fun `the result carries the expansion of kept hits only`() {
    val expander = TypingExpander(
      mapOf("https://e/a" to setOf(event), "https://e/b" to setOf(event), "https://e/c" to setOf(event)),
    )

    val result = find(limit = 2, adapters = listOf(adapter(modelOf("https://e/a", "https://e/b", "https://e/c"))), expander = expander)

    assertEquals(setOf("https://e/a", "https://e/b", "https://e/c"), expander.lastHits)
    assertEquals(setOf("https://e/a", "https://e/b"), subjectsOf(result))
    assertTrue(result.contains(vf.createIRI("https://e/a"), RDF.TYPE, event))
    assertTrue(result.contains(vf.createIRI("https://e/b"), name, vf.createLiteral("expanded https://e/b")))
    assertTrue(result.filter(vf.createIRI("https://e/c"), null, null).isEmpty(), "the dropped hit is not expanded into the result")
  }
}
