package org.sempods.pods.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sempods.pods.PodId
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The [PodMediaStore] contract, as assertions rather than as prose** — run by every
 * implementation.
 *
 * A test fixture instead of a test, because the two implementations live in different modules:
 * `FilesystemPodMediaStore` in `:sempods` and `S3PodMediaStore` in `:sempods-media-s3`, which
 * depends on `:sempods` and therefore cannot be reached from here. A copied file would have been the
 * alternative, and "the same assertions, two backends" stops being true the first time somebody
 * edits one of them.
 *
 * Two things a subclass may **not** assume, and both are the seam's own words:
 *
 * - **Nothing about the physical layout.** A store owns how a [PodMediaRef] becomes a location, so
 *   nothing here looks at a path or a key. Those assertions belong in the implementation's own test.
 * - **Nothing about cursor exactness.** [PodMediaStore.iterate] promises only that resuming replays
 *   *at most* one page, so this suite checks that a resume skips nothing — never that it repeats
 *   nothing. The filesystem store happens to be exact and pins that for itself.
 *
 * And one thing it assumes about the *store*: it may already hold other data, and it may keep it
 * between test methods. An object-store bucket is shared and slow to create, so everything here is
 * scoped to pod ids this instance minted — an unscoped assertion would be a promise about somebody
 * else's bytes.
 */
abstract class PodMediaStoreConformanceTest {

  /** The store under test. One per test method or one for the class — either satisfies this suite. */
  protected abstract val store: PodMediaStore

  /** Sources live outside whatever the store calls its own space, so they cannot be listed by it. */
  @TempDir
  protected lateinit var sources: Path

  /**
   * Media ids are minted per instance so that two runs against the same bucket cannot collide.
   * They are not real content hashes — the store does not hash anything, it is told the id — but
   * they keep to the shape a real one has: base64url, and never containing `/` or `.`.
   */
  private val salt = randomToken()

  protected val podA: PodId = mintPodId()
  protected val podB: PodId = mintPodId()

  private val ourPods = setOf(podA, podB)

  /**
   * A pod id no other run holds, and deliberately not in any deployment's minting shape: a store is
   * told what a pod id is and never reads meaning out of one, so a suite that used a real shape
   * would let an implementation depend on it without failing here.
   */
  protected fun mintPodId(): PodId = PodId(randomToken())

  private fun ref(podId: PodId, name: String) = PodMediaRef(podId, "$name-$salt")

  protected fun source(content: String): Path =
    Files.createTempFile(sources, "src-", ".bin").apply { writeText(content) }

  private fun read(ref: PodMediaRef): ByteArray = store.open(ref).use { it.readBytes() }

  private fun listScoped(podId: PodId): List<MediaEntry> = store.iterate(podId) { it.toList() }

  /** Every entry of the pods this instance created, however many other pods the store holds. */
  private fun listOurs(): List<MediaEntry> =
    store.iterate { entries -> entries.filter { it.ref.podId in ourPods }.toList() }

  @Test
  fun `put then open returns the bytes, and exists agrees`() {
    val one = ref(podA, "one")
    assertFalse(store.exists(one))

    store.put(one, "text/plain", source("hello"))

    assertTrue(store.exists(one))
    assertContentEquals("hello".toByteArray(), read(one))
  }

  @Test
  fun `put replaces, because a media id is its content hash and a rewrite is the same bytes`() {
    val one = ref(podA, "replaced")
    store.put(one, "text/plain", source("first"))
    store.put(one, "text/plain", source("second"))

    assertContentEquals("second".toByteArray(), read(one))
  }

  @Test
  fun `open fails for a ref that was never written`() {
    // *That* it fails, not *how*: the exception is the implementation's own — a `NoSuchFileException`
    // from a filesystem, an SDK's not-found from an object store — and nothing above the seam tells
    // them apart. See PodMediaStore.open.
    assertFails { store.open(ref(podA, "missing")).use { it.readBytes() } }
  }

  @Test
  fun `delete removes the object and is idempotent`() {
    val one = ref(podA, "deleted")
    store.put(one, "text/plain", source("hello"))

    store.delete(one)
    assertFalse(store.exists(one))

    // Idempotent on purpose: the sweep may run twice over the same candidate, and a failure on the
    // second run would leave the row behind for ever.
    store.delete(one)
    assertFalse(store.exists(one))
  }

  @Test
  fun `the two pods are separate objects even for the same media id`() {
    // The no-dedup-across-pods rule, at the layer that has to keep it: a shared object would make
    // one pod's deletion depend on another's.
    store.put(ref(podA, "same"), "text/plain", source("a"))
    store.put(ref(podB, "same"), "text/plain", source("b"))

    store.delete(ref(podA, "same"))

    assertFalse(store.exists(ref(podA, "same")))
    assertTrue(store.exists(ref(podB, "same")), "one pod's deletion must not reach into another's")
    assertContentEquals("b".toByteArray(), read(ref(podB, "same")))
  }

  @Test
  fun `iterate is scoped to one pod`() {
    store.put(ref(podA, "one"), "text/plain", source("a1"))
    store.put(ref(podA, "two"), "text/plain", source("a2"))
    store.put(ref(podB, "one"), "text/plain", source("b1"))

    assertEquals(setOf(ref(podA, "one"), ref(podA, "two")), listScoped(podA).map(MediaEntry::ref).toSet())
    assertEquals(setOf(ref(podB, "one")), listScoped(podB).map(MediaEntry::ref).toSet())
  }

  @Test
  fun `iterate over every pod includes a pod nothing else knows about`() {
    // The reason the method takes a nullable podId at all: a pod deleted inside its grace period
    // still has bytes, and an enumeration driven by the pod registry would never see them.
    store.put(ref(podA, "orphan"), "text/plain", source("still here"))

    assertTrue(listOurs().map(MediaEntry::ref).contains(ref(podA, "orphan")))
  }

  @Test
  fun `iterate resumes after a cursor without skipping anything`() {
    store.put(ref(podA, "one"), "text/plain", source("a1"))
    store.put(ref(podA, "two"), "text/plain", source("a2"))
    store.put(ref(podA, "three"), "text/plain", source("a3"))

    val all = listScoped(podA)
    val resumed = store.iterate(podA, after = all.first().cursor) { it.map(MediaEntry::ref).toList() }

    val expected = all.drop(1).map(MediaEntry::ref)
    assertTrue(
      resumed.containsAll(expected),
      "resuming must skip nothing after the cursor; expected at least $expected, got $resumed",
    )
    assertTrue(
      all.map(MediaEntry::ref).containsAll(resumed),
      "resuming must not invent entries; got $resumed",
    )
    // The one thing that has to be exact even under the weak contract: the entry the caller said it
    // had finished. Replaying that would make an idempotent consumer do its last unit of work twice
    // for nothing, on every resume.
    assertFalse(resumed.contains(all.first().ref))
  }

  @Test
  fun `a pod with nothing in it lists nothing rather than failing`() {
    assertEquals(emptyList(), listScoped(mintPodId()))
  }

  private companion object {

    /** 32 hex characters — inside every [PodId] rule, and collision-free enough for a test run. */
    private fun randomToken(): String = UUID.randomUUID().toString().replace("-", "")
  }
}
