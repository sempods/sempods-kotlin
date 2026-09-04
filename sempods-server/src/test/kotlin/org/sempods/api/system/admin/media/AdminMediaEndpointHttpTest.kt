package org.sempods.api.system.admin.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.admin.AdminAuthorizerTestDouble
import org.sempods.pods.media.PodMediaFacade
import org.sempods.pods.media.PodMediaStore
import org.sempods.pods.media.persist.PodMedia
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host-level media maintenance surface: `POST /_system/admin/media/sweep` and
 * `.../reconcile`.
 *
 * **This test pins the HTTP contract, not the behaviour** — authorization, status codes, the JSON
 * shape, and that a malformed request changes nothing. What a sweep and a reconcile actually *do*
 * lives in `PodMediaFacadeTest`, where the calls can be scoped to one pod and the assertions are
 * therefore exact.
 *
 * That split is forced by what these routes are: they are host-level and take no pod, so every call
 * here reaches the whole database — which one lazily built injector shares with every other sempods
 * test in this JVM. So the rows seeded here are stamped **decades in the past** and swept with a
 * correspondingly long grace period: the cutoff lands between them and everything any other *class*
 * wrote, and a route that is deliberately global stays harmless in a shared fixture. Between this
 * class's own methods that stamping separates nothing, which is what the `@ResourceLock` is for.
 */
/**
 * Serialised against itself. A sweep is host-level and takes no pod, so the one a method runs
 * reaches the candidates every other method seeded: it deletes them, and their assertions then find
 * nothing. The committed configuration hides that (`same_thread` within a class);
 * `-PtestMethodsConcurrent` shows it.
 *
 * Rung 1 of `docs/testing.md` §"When a test is not safe" has nothing to key on here. The only
 * dimension a caller owns is the cutoff, and a sweep takes *everything* older than it — so a
 * per-method time band still contains every band below it. `an empty body means the deployment's
 * own grace period` closes the question: its cutoff is the deployment's 30 days, which is the
 * assertion. So rung 2, under a name nothing else holds.
 */
