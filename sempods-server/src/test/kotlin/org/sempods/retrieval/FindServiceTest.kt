package org.sempods.retrieval

import org.sempods.pods.grants.SempodsCredentials
import org.sempods.spec.PodRef
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [FindService]'s central logic, exercised with fake adapters / expander (no pod store
 * needed): the post-merge limit that only bites once several adapters overflow the request limit,
 * which blank nodes a kept hit keeps, and where an observation is placed. The single-adapter path is
 * covered end-to-end by `FindEndpointHttpTest`.
 */
class FindServiceTest {

  private val vf = SimpleValueFactory.getInstance()
  private val name = vf.createIRI("https://schema.org/name")
  private val findResult = vf.createIRI("https://schema.sempods.org/findResult")
  private val text = vf.createIRI("https://schema.org/text")
  private val position = vf.createIRI("https://schema.org/position")
  private val contextA = vf.createIRI("https://example.org/t/_system/contexts/A")
  private val contextB = vf.createIRI("https://example.org/t/_system/contexts/B")
  private val hit = vf.createIRI("https://example.org/t/articles/42")

  private fun modelOf(vararg iris: String): Model =
    LinkedHashModel().apply { iris.forEach { add(vf.createIRI(it), name, vf.createLiteral("x"), contextA) } }

  private fun adapter(model: Model) = object : FindAdapter {
    override val selfScoped = true
    override fun find(request: FindRequest, sandbox: FindSandbox): Model = model
  }

  /** An index that cannot scope: it answers [model] whatever the caller may read. */
  private fun index(model: Model) = object : FindAdapter {
    override fun find(request: FindRequest, sandbox: FindSandbox): Model = model
  }

  /** Expands from [store], within the sandbox, and records each call. */
  private class StoreExpander(private val store: Model) : ResourceExpander {
    val expanded = mutableListOf<Set<IRI>>()
    override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model {
      expanded += hits.toSet()
      val scope = sandbox.effectiveScope()?.mapTo(HashSet()) { it.toString() }
      return store.filterTo(LinkedHashModel()) { st ->
        st.subject in hits && (scope == null || st.context?.stringValue() in scope)
      }
    }
  }

  /** Records the hits handed to it; returns no expansion triples. */
  private class CapturingExpander : ResourceExpander {
    var lastHits: Set<String>? = null
    override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model {
      lastHits = hits.map { it.stringValue() }.toSet()
      return LinkedHashModel()
    }
  }

  /** Expands nothing, and answers [holding] for every hit it is asked about. */
  private class HoldingExpander(private val holding: Map<IRI, Set<Resource>>) : ResourceExpander {
    val asked = mutableListOf<Set<IRI>>()
    override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model = LinkedHashModel()
    override fun holdingContexts(hits: Collection<IRI>, sandbox: FindSandbox): Map<IRI, Set<Resource>> {
      asked += hits.toSet()
      return holding.filterKeys { it in hits }
    }
  }

  private fun find(vararg models: Model, limit: Int = 10, expander: ResourceExpander = CapturingExpander(), on: FindSandbox = sandbox): Model =
    FindService(adapters = models.map { adapter(it) }.toSet(), expander = expander)
      .find(FindRequest(tokens = listOf("x"), types = emptySet(), limit = limit), on)

  private val sandbox = FindSandbox(
    podUri = URI("https://example.org/t"),
    credentials = SempodsCredentials(pod = PodRef(uri = URI("https://example.org/t"), name = "t", owner = "o"), restrictedContexts = null),
    visibleContexts = null,
  )

  private fun subjectsOf(model: Model): Set<String> =
    model.subjects().filterIsInstance<IRI>().map { it.stringValue() }.toSet()

  @Test
  fun `each adapter runs under the caller's trace, and under none when the caller has none`() {
    val seen = ConcurrentLinkedQueue<String>()
    val recording = object : FindAdapter {
      override fun find(request: FindRequest, sandbox: FindSandbox): Model {
        seen += TraceContextHolder.getTraceId() ?: "none"
        return LinkedHashModel()
      }
    }
    val service = FindService(adapters = setOf(recording, adapter(LinkedHashModel())), expander = CapturingExpander())
    val request = FindRequest(tokens = listOf("x"), types = emptySet(), limit = 10)

    val trace = TraceContext.random()
    TraceContextHolder.with(trace) { service.find(request, sandbox) }
    service.find(request, sandbox)

    assertEquals(listOf(trace.traceId, "none"), seen.toList())
  }

