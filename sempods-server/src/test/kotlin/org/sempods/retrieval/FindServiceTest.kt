package org.sempods.retrieval

import org.sempods.pods.grants.SempodsCredentials
import org.sempods.spec.PodRef
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

/**
 * Unit tests for [FindService]'s central merge + limit logic, exercised with fake adapters /
 * expander (no pod store needed). Focus: the post-merge limit that only bites once several
 * adapters overflow the request limit — the single-adapter path is covered end-to-end by
 * `FindEndpointHttpTest`.
 */
class FindServiceTest {

  private val vf = SimpleValueFactory.getInstance()
  private val name = vf.createIRI("https://schema.org/name")

  private fun modelOf(vararg iris: String): Model =
    LinkedHashModel().apply { iris.forEach { add(vf.createIRI(it), name, vf.createLiteral("x")) } }

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

  private fun subjectsOf(model: Model): Set<String> =
    model.subjects().filterIsInstance<IRI>().map { it.stringValue() }.toSet()

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
}
