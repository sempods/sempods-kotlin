package org.sempods.pods.media

import com.google.inject.Inject
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.SempodsIntegrationTest
import org.sempods.pods.PodId
import org.sempods.pods.media.impls.fs.FilesystemPodMediaStore
import org.sempods.pods.media.persist.PodMedia
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.toPodId
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The facade is where the registry and the bytes have to agree. It is built by hand here rather
 * than injected: `PodMediaFacade` is bound by the *deployment* composition (`SempodsMediaModule`),
 * which `:sempods` cannot see, and a test store over a temp directory is what these assertions want
 * anyway — the DAO is the real one, against the real database.
 */
class PodMediaFacadeTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var dao: PodMediaDao

  @Inject
  private lateinit var podDao: PodDao

  @TempDir
  lateinit var storeRoot: Path

  @TempDir
  lateinit var sources: Path

  private val mediaStore: FilesystemPodMediaStore by lazy { FilesystemPodMediaStore(storeRoot) }
  private val facade: PodMediaFacade by lazy {
    PodMediaFacade(mediaStore, dao, podDao, PodMediaConfig(maxUploadBytes = 1024))
  }

  private val contextA = URI("https://contexts.example.org/a")
  private val contextB = URI("https://contexts.example.org/b")

  private fun source(content: String): Path =
    Files.createTempFile(sources, "src-", ".bin").apply { writeText(content) }

  @Test
  fun `store writes both the object and the row`() {
    val podId = ObjectId()
    val content = "image-bytes-${randomId()}"

    val stored = facade.store(podId, contextA, source(content), "image/jpeg", filename = "photo.jpg")

    assertEquals(setOf(contextA.toString()), stored.contexts)
    assertEquals("image/jpeg", stored.assignments.single().contentType)
    assertEquals("photo.jpg", stored.assignments.single().filename)
    assertEquals(content.length.toLong(), stored.size)
    assertEquals(PodMediaRef(podId.toPodId(), stored.mediaId), stored.ref)
    assertTrue(mediaStore.exists(stored.ref))
    assertContentEquals(content.toByteArray(), facade.open(stored).use { it.readBytes() })
    assertEquals(stored, dao.find(podId, stored.mediaId))
  }

  @Test
  fun `the media id is the content hash, so the same bytes dedup within a pod`() {
    val podId = ObjectId()
    val content = "identical-${randomId()}"

    val first = facade.store(podId, contextA, source(content), "image/jpeg")
    val second = facade.store(podId, contextB, source(content), "image/jpeg")

    assertEquals(first.mediaId, second.mediaId, "identical bytes must resolve to one media")
    assertEquals(
      setOf(contextA.toString(), contextB.toString()),
      assertNotNull(dao.find(podId, first.mediaId)).contexts,
      "the second store must add an assignment rather than a second row",
    )
  }

  @Test
  fun `two pods storing the same bytes get two independent objects`() {
    val content = "shared-${randomId()}"
    val firstPod = ObjectId()
    val secondPod = ObjectId()

    val first = facade.store(firstPod, contextA, source(content), "image/jpeg")
    val second = facade.store(secondPod, contextA, source(content), "image/jpeg")

    assertEquals(first.mediaId, second.mediaId)
    // Same id, different refs: one pod's deletion must never reach into another's bytes.
    assertTrue(first.ref != second.ref)
    assertTrue(mediaStore.exists(first.ref) && mediaStore.exists(second.ref))
  }

  @Test
  fun `a row whose object went missing is healed by the next store of the same bytes`() {
    val podId = ObjectId()
    val content = "healed-${randomId()}"
    val stored = facade.store(podId, contextA, source(content), "image/jpeg")
    mediaStore.delete(stored.ref)

    facade.store(podId, contextB, source(content), "image/jpeg")

    assertTrue(mediaStore.exists(stored.ref), "the upload must restore the missing object")
    assertContentEquals(content.toByteArray(), facade.open(stored).use { it.readBytes() })
  }

  @Test
  fun `store refuses an object over the configured maximum`() {
    val tooLarge = source("x".repeat(1025))

    assertFailsWith<IllegalArgumentException> {
      facade.store(ObjectId(), contextA, tooLarge, "application/octet-stream")
    }
  }

  @Test
  fun `store refuses a content type the content route could not serve`() {
    // The invariant behind every assignment, checked here so that a *future* ingestion path cannot
    // reintroduce it quietly. An unservable type is accepted at upload and fails on every later
    // read, which is far enough apart that nothing connects the two.
    assertFailsWith<IllegalArgumentException> {
      facade.store(ObjectId(), contextA, source("content-${randomId()}"), "not-a-media-type")
    }
  }

  @Test
  fun `assign and unassign move the row through its reference count`() {
    val podId = ObjectId()
    val stored = facade.store(podId, contextA, source("content-${randomId()}"), "image/jpeg")

    assertTrue(facade.assign(podId, stored.mediaId, contextB, describedBy = stored.assignments.single()))
    val assigned = assertNotNull(dao.find(podId, stored.mediaId))
    assertEquals(setOf(contextA.toString(), contextB.toString()), assigned.contexts)
    // The copy describes the media in the terms the copier could see, not the store's own guess.
    assertEquals("image/jpeg", assigned.assignments.single { it.context == contextB.toString() }.contentType)

    assertTrue(facade.unassign(podId, stored.mediaId, contextA))
    assertTrue(facade.unassign(podId, stored.mediaId, contextB))

    val unreferenced = assertNotNull(dao.find(podId, stored.mediaId))
    assertEquals(emptySet(), unreferenced.contexts)
    assertNotNull(unreferenced.unreferencedSince)
    // The bytes stay: the grace period is the whole point, and `sweepUnreferenced` is what ends it.
    assertTrue(mediaStore.exists(stored.ref))
  }

  @Test
  fun `findReadable answers only when the contexts intersect`() {
    val podId = ObjectId()
    val stored = facade.store(podId, contextA, source("content-${randomId()}"), "image/jpeg")

    assertEquals(stored.mediaId, assertNotNull(facade.findReadable(podId, stored.mediaId, setOf(contextA))).mediaId)
    assertNull(facade.findReadable(podId, stored.mediaId, setOf(contextB)))
    assertNull(facade.findReadable(podId, stored.mediaId, emptySet()))
  }

  @Test
  fun `findReadable does not distinguish forbidden from absent`() {
    val podId = ObjectId()
    val stored = facade.store(podId, contextA, source("content-${randomId()}"), "image/jpeg")

    // The id is a content hash, so telling the two apart would answer "does this pod hold exactly
    // these bytes" for anyone who has them. Both are null, and M2 turns both into the same 404.
    assertNull(facade.findReadable(podId, stored.mediaId, setOf(contextB)))
    assertNull(facade.findReadable(podId, randomId(), setOf(contextA)))
  }

  // -- the sweep --
  //
  // Every one of these is scoped to its own pod. The database is shared by the whole test JVM, so an
  // unscoped sweep here would delete rows other tests are asserting on — the `podId` parameter is
  // what makes these assertions exact rather than approximately true.

  /**
   * Stores a media and drops its only assignment, leaving it unreferenced [anHourAgo].
   *
   * The timestamp is stated rather than taken from `facade.unassign`'s own `Instant.now()`, and that
   * is not tidiness: the candidate query is *strictly* before its cutoff, BSON dates carry
   * milliseconds, and a `gracePeriod = ZERO` sweep in the same millisecond as the unassign therefore
   * finds nothing. Stating the moment makes the test about the sweep instead of about how fast the
   * machine ran. What `unassign` stamps is asserted where it belongs, in the reference-count test
   * above.
   */
  private fun storeUnreferenced(podId: ObjectId, content: String = "content-${randomId()}"): PodMedia {
    val stored = facade.store(podId, contextA, source(content), "image/jpeg")
    dao.removeContext(podId, stored.mediaId, contextA.toString(), anHourAgo)
    return stored
  }

  private val anHourAgo: Instant get() = Instant.now().minusSeconds(3600)

  @Test
  fun `sweepUnreferenced deletes both the row and the bytes once the grace period is over`() {
    val podId = ObjectId()
    val swept = storeUnreferenced(podId)

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ZERO, podId = podId)

    assertEquals(1, report.candidates)
    assertEquals(1, report.deleted)
    assertEquals(0, report.revived)
    assertEquals(0, report.objectDeletionFailures)
    assertEquals(swept.size, report.reclaimedBytes)
    assertNull(dao.find(podId, swept.mediaId), "the row is gone")
    assertFalse(mediaStore.exists(swept.ref), "and so are the bytes")
  }

  @Test
  fun `sweepUnreferenced leaves a media that is still inside its grace period`() {
    val podId = ObjectId()
    val kept = storeUnreferenced(podId)

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ofDays(30), podId = podId)

    assertEquals(0, report.candidates)
    assertNotNull(dao.find(podId, kept.mediaId))
    assertTrue(mediaStore.exists(kept.ref))
  }

  @Test
  fun `sweepUnreferenced never touches a media that is still assigned`() {
    // A real pod, not a synthetic id: the sweep's stranded-pod backstop marks the media of pods that
    // do not exist, so a test about *assigned* media has to give it one that does. Every media row
    // in production belongs to a pod that was there when it was written, and these two tests are the
    // ones where that stops being a detail.
    val podId = checkNotNull(sempodsTestFactory.newPod().id)
    val referenced = facade.store(podId, contextA, source("content-${randomId()}"), "image/jpeg")

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ZERO, podId = podId)

    assertEquals(0, report.candidates)
    assertNotNull(dao.find(podId, referenced.mediaId))
    assertTrue(mediaStore.exists(referenced.ref))
  }

  @Test
  fun `a dry run reports what would go and deletes nothing`() {
    val podId = ObjectId()
    val candidate = storeUnreferenced(podId)

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ZERO, dryRun = true, podId = podId)

    assertTrue(report.dryRun)
    assertEquals(1, report.candidates)
    assertEquals(0, report.deleted)
    // The number an operator is actually deciding on: how much this would free.
    assertEquals(candidate.size, report.reclaimedBytes)
    assertNotNull(dao.find(podId, candidate.mediaId))
    assertTrue(mediaStore.exists(candidate.ref))
  }

  @Test
  fun `sweepUnreferenced reaches the rows of a pod that no longer exists`() {
    // The case the whole design turns on: `SempodsFacade.deletePod` stamps the rows and then removes
    // the pod, so nothing driven by the pod registry could ever find them again. The sweep keys on
    // `unreferencedSince` and `podId` alone — the row *is* the tombstone.
    val goneForever = ObjectId()
    val stored = facade.store(goneForever, contextA, source("content-${randomId()}"), "image/jpeg")
    dao.markPodUnreferenced(goneForever, anHourAgo)

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ZERO, podId = goneForever)

    assertEquals(1, report.deleted)
    assertNull(dao.find(goneForever, stored.mediaId))
    assertFalse(mediaStore.exists(stored.ref))
  }

  @Test
  fun `the assignment came back, so the sweep does not delete`() {
    // The grace period is only worth having if re-assigning inside it actually saves the bytes. Here
    // the second upload lands before the sweep starts, so the row is not even a candidate.
    //
    // The *mid-run* version of this — the candidate list is read before the deletes, so an upload
    // can land in between — cannot be interleaved from a test that drives both sides in sequence.
    // What makes that case safe is the conditional delete, and it is asserted where it lives:
    // `PodMediaDaoTest.deleteIfUnreferencedBefore leaves a row whose assignment came back`.
    // A real pod, for the same reason as above: the media ends up assigned again, and the backstop
    // would otherwise read it as one that outlived its pod.
    val podId = checkNotNull(sempodsTestFactory.newPod().id)
    val content = "revived-${randomId()}"
    val candidate = storeUnreferenced(podId, content)
    facade.store(podId, contextB, source(content), "image/jpeg")

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ZERO, podId = podId)

    assertEquals(0, report.candidates, "a re-assigned row is referenced again and not a candidate")
    assertEquals(0, report.deleted)
    assertEquals(0L, report.reclaimedBytes)
    assertEquals(setOf(contextB.toString()), assertNotNull(dao.find(podId, candidate.mediaId)).contexts)
    assertTrue(mediaStore.exists(candidate.ref))
  }

  @Test
  fun `a failed object delete keeps the row so the next sweep retries`() {
    // The row is the only record that these bytes are collectable. Deleting it first — the original
    // order — turned a transient store failure into a permanent leak: no candidate would ever name
    // the object again, and only a human reading a reconcile report could find it.
    val podId = ObjectId()
    val candidate = storeUnreferenced(podId)
    val failing = object : PodMediaStore by mediaStore {
      override fun delete(ref: PodMediaRef) = throw java.io.IOException("the store is having a bad day")
    }
    val brittle = PodMediaFacade(failing, dao, podDao, PodMediaConfig(maxUploadBytes = 1024))

    val failedRun = brittle.sweepUnreferenced(gracePeriod = Duration.ZERO, podId = podId)

    assertEquals(1, failedRun.candidates)
    assertEquals(0, failedRun.deleted)
    assertEquals(1, failedRun.objectDeletionFailures)
    assertEquals(0L, failedRun.reclaimedBytes, "nothing was reclaimed, so nothing may be reported as such")
    assertNotNull(dao.find(podId, candidate.mediaId), "the row must survive a failure it can be retried from")

    // And the retry is an ordinary next run — no operator step in between.
    val retry = facade.sweepUnreferenced(gracePeriod = Duration.ZERO, podId = podId)

    assertEquals(1, retry.deleted)
    assertNull(dao.find(podId, candidate.mediaId))
    assertFalse(mediaStore.exists(candidate.ref))
  }

  @Test
  fun `sweepUnreferenced marks the media of a pod that no longer exists`() {
    // An upload authorized moments before `deletePod` can commit its row just after the cascade
    // marked everything. That row carries no stamp and names contexts that are gone, so nothing
    // would ever collect it — and nobody can reach it either, because without a pod there is no
    // route to the media at all.
    val strandedPod = ObjectId()
    val escaped = facade.store(strandedPod, contextA, source("content-${randomId()}"), "image/jpeg")
    assertNull(assertNotNull(dao.find(strandedPod, escaped.mediaId)).unreferencedSince)

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ofDays(30), podId = strandedPod)

    assertEquals(1, report.strandedPodsMarked)
    // Marked, not deleted: a stranded row gets the same grace period as everything else, counted
    // from the moment it is noticed — which is why this run reclaims nothing.
    assertEquals(0, report.deleted)
    val marked = assertNotNull(dao.find(strandedPod, escaped.mediaId))
    assertNotNull(marked.unreferencedSince, "the row must become a candidate")
    assertEquals(emptySet(), marked.contexts, "its contexts went with the pod")
    assertTrue(mediaStore.exists(escaped.ref), "the bytes stay until the grace period is over")
  }

  @Test
  fun `sweepUnreferenced leaves the media of a pod that still exists alone`() {
    // The backstop asks whether a pod exists, so a live pod's assigned media must be invisible to
    // it — otherwise every ordinary upload would be stamped for deletion.
    val pod = sempodsTestFactory.newPod()
    val podId = checkNotNull(pod.id)
    val live = facade.store(podId, contextA, source("content-${randomId()}"), "image/jpeg")

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ZERO, podId = podId)

    assertEquals(0, report.strandedPodsMarked)
    assertNull(assertNotNull(dao.find(podId, live.mediaId)).unreferencedSince)
    assertTrue(mediaStore.exists(live.ref))
  }

  @Test
  fun `sweepUnreferenced takes a row whose object is already gone`() {
    val podId = ObjectId()
    val broken = storeUnreferenced(podId)
    mediaStore.delete(broken.ref)

    val report = facade.sweepUnreferenced(gracePeriod = Duration.ZERO, podId = podId)

    // `delete` is a no-op on a missing object, so this is a plain success — a repeated sweep after a
    // crash must not report a failure it cannot act on.
    assertEquals(1, report.deleted)
    assertEquals(0, report.objectDeletionFailures)
    assertNull(dao.find(podId, broken.mediaId))
  }

  @Test
  fun `a negative grace period is refused rather than reaching into the future`() {
    assertFailsWith<IllegalArgumentException> { facade.sweepUnreferenced(gracePeriod = Duration.ofSeconds(-1)) }
  }

  // -- the reconcile --

  @Test
  fun `reconcile reports nothing when the store and the registry agree`() {
    val podId = ObjectId()
    facade.store(podId, contextA, source("content-${randomId()}"), "image/jpeg")

    val report = facade.reconcile(podId)

    assertEquals(1, report.checkedObjects)
    assertEquals(1, report.checkedRows)
    assertEquals(emptyList(), report.objectsWithoutRow)
    assertEquals(emptyList(), report.rowsWithoutObject)
  }

  @Test
  fun `reconcile reports an object no registry row claims`() {
    // A leaked byte: a crash between the store write and the DAO insert, or between the two halves
    // of a sweep. Inert, and paid for until somebody knows about it.
    val podId = ObjectId()
    val orphan = PodMediaRef(podId.toPodId(), "orphan-${randomId()}")
    mediaStore.put(orphan, "image/jpeg", source("stray-${randomId()}"))

    val report = facade.reconcile(podId)

    assertEquals(listOf(orphan), report.objectsWithoutRow)
    assertEquals(emptyList(), report.rowsWithoutObject)
  }

  @Test
  fun `reconcile skips an object under a pod id this deployment never minted`() {
    // A store may sit on space it shares — a bucket with other prefixes, a directory somebody else
    // writes to as well. It cannot tell one of those from a pod of ours, because a `PodId` is opaque
    // to it; only the side that mints ids knows their shape, so the filter is here. Naming a
    // stranger's bytes an orphan would send an operator after data that is not theirs to delete.
    val stranger = PodMediaRef(PodId("not-a-pod-of-ours"), "object-${randomId()}")
    mediaStore.put(stranger, "image/jpeg", source("stray-${randomId()}"))

    val report = facade.reconcile()

    assertFalse(stranger in report.objectsWithoutRow)
  }

  @Test
  fun `reconcile reports a row whose bytes are gone`() {
    // The other direction, and the one with a caller: a broken media is a 404 waiting to happen for
    // whoever holds its URL.
    val podId = ObjectId()
    val stored = facade.store(podId, contextA, source("content-${randomId()}"), "image/jpeg")
    mediaStore.delete(stored.ref)

    val report = facade.reconcile(podId)

    assertEquals(listOf(stored.ref), report.rowsWithoutObject)
    assertEquals(emptyList(), report.objectsWithoutRow)
  }

  @Test
  fun `reconcile counts a stamped row of a deleted pod as present, not as an orphan`() {
    // Matching objects against *rows* rather than against live pods is what makes this right. Going
    // through `PodDao` would report every object inside a deleted pod's grace period as a leak,
    // which is exactly backwards — those bytes are waiting to be swept, not lost.
    val goneForever = ObjectId()
    facade.store(goneForever, contextA, source("content-${randomId()}"), "image/jpeg")
    dao.markPodUnreferenced(goneForever, Instant.now())

    val report = facade.reconcile(goneForever)

    assertEquals(emptyList(), report.objectsWithoutRow)
    assertEquals(emptyList(), report.rowsWithoutObject)
  }
}