  @Test
  fun `caps merged hits to the limit deterministically and prunes dropped hits' edges`() {
    val expander = CapturingExpander()
    val service = FindService(
      adapters = setOf(
        adapter(modelOf("https://e/a", "https://e/b")),
        adapter(modelOf("https://e/c", "https://e/d")),
      ),
      expander = expander,
    )

    val result = service.find(
      request = FindRequest(tokens = listOf("x"), types = emptySet(), limit = 2),
      sandbox = sandbox,
    )

    // 4 distinct hits across two adapters, limit 2 → deterministic IRI-order keep = {a, b}.
    assertEquals(setOf("https://e/a", "https://e/b"), subjectsOf(result))
    // Expansion ran over exactly the kept set — dropped hits c/d are gone, not expanded.
    assertEquals(setOf("https://e/a", "https://e/b"), expander.lastHits)
  }

  @Test
  fun `keeps all hits when the union is within the limit`() {
    val expander = CapturingExpander()
    val service = FindService(
      adapters = setOf(
        adapter(modelOf("https://e/a", "https://e/b")),
        adapter(modelOf("https://e/c", "https://e/d")),
      ),
      expander = expander,
    )

    val result = service.find(
      request = FindRequest(tokens = listOf("x"), types = emptySet(), limit = 10),
      sandbox = sandbox,
    )

    val all = setOf("https://e/a", "https://e/b", "https://e/c", "https://e/d")
    assertEquals(all, subjectsOf(result))
    assertEquals(all, expander.lastHits)
  }

  @Test
  fun `a kept hit keeps every observation and its nested blank nodes, and a dropped hit's go with it`() {
    val first = vf.createBNode()
    val second = vf.createBNode()
    val nested = vf.createBNode()
    val other = vf.createIRI("https://example.org/t/articles/99")
    val otherObservation = vf.createBNode()
    val adapter = LinkedHashModel().apply {
      add(hit, findResult, first, contextA)
      add(first, position, vf.createLiteral(2), contextA)
      add(hit, findResult, second, contextB)
      add(second, position, vf.createLiteral(7), contextB)
      add(second, text, nested, contextB)
      add(nested, name, vf.createLiteral("nested"), contextB)
      add(vf.createBNode(), text, vf.createLiteral("reached by no hit"), contextA)
      add(other, findResult, otherObservation, contextA)
      add(otherObservation, text, vf.createLiteral("other"), contextA)
    }

    // `articles/42` sorts before `articles/99`: limit 1 keeps it, with both of its observations.
    val result = find(adapter, limit = 1, expander = HoldingExpander(mapOf(hit to setOf(contextA, contextB))))

    assertEquals(setOf<Resource>(hit), result.subjects().filterIsInstance<IRI>().toSet())
    assertEquals(2, result.filter(hit, findResult, null).size)
    assertEquals(6, result.size, "the two observations, their positions and the nested node: $result")
    assertTrue(result.none { it.`object` == vf.createLiteral("other") || it.`object` == vf.createLiteral("reached by no hit") })
  }

  @Test
  fun `an observation the adapter placed in a context that holds the hit stays there`() {
    val observation = vf.createBNode()
    val adapter = LinkedHashModel().apply {
      add(hit, name, vf.createLiteral("Harbour lantern"), contextB)
      add(hit, findResult, observation, contextB)
      add(observation, text, vf.createLiteral("Harbour lantern"), contextB)
    }

    val expander = HoldingExpander(emptyMap())
    val result = find(adapter, expander = expander)

    assertEquals(setOf<Resource?>(contextB), result.contexts())
    assertEquals(3, result.size)
    assertTrue(expander.asked.isEmpty(), "the stored statement already names the holding context")
  }

