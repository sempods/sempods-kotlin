package org.sempods.pods

import org.sempods.pods.changes.ChangeOperation
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Docker-free unit test of the change-capture path: drives [InMemoryPodRepository] against a real
 * [MemoryStore] and asserts the [PodChangeSet] handed to listeners (operation classification,
 * blank-node closure, net delta with churn netting, multi-resource grouping). No sinks are wired —
 * the dispatcher only carries a recording listener, since the write path is store-only.
 */
class PodChangeCaptureTest {

  private val type = RDF.TYPE
  private val event = Values.iri("https://schema.org/Event")
  private val name = Values.iri("https://schema.org/name")
  private val ctx = Values.iri("https://pods.test/ctx/main")
  private val otherCtx = Values.iri("https://pods.test/ctx/other")

  private lateinit var repo: InMemoryPodRepository
  private lateinit var recorder: RecordingListener
  private lateinit var rdfRepository: SailRepository

  private class RecordingListener : PodChangeListener {
    val received = mutableListOf<PodChangeSet>()
    override val durability = Durability.CRITICAL
    override fun onChange(changeSet: PodChangeSet) {
      received.add(changeSet)
    }

    fun last(): PodChangeSet = received.last()
  }

  @BeforeEach
  fun setUp() {
    rdfRepository = SailRepository(MemoryStore())
    rdfRepository.init()
    recorder = RecordingListener()
    repo = InMemoryPodRepository(
      podId = ObjectId(),
      podName = "test-pod",
      rdfRepository = rdfRepository,
      podChangeDispatcher = PodChangeDispatcher(setOf(recorder)),
    )
  }

  @AfterEach
  fun tearDown() {
    rdfRepository.shutDown()
  }

  @Test
  fun `create then identical write then update`() {
    val uri = URI("https://example.org/e1")
    val iri = uri.toIri()
    val v1 = LinkedHashModel().apply {
      add(iri, type, event, ctx)
      add(iri, name, Values.literal("A"), ctx)
    }

    assertTrue(repo.putResource(uri, v1))

    val created = recorder.last().resources.single()
    assertEquals(ChangeOperation.CREATED, created.operation)
    assertEquals(setOf(ctx), created.contexts, "the touched named graph is surfaced first-class")
    assertEquals(2, created.after.size)
    assertEquals(2, created.added.size)
    assertTrue(created.removed.isEmpty())

    // Identical, non-forced write is a no-op — no change event.
    val before = recorder.received.size
    assertTrue(!repo.putResource(uri, v1))
    assertEquals(before, recorder.received.size)

    // Update only the name: the unchanged type triple is removed-then-re-added by replace-all
    // and must net out of the delta.
    val v2 = LinkedHashModel().apply {
      add(iri, type, event, ctx)
      add(iri, name, Values.literal("B"), ctx)
    }
    assertTrue(repo.putResource(uri, v2))

    val updated = recorder.last().resources.single()
    assertEquals(ChangeOperation.UPDATED, updated.operation)
    assertEquals(1, updated.added.size, "churn-netted: only the new name triple")
    assertTrue(updated.added.contains(iri, name, Values.literal("B"), ctx))
    assertEquals(1, updated.removed.size, "churn-netted: only the old name triple")
    assertTrue(updated.removed.contains(iri, name, Values.literal("A"), ctx))
  }

  @Test
  fun `an identical re-write is a no-op with no change event`() {
    val uri = URI("https://example.org/e-noop")
    val iri = uri.toIri()
    val model = LinkedHashModel().apply {
      add(iri, type, event, ctx)
      add(iri, name, Values.literal("A"), ctx)
    }
    repo.putResource(uri, model)
    val afterCreate = recorder.received.size

    // Identical content ⇒ genuine no-op: returns false, no change event, no pod-timestamp bump.
    // (Patch/slot writes that don't change the RDF now report no-op — they no longer force.)
    assertTrue(!repo.putResource(uri, model))
    assertEquals(afterCreate, recorder.received.size)
  }

