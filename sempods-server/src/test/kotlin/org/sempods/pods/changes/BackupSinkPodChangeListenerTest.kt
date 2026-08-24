package org.sempods.pods.changes

import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.RdfResourceBackupDao
import org.sempods.rdf.RdfWriterUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.bson.types.ObjectId
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Docker-free unit test of the per-`(resource, context)` backup sink: N-Quads upsert, delete, the
 * bounded retry that bounds a partially applied multi-row set (see the class KDoc), and the net row
 * delta the sink applies to the pod's `resourceRowCount`.
 *
 * The DAO mock is what decides whether a row *appeared*: `upsert` answers `true` only for an insert
 * and `delete` only for a row that was there, which is the distinction the real DAO reads off its
 * own fetch. A relaxed mock answers `false` to both, so a test that says nothing about it is
 * describing a pure replacement — which is exactly the change set that must move no count.
 */
class BackupSinkPodChangeListenerTest {

  private val podId = ObjectId()
  private val ctx = Values.iri("https://pods.test/ctx/main")
  private val ctxUri = URI(ctx.stringValue())
  private val event = Values.iri("https://schema.org/Event")
  private val name = Values.iri("https://schema.org/name")

  @Test
  fun `a written context is mirrored as N-Quads that round-trip isomorphic`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val uri = URI("https://example.org/bk-1")
    val iri = uri.toIri()
    val after = LinkedHashModel().apply {
      add(iri, RDF.TYPE, event, ctx)
      add(iri, name, Values.literal("A"), ctx)
    }

    BackupSinkPodChangeListener(dao, mockk(relaxed = true)).onChange(
      PodChangeSet(podId, "pod", listOf(ResourceChange(uri, ChangeOperation.UPDATED, setOf(ctx), after, after, LinkedHashModel()))),
    )