  @Test
  fun `an observation stays in its context when the store holds the hit there, though the response shows only another`() {
    val observation = vf.createBNode()
    val adapter = LinkedHashModel().apply {
      add(hit, findResult, observation, contextB)
      add(observation, text, vf.createLiteral("Harbour lantern"), contextB)
    }
    val expander = object : ResourceExpander {
      val asked = mutableListOf<Set<IRI>>()
      override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model =
        LinkedHashModel().apply { add(hit, name, vf.createLiteral("Harbour lantern"), contextA) }
      override fun holdingContexts(hits: Collection<IRI>, sandbox: FindSandbox): Map<IRI, Set<Resource>> {
        asked += hits.toSet()
        return mapOf(hit to setOf(contextA, contextB))
      }
    }

    val result = find(adapter, expander = expander)

    assertEquals(listOf(setOf<IRI>(hit)), expander.asked)
    val link = result.filter(hit, findResult, null).single()
    assertEquals(contextB, link.context)
    assertEquals(contextB, result.filter(assertIs<BNode>(link.`object`), text, null).single().context)
  }

  @Test
  fun `an observation without a context moves to the first context that holds the hit, details with their link`() {
    val observation = vf.createBNode()
    val adapter = LinkedHashModel().apply {
      add(hit, findResult, observation)
      add(observation, text, vf.createLiteral("Harbour lantern"))
    }

    val result = find(adapter, expander = HoldingExpander(mapOf(hit to setOf(contextB, contextA))))

    val link = result.filter(hit, findResult, null).single()
    assertEquals(contextA, link.context)
    val node = assertIs<BNode>(link.`object`)
    assertEquals(contextA, result.filter(node, text, null).single().context)
  }

  @Test
  fun `an observation the adapter attributes to a context outside the selection is dropped, not relabelled`() {
    val observation = vf.createBNode()
    val outside = vf.createIRI("https://example.org/t/_system/contexts/C")
    val adapter = LinkedHashModel().apply {
      add(hit, name, vf.createLiteral("Harbour map"), contextA)
      add(hit, findResult, observation, outside)
      add(observation, text, vf.createLiteral("Harbour map"), outside)
    }

    val result = find(adapter, on = scopedTo(contextA, contextB), expander = HoldingExpander(mapOf(hit to setOf(contextA))))

    assertEquals(1, result.size, "$result")
    assertEquals(vf.createLiteral("Harbour map"), result.single().`object`)
  }

  @Test
  fun `a candidate no context holds takes no place under the limit`() {
    val stale = vf.createIRI("https://example.org/t/a")
    val held = vf.createIRI("https://example.org/t/b")
    val observationOnly = LinkedHashModel().apply { add(stale, findResult, vf.createBNode()) }
    val stored = LinkedHashModel().apply { add(held, name, vf.createLiteral("Harbour map"), contextA) }

    val expander = HoldingExpander(emptyMap())
    val result = find(observationOnly, stored, limit = 1, expander = expander)

    assertEquals(listOf(setOf(stale)), expander.asked)
    assertEquals(setOf<Resource>(held), result.subjects().filterIsInstance<IRI>().toSet())
  }

  @Test
  fun `a bare link survives when the store says which context holds the hit, and the hit is dropped when none does`() {
    val unheld = vf.createIRI("https://example.org/t/articles/43")
    val adapter = LinkedHashModel().apply {
      add(hit, findResult, vf.createBNode())
      add(unheld, findResult, vf.createBNode())
    }

    val expander = HoldingExpander(mapOf(hit to setOf(contextA)))
    val result = find(adapter, expander = expander)

    assertEquals(listOf(setOf(hit, unheld)), expander.asked)
    assertEquals(setOf<Resource>(hit), result.subjects().filterIsInstance<IRI>().toSet())
    assertEquals(contextA, result.single().context)
  }

  @Test
  fun `a statement about a hit that is neither stored in a context nor an observation is dropped`() {
    val adapter = LinkedHashModel().apply {
      add(hit, name, vf.createLiteral("Harbour map"), contextA)
      add(hit, name, vf.createLiteral("a claim no context holds"))
      add(hit, text, vf.createLiteral("stored outside the selection"), vf.createIRI("https://example.org/t/_system/contexts/C"))
    }

    val result = find(adapter, on = scopedTo(contextA))

    assertEquals(1, result.size, "$result")
    assertEquals(vf.createLiteral("Harbour map"), result.single().`object`)
  }

