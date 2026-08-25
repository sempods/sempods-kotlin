package org.sempods.pods.media.impls.s3

import org.sempods.pods.PodId
import org.sempods.pods.media.MediaEntry
import org.sempods.pods.media.PodMediaRef
import org.sempods.pods.media.PodMediaStore
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.InputStream
import java.nio.file.Path

/**
 * A [PodMediaStore] on anything that speaks S3: one bucket, one key prefix per pod, one object per
 * media named by its content hash.
 *
 * ```
 * {bucket}/{podId}/{mediaId}
 * ```
 *
 * **That layout is documented rather than internal**, although the seam leaves it to the
 * implementation: it is the same one [org.sempods.pods.media.impls.fs.FilesystemPodMediaStore]
 * writes, so moving a deployment between the two — or backing this bucket up — is an `rclone sync`
 * and needs nothing from the pod server. sempods holds no copy operation of its own; see
 * `docs/media.md` §"Deliberately outside".
 *
 * **The class is named for the protocol, not for a vendor**, and that is the whole reason this
 * module exists: Cloudflare R2, Hetzner Object Storage, SeaweedFS, Garage and AWS S3 itself are the
 * same code and differ only in an [S3MediaStoreConfig]. "Which store" is a deployment decision the
 * pod server never learns.
 *
 * It lives in `:sempods-media-s3`, a **sibling** of `:sempods` that depends on it. That direction is
 * forced — `:sempods` owns the seam and cannot import an implementation that imports it — and it is
 * also what keeps the S3 SDK off the pod server's classpath for every deployment that does not run
 * this store.
 *
 * **Durability moves onto the store here.** The filesystem store's bytes live on one disk this
 * server owns; these live wherever the endpoint points — which is a provider's problem for R2 or
 * AWS, and still one box's disks for a single self-hosted node. Either way it is the deployment's
 * business, not this class's — and the deployment owes these bytes a backup, because for a
 * media object the store holds the only copy there is.
 */