@ResourceLock("sempods-admin-media-sweep")
class AdminMediaEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var adminAuthorizer: AdminAuthorizerTestDouble

  @Inject
  private lateinit var podMediaFacade: PodMediaFacade

  @Inject
  private lateinit var mediaStore: PodMediaStore

  @Inject
  private lateinit var mediaDao: PodMediaDao

  @TempDir
  lateinit var sources: Path

  private val objectMapper = ObjectMapper()

  private val adminBearer = "Bearer ${AdminAuthorizerTestDouble.TEST_ADMIN_SECRET}"

  private val context = URI("https://contexts.example.org/admin-media-${randomId()}")

  private fun url(route: String) = "${SempodsModule.config.apiBaseUrl}_system/admin/media/$route"

  private fun post(route: String, body: String? = null, authorization: String? = adminBearer): TestHttpResponse =
    http.preparePost(url(route))
      .addHeader("Content-Type", "application/json")
      .apply { authorization?.let { addHeader("Authorization", it) } }
      .apply { body?.let { setBody(it) } }
      .execute()

  private fun TestHttpResponse.int(name: String): Int = objectMapper.readTree(responseBody).path(name).asInt()

  private fun TestHttpResponse.bool(name: String): Boolean = objectMapper.readTree(responseBody).path(name).asBoolean()

  /**
   * A media whose last assignment went away at [ANCIENT] — old enough that [LONG_GRACE] separates it
   * from every row any other test in this JVM produced.
   */
  private fun seedAncientlyUnreferenced(podId: ObjectId): PodMedia {
    val source = Files.createTempFile(sources, "src-", ".bin").apply { writeText("bytes-${randomId()}") }
    val stored = podMediaFacade.store(podId, context, source, "image/jpeg", filename = "photo.jpg")
    mediaDao.removeContext(podId, stored.mediaId, context.toString(), ANCIENT)
    return stored
  }

  @Test
  fun `both routes refuse an unauthenticated caller without disclosing anything`() {
    assertEquals(401, post("sweep", authorization = null).statusCode)
    assertEquals(401, post("sweep", authorization = "Bearer wrong-${randomId()}").statusCode)
    assertEquals(401, post("reconcile", authorization = null).statusCode)
  }

  @Test
  fun `with no admin authority configured both routes fail closed`() {
    // 503, not 401: the deployment is misconfigured, not the caller. Fail closed either way — an
    // unconfigured authority never passes.
    adminAuthorizer.withAuthorizer(AdminAuthorizerTestDouble.unconfigured) {
      assertEquals(503, post("sweep").statusCode)
      assertEquals(503, post("reconcile").statusCode)
    }
  }

  @Test
  fun `sweep deletes the row and the bytes and reports what it did`() {
    val podId = ObjectId()
    val swept = seedAncientlyUnreferenced(podId)

    val response = post("sweep", """{"gracePeriodSeconds":$LONG_GRACE_SECONDS}""")

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    assertFalse(response.bool("dryRun"))
    assertEquals(LONG_GRACE_SECONDS, objectMapper.readTree(response.responseBody).path("gracePeriodSeconds").asLong())
    assertTrue(response.int("candidates") >= 1)
    assertTrue(response.int("deleted") >= 1)
    assertTrue(response.int("reclaimedBytes") >= swept.size)
    assertNull(mediaDao.find(podId, swept.mediaId), "the row is gone")
    assertFalse(mediaStore.exists(swept.ref), "and so are the bytes")
  }

  @Test
  fun `a dry run reports the candidate and leaves it exactly where it was`() {
    val podId = ObjectId()
    val candidate = seedAncientlyUnreferenced(podId)

    val response = post("sweep", """{"gracePeriodSeconds":$LONG_GRACE_SECONDS,"dryRun":true}""")

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    assertTrue(response.bool("dryRun"))
    assertTrue(response.int("candidates") >= 1)
    assertEquals(0, response.int("deleted"))
    assertNotNull(mediaDao.find(podId, candidate.mediaId))
    assertTrue(mediaStore.exists(candidate.ref))
  }

  @Test
  fun `an empty body means the deployment's own grace period`() {
    val podId = ObjectId()
    val insideGracePeriod = seedAncientlyUnreferenced(podId)

    // The cron's call. `SempodsTestModule` leaves the default (30 days) in place, so a row stamped
    // decades ago *is* past it — the assertion is that the route ran with a configured value rather
    // than refusing a request that named none.
    val response = post("sweep")

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    assertEquals(
      Duration.ofDays(30).seconds,
      objectMapper.readTree(response.responseBody).path("gracePeriodSeconds").asLong(),
    )
    assertNull(mediaDao.find(podId, insideGracePeriod.mediaId))
  }

  @Test
  fun `a request the server cannot honour changes nothing`() {
    val podId = ObjectId()
    val untouched = seedAncientlyUnreferenced(podId)

    // A negative grace period would reach into the future and take rows still inside theirs.
    assertEquals(400, post("sweep", """{"gracePeriodSeconds":-1}""").statusCode)
    // An unknown field is a 400 rather than a shrug: the project-wide mapper ignores them, which
    // here would turn a misspelt `dryRun` into "no assertion" and delete bytes somebody meant to
    // preview. Same reasoning as AdminPodsEndpoint's expectedRegistrationId, same kind of stakes.
    assertEquals(400, post("sweep", """{"dry_run":true}""").statusCode)
    assertEquals(400, post("sweep", """{"gracePeriodSeconds":"soon"}""").statusCode)

    assertNotNull(mediaDao.find(podId, untouched.mediaId), "a rejected request must not sweep")
    assertTrue(mediaStore.exists(untouched.ref))
  }

  @Test
  fun `reconcile answers a report and corrects nothing`() {
    val podId = ObjectId()
    val broken = seedAncientlyUnreferenced(podId)
    mediaStore.delete(broken.ref)

    val response = post("reconcile")

    assertEquals(200, response.statusCode, "body=${response.responseBody}")
    val report = objectMapper.readTree(response.responseBody)
    assertTrue(report.path("checkedRows").asInt() >= 1)
    assertTrue(report.path("rowsWithoutObjectCount").asInt() >= 1)
    assertTrue(report.has("objectsWithoutRow") && report.path("objectsWithoutRow").isArray)
    assertTrue(report.has("truncated"))
    // A report, not a repair: the row it just named is still there.
    assertNotNull(mediaDao.find(podId, broken.mediaId))
  }

  companion object {

    /**
     * Long before any other test's rows, so a global sweep driven from here cannot reach them.
     * Twenty-six years is not a plausible grace period, which is the point — nothing about this
     * value could be mistaken for a production one.
     */
    private val ANCIENT: Instant = Instant.parse("2000-01-01T00:00:00Z")

    /** A cutoff between [ANCIENT] and now, expressed the way the route takes it. */
    private val LONG_GRACE_SECONDS = Duration.ofDays(3650).seconds
  }
}
