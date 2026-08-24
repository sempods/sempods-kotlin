package org.sempods.pods.media.persist

import com.google.inject.Inject
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.SempodsIntegrationTest
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reference count of a media object is its set of context assignments, and
 * [PodMedia.unreferencedSince] is what says the set just emptied. Everything downstream — the sweep,
 * the grace period, whether a pod's bytes are ever reclaimed — reads that one field, so these are
 * the transitions M1 exists to get right.
 */
class PodMediaDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var dao: PodMediaDao

  private val contextA = "https://contexts.example.org/a"
  private val contextB = "https://contexts.example.org/b"

  private fun assignment(context: String, filename: String? = "photo.jpg", contentType: String = "image/jpeg") =
    MediaAssignment(
      context = context,
      contentType = contentType,
      filename = filename,
      // Truncated because BSON dates carry milliseconds: an untruncated Instant would not survive a
      // round trip, and the assertions below would compare a value the database cannot store.
      createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
      createdBy = "https://id.example.org/alice#me",
    )

  private fun seed(
    podId: ObjectId = ObjectId(),
    mediaId: String = randomId(),
    contexts: Set<String> = setOf(contextA),
    filename: String? = "photo.jpg",
  ): PodMedia {
    val assignments = contexts.mapTo(LinkedHashSet()) { assignment(it, filename) }
    val row = PodMedia(
      podId = podId,
      mediaId = mediaId,
      size = 1234,
      assignments = assignments,
      unreferencedSince = null,
    )
    assignments.forEach { dao.upsertAndAssign(row, it) }
    return row
  }

  @Test
  fun `a row round-trips through the document mapping`() {
    val seeded = seed()

    assertEquals(seeded, dao.find(seeded.podId, seeded.mediaId))
  }

  @Test
  fun `upsertAndAssign creates once and assigns every time`() {
    // This is what makes `store` idempotent under concurrency rather than only in sequence: with a
    // read-then-insert, two uploads of identical bytes would both see no row and one would hit the
    // unique index. One statement means the loser assigns instead of failing.
    val podId = ObjectId()
    val mediaId = randomId()
    val candidate = PodMedia(podId = podId, mediaId = mediaId, size = 7, assignments = emptySet(), unreferencedSince = null)
    val mine = assignment(contextA, filename = "mine.jpg")
    val theirs = assignment(contextB, filename = "theirs.png", contentType = "image/png")

    val first = dao.upsertAndAssign(candidate, mine)
    val second = dao.upsertAndAssign(candidate.copy(size = 999), theirs)

    assertEquals(setOf(mine), first.assignments)
    // Each assignment keeps its own description — that is what stops the second party from reading
    // back the first one's and learning the bytes were already here.
    assertEquals(setOf(mine, theirs), second.assignments)
    assertEquals(7, second.size, "size is settled by the bytes, so an existing row keeps it")
  }

  @Test
  fun `re-assigning a context replaces its description rather than duplicating it`() {
    val podId = ObjectId()
    val mediaId = randomId()
    val candidate = PodMedia(podId = podId, mediaId = mediaId, size = 7, assignments = emptySet(), unreferencedSince = null)
    dao.upsertAndAssign(candidate, assignment(contextA, filename = "first.jpg"))

    val updated = dao.upsertAndAssign(candidate, assignment(contextA, filename = "second.jpg"))

    // An assignment is identified by its context. Two entries differing only in `createdAt` would
    // make the reference count wrong and leave the reader to guess which description holds.
    assertEquals(1, updated.assignments.size)
    assertEquals("second.jpg", updated.assignments.single().filename)
  }

  @Test
  fun `upsertAndAssign brings an unreferenced row back`() {
    val seeded = seed(contexts = setOf(contextA))
    dao.removeContext(seeded.podId, seeded.mediaId, contextA, Instant.now())

    val revived = dao.upsertAndAssign(seeded, assignment(contextB))

    assertEquals(setOf(contextB), revived.contexts)
    assertNull(revived.unreferencedSince, "a row with an assignment is not a sweep candidate")
  }

  @Test
  fun `an absent filename stays absent rather than becoming an empty string`() {
    val seeded = seed(filename = null)

    assertNull(assertNotNull(dao.find(seeded.podId, seeded.mediaId)).assignments.single().filename)
  }

  @Test
  fun `removing the last assignment stamps unreferencedSince`() {
    val seeded = seed(contexts = setOf(contextA))
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    assertTrue(dao.removeContext(seeded.podId, seeded.mediaId, contextA, now))

    val after = assertNotNull(dao.find(seeded.podId, seeded.mediaId))
    assertEquals(emptySet(), after.contexts)
    assertEquals(now, after.unreferencedSince, "the row must say when it became unreferenced")
  }

  @Test
  fun `removing one of several assignments leaves the row referenced`() {
    val seeded = seed(contexts = setOf(contextA, contextB))

    assertTrue(dao.removeContext(seeded.podId, seeded.mediaId, contextA, Instant.now()))

    val after = assertNotNull(dao.find(seeded.podId, seeded.mediaId))
    assertEquals(setOf(contextB), after.contexts)
    assertNull(after.unreferencedSince, "a row that still carries an assignment is not unreferenced")
  }

  @Test
  fun `assigning again clears unreferencedSince`() {
    val seeded = seed(contexts = setOf(contextA))
    dao.removeContext(seeded.podId, seeded.mediaId, contextA, Instant.now())
    assertNotNull(assertNotNull(dao.find(seeded.podId, seeded.mediaId)).unreferencedSince)

    assertTrue(dao.addAssignment(seeded.podId, seeded.mediaId, assignment(contextB)))

    val after = assertNotNull(dao.find(seeded.podId, seeded.mediaId))
    assertEquals(setOf(contextB), after.contexts)
    assertNull(after.unreferencedSince, "an assignment brought the row back — it is not a sweep candidate")
  }

  @Test
  fun `assigning the same context twice changes nothing at all`() {
    val seeded = seed(contexts = setOf(contextA))
    val before = assertNotNull(dao.find(seeded.podId, seeded.mediaId))

    // A *later* assignment, with a different description and a later timestamp — the retry a
    // context-copy makes. `addAssignment` is the idempotent half of the pair, so the stored entry
    // must survive untouched; a fresh `createdAt` on every retry would mean the answer keeps moving.
    assertTrue(
      dao.addAssignment(
        seeded.podId,
        seeded.mediaId,
        assignment(contextA, filename = "retry.png", contentType = "image/png")
          .copy(createdAt = before.assignments.single().createdAt.plusSeconds(60)),
      ),
    )

    assertEquals(before.assignments, assertNotNull(dao.find(seeded.podId, seeded.mediaId)).assignments)
  }

  @Test
  fun `removing a context the row does not carry leaves it referenced`() {
    val seeded = seed(contexts = setOf(contextA))

    assertTrue(dao.removeContext(seeded.podId, seeded.mediaId, contextB, Instant.now()))

    val after = assertNotNull(dao.find(seeded.podId, seeded.mediaId))
    assertEquals(setOf(contextA), after.contexts)
    assertNull(after.unreferencedSince)
  }

  @Test
  fun `removing an assignment from an already-unreferenced row is a no-op, not a failure`() {
    val seeded = seed(contexts = setOf(contextA))
    val firstRemoval = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    dao.removeContext(seeded.podId, seeded.mediaId, contextA, firstRemoval)

    // The row now has no `contexts` field at all. An aggregation expression reading a missing field
    // as an array is where this quietly breaks — `$setDifference` on it yields null, and `$size`
    // fails the whole update rather than the caller's expectation of a no-op.
    assertTrue(dao.removeContext(seeded.podId, seeded.mediaId, contextA, firstRemoval.plusSeconds(60)))

    val after = assertNotNull(dao.find(seeded.podId, seeded.mediaId))
    assertEquals(emptySet(), after.contexts)
    assertEquals(
      firstRemoval,
      after.unreferencedSince,
      "the stamp marks when the row became unreferenced, so a repeated removal must not move it — " +
          "otherwise a caller that unassigns twice postpones the sweep for ever",
    )
  }

  @Test
  fun `the grace period cannot be restarted by a later removal`() {
    // The failure this guards is silent and unbounded: a context cascade that touches rows which
    // are already unreferenced would push every one of them out by its own runtime, again and
    // again, and nothing about the data would look wrong.
    val seeded = seed(contexts = setOf(contextA, contextB))
    val becameUnreferenced = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    dao.removeContext(seeded.podId, seeded.mediaId, contextA, becameUnreferenced.minusSeconds(30))
    dao.removeContext(seeded.podId, seeded.mediaId, contextB, becameUnreferenced)

    dao.removeContext(seeded.podId, seeded.mediaId, contextB, becameUnreferenced.plusSeconds(3600))

    assertEquals(becameUnreferenced, assertNotNull(dao.find(seeded.podId, seeded.mediaId)).unreferencedSince)
    assertTrue(
      seeded.mediaId in dao.findUnreferencedBefore(becameUnreferenced.plusMillis(1)).map { it.mediaId },
      "the row must still be a sweep candidate against the original cutoff",
    )
  }

  @Test
  fun `both updates report a miss for a media this pod does not hold`() {
    val absent = randomId()

    assertFalse(dao.addAssignment(ObjectId(), absent, assignment(contextA)))
    assertFalse(dao.removeContext(ObjectId(), absent, contextA, Instant.now()))
  }

  @Test
  fun `findUnreferencedBefore sees only rows unreferenced strictly earlier`() {
    val unreferencedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    val swept = seed(contexts = setOf(contextA))
    val kept = seed(contexts = setOf(contextA))
    dao.removeContext(swept.podId, swept.mediaId, contextA, unreferencedAt)

    val candidates = dao.findUnreferencedBefore(unreferencedAt.plusMillis(1)).map { it.mediaId }

    assertTrue(swept.mediaId in candidates, "an unreferenced row before the cutoff is a candidate")
    assertFalse(kept.mediaId in candidates, "a referenced row is never a candidate")
    // Strictly before: a caller passing the very instant the row was stamped must not pick it up.
    assertFalse(swept.mediaId in dao.findUnreferencedBefore(unreferencedAt).map { it.mediaId })
  }

  @Test
  fun `two pods holding the same media id do not disturb each other`() {
    val mediaId = randomId()
    val first = seed(mediaId = mediaId, contexts = setOf(contextA))
    val second = seed(mediaId = mediaId, contexts = setOf(contextA))

    dao.removeContext(first.podId, mediaId, contextA, Instant.now())

    assertEquals(emptySet(), assertNotNull(dao.find(first.podId, mediaId)).contexts)
    assertEquals(setOf(contextA), assertNotNull(dao.find(second.podId, mediaId)).contexts)
  }

  // -- the context cascade (PodFacade.removeContext) --

  @Test
  fun `removeContextFromAll strips the context from every media of the pod`() {
    val podId = ObjectId()
    val onlyThatContext = seed(podId = podId, contexts = setOf(contextA))
    val alsoElsewhere = seed(podId = podId, contexts = setOf(contextA, contextB))
    val untouched = seed(podId = podId, contexts = setOf(contextB))
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    assertEquals(2, dao.removeContextFromAll(podId, contextA, now), "only the rows carrying it match")

    val emptied = assertNotNull(dao.find(podId, onlyThatContext.mediaId))
    assertEquals(emptySet(), emptied.contexts)
    assertEquals(now, emptied.unreferencedSince, "the row lost its last assignment and must say so")

    val remaining = assertNotNull(dao.find(podId, alsoElsewhere.mediaId))
    assertEquals(setOf(contextB), remaining.contexts)
    assertNull(remaining.unreferencedSince, "a row that still carries an assignment is not a candidate")

    assertEquals(setOf(contextB), assertNotNull(dao.find(podId, untouched.mediaId)).contexts)
  }

  @Test
  fun `removeContextFromAll stops at the pod boundary`() {
    // Contexts are pod-scoped URIs, so a collision is not expected — but the cascade runs on every
    // context deletion, and a filter that forgot the pod would quietly strip another pod's media.
    val mine = ObjectId()
    val theirs = ObjectId()
    val myMedia = seed(podId = mine, contexts = setOf(contextA))
    val theirMedia = seed(podId = theirs, contexts = setOf(contextA))

    dao.removeContextFromAll(mine, contextA, Instant.now())

    assertEquals(emptySet(), assertNotNull(dao.find(mine, myMedia.mediaId)).contexts)
    assertEquals(setOf(contextA), assertNotNull(dao.find(theirs, theirMedia.mediaId)).contexts)
  }

  @Test
  fun `removeContextFromAll does not restart the grace period of rows that are already unreferenced`() {
    // The failure this guards is silent and unbounded: every context deletion would re-stamp every
    // unreferenced row of the pod, so on a pod with regular context churn nothing would ever be
    // collectable — and nothing about the data would look wrong.
    val podId = ObjectId()
    val alreadyUnreferenced = seed(podId = podId, contexts = setOf(contextB))
    val becameUnreferenced = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600)
    dao.removeContext(podId, alreadyUnreferenced.mediaId, contextB, becameUnreferenced)

    dao.removeContextFromAll(podId, contextA, Instant.now())

    assertEquals(
      becameUnreferenced,
      assertNotNull(dao.find(podId, alreadyUnreferenced.mediaId)).unreferencedSince,
    )
  }

  // -- the pod cascade (SempodsFacade.deletePod) --

  @Test
  fun `markPodUnreferenced stamps every row of the pod and empties its assignments`() {
    val podId = ObjectId()
    val referenced = seed(podId = podId, contexts = setOf(contextA, contextB))
    val other = ObjectId()
    val foreign = seed(podId = other, contexts = setOf(contextA))
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    assertEquals(1, dao.markPodUnreferenced(podId, now))

    val stamped = assertNotNull(dao.find(podId, referenced.mediaId))
    assertEquals(now, stamped.unreferencedSince)
    // "Stamped ⟺ no assignment" is the invariant everything downstream reads. The pod's contexts go
    // in the same cascade, so a row still naming them would claim to be referenced by contexts that
    // no longer exist.
    assertEquals(emptySet(), stamped.contexts)

    val untouched = assertNotNull(dao.find(other, foreign.mediaId))
    assertEquals(setOf(contextA), untouched.contexts)
    assertNull(untouched.unreferencedSince)
  }

  @Test
  fun `markPodUnreferenced keeps an older stamp rather than restarting the grace period`() {
    val podId = ObjectId()
    val seeded = seed(podId = podId, contexts = setOf(contextA))
    val becameUnreferenced = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600)
    dao.removeContext(podId, seeded.mediaId, contextA, becameUnreferenced)

    dao.markPodUnreferenced(podId, Instant.now())

    assertEquals(
      becameUnreferenced,
      assertNotNull(dao.find(podId, seeded.mediaId)).unreferencedSince,
      "a row unreferenced long before the pod went must not have its deletion postponed by it",
    )
  }

  // -- the sweep --

  @Test
  fun `deleteIfUnreferencedBefore takes a row that is past its grace period`() {
    val seeded = seed(contexts = setOf(contextA))
    val unreferencedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    dao.removeContext(seeded.podId, seeded.mediaId, contextA, unreferencedAt)

    assertTrue(dao.deleteIfUnreferencedBefore(seeded.podId, seeded.mediaId, unreferencedAt.plusMillis(1)))

    assertNull(dao.find(seeded.podId, seeded.mediaId))
  }

  @Test
  fun `deleteIfUnreferencedBefore leaves a row whose assignment came back`() {
    // This is the whole reason the delete is conditional: the sweep reads its candidates, and an
    // upload or a context-copy lands before it gets to this row. An unconditional delete by key
    // would take away bytes somebody had just re-assigned.
    val seeded = seed(contexts = setOf(contextA))
    val unreferencedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    dao.removeContext(seeded.podId, seeded.mediaId, contextA, unreferencedAt)
    dao.addAssignment(seeded.podId, seeded.mediaId, assignment(contextB))

    assertFalse(dao.deleteIfUnreferencedBefore(seeded.podId, seeded.mediaId, unreferencedAt.plusMillis(1)))

    assertEquals(setOf(contextB), assertNotNull(dao.find(seeded.podId, seeded.mediaId)).contexts)
  }

  @Test
  fun `deleteIfUnreferencedBefore leaves a referenced row and reports a miss for an unknown one`() {
    val referenced = seed(contexts = setOf(contextA))

    assertFalse(dao.deleteIfUnreferencedBefore(referenced.podId, referenced.mediaId, Instant.now()))
    assertFalse(dao.deleteIfUnreferencedBefore(ObjectId(), randomId(), Instant.now()))

    assertNotNull(dao.find(referenced.podId, referenced.mediaId))
  }

  @Test
  fun `findUnreferencedBefore can be scoped to one pod`() {
    val mine = ObjectId()
    val theirs = ObjectId()
    val myMedia = seed(podId = mine, contexts = setOf(contextA))
    val theirMedia = seed(podId = theirs, contexts = setOf(contextA))
    val unreferencedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    dao.removeContext(mine, myMedia.mediaId, contextA, unreferencedAt)
    dao.removeContext(theirs, theirMedia.mediaId, contextA, unreferencedAt)

    val candidates = dao.findUnreferencedBefore(unreferencedAt.plusMillis(1), podId = mine).map { it.mediaId }

    assertEquals(listOf(myMedia.mediaId), candidates)
  }

  // -- the reconcile's registry half --

  @Test
  fun `iterate hands out every row of a pod exactly once`() {
    val podId = ObjectId()
    val seeded = List(3) { seed(podId = podId, contexts = setOf(contextA)) }
    seed(contexts = setOf(contextA))

    val walked = dao.iterate(podId) { rows -> rows.map { it.ref }.toList() }

    assertEquals(seeded.map { it.ref }.toSet(), walked.toSet())
    assertEquals(walked.size, walked.toSet().size, "no row twice")
  }

  @Test
  fun `iterate without a pod sees rows across pods`() {
    val first = seed(contexts = setOf(contextA))
    val second = seed(contexts = setOf(contextA))

    val walked = dao.iterate { rows -> rows.map { it.ref }.toSet() }

    assertTrue(first.ref in walked)
    assertTrue(second.ref in walked)
  }
}