    val nquads = slot<String>()
    verify { dao.upsert(podId, uri, ctxUri, capture(nquads), any()) }
    val restored = RdfWriterUtil.readNQuads(nquads.captured.byteInputStream())
    assertTrue(Models.isomorphic(after, restored), "backup N-Quads must round-trip isomorphic, incl. context")
  }

  @Test
  fun `a removed context deletes its backup row and writes nothing`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val uri = URI("https://example.org/bk-2")
    val iri = uri.toIri()
    // Full delete: after empty, the touched context is no longer present → its row is dropped.
    val removed = LinkedHashModel().apply { add(iri, RDF.TYPE, event, ctx) }

    BackupSinkPodChangeListener(dao, mockk(relaxed = true)).onChange(
      PodChangeSet(podId, "pod", listOf(ResourceChange(uri, ChangeOperation.DELETED, setOf(ctx), LinkedHashModel(), LinkedHashModel(), removed))),
    )

    verify { dao.delete(podId, uri, ctxUri) }
    verify(exactly = 0) { dao.upsert(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `a transient failure mid-set is retried until the whole set applied`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val first = URI("https://example.org/bk-r1")
    val second = URI("https://example.org/bk-r2")
    // The second row fails once. Since rows are idempotent full replacements, the retry re-runs the
    // whole set — so the first row is written twice and the set ends fully applied.
    every { dao.upsert(any(), first, any(), any(), any()) } returnsMany listOf(true, false)
    var calls = 0
    every { dao.upsert(any(), second, any(), any(), any()) } answers {
      if (calls++ == 0) throw RuntimeException("blip") else true
    }

    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(upsert(first), upsert(second)))

    verify(exactly = 2) { dao.upsert(podId, first, ctxUri, any(), any()) }
    verify(exactly = 2) { dao.upsert(podId, second, ctxUri, any(), any()) }
    // The count is the reason the delta is accumulated across attempts rather than per attempt:
    // the first row inserted in attempt 1 comes back as a replacement in attempt 2, so a
    // per-attempt count would report the set as one new row instead of two.
    verify(exactly = 1) { podDao.bumpResourceRowCount(podId, 2L) }
  }

  @Test
  fun `a persistent failure propagates after the bounded retries`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val uri = URI("https://example.org/bk-r3")
    every { dao.upsert(any(), uri, any(), any(), any()) } throws RuntimeException("mongo down")

    // Propagating is the point: it is what makes the write path compensate the store and fail the
    // request, instead of reporting success for a change the backup never took.
    assertFailsWith<RuntimeException> { BackupSinkPodChangeListener(dao, mockk(relaxed = true)).onChange(changeSet(upsert(uri))) }

    verify(exactly = 3) { dao.upsert(podId, uri, ctxUri, any(), any()) }
  }

  @Test
  fun `a new row counts up and a replacement alongside it counts for nothing`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val created = URI("https://example.org/bk-c1")
    val replaced = URI("https://example.org/bk-c2")
    every { dao.upsert(any(), created, any(), any(), any()) } returns true
    every { dao.upsert(any(), replaced, any(), any(), any()) } returns false

    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(upsert(created), upsert(replaced)))

    // One row appeared; the other was already there and is only newer now.
    verify(exactly = 1) { podDao.bumpResourceRowCount(podId, 1L) }
  }

  @Test
  fun `a set that moves no row issues no count write at all`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    // The common write: an existing resource edited in place. Nothing appeared, nothing vanished.
    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(upsert(URI("https://example.org/bk-c4"))))

    verify(exactly = 0) { podDao.bumpResourceRowCount(any(), any()) }
  }

  @Test
  fun `rows written before a set gave up are still counted`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val written = URI("https://example.org/bk-c5")
    val doomed = URI("https://example.org/bk-c6")
    // The first row inserts on the first attempt and replaces on the two retries; the second never
    // lands. The request fails and the store is compensated — but the first row is on disk, and a
    // count that ignored it would sit below the collection for good.
    every { dao.upsert(any(), written, any(), any(), any()) } returnsMany listOf(true, false, false)
    every { dao.upsert(any(), doomed, any(), any(), any()) } throws RuntimeException("mongo down")

    assertFailsWith<RuntimeException> {
      BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(upsert(written), upsert(doomed)))
    }

    verify(exactly = 1) { podDao.bumpResourceRowCount(podId, 1L) }
  }

  @Test
  fun `a count write that adds is retried, and still does not fail the write`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val uri = URI("https://example.org/bk-c7")
    every { dao.upsert(any(), uri, any(), any(), any()) } returns true
    every { podDao.bumpResourceRowCount(any(), any()) } throws RuntimeException("count write down")

    // The sign decides. Repeating a delta that adds risks applying it twice, which lands the count
    // above the collection — reported once by the next recovery and lowered. Not repeating it risks
    // losing it, which lands below, where nothing ever looks. So this one is retried.
    // Not thrown either: the rows landed, so the write path must not be told otherwise.
    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(upsert(uri)))

    verify(exactly = 3) { podDao.bumpResourceRowCount(podId, 1L) }
  }

  @Test
  fun `a count write that adds is retried only until it lands`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val uri = URI("https://example.org/bk-c17")
    every { dao.upsert(any(), uri, any(), any(), any()) } returns true
    var calls = 0
    every { podDao.bumpResourceRowCount(any(), any()) } answers { if (calls++ == 0) throw RuntimeException("blip") }

    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(upsert(uri)))

    verify(exactly = 2) { podDao.bumpResourceRowCount(podId, 1L) }
  }

  @Test
  fun `a set that drops rows subtracts them before it drops them`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val first = URI("https://example.org/bk-c9")
    val second = URI("https://example.org/bk-c10")
    every { dao.delete(any(), any(), any()) } returns true

    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(delete(first), delete(second)))

    // The subtraction lands before a single row goes, so a recovery reading mid-set sees the count
    // below the collection rather than above it — and there is nothing left to correct afterwards.
    verifyOrder {
      podDao.bumpResourceRowCount(podId, -2L)
      dao.delete(podId, first, ctxUri)
      dao.delete(podId, second, ctxUri)
    }
    verify(exactly = 1) { podDao.bumpResourceRowCount(any(), any()) }
  }

  @Test
  fun `a delete that finds nothing is added back at the end`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val present = URI("https://example.org/bk-c11")
    val alreadyGone = URI("https://example.org/bk-c12")
    every { dao.delete(any(), present, any()) } returns true
    every { dao.delete(any(), alreadyGone, any()) } returns false

    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(delete(present), delete(alreadyGone)))

    // Subtracting up front is a guess — two rows were planned, one was already gone — so the
    // closing write adds back what did not happen.
    verifyOrder {
      podDao.bumpResourceRowCount(podId, -2L)
      podDao.bumpResourceRowCount(podId, 1L)
    }
  }

  @Test
  fun `an insert alongside a delete is added only after the subtraction`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val created = URI("https://example.org/bk-c13")
    val dropped = URI("https://example.org/bk-c14")
    every { dao.upsert(any(), created, any(), any(), any()) } returns true
    every { dao.delete(any(), dropped, any()) } returns true

    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(upsert(created), delete(dropped)))

    // Never a single net `$inc` of zero: what adds waits for its row, what removes goes first.
    verifyOrder {
      podDao.bumpResourceRowCount(podId, -1L)
      podDao.bumpResourceRowCount(podId, 1L)
    }
  }

  @Test
  fun `a subtraction whose outcome is unknown is not applied a second time`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val dropped = URI("https://example.org/bk-c15")
    every { dao.delete(any(), dropped, any()) } returns true
    every { podDao.bumpResourceRowCount(any(), any()) } throws RuntimeException("response lost")

    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(delete(dropped)))

    // A write that failed is not a write that did not happen — the server may have applied it and
    // lost the answer. Repeating it would double the subtraction, and a count below the collection
    // is the mistake nothing ever reports. Issued once, and the closing write owes nothing.
    verify(exactly = 1) { podDao.bumpResourceRowCount(podId, -1L) }
    verify(exactly = 1) { podDao.bumpResourceRowCount(any(), any()) }
  }

  @Test
  fun `a delete whose outcome the retry could not recover is added back`() {
    val dao = mockk<RdfResourceBackupDao>(relaxed = true)
    val podDao = mockk<PodDao>(relaxed = true)
    val dropped = URI("https://example.org/bk-c16")
    // The row goes on the first attempt but the answer is lost, so the set is retried; the second
    // attempt finds it already absent and can say nothing about who removed it.
    var calls = 0
    every { dao.delete(any(), dropped, any()) } answers {
      if (calls++ == 0) throw RuntimeException("response lost") else false
    }

    BackupSinkPodChangeListener(dao, podDao).onChange(changeSet(delete(dropped)))

    // Added back on purpose: the count may now sit above the collection, which the next recovery
    // reports once and lowers. Assuming the row went would leave it below, where nothing looks.
    verifyOrder {
      podDao.bumpResourceRowCount(podId, -1L)
      podDao.bumpResourceRowCount(podId, 1L)
    }
  }

  private fun delete(uri: URI): ResourceChange {
    val removed = LinkedHashModel().apply { add(uri.toIri(), RDF.TYPE, event, ctx) }
    return ResourceChange(uri, ChangeOperation.DELETED, setOf(ctx), LinkedHashModel(), LinkedHashModel(), removed)
  }

  private fun upsert(uri: URI): ResourceChange {
    val after = LinkedHashModel().apply { add(uri.toIri(), RDF.TYPE, event, ctx) }
    return ResourceChange(uri, ChangeOperation.UPDATED, setOf(ctx), after, after, LinkedHashModel())
  }

  private fun changeSet(vararg resources: ResourceChange) =
    PodChangeSet(podId, "pod", resources.toList())

  private fun URI.toIri() = Values.iri(this.toString())
}