  @Test
  fun `putResource rejects non-IRI contexts and blank nodes before any store mutation`() {
    val uri = URI("https://example.org/e-badctx")
    val iri = uri.toIri()

    // Default graph (null context) and blank-node context are both rejected up front.
    val defaultGraph = LinkedHashModel().apply { add(iri, type, event) }
    assertFailsWith<IllegalArgumentException> { repo.putResource(uri, defaultGraph) }

    val bnodeContext = LinkedHashModel().apply { add(iri, type, event, Values.bnode()) }
    assertFailsWith<IllegalArgumentException> { repo.putResource(uri, bnodeContext) }

    // Blank nodes are forbidden outright (no anonymous nested values).
    val bnodeObject = LinkedHashModel().apply { add(iri, name, Values.bnode(), ctx) }
    assertFailsWith<IllegalArgumentException> { repo.putResource(uri, bnodeObject) }

    // Fail-fast before the store mutation: nothing dispatched, nothing written.
    assertTrue(recorder.received.isEmpty())
    repo.withConnection { conn -> assertFalse(conn.hasStatement(iri, null, null, false)) }
  }

  @Test
  fun `delete reports DELETED with empty after and full removed`() {
    val uri = URI("https://example.org/e2")
    val iri = uri.toIri()
    repo.putResource(uri, LinkedHashModel().apply { add(iri, type, event, ctx) })

    assertTrue(repo.deleteResource(uri))

    val change = recorder.last().resources.single()
    assertEquals(ChangeOperation.DELETED, change.operation)
    assertTrue(change.after.isEmpty())
    assertEquals(1, change.removed.size)
    assertTrue(change.added.isEmpty())
  }

  @Test
  fun `listener failure rolls the store change back via the captured undo log`() {
    val failing = object : PodChangeListener {
      override val durability = Durability.CRITICAL
      override fun onChange(changeSet: PodChangeSet) = throw RuntimeException("boom")
    }
    val failingRepo = InMemoryPodRepository(
      podId = ObjectId(),
      podName = "test-pod",
      rdfRepository = rdfRepository,
      podChangeDispatcher = PodChangeDispatcher(setOf(failing)),
    )
    val uri = URI("https://example.org/e6")
    val iri = uri.toIri()
    val model = LinkedHashModel().apply { add(iri, type, event, ctx) }

    assertFailsWith<IllegalStateException> { failingRepo.putResource(uri, model) }

    // The committed create was undone — the store no longer holds the resource.
    rdfRepository.connection.use { conn ->
      assertFalse(conn.hasStatement(iri, null, null, false))
    }
  }

  @Test
  fun `removeContext yields one grouped change per affected resource`() {
    val uri1 = URI("https://example.org/e4")
    val uri2 = URI("https://example.org/e5")
    val iri1 = uri1.toIri()
    val iri2 = uri2.toIri()
    repo.putResource(uri1, LinkedHashModel().apply {
      add(iri1, type, event, ctx)
      add(iri1, name, Values.literal("kept-1"), otherCtx)
    })
    repo.putResource(uri2, LinkedHashModel().apply {
      add(iri2, type, event, ctx)
      add(iri2, name, Values.literal("kept-2"), otherCtx)
    })

    assertTrue(repo.removeContext(URI(ctx.toString())))

    val change = recorder.last()
    assertEquals(2, change.resources.size)
    change.resources.forEach { rc ->
      assertEquals(ChangeOperation.UPDATED, rc.operation)
      assertEquals(setOf(ctx), rc.contexts, "only the removed context is reported as touched")
      assertEquals(1, rc.removed.size, "only the ctx triple removed")
      assertTrue(rc.removed.contains(null, type, event, ctx))
      assertEquals(1, rc.after.size, "the otherCtx triple survives")
    }
  }
}
