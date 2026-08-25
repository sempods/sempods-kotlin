package org.sempods.pods.media.impls.fs

import org.sempods.pods.PodId
import org.sempods.pods.media.MediaEntry
import org.sempods.pods.media.PodMediaRef
import org.sempods.pods.media.PodMediaStoreConformanceTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam's contract against the implementation that has no moving parts, plus what this store
 * promises **beyond** it.
 *
 * The contract itself lives in [PodMediaStoreConformanceTest], a test fixture, so that
 * `S3PodMediaStore` in `:sempods-media-s3` runs the same assertions rather than a copy of them. What
 * stays here is what an object store is explicitly allowed to do differently: the on-disk layout,
 * the staging files, cursor *exactness*, and what happens to something that is not ours at all.
 */
class FilesystemPodMediaStoreTest : PodMediaStoreConformanceTest() {

  @TempDir
  lateinit var root: Path

  override val store: FilesystemPodMediaStore by lazy { FilesystemPodMediaStore(root) }

  private fun ref(podId: PodId, mediaId: String) = PodMediaRef(podId, mediaId)

  private fun listAll(podId: PodId? = null): List<PodMediaRef> =
    store.iterate(podId) { entries -> entries.map(MediaEntry::ref).toList() }

  @Test
  fun `put leaves no staging file behind`() {
    store.put(ref(podA, "one"), "text/plain", source("hello"))

    val strays = root.resolve(podA.value).listDirectoryEntries()
      .filter { it.fileName.toString().startsWith(".staging-") }
    assertTrue(strays.isEmpty(), "a completed put must clean up its staging file, found: $strays")
  }

  @Test
  fun `iterate over every pod skips stray staging files`() {
    store.put(ref(podA, "one"), "text/plain", source("a1"))
    store.put(ref(podB, "one"), "text/plain", source("b1"))
    // A crash inside `put` can strand one of these. The reconcile reads `iterate` as the set of
    // objects, so a staging file appearing there would be reported as an orphan.
    root.resolve(podA.value).resolve(".staging-crashed.tmp").writeText("half a file")

    assertEquals(setOf(ref(podA, "one"), ref(podB, "one")), listAll().toSet())
  }

  @Test
  fun `a stray file inside a pod directory is reported, because that is the reconcile working`() {
    // The other side of the coin from the staging file above, and the distinction is deliberate: a
    // half-written file this store made is not an object, while a file somebody else put where the
    // objects live is exactly the leaked byte the reconcile exists to name.
    store.put(ref(podA, "one"), "text/plain", source("a1"))
    root.resolve(podA.value).resolve("planted").writeText("not in the registry")

    assertEquals(setOf(ref(podA, "one"), ref(podA, "planted")), listAll(podA).toSet())
  }

  @Test
  fun `every directory is a tenant, because whose it is not this store's judgement`() {
    // A `PodId` promises nothing about its form, so a directory name cannot be read as evidence of
    // anything. The store hands back what it holds and `PodMediaFacade.reconcile` decides ownership
    // — being the side that mints ids. A store filtering here would be guessing.
    store.put(ref(podA, "one"), "text/plain", source("a1"))
    root.resolve("lost+found").createDirectories().resolve("junk").writeText("not ours")

    assertEquals(
      setOf(ref(podA, "one"), PodMediaRef(PodId("lost+found"), "junk")),
      listAll().toSet(),
    )
  }

  @Test
  fun `an object lands in the pod's own directory, under its media id`() {
    // The one assertion about layout, and it belongs here rather than in the conformance suite,
    // which may assume nothing about how a ref becomes a location. It is asserted at all because
    // this layout is what an external backup or an `rclone sync` to an S3 bucket operates on — see
    // the class KDoc.
    store.put(ref(podA, "layout-probe"), "text/plain", source("bytes"))

    assertTrue(root.resolve(podA.value).resolve("layout-probe").toFile().isFile)
  }

  @Test
  fun `iterate resumes after a cursor exactly, which is this store's own affordance`() {
    store.put(ref(podA, "one"), "text/plain", source("a1"))
    store.put(ref(podA, "two"), "text/plain", source("a2"))
    store.put(ref(podB, "one"), "text/plain", source("b1"))

    val all = store.iterate { it.toList() }
    val resumed = store.iterate(after = all.first().cursor) { entries -> entries.map(MediaEntry::ref).toList() }

    // The seam promises only that a resume skips nothing — the conformance suite checks that much.
    // Here the cursor *is* `{podId}/{mediaId}` and sorts the way the walk visits, so nothing is
    // replayed either. An object store is allowed to do worse and says so.
    assertEquals(all.drop(1).map(MediaEntry::ref), resumed)
  }

  @Test
  fun `resuming after the last cursor yields nothing`() {
    store.put(ref(podA, "one"), "text/plain", source("a1"))
    store.put(ref(podB, "one"), "text/plain", source("b1"))

    val all = store.iterate { it.toList() }

    assertEquals(emptyList(), store.iterate(after = all.last().cursor) { it.toList() })
  }

  @Test
  fun `an empty store lists nothing rather than failing`() {
    assertEquals(emptyList(), listAll())
  }
}
