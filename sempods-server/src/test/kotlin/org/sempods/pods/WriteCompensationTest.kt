package org.sempods.pods

import org.sempods.ontologies.Ontologies
import org.sempods.pods.changes.Durability
import org.sempods.pods.changes.PodChangeDispatcher
import org.sempods.pods.changes.PodChangeListener
import org.sempods.pods.changes.PodChangeSet
import org.sempods.rdf.toIri
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Docker-free test of the write path's compensating rollback — the guarantee the pod's durability
 * rests on since the legacy decommission: the backup collection is the only
 * durable persistence, so a write the critical sink did not record must not be reported as
 * successful, and must not linger in the store either (the next restart would drop it).
 *
 * Drives [InMemoryPodRepository] directly against a bare [MemoryStore], with the sink replaced by a
 * listener that fails on demand.
 */
class WriteCompensationTest {

  private class FailingListener(override val durability: Durability) : PodChangeListener {
    override fun onChange(changeSet: PodChangeSet) = throw RuntimeException("sink is down")
  }

  private val context = Values.iri("https://pods.test/compensation/contexts/main")

  private fun repositoryWith(listener: PodChangeListener): PodRepository {
    val rdfRepository = SailRepository(MemoryStore())
    rdfRepository.init()
    return InMemoryPodRepository(ObjectId(), "test-pod", rdfRepository, PodChangeDispatcher(setOf(listener)))
  }

  private fun eventModel(resourceUri: URI) = LinkedHashModel().apply {
    add(resourceUri.toIri(), RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
    add(resourceUri.toIri(), Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Compensate me"), context)
  }

  @Test
  fun `a critical sink failure fails the write and leaves no trace in the store`() {
    val repo = repositoryWith(FailingListener(Durability.CRITICAL))
    val resourceUri = URI("https://example.org/comp-${System.nanoTime()}")

    assertFailsWith<IllegalStateException> { repo.putResource(resourceUri, eventModel(resourceUri)) }

    repo.withConnection { conn ->
      assertFalse(
        conn.hasStatement(resourceUri.toIri(), null, null, false),
        "the store must be compensated — a write the backup did not record must not survive in memory",
      )
    }
  }

  @Test
  fun `a critical sink failure on an update restores the previous state, not an empty one`() {
    val resourceUri = URI("https://example.org/comp-upd-${System.nanoTime()}")
    val rdfRepository = SailRepository(MemoryStore())
    rdfRepository.init()

    var failing = false
    val listener = object : PodChangeListener {
      override val durability = Durability.CRITICAL
      override fun onChange(changeSet: PodChangeSet) {
        if (failing) throw RuntimeException("sink is down")
      }
    }
    val repo = InMemoryPodRepository(ObjectId(), "test-pod", rdfRepository, PodChangeDispatcher(setOf(listener)))
    assertTrue(repo.putResource(resourceUri, eventModel(resourceUri)))

    failing = true
    val updated = LinkedHashModel().apply {
      add(resourceUri.toIri(), RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceUri.toIri(), Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Never persisted"), context)
    }
    assertFailsWith<IllegalStateException> { repo.putResource(resourceUri, updated) }

    // The undo log replays the delta both ways: the failed update is gone, the prior state is back.
    repo.withConnection { conn ->
      assertTrue(conn.hasStatement(resourceUri.toIri(), Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Compensate me"), false, context))
      assertFalse(conn.hasStatement(resourceUri.toIri(), Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Never persisted"), false, context))
    }
  }

  @Test
  fun `a best-effort sink failure leaves the write applied`() {
    val repo = repositoryWith(FailingListener(Durability.BEST_EFFORT))
    val resourceUri = URI("https://example.org/comp-be-${System.nanoTime()}")

    assertTrue(repo.putResource(resourceUri, eventModel(resourceUri)))

    repo.withConnection { conn ->
      assertTrue(
        conn.hasStatement(resourceUri.toIri(), null, null, false),
        "a lagging best-effort sink must never roll a user's write back",
      )
    }
  }
}