class S3PodMediaStore internal constructor(
  private val s3: S3Client,
  private val bucket: String,
) : PodMediaStore, AutoCloseable {

  constructor(config: S3MediaStoreConfig) : this(client(config), config.bucket) {
    logger.info { "Pod media: S3 store $config" }
  }

  override fun put(ref: PodMediaRef, contentType: String, source: Path) {
    // `contentType` goes onto the object rather than being dropped as the filesystem store drops
    // it: a later presigned-GET delivery path (`docs/media.md` §Delivery) serves what the
    // object carries, and there would be nothing to set it from at that point. It is still **not**
    // the authoritative record — `media` is, one claim per assignment, and two contexts
    // may legitimately disagree about the same bytes.
    s3.putObject(
      PutObjectRequest.builder().bucket(bucket).key(key(ref)).contentType(contentType).build(),
      source,
    )
  }

  override fun open(ref: PodMediaRef): InputStream =
    s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key(ref)).build())

  override fun delete(ref: PodMediaRef) {
    // S3 `DeleteObject` answers `204` whether or not the key was there, which is the idempotence the
    // seam asks for without a preceding existence check.
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(ref)).build())
  }

  override fun exists(ref: PodMediaRef): Boolean =
    headOrNull(key(ref)) != null

  /**
   * Lists the bucket, page by page, and yields the keys that name a media object.
   *
   * **Lazy inside [consume] and nowhere else** — this is the case the scoped signature exists for.
   * Each page is one round trip, fetched when the consumer asks for the entry after the last one it
   * has seen, so a reconcile that stops early stops paging too.
   *
   * **The cursor is the object key**, which makes resuming exact here although the seam only
   * promises page-coarse. `ListObjectsV2` returns keys in binary order and takes `start-after`, so
   * the weaker contract simply is not needed — it stays in the seam because a store without that
   * parameter must remain implementable, not because this one uses the slack. `start-after` is sent
   * on the first request only; once a continuation token is in play S3 ignores it, which is exactly
   * right since the token already carries the position.
   *
   * Keys that do not fit this store's `{prefix}/{name}` layout are skipped — a nested path is not
   * something it wrote. Which *prefixes* are pods of this deployment it cannot say, since a [PodId]
   * promises nothing about its form; a shared bucket's other prefixes therefore come back as
   * tenants and `PodMediaFacade.reconcile` sorts them out. A stray key *inside* a pod's prefix is a
   * different matter and **is** reported — that is the reconcile working.
   */
  override fun <T> iterate(
    podId: PodId?,
    after: String?,
    consume: (Sequence<MediaEntry>) -> T,
  ): T {
    val prefix = podId?.let { "${it.value}/" }
    var skipped = 0

    val entries = sequence {
      var continuationToken: String? = null
      var first = true
      while (true) {
        val request = ListObjectsV2Request.builder().bucket(bucket)
        if (prefix != null) request.prefix(prefix)
        if (first && after != null) request.startAfter(after)
        if (continuationToken != null) request.continuationToken(continuationToken)

        val page = s3.listObjectsV2(request.build())
        for (item in page.contents()) {
          val ref = parseKey(item.key())
          if (ref == null) {
            skipped++
            continue
          }
          yield(MediaEntry(ref, item.key()))
        }

        if (page.isTruncated != true) break
        continuationToken = page.nextContinuationToken()
        first = false
      }
    }

    return consume(entries).also {
      // One line per walk rather than one per key: a bucket shared with something else would
      // otherwise fill the log with a warning per object, and the count is the whole diagnosis.
      if (skipped > 0) logger.debug { "Ignored $skipped bucket key(s) that do not name a media object" }
    }
  }

  /** Closes the underlying client. A store built from an injected client is the caller's to close. */
  override fun close() = s3.close()

  private fun headOrNull(key: String) = try {
    s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
  } catch (_: NoSuchKeyException) {
    null
  } catch (e: S3Exception) {
    // `HeadObject` has no response body, so an implementation that does not send the `NoSuchKey`
    // code leaves the SDK with only the status to go on and it raises the generic exception. A 404
    // is still a 404; anything else is a real failure and must not be read as "not there".
    if (e.statusCode() == 404) null else throw e
  }

  /**
   * The one place a ref becomes a key, and the constraint that goes with this store's layout: a pod
   * id becomes one key segment, so a token with a `/` in it is refused rather than written.
   * [parseKey] could not read such a key back, and an object `iterate` cannot hand back is a byte
   * lost without a sound — the one outcome `PodMediaStore` rules out. A deployment minting tokens
   * like that wants a store that encodes them.
   */
  private fun key(ref: PodMediaRef): String {
    require(!ref.podId.value.contains('/')) {
      "this store lays out one key prefix per pod, so a pod id must be a single segment: $ref"
    }
    return "${ref.podId.value}/${ref.mediaId}"
  }

  /**
   * The one place a key becomes a ref — `null` for anything that is not one of this store's media
   * objects.
   */
  private fun parseKey(key: String): PodMediaRef? {
    val separator = key.indexOf('/')
    if (separator < 0) return null
    val owner = key.substring(0, separator)
    val name = key.substring(separator + 1)
    if (owner.isEmpty() || name.isEmpty() || name.contains('/')) return null
    return PodMediaRef(PodId(owner), name)
  }

  companion object {

    private val logger = KotlinLogging.logger {}

    /**
     * The client every deployment gets, with the three settings a non-AWS endpoint needs.
     *
     * - **`forcePathStyle`** per [S3MediaStoreConfig.pathStyle]: virtual-host style would resolve
     *   `{bucket}.{host}`, which a store reached by container name cannot answer.
     * - **Checksums `WHEN_REQUIRED` in both directions.** Since SDK 2.30 the default is to attach a
     *   CRC32 to every upload and to demand one back; S3-compatible stores that predate it — or that
     *   implement the older trailer form — answer `400` or fail validation on ordinary objects.
     *
     *   What that gives up, stated exactly rather than waved away with "the id is a hash": **a hash
     *   nobody recomputes proves nothing.** An upload is covered by construction — `PodMediaFacade`
     *   digests the staged file and the id *is* that digest, so the name and the bytes agree before
     *   the `put`. Nothing after that re-reads them: **a corruption introduced in transit, or one
     *   that develops in the store afterwards, is not detected here or anywhere else in sempods.**
     *   Re-reading every served byte is a request-path cost this design has not taken on, and
     *   verifying the whole store periodically is the job of whatever backs it up — an object store
     *   scrubs, and `restic check --read-data` does it for a filesystem. That boundary is the trade;
     *   the transport is TLS in production.
     *
     * `internal` rather than private so this module's own test can hold a client configured exactly
     * like the store's and reach *past* it — asserting the key layout means bypassing the class that
     * owns it, and a second builder copied into the test would be free to drift from this one.
     */
    internal fun client(config: S3MediaStoreConfig): S3Client = S3Client.builder()
      .endpointOverride(config.endpoint)
      .region(Region.of(config.region))
      .credentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secret)),
      )
      .forcePathStyle(config.pathStyle)
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
      .build()
  }
}
