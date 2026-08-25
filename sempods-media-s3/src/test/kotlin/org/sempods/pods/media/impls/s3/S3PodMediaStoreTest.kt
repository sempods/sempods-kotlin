package org.sempods.pods.media.impls.s3

import org.sempods.pods.media.MediaEntry
import org.sempods.pods.media.PodMediaRef
import org.sempods.pods.media.PodMediaStoreConformanceTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam's contract against a real S3-compatible store, plus what this implementation promises
 * beyond it.
 *
 * **Against a real Garage, not a mock.** The whole claim of this milestone is "same assertions, two
 * backends", and a mock would only prove that the SDK calls itself — the failures this catches are
 * the ones a real S3-compatible server produces: path-style addressing, the region SigV4 signs with,
 * and the checksum defaults SDK 2.30 introduced. Garage is a *test* dependency here and nothing
 * more; which S3 a deployment points at is its own decision.
 *
 * **It skips rather than fails when Garage is not up**, so `./gradlew build` on a machine with no
 * containers still passes and the message says exactly how to change that. The alternative — a
 * failing build for a missing container — makes the whole suite red for a reason unrelated to the
 * change under test, and people learn to ignore it.
 *
 * One property this suite deliberately relies on and the filesystem one does not: **the bucket is
 * shared and outlives a test method.** Everything is scoped to pod ids the fixture mints per
 * instance; see [PodMediaStoreConformanceTest].
 */
class S3PodMediaStoreTest : PodMediaStoreConformanceTest() {

  override val store: S3PodMediaStore get() = shared

  @Test
  fun `an object lands under the pod's key prefix`() {
    // The one assertion about layout, and it belongs here rather than in the conformance suite: the
    // seam says a store owns how a ref becomes a location.
    val ref = PodMediaRef(podA, "layout-probe")
    store.put(ref, "text/plain", source("bytes"))

    assertEquals(listOf("${podA.value}/layout-probe"), rawKeys("${podA.value}/"))
  }

  @Test
  fun `a key outside this store's layout is ignored rather than reported as media`() {
    // Its own layout is all it may judge: a nested path is not something it wrote. Which *prefixes*
    // are pods it cannot say — a `PodId` promises nothing about its form — so a shared bucket's
    // other prefixes come back as tenants and `PodMediaFacade.reconcile` drops them.
    val stray = "nested/deeper/whatever-${podA.value}"
    put(stray, "not ours")
    try {
      assertTrue(store.iterate { entries -> entries.none { it.ref.mediaId.startsWith("whatever-") } })
    } finally {
      delete(stray)
    }
  }

  @Test
  fun `a stray key inside a pod prefix is reported, because that is the reconcile working`() {
    store.put(PodMediaRef(podA, "real"), "text/plain", source("a1"))
    put("${podA.value}/planted", "not in the registry")

    assertEquals(
      setOf(PodMediaRef(podA, "real"), PodMediaRef(podA, "planted")),
      store.iterate(podA) { entries -> entries.map(MediaEntry::ref).toSet() },
    )
  }

  /** Straight through the SDK, past the store — this is how "the store's own layout" gets asserted. */
  private fun rawKeys(prefix: String): List<String> =
    client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build())
      .contents().map { it.key() }

  private fun put(key: String, content: String) {
    client.putObject(
      PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
      RequestBody.fromString(content),
    )
  }

  private fun delete(key: String) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build())
  }

  companion object {

    /**
     * Matches `deployments/test/garage/init.sh`, which is what creates the bucket and imports these
     * credentials. Constants rather than a discovery step: a test that had to be told where its
     * store is would be one more thing to configure before the suite can run at all.
     */
    private const val ENDPOINT = "http://localhost:3900"
    private const val BUCKET = "sempods-media-test"
    private const val ACCESS_KEY = "GK0123456789abcdef01234567"
    private const val SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    /** Garage's own `s3_region`, which SigV4 signs with — see `deployments/test/garage/garage.toml`. */
    private const val REGION = "garage"

    private val config = S3MediaStoreConfig(
      endpoint = URI.create(ENDPOINT),
      bucket = BUCKET,
      accessKey = ACCESS_KEY,
      secret = SECRET,
      region = REGION,
    )

    /**
     * A client configured exactly like the store's, reached *past* it — asserting the key layout
     * means bypassing the class that owns it. Shared with the store rather than a second connection,
     * so the two cannot be looking at different things.
     */
    private val client = S3PodMediaStore.client(config)

    private val shared = S3PodMediaStore(client, BUCKET)

    @JvmStatic
    @BeforeAll
    fun requireGarage() {
      val reachable = try {
        // A listing rather than a `HeadBucket`: it exercises credentials, region and path-style
        // addressing in one call, which are the three things a misconfigured endpoint gets wrong.
        shared.iterate { it.take(1).toList() }
        true
      } catch (e: Exception) {
        System.err.println("S3 store tests skipped: $ENDPOINT is not answering (${e.message})")
        false
      }
      // **In CI the skip is a failure.** The skip exists so a developer without containers can still
      // run `./gradlew build`; it must never be what CI silently does, because then the one claim
      // this module rests on — the same conformance assertions against a real S3 store — would be
      // checked nowhere. `CI` is set by GitHub Actions, and `.github/actions/setup-test-environment`
      // is what makes Garage available there.
      if (!reachable && System.getenv("CI") == "true") {
        throw IllegalStateException(
          "Garage is not answering at $ENDPOINT, and this is CI — the S3 conformance suite must " +
              "run here. Check the garage service and deployments/test/garage/init.sh in " +
              ".github/actions/setup-test-environment.",
        )
      }
      assumeTrue(
        reachable,
        "Garage is not running. Start it with:\n" +
            "  docker compose -f deployments/local/compose.yaml -f deployments/test/compose.test.yaml up -d\n" +
            "  deployments/test/garage/init.sh",
      )
    }

    @JvmStatic
    @AfterAll
    fun closeStore() {
      runCatching { shared.close() }
    }
  }
}
