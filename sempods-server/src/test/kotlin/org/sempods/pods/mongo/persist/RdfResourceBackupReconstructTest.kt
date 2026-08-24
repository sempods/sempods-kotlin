package org.sempods.pods.mongo.persist

import org.sempods.rdf.RdfWriterUtil
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Docker-free unit test of [reconstructModel]: per-context N-Quads rows merge back into one model. */
class RdfResourceBackupReconstructTest {

  @Test
  fun `reconstructModel merges per-context rows back into one isomorphic model`() {
    val podId = ObjectId()
    val s = Values.iri("https://example.org/r1")
    val ctxA = Values.iri("https://pods.test/ctx/a")
    val ctxB = Values.iri("https://pods.test/ctx/b")
    val event = Values.iri("https://schema.org/Event")
    val name = Values.iri("https://schema.org/name")

    val full = LinkedHashModel().apply {
      add(s, RDF.TYPE, event, ctxA)
      add(s, name, Values.literal("A"), ctxA)
      add(s, name, Values.literal("B"), ctxB)
    }
    val rowA = LinkedHashModel().apply {
      add(s, RDF.TYPE, event, ctxA)
      add(s, name, Values.literal("A"), ctxA)
    }
    val rowB = LinkedHashModel().apply { add(s, name, Values.literal("B"), ctxB) }

    val rows = listOf(
      RdfResourceBackupDbo(podId = podId, resourceUri = s.stringValue(), context = ctxA.stringValue(), nquads = RdfWriterUtil.writeNQuads(rowA)),
      RdfResourceBackupDbo(podId = podId, resourceUri = s.stringValue(), context = ctxB.stringValue(), nquads = RdfWriterUtil.writeNQuads(rowB)),
    )

    assertTrue(Models.isomorphic(full, reconstructModel(rows)), "merged rows must reconstruct the full model, incl. contexts")
  }
}
