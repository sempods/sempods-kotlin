package org.sempods.api.pod.system.resources

import com.google.inject.Inject
import okhttp3.OkHttpClient
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.pod.resources.RepresentationTags
import org.sempods.client.SempodsContent
import org.sempods.client.SempodsContextSelection
import org.sempods.client.SempodsOkHttp
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsPodSlots
import org.sempods.client.SempodsReadOptions
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.SempodsResponse
import org.sempods.client.SempodsSession
import org.sempods.client.SempodsWriteOptions
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpRequest
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.commons.utils.UriEncodingUtil.encodeUriToUrlSafeBase64
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.mongo.persist.PodDbo
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A single-edge `DELETE` under `If-Match` and `If-None-Match`, which name the tag of the edge's slot
 * in the write context (`SPS-CRUD-059` to `SPS-CRUD-061`).
 */
class ConditionalEdgeRemovalHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val objectMapper = JsonMappers.default()

  private val hasMember = "https://example.org/vocab/hasMember"

  private fun base(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}"

  private fun group(pod: PodDbo) = "${base(pod)}/groups/${randomId()}"

  private fun user(pod: PodDbo, name: String) = "${base(pod)}/users/$name"

  private fun context(pod: PodDbo, path: String): URI =
    sempodsUriBuilder.buildContext(pod.name, path).also { register(pod, it) }

  private fun register(pod: PodDbo, context: URI) {
    podContextsDao.create(podId = checkNotNull(pod.id), contextUri = context.toString(), label = null, description = null, createdBy = "test")
  }

  private fun slot(pod: PodDbo, subject: String, context: URI) =
    "${base(pod)}/_system/resources/${encodeUriToUrlSafeBase64(URI(subject))}/${encodeUriToUrlSafeBase64(URI(hasMember))}?context=${enc(context.toString())}"

  private fun edge(pod: PodDbo, subject: String, target: String, context: URI) =
    "${base(pod)}/_system/resources/${encodeUriToUrlSafeBase64(URI(subject))}/${encodeUriToUrlSafeBase64(URI(hasMember))}/" +
      "${encodeUriToUrlSafeBase64(URI(target))}?context=${enc(context.toString())}"

  private fun TestHttpRequest.bearer(token: String) = addHeader("Authorization", "Bearer $token")

  private fun TestHttpRequest.conditions(vararg conditions: Pair<String, String>) =
    apply { conditions.forEach { (header, value) -> addHeader(header, value) } }

  private fun add(slotUrl: String, token: String, target: String, vararg conditions: Pair<String, String>): TestHttpResponse =
    http.preparePost(slotUrl)
      .addHeader("Content-Type", "application/ld+json")
      .bearer(token)
      .conditions(*conditions)
      .setBody("""{"@id":"$target"}""")
      .execute()

  private fun remove(edgeUrl: String, token: String, vararg conditions: Pair<String, String>): TestHttpResponse =
    http.prepareDelete(edgeUrl).bearer(token).conditions(*conditions).execute()

  private fun tagOf(slotUrl: String, token: String): String =
    assertNotNull(http.prepareGet(slotUrl).bearer(token).execute().headers.get("ETag"))

  /** The IRIs the slot holds in the selected context; none when the read finds nothing. */
  private fun members(slotUrl: String, token: String): Set<String> {
    val read = http.prepareGet(slotUrl).addHeader("Accept", "application/ld+json").bearer(token).execute()
    if (read.statusCode == 404) return emptySet()
    assertEquals(200, read.statusCode, read.responseBody)
    return objectMapper.readTree(read.responseBody).mapTo(HashSet()) { it.path("@id").asText() }
  }

  private fun outcome(response: TestHttpResponse): String = objectMapper.readTree(response.responseBody).path("outcome").asText()

  /** Runs [times] calls of [call] released together, and returns what each answered. */
  private fun <T> race(times: Int, call: (Int) -> T): List<T> {
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(times)
    try {
      val answers = (0 until times).map { i -> pool.submit<T> { start.await(); call(i) } }
      start.countDown()
      return answers.map { it.get() }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `the slot's current tag removes the edge and answers the slot's new tag, which the next removal may name`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val owner = mintScopedToken(pod.name, listOf("$x#read", "$x#write"))
    val group = group(pod)
    val (u1, u2) = listOf(user(pod, "u1"), user(pod, "u2"))
    listOf(u1, u2).forEach { assertEquals(201, add(slot(pod, group, x), owner, it).statusCode) }
    val e0 = tagOf(slot(pod, group, x), owner)

    val removed = remove(edge(pod, group, u2, x), owner, "If-Match" to e0)
    assertEquals(200, removed.statusCode, removed.responseBody)
    assertEquals("removed", outcome(removed))
    val e1 = assertNotNull(removed.headers.get("ETag"))
    assertNotEquals(e0, e1)
    assertEquals(tagOf(slot(pod, group, x), owner), e1, "the echo is the tag a read now gives")

    assertEquals(200, remove(edge(pod, group, u1, x), owner, "If-Match" to e1).statusCode, "chained without a read")
    assertEquals(emptySet(), members(slot(pod, group, x), owner))
  }

  @Test
  fun `a stale tag fails the removal with 412, present edge or absent`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val owner = mintScopedToken(pod.name, listOf("$x#read", "$x#write"))
    val group = group(pod)
    val (u1, u2, u3, absent) = listOf("u1", "u2", "u3", "absent").map { user(pod, it) }
    listOf(u1, u2).forEach { assertEquals(201, add(slot(pod, group, x), owner, it).statusCode) }
    val e0 = tagOf(slot(pod, group, x), owner)

    // Unchanged slot: the absent edge is already absent, under the same tag.
    val none = remove(edge(pod, group, absent, x), owner, "If-Match" to e0)
    assertEquals(200, none.statusCode, none.responseBody)
    assertEquals("already_absent", outcome(none))
    assertEquals(e0, none.headers.get("ETag"))

    assertEquals(201, add(slot(pod, group, x), owner, u3).statusCode)
    for (target in listOf(u2, absent)) {
      val refused = remove(edge(pod, group, target, x), owner, "If-Match" to e0)
      assertEquals(412, refused.statusCode, target)
      assertNull(refused.headers.get("ETag"))
    }
    assertEquals(setOf(u1, u2, u3), members(slot(pod, group, x), owner))
  }

  @Test
  fun `star asks whether the slot holds a statement in the write context`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val y = context(pod, "y")
    val owner = mintScopedToken(pod.name, listOf("$x#read", "$x#write", "$y#read", "$y#write"))
    val group = group(pod)
    val u1 = user(pod, "u1")
    // The same slot holds a value in y, which does not count for x.
    assertEquals(201, add(slot(pod, group, y), owner, u1).statusCode)

    val empty = remove(edge(pod, group, u1, x), owner, "If-None-Match" to "*")
    assertEquals(200, empty.statusCode, empty.responseBody)
    assertEquals("already_absent", outcome(empty))
    assertNotNull(empty.headers.get("ETag"), "an empty slot has a tag to chain on")
    assertEquals(412, remove(edge(pod, group, u1, x), owner, "If-Match" to "*").statusCode)

    assertEquals(201, add(slot(pod, group, x), owner, u1).statusCode)
    assertEquals(412, remove(edge(pod, group, u1, x), owner, "If-None-Match" to "*").statusCode)
    assertEquals(setOf(u1), members(slot(pod, group, x), owner))
    val present = remove(edge(pod, group, u1, x), owner, "If-Match" to "*")
    assertEquals(200, present.statusCode, present.responseBody)
    assertEquals("removed", outcome(present))
    assertEquals(setOf(u1), members(slot(pod, group, y), owner))
  }

  @Test
  fun `a change to the same slot in another context leaves the tag valid`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val y = context(pod, "y")
    val owner = mintScopedToken(pod.name, listOf("$x#read", "$x#write", "$y#read", "$y#write"))
    val group = group(pod)
    val (u1, u2, u3) = listOf("u1", "u2", "u3").map { user(pod, it) }
    listOf(u1, u2).forEach { assertEquals(201, add(slot(pod, group, x), owner, it).statusCode) }
    assertEquals(201, add(slot(pod, group, y), owner, u1).statusCode)
    val inX = tagOf(slot(pod, group, x), owner)

    assertEquals(201, add(slot(pod, group, y), owner, u3).statusCode)
    assertEquals(200, remove(edge(pod, group, u1, y), owner).statusCode)

    val removed = remove(edge(pod, group, u2, x), owner, "If-Match" to inX)
    assertEquals(200, removed.statusCode, "only the slot in x decides: ${removed.responseBody}")
    assertEquals("removed", outcome(removed))
    assertEquals(setOf(u1), members(slot(pod, group, x), owner))
    assertEquals(setOf(u3), members(slot(pod, group, y), owner))
  }

  @Test
  fun `a caller who may not write gets 403 and no tag under any condition, whether the context exists or not`() {
    val pod = sempodsTestFactory.newPod()
    val own = context(pod, "own")
    val target = sempodsUriBuilder.buildContext(pod.name, "elsewhere-${randomId()}")
    val group = group(pod)
    val u1 = user(pod, "u1")
    val callers = listOf(
      mintScopedToken(pod.name, listOf("$own#read", "$own#write"), webId = "https://id.test/${randomId()}"),
      mintScopedToken(pod.name, listOf("$own#write", "$target#read"), webId = "https://id.test/${randomId()}"),
    )
    // The tags the slot has before and after it is seeded: a guess that happens to be right.
    fun tagOver(vararg values: String) = RepresentationTags.slot(
      LinkedHashModel().apply { values.forEach { add(Values.iri(group), Values.iri(hasMember), Values.iri(it), Values.iri(target.toString())) } },
      URI(group),
      URI(hasMember),
      target,
      false,
    ).let { "\"${it.value}\"" }
    val conditions = listOf(
      emptyList(),
      listOf("If-Match" to tagOver()),
      listOf("If-Match" to tagOver(u1)),
      listOf("If-Match" to "\"stale\""),
      listOf("If-Match" to "*"),
      listOf("If-None-Match" to "*"),
      listOf("If-Match" to "no-quotes"),
    )
    fun attempts(): List<TestHttpResponse> = callers.flatMap { caller ->
      conditions.map { remove(edge(pod, group, u1, target), caller, *it.toTypedArray()) }
    }

    val absent = attempts()
    register(pod, target)
    val owner = mintScopedToken(pod.name, listOf("$target#read", "$target#write"), webId = "https://id.test/${randomId()}")
    assertEquals(201, add(slot(pod, group, target), owner, u1).statusCode)
    assertEquals(tagOver(u1), tagOf(slot(pod, group, target), owner), "the guess is the slot's tag")
    val present = attempts()

    absent.zip(present).forEachIndexed { i, (before, after) ->
      for (answer in listOf(before, after)) {
        assertEquals(403, answer.statusCode, "attempt $i: ${answer.responseBody}")
        assertNull(answer.headers.get("ETag"), "attempt $i")
      }
      assertEquals(before.responseBody, after.responseBody, "attempt $i: the body must not tell the two states apart")
      assertEquals(
        before.headers.toMultimap() - setOf("date", "traceparent"),
        after.headers.toMultimap() - setOf("date", "traceparent"),
        "attempt $i: nor may the headers",
      )
    }
    assertEquals(setOf(u1), members(slot(pod, group, target), owner))
  }

  @Test
  fun `without a condition the removal stays unconditional and idempotent, and answers the slot's tag`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val owner = mintScopedToken(pod.name, listOf("$x#read", "$x#write"))
    val group = group(pod)
    val (u1, u2) = listOf(user(pod, "u1"), user(pod, "u2"))
    listOf(u1, u2).forEach { assertEquals(201, add(slot(pod, group, x), owner, it).statusCode) }

    val first = remove(edge(pod, group, u2, x), owner)
    val second = remove(edge(pod, group, u2, x), owner)

    assertEquals(listOf(200, 200), listOf(first.statusCode, second.statusCode))
    assertEquals(listOf("removed", "already_absent"), listOf(outcome(first), outcome(second)))
    assertEquals(first.headers.get("ETag"), assertNotNull(second.headers.get("ETag")))
    assertEquals(setOf(u1), members(slot(pod, group, x), owner))
  }

  @Test
  fun `conditional writes racing on one slot under one tag let exactly one through`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val owner = mintScopedToken(pod.name, listOf("$x#read", "$x#write"))
    val group = group(pod)
    val seeded = (0 until 8).map { user(pod, "u$it") }
    seeded.forEach { assertEquals(201, add(slot(pod, group, x), owner, it).statusCode) }

    val tag = tagOf(slot(pod, group, x), owner)
    val removals = race(seeded.size) { i -> remove(edge(pod, group, seeded[i], x), owner, "If-Match" to tag).statusCode }
    assertEquals(listOf(200), removals.filter { it != 412 }, "one removal may pass the tag: $removals")
    assertEquals(seeded.size - 1, members(slot(pod, group, x), owner).size)

    // Edge removals and additions under one tag: the slot route takes the same lock.
    val next = tagOf(slot(pod, group, x), owner)
    val left = members(slot(pod, group, x), owner).toList()
    val mixed = race(8) { i ->
      if (i % 2 == 0) remove(edge(pod, group, left[i / 2], x), owner, "If-Match" to next).statusCode
      else add(slot(pod, group, x), owner, user(pod, "new$i"), "If-Match" to next).statusCode
    }
    assertEquals(1, mixed.count { it != 412 }, "one write may pass the tag: $mixed")
  }

  @Test
  fun `an edge removal that empties the slot echoes the tag a slot DELETE echoes`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x")
    val y = context(pod, "y")
    val owner = mintScopedToken(pod.name, listOf("$x#read", "$x#write", "$y#read", "$y#write"))
    val u1 = user(pod, "u1")
    val alone = group(pod)
    // This one keeps a statement in y, so emptying the slot in x does not remove the subject.
    val shared = group(pod).also { assertEquals(201, add(slot(pod, it, y), owner, u1).statusCode) }

    for (group in listOf(alone, shared)) {
      assertEquals(201, add(slot(pod, group, x), owner, u1).statusCode)
      val emptiedByEdge = assertNotNull(remove(edge(pod, group, u1, x), owner).headers.get("ETag"))
      assertEquals(201, add(slot(pod, group, x), owner, u1).statusCode)
      val cleared = http.prepareDelete(slot(pod, group, x)).bearer(owner).execute()
      assertEquals(200, cleared.statusCode)
      assertEquals(cleared.headers.get("ETag"), emptiedByEdge, group)

      assertEquals(201, add(slot(pod, group, x), owner, u1, "If-Match" to emptiedByEdge).statusCode, "the next write may name it")
    }
  }

  // ── A client that mirrors a source into the slot ─────────────────────────────

  /** What a [Projection] read: the slot's tag, null for an empty slot, and whether the source holds [member]. */
  private class Plan(val member: String, val tag: String?, val isMember: Boolean)

  /**
   * A worker keeping the members of [group] in [context] equal to [source]: it reads the slot's tag,
   * then the source, and sends the write derived from both under that tag.
   */
  private inner class Projection(
    private val slots: SempodsPodSlots,
    private val group: String,
    private val context: String,
    private val source: Set<String>,
  ) {

    fun plan(member: String): Plan {
      val tag = slots.getJson(group, hasMember, SempodsReadOptions.of(SempodsContextSelection.of(context))).headers["ETag"]
      return Plan(member, tag, member in source)
    }

    fun apply(plan: Plan): SempodsResponse<ByteArray> {
      val inContext = SempodsWriteOptions.inContext(context)
      // An empty slot has no tag to read, so the write asks for the slot to be empty still.
      val options = plan.tag?.let(inContext::withIfMatch) ?: inContext.withIfNoneMatch("*")
      return if (plan.isMember) slots.add(group, hasMember, SempodsContent.of("""{"@id":"${plan.member}"}"""), options)
      else slots.removeEdge(group, hasMember, plan.member, options)
    }

    /** Plans and applies until the pod takes the write, reading both again after a `412`. */
    fun sync(member: String): SempodsResponse<ByteArray> {
      repeat(3) { apply(plan(member)).takeIf { it.status != 412 }?.let { return it } }
      fail("$member: no attempt passed its precondition")
    }

    fun members(): Set<String> =
      slots.getJson(group, hasMember, SempodsReadOptions.of(SempodsContextSelection.of(context))).body
        ?.let { objectMapper.readTree(it).mapTo(HashSet()) { value -> value.path("@id").asText() } }
        .orEmpty()
  }

  private fun <T> withCorePod(podName: String, token: String, block: (SempodsPod) -> T): T {
    val client = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    try {
      return block(SempodsPod(SempodsSession(SempodsPodBase.of("${SempodsModule.config.apiBaseUrl}$podName"), SempodsRequestAuth.bearer(token)), client))
    } finally {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }

  private fun String.outcome(): String = objectMapper.readTree(this).path("outcome").asText()

  @Test
  fun `a client that reads the tag before the source catches a stale removal, and cannot order a stale addition`() {
    val pod = sempodsTestFactory.newPod()
    val x = context(pod, "x").toString()
    val owner = mintScopedToken(pod.name, listOf("$x#read", "$x#write"))
    val (u1, u2) = listOf(user(pod, "u1"), user(pod, "u2"))

    withCorePod(pod.name, owner) { core ->
      // Caught: A decides on a removal, B adds under the same tag first, and A's removal fails.
      val source = mutableSetOf(u1)
      val projection = Projection(core.slots(), group(pod), x, source)
      assertEquals(201, projection.sync(u1).status)

      val a = projection.plan(u2)
      source += u2
      assertEquals(201, projection.sync(u2).status, "B adds u2")
      assertEquals(412, projection.apply(a).status, "A's removal was decided on a slot that has changed")
      val retried = projection.sync(u2)
      assertEquals(200, retried.status)
      assertEquals("already_present", String(assertNotNull(retried.body)).outcome(), "A read again and kept u2")
      assertEquals(source, projection.members())

      // The limit: B's removal changes nothing, so the tag A read still holds and A's stale addition lands.
      val limitSource = mutableSetOf(u1, u2)
      val limited = Projection(core.slots(), group(pod), x, limitSource)
      assertEquals(201, limited.sync(u1).status)

      val late = limited.plan(u2)
      limitSource -= u2
      val removal = limited.sync(u2)
      assertEquals(200, removal.status)
      assertEquals("already_absent", String(assertNotNull(removal.body)).outcome())
      assertEquals(201, limited.apply(late).status, "the tag A read is still the slot's tag")
      assertTrue(u2 in limited.members() && u2 !in limitSource, "the slot keeps a member the source no longer has")
    }
  }
}
