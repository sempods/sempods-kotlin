package org.sempods.pods.contexts.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.SempodsIntegrationTest
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [PodContextsDao] stores, lists and deletes.
 *
 * **Two of these are about the collection's history**: rows written before `isPublic` existed are
 * still on disk, the decoder defaults them to private, and the *query* does not agree with the
 * decoder. [preIsPublicRow] states that shape as a document, because the DAO cannot produce it.
 */
class PodContextsDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  @Inject
  private lateinit var contextsDao: PodContextsDao

  /** Two pod ids — the second one is how "scoped to this pod" gets asserted. */
  private val probePodId = ObjectId()
  private val otherPodId = ObjectId()

  private val eventsUri = "https://sempods.org/alice/events"
  private val notesUri = "https://sempods.org/alice/notes"

  @Test
  fun `a stored context reads back field for field, and the lookups are pod-scoped`() {
    val created = assertNotNull(
      contextsDao.create(
        podId = probePodId,
        contextUri = eventsUri,
        label = "Events",
        description = "Everything Alice is going to",
        createdBy = "https://id.sempods.org/e/abc",
      ),
    )
    assertNotNull(created.id, "create must return the row carrying the id it was stored under")
    assertTrue(contextsDao.setPublic(probePodId, eventsUri, isPublic = true))
    contextsDao.create(probePodId, notesUri, null, null, null)
    contextsDao.create(otherPodId, eventsUri, null, null, null)

    val read = assertNotNull(contextsDao.fetchByContextUri(probePodId, eventsUri))
    assertEquals(created.id, read.id)
    assertEquals(probePodId, read.podId)
    assertEquals(eventsUri, read.contextUri)
    assertEquals("Events", read.label)
    assertEquals("Everything Alice is going to", read.description)
    assertTrue(read.isPublic)
    assertEquals("https://id.sempods.org/e/abc", read.createdBy)

    assertTrue(contextsDao.exists(probePodId, eventsUri))
    assertFalse(contextsDao.exists(probePodId, "https://sempods.org/alice/absent"))
    assertFalse(contextsDao.exists(otherPodId, notesUri), "the check is pod-scoped")

    // The listing path, including the visibility filter that reads the stored boolean.
    assertEquals(listOf(eventsUri, notesUri), contextsDao.fetchByPod(probePodId).map { it.contextUri })
    assertEquals(listOf(eventsUri), contextsDao.fetchByPod(probePodId, public = true).map { it.contextUri })
    assertEquals(listOf(notesUri), contextsDao.fetchByPod(probePodId, public = false).map { it.contextUri })
  }

  @Test
  fun `a fresh context is private, and setPublic is idempotent`() {
    assertNotNull(contextsDao.create(probePodId, eventsUri, null, null, null))
    assertFalse(
      assertNotNull(contextsDao.fetchByContextUri(probePodId, eventsUri)).isPublic,
      "a context is private unless asked otherwise",
    )

    assertTrue(contextsDao.setPublic(probePodId, eventsUri, isPublic = true))
    assertTrue(assertNotNull(contextsDao.fetchByContextUri(probePodId, eventsUri)).isPublic)
    assertFalse(
      contextsDao.setPublic(probePodId, eventsUri, isPublic = true),
      "setting the value it already has modifies nothing, which the backfill's idempotency rests on",
    )
  }

  @Test
  fun `a row written before isPublic existed reads back as private`() {
    // The rows the public-contexts backfill was written for. They carry no `isPublic` field at all,
    // so the reader's default is the only thing that decides — and `getBoolean(key, false)` is what
    // makes it "private" rather than an exception or, worse, `true`.
    preIsPublicRow(eventsUri)

    val read = assertNotNull(contextsDao.fetchByContextUri(probePodId, eventsUri))
    assertFalse(read.isPublic)
    assertEquals(listOf(eventsUri), contextsDao.fetchByPod(probePodId).map { it.contextUri })

    // And the backfill's own operation still works on it.
    assertTrue(contextsDao.setPublic(probePodId, eventsUri, isPublic = true))
    assertTrue(assertNotNull(contextsDao.fetchByContextUri(probePodId, eventsUri)).isPublic)
  }

  @Test
  fun `the visibility filter misses a pre-isPublic row, and that is unchanged behaviour`() {
    // Measured rather than reasoned about, and the answer is not the one the reader gives: the
    // *decoder* defaults a missing `isPublic` to `false`, but the *query* does not. `{isPublic:
    // false}` matches only rows that carry the field — `null` is the one value whose equality
    // filter also matches an absent field — so a row written before the flag existed appears in
    // neither visibility listing, only in the unfiltered one.
    //
    // It predates the driver: the filter Morphia issued missed the same row, measured before this
    // collection moved. Anything wanting those rows in the private listing is a `$ne: true` change
    // to `fetchByPod`, and that is a product decision about pre-backfill rows rather than a bug to
    // fix in passing.
    preIsPublicRow(eventsUri)

    assertEquals(listOf(eventsUri), contextsDao.fetchByPod(probePodId).map { it.contextUri })
    assertEquals(emptyList(), contextsDao.fetchByPod(probePodId, public = false))
    assertEquals(emptyList(), contextsDao.fetchByPod(probePodId, public = true))
  }

  @Test
  fun `a second create for the same context answers null rather than a duplicate row`() {
    assertNotNull(contextsDao.create(probePodId, eventsUri, null, null, null))
    assertNull(contextsDao.create(probePodId, eventsUri, null, null, null))
    assertEquals(1, contextsDao.fetchByPod(probePodId).size)
    // Same URI, different pod: the unique index is composite, and the second row is legitimate.
    assertNotNull(contextsDao.create(otherPodId, eventsUri, null, null, null))
  }

  @Test
  fun `delete and deleteByPod remove exactly their rows`() {
    contextsDao.create(probePodId, eventsUri, null, null, null)
    contextsDao.create(probePodId, notesUri, null, null, null)
    contextsDao.create(otherPodId, eventsUri, null, null, null)

    assertTrue(contextsDao.delete(probePodId, eventsUri))
    assertFalse(contextsDao.delete(probePodId, eventsUri), "already gone is `false`, not an error")
    assertNull(contextsDao.fetchByContextUri(probePodId, eventsUri))

    // The pod-deletion cascade in `SempodsFacade`.
    assertEquals(1L, contextsDao.deleteByPod(probePodId), "deleteMany, not deleteOne")
    assertEquals(emptyList(), contextsDao.fetchByPod(probePodId))
    assertEquals(1, contextsDao.fetchByPod(otherPodId).size, "another pod's contexts are not swept")

    assertEquals(0L, contextsDao.deleteByPod(probePodId), "a repeated cascade is a no-op, not an error")
  }

  /**
   * A row as it sat on disk before the visibility flag existed: no `isPublic` field at all.
   *
   * Written as a document because that is the shape being asserted, and because the DAO cannot
   * produce it — every row it writes carries the boolean.
   */
  private fun preIsPublicRow(contextUri: String) {
    db.getCollection(SempodsCollections.CONTEXTS).insertOne(
      Document()
        .append(PodContextDboFields.id, ObjectId())
        .append(PodContextDboFields.podId, probePodId)
        .append(PodContextDboFields.contextUri, contextUri)
        .append(PodContextDboFields.createdAt, Date.from(CREATED_AT)),
    )
  }

  private companion object {

    /** Millisecond-precise on purpose: BSON has nowhere to put the nanoseconds. */
    val CREATED_AT: Instant = Instant.parse("2026-08-16T10:15:30.123Z")
  }
}