  @Test
  fun `two adapters' blank nodes stay apart even when they share a label`() {
    fun labelled(text: String) = LinkedHashModel().apply {
      add(hit, findResult, vf.createBNode("m"), contextA)
      add(vf.createBNode("m"), this@FindServiceTest.text, vf.createLiteral(text), contextA)
    }

    val result = find(labelled("one"), labelled("two"), expander = HoldingExpander(mapOf(hit to setOf(contextA))))

    val observations = result.filter(hit, findResult, null).objects()
    assertEquals(2, observations.size)
    observations.forEach { assertEquals(1, result.filter(it as Resource, text, null).size) }
  }

  @Test
  fun `a selection that matches nothing asks no adapter`() {
    var asked = false
    val adapter = object : FindAdapter {
      override fun find(request: FindRequest, sandbox: FindSandbox): Model = LinkedHashModel().also { asked = true }
    }

    val result = FindService(setOf(adapter), CapturingExpander())
      .find(FindRequest(tokens = listOf("x"), types = emptySet(), limit = 10), scopedTo())

    assertTrue(result.isEmpty())
    assertTrue(!asked)
  }

  @Test
  fun `the plain result is the merged hits plus their expansion, unchanged by placement`() {
    val adapter = modelOf("https://e/a", "https://e/b")
    val expansion = LinkedHashModel().apply { add(vf.createIRI("https://e/a"), name, vf.createLiteral("A"), contextB) }
    val expander = object : ResourceExpander {
      override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model = expansion
    }

    val result = find(adapter, expander = expander)

    assertTrue(Models.isomorphic(LinkedHashModel(adapter).apply { addAll(expansion) }, result))
  }

  @Test
  fun `an expander that overrides only expand still confirms a hit seen only through an observation`() {
    val observation = vf.createBNode()
    val adapter = LinkedHashModel().apply {
      add(hit, findResult, observation)
      add(observation, text, vf.createLiteral("Harbour lantern"))
    }
    val expander = object : ResourceExpander {
      override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model =
        LinkedHashModel().apply { if (hit in hits) add(hit, name, vf.createLiteral("Harbour lantern"), contextA) }
    }

    val result = find(adapter, expander = expander)

    val link = result.filter(hit, findResult, null).single()
    assertEquals(contextA, link.context)
    assertEquals(contextA, result.filter(assertIs<BNode>(link.`object`), text, null).single().context)
    assertEquals(contextA, result.filter(hit, name, null).single().context)
  }

  @Test
  fun `an expansion statement without a context is dropped, with an observation and without one`() {
    val stored = modelOf(hit.stringValue())
    val unsourced = object : ResourceExpander {
      override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model =
        LinkedHashModel().apply { add(hit, vf.createIRI("http://www.w3.org/2000/01/rdf-schema#label"), vf.createLiteral("no source")) }
    }
    val observation = vf.createBNode()
    val observed = LinkedHashModel(stored).apply { add(hit, findResult, observation) }

    listOf(stored, observed).forEach { adapter ->
      val result = find(adapter, expander = unsourced)

      assertTrue(result.none { it.context == null }, "a statement left the find with no context: $result")
      assertEquals(adapter.size, result.size, "the unsourced label is dropped and nothing else is: $result")
    }
  }

  private val rdfType = vf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
  private val article = vf.createIRI("https://schema.org/Article")
  private val event = vf.createIRI("https://schema.org/Event")

  private fun observed(model: Model, of: IRI, at: Int? = null, nested: Boolean = false): BNode {
    val observation = vf.createBNode()
    model.add(of, findResult, observation)
    model.add(observation, text, vf.createLiteral("…match…"))
    at?.let { model.add(observation, position, vf.createLiteral(it)) }
    if (nested) vf.createBNode().also { detail ->
      model.add(observation, vf.createIRI("https://schema.org/about"), detail)
      model.add(detail, name, vf.createLiteral("detail"))
    }
    return observation
  }

  private fun search(vararg adapters: FindAdapter, expander: ResourceExpander, types: Set<IRI> = emptySet(), limit: Int = 10, on: FindSandbox = sandbox): Model =
    FindService(adapters = adapters.toSet(), expander = expander)
      .find(FindRequest(tokens = listOf("x"), types = types, limit = limit), on)

