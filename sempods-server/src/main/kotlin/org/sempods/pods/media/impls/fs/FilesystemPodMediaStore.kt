package org.sempods.pods.media.impls.fs

import org.sempods.pods.PodId
import org.sempods.pods.media.MediaEntry
import org.sempods.pods.media.PodMediaRef
import org.sempods.pods.media.PodMediaStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * A [PodMediaStore] on the local filesystem: one directory per pod, one file per media, named by
 * its content hash.
 *
 * ```
 * {root}/{podId}/{mediaId}
 * ```
 *
 * The layout is this class's own — nothing outside it knows or stores a path, which is what lets a
 * later version shard `{mediaId}` into subdirectories, should one pod ever hold enough objects for
 * a single directory to hurt, without touching the registry or any other store. It is nonetheless
 * *documented*, and deliberately: with no copy operation inside sempods, this directory is what a
 * backup and a move to another backend actually operate on. `restic` sends it offsite and `rclone
 * sync` puts it into an S3 bucket where `S3PodMediaStore` finds it under the same keys.
 *
 * The zero-dependency implementation, and the one a self-hoster gets without running a second
 * service. It is also what the seam's own test runs against, which is why the mapping is as plain
 * as it is — a store whose layout needs explaining cannot serve as the reference the S3
 * implementation is compared to.
 *
 * **A directory is durable state.** Unlike everything else the pod server keeps, these bytes cannot
 * be rebuilt from MongoDB, so the deployment that selects this store owes it a volume and a
 * backup — the directory this points at is the only copy of every byte in it.
 */
class FilesystemPodMediaStore(private val root: Path) : PodMediaStore {

  init {
    Files.createDirectories(root)
    logger.info { "Pod media: filesystem store at ${root.toAbsolutePath()}" }
  }

  override fun put(ref: PodMediaRef, contentType: String, source: Path) {
    // `contentType` has nowhere to go here and is dropped on purpose — see PodMediaStore.put. The
    // registry row carries it, and nothing reads these files except `open` below.
    val target = resolve(ref)
    Files.createDirectories(target.parent)

    // Write beside the target and move into place: an interrupted copy must not leave a truncated
    // file under a name whose whole meaning is "the bytes hashing to this". ATOMIC_MOVE is not
    // requested — a plain move is atomic enough within one filesystem, and demanding it would fail
    // on filesystems that cannot promise it for no gain we can name.
    val staging = Files.createTempFile(target.parent, STAGING_PREFIX, ".tmp")
    try {
      Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING)
      Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(staging)
    }
  }

  override fun open(ref: PodMediaRef): InputStream = Files.newInputStream(resolve(ref))

  override fun delete(ref: PodMediaRef) {
    Files.deleteIfExists(resolve(ref))
    // Leave the pod directory even when it just emptied: removing it would race a concurrent `put`
    // into the same pod, and an empty directory costs an inode.
  }

  override fun exists(ref: PodMediaRef): Boolean = resolve(ref).isRegularFile()

  /**
   * Walks pod directories in name order and the files inside them likewise.
   *
   * **The ordering is this store's own device for making its cursor mean something**, not a promise
   * to callers — [PodMediaStore.iterate] says explicitly that no caller may assume an order. Here the
   * cursor token *is* `{podId}/{mediaId}`, so resuming is exact rather than page-coarse; an object
   * store will do worse and is allowed to.
   *
   * Nothing long-lived is held open: each directory is listed and closed in turn, so the whole walk
   * costs one directory's worth of names at a time. The scoped signature still applies — it exists
   * for backends where laziness *does* hold a resource.
   */
  override fun <T> iterate(
    podId: PodId?,
    after: String?,
    consume: (Sequence<MediaEntry>) -> T,
  ): T {
    if (!root.isDirectory()) return consume(emptySequence())

    val podDirectories = when (podId) {
      null -> root.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
      else -> listOf(root.resolve(podId.value)).filter { it.isDirectory() }
    }

    val entries = sequence {
      for (podDirectory in podDirectories) {
        val owner = PodId.parseOrNull(podDirectory.name)
        if (owner == null) {
          // Not a pod id, so not something this store wrote. A well-formed one it cannot judge
          // at all — `PodId` is opaque here; `PodMediaFacade.reconcile` does that.
          logger.warn { "Ignoring directory ${podDirectory.name} — not a pod id" }
          continue
        }
        // Cheap skip: a whole pod that sorts before the resume point cannot contain it.
        if (after != null && podDirectory.name < after.substringBefore('/')) continue

        val files = podDirectory.listDirectoryEntries()
          .filter { it.isRegularFile() }
          // Staging files are not objects. They exist only inside a `put` and are cleaned up by it,
          // but a crash can strand one, and the reconcile must not read it as an orphan.
          .filterNot { it.name.startsWith(STAGING_PREFIX) }
          .sortedBy { it.name }

        for (file in files) {
          // The cursor is this store's own device: `{podId}/{mediaId}` sorts exactly the way the
          // walk above visits, so resuming is exact rather than page-coarse. An object store will
          // hand out its continuation token instead and is allowed to do worse.
          val cursor = "${podDirectory.name}/${file.name}"
          if (after != null && cursor <= after) continue
          yield(MediaEntry(PodMediaRef(owner, file.name), cursor))
        }
      }
    }

    return consume(entries)
  }

  /**
   * The one place a ref becomes a path.
   *
   * [PodMediaRef.mediaId] is minted as base64url, which cannot contain `/` or `.` runs, so it can
   * never escape the directory — but this is the only code that turns an outside-supplied string
   * into a path, and a check that costs one comparison is cheaper than trusting that forever.
   */
  private fun resolve(ref: PodMediaRef): Path {
    val resolved = root.resolve(ref.podId.value).resolve(ref.mediaId).normalize()
    require(resolved.startsWith(root.normalize())) { "media ref escapes the store root: $ref" }
    require(resolved.parent?.parent == root.normalize()) { "media ref must name one object: $ref" }
    return resolved
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    /** Marks a half-written object, so [iterate] can tell one from a real one. */
    private const val STAGING_PREFIX = ".staging-"
  }
}
