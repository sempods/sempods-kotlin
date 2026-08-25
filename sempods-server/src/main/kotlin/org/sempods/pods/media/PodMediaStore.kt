package org.sempods.pods.media

import org.sempods.pods.PodId
import java.io.InputStream
import java.nio.file.Path

/**
 * Where a pod's binaries live — bytes in, bytes out, and nothing about what they mean.
 *
 * **Not the `…Store` of the rest of this codebase.** `RefreshTokenStore`, `DynamicClientStore` and
 * `PodServiceClientStore` are each a service wrapping their DAO; this one is the opposite end —
 * pure byte storage with no row, no query and no domain logic. The three roles here are worth
 * keeping apart:
 *
 * | | holds | bound |
 * |---|---|---|
 * | [PodMediaStore] | the bytes | only when a deployment configures a backend |
 * | `persist.PodMediaDao` | the rows — content type, size, context assignments | always |
 * | [PodMediaFacade] | both, and the rules connecting them | with the store |
 *
 * Deliberately generic: no image knowledge, no variants, no transformation. Which implementation
 * runs is a *deployment* decision and is bound by `SempodsMediaModule` in
 * `:deployments:sempods:image`, not here — an S3-backed store lives in its own sibling module and
 * therefore cannot be named from `:sempods`.
 *
 * **Addressed by [PodMediaRef], never by a storage key.** An implementation owns how a ref becomes
 * a physical location; see that class for why. `podId` is the ownership boundary — no operation
 * crosses it, and [iterate] is scoped to one pod or explicitly to all.
 *
 * **A layout may reject a ref, and must reject rather than half-accept it.** A
 * [org.sempods.pods.PodId] promises nothing about its form, so a token a deployment mints may be
 * one this implementation's layout cannot express — and the only wrong answer is to store an
 * object [iterate] then cannot hand back, which loses bytes silently. Refuse it from [put], with a
 * message naming the constraint. Encoding the token into something the backend does take is the
 * other legitimate answer; both are the implementation's to choose, and whichever it picks belongs
 * in its KDoc.
 *
 * **Objects are immutable.** [PodMediaRef.mediaId] is the base64url SHA-256 of the content, so
 * [put] for an existing ref writes identical bytes. That is what means no implementation needs a
 * compare-and-swap, and what lets an external tool copy a whole backend with a plain sync.
 *
 * **sempods holds no copy operation of its own.** Backup and a move between backends run outside the
 * server — `restic`, `rclone`, an object-store lifecycle rule — against whatever layout the
 * implementation in use documents. Both of the ones shipped here happen to lay a pod's objects out
 * the same way, which is what makes such a move a one-liner, but that is each implementation's
 * statement to make and not a promise of this interface. See `docs/media.md`
 * §"Deliberately outside".
 */
interface PodMediaStore {

  /**
   * Store the bytes of [source] for [ref], replacing whatever was there.
   *
   * [contentType] is metadata for backends that carry it (S3 object metadata, so that a later
   * presigned GET serves the right type — `docs/media.md` §Delivery). It is **not** the
   * authoritative record: `media` is, and a backend with nowhere to put it may drop it.
   */
  fun put(ref: PodMediaRef, contentType: String, source: Path)

  /**
   * Open the object for [ref]. The caller closes. Throws when it does not exist.
   *
   * **The exception type is the implementation's own** — a `NoSuchFileException` from a filesystem
   * and an SDK's not-found from an object store. Normalising them would mean wrapping every call in
   * a translation nobody reads, for a distinction no caller makes: everything above this either
   * checked [exists] first or treats "cannot read the bytes" as one outcome. Where that outcome
   * reaches HTTP it is already a fixed string with the detail in the log, because a store's message
   * is its physical layout — see `PodMediaEndpoint`.
   */
  fun open(ref: PodMediaRef): InputStream

  /** Remove the object for [ref]. A no-op when it is already gone, so a repeated sweep is safe. */
  fun delete(ref: PodMediaRef)

  fun exists(ref: PodMediaRef): Boolean

  /**
   * Hands [consume] every object this store holds — for one pod, or for all of them when [podId] is
   * `null`. A batch path (the reconcile report), never a request path.
   *
   * Named `iterate` rather than `list` although every object store calls it listing: those return a
   * page, and this returns nothing at all. What it offers is a scope to walk in, and a name that
   * promised a collection would be wrong about the one property that matters here.
   *
   * **`podId = null` must include pods that no longer exist.** That is the case the whole method is
   * for: a pod deleted inside its grace period still has bytes, and an enumeration driven by the
   * pod registry instead of by the store would never see them.
   *
   * **Scoped, because laziness over a resource has to be closed.** A returned `Sequence` would
   * leave a directory handle or a paging cursor open until the caller happened to exhaust it, and
   * the reconcile walks pod after pod in a loop. The sequence is valid only inside [consume];
   * letting it escape is a programming error no implementation can defend against.
   *
   * **Resumable via [MediaEntry.cursor], and the contract is deliberately weak.** Pass the cursor
   * of the last entry you finished as [after] to carry on. The token is **opaque** and may be
   * coarser than one entry — an object store hands out a continuation token per *page*, so
   * resuming may **replay up to one page** of entries already seen. A consumer must therefore be
   * idempotent, which the reconcile is: it only reports. Requiring an exact per-entry resume
   * instead would force every implementation into lexicographic ordering, for a guarantee no
   * caller needs.
   *
   * TODO: no production caller passes [after] any more — `PodMediaFacade`'s reconcile walks a pod
   * in one go. Both halves are still pinned by the conformance suite, so they are exercised rather
   * than dead, but decide whether to keep or drop them the next time this seam is opened.
   *
   * Ordering is an implementation's own business. It has to be *stable* enough that its own cursor
   * means something; it does not have to be lexicographic, and no caller may assume an order.
   */
  fun <T> iterate(
    podId: PodId? = null,
    after: String? = null,
    consume: (Sequence<MediaEntry>) -> T,
  ): T
}

/** One object from [PodMediaStore.iterate], with the point to resume after it. */
data class MediaEntry(
  val ref: PodMediaRef,

  /**
   * An opaque resume point, meaningful only to the [PodMediaStore] that produced it — pass it back
   * as `after`, never parse it, and never carry one from one store to another.
   *
   * A plain `String` rather than a wrapper type, for the same reason `SparqlResult.cursor` is one:
   * a cursor's whole job is to be handed back and, for a walk that must survive a restart, written
   * down. It has no identity and no second use to confuse it with, so a type would buy nothing.
   */
  val cursor: String,
)
