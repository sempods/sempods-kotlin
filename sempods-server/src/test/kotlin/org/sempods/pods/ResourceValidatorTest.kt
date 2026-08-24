package org.sempods.pods

import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Docker-free unit test of [ResourceValidator]: a deterministic, content-sensitive hash. */
class ResourceValidatorTest {

  private val s = Values.iri("https://example.org/r1")
  private val ctx = Values.iri("https://pods.test/ctx/main")
  private val event = Values.iri("https://schema.org/Event")
  private val name = Values.iri("https://schema.org/name")

  @Test
  fun `same statements in a different order hash to the same value`() {
    val a = LinkedHashModel().apply {
      add(s, RDF.TYPE, event, ctx)
      add(s, name, Values.literal("A"), ctx)
    }
    val b = LinkedHashModel().apply {
      add(s, name, Values.literal("A"), ctx)
      add(s, RDF.TYPE, event, ctx)
    }
    assertEquals(ResourceValidator.compute(a), ResourceValidator.compute(b), "validator must be order-independent")
  }

  @Test
  fun `a content change changes the validator`() {
    val a = LinkedHashModel().apply { add(s, name, Values.literal("A"), ctx) }
    val b = LinkedHashModel().apply { add(s, name, Values.literal("B"), ctx) }
    assertNotEquals(ResourceValidator.compute(a), ResourceValidator.compute(b))
  }

  @Test
  fun `the same context statement in a different graph changes the validator`() {
    val otherCtx = Values.iri("https://pods.test/ctx/other")
    val a = LinkedHashModel().apply { add(s, name, Values.literal("A"), ctx) }
    val b = LinkedHashModel().apply { add(s, name, Values.literal("A"), otherCtx) }
    assertNotEquals(ResourceValidator.compute(a), ResourceValidator.compute(b), "context is part of the validator")
  }
}