  @Test
  fun `under the limit a hit ranks by its lowest position, and one with none ranks last`() {
    val a = vf.createIRI("https://e/a")
    val b = vf.createIRI("https://e/b")
    val c = vf.createIRI("https://e/c")
    val first = modelOf("https://e/a", "https://e/b").apply { observed(this, b, at = 7) }
    val second = modelOf("https://e/c").apply {
      observed(this, c, at = 9)
      observed(this, c, at = 2)
    }

    assertEquals(setOf(c.stringValue(), b.stringValue()), subjectsOf(find(first, second, limit = 2)))
    assertEquals(setOf(c.stringValue()), subjectsOf(find(first, second, limit = 1)))
    assertEquals(setOf(a, b, c).map { it.stringValue() }.toSet(), subjectsOf(find(first, second, limit = 3)))
  }

  @Test
  fun `an index's hit stays only where the store shows it typed in the selection, observations included`() {
    val held = vf.createIRI("https://e/held")
    val hidden = vf.createIRI("https://e/hidden")
    val untyped = vf.createIRI("https://e/untyped")
    val store = LinkedHashModel().apply {
      add(held, rdfType, article, contextA)
      add(hidden, rdfType, article, contextB)
      add(untyped, name, vf.createLiteral("no type"), contextA)
    }
    val answer = LinkedHashModel().apply { listOf(held, hidden, untyped).forEach { observed(this, it) } }

    val result = search(index(answer), expander = StoreExpander(store), on = scopedTo(contextA))

    assertEquals(setOf(held.stringValue()), subjectsOf(result))
    assertEquals(1, result.filter(null, findResult, null).size, "only the kept hit's observation is left: $result")
    assertTrue(result.filter(null, rdfType, null).all { it.context == contextA }, "the expansion stays in the selection: $result")
  }

  @Test
  fun `an index's hit whose stored type is not a requested one is dropped`() {
    val store = LinkedHashModel().apply { add(hit, rdfType, event, contextA) }
    val answer = LinkedHashModel().apply { observed(this, hit) }

    assertTrue(search(index(answer), expander = StoreExpander(store), types = setOf(article)).isEmpty())
    assertEquals(setOf(hit.stringValue()), subjectsOf(search(index(answer), expander = StoreExpander(store), types = setOf(event))))
  }

  @Test
  fun `an index's stored statement stands only in a context the store holds its subject in`() {
    val store = LinkedHashModel().apply { add(hit, rdfType, article, contextA) }
    val answer = LinkedHashModel().apply {
      add(hit, name, vf.createLiteral("as stored"), contextA)
      add(hit, name, vf.createLiteral("from elsewhere"), contextB)
    }

    val result = search(index(answer), expander = StoreExpander(store))

    assertEquals(setOf(vf.createLiteral("as stored")), result.filter(hit, name, null).objects())
  }

  @Test
  fun `an index's kept hit keeps the blank nodes it reaches, expanded once`() {
    val store = LinkedHashModel().apply { add(hit, rdfType, article, contextA) }
    val answer = LinkedHashModel().apply { observed(this, hit, at = 1, nested = true) }
    val expander = StoreExpander(store)

    val result = search(index(answer), expander = expander)

    assertEquals(answer.size + store.size, result.size, "every observation statement and the expansion: $result")
    assertTrue(result.all { it.context == contextA }, "placed where the store holds the hit: $result")
    assertEquals(listOf(setOf(hit)), expander.expanded, "the check's expansion serves the kept hit")
  }

  @Test
  fun `a hit a self-scoped adapter returned is not checked, though an index returned it too`() {
    val trusted = modelOf(hit.stringValue())
    val answer = LinkedHashModel().apply { observed(this, hit) }
    val expander = StoreExpander(LinkedHashModel())

    val result = search(adapter(trusted), index(answer), expander = expander)

    assertEquals(setOf(hit.stringValue()), subjectsOf(result))
    assertEquals(1, result.filter(hit, findResult, null).size, "the index's observation stays with the hit: $result")
  }

  private fun scopedTo(vararg contexts: IRI) = FindSandbox(
    podUri = sandbox.podUri,
    credentials = sandbox.credentials,
    visibleContexts = null,
    contextFilter = contexts.mapTo(HashSet()) { URI(it.stringValue()) },
  )
}
