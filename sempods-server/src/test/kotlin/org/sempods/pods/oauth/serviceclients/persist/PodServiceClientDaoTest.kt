package org.sempods.pods.oauth.serviceclients.persist

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [PodServiceClientDao] stores, revokes and deletes.
 *
 * What the collection holds is a credential — `secretHash` is what every
 * `grant_type=client_credentials` exchange is checked against — so a silent mapping mistake here
 * does not lose data, it stops a service client from authenticating against its own pods.
 *
 * **One assertion here carries more than the rest**: `delete` is a compare-and-swap whose failure
 * mode is deleting somebody else's row.
 */
class PodServiceClientDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var serviceClientDao: PodServiceClientDao

  /** Two pod ids — the second one is how "scoped to this pod" gets asserted. */
  private val probePodId = ObjectId()
  private val otherPodId = ObjectId()

  private val eventsRoot = "https://sempods.org/alice/events"
  private val notesRoot = "https://sempods.org/alice/notes"

  @Test
  fun `a stored client reads back field for field, and clientIds are pod-scoped`() {
    val created = create("notes-app", setOf("$eventsRoot#manage"), label = "notes-app")
    create("reader-app", setOf("$notesRoot#read"))
    create("notes-app", setOf("$eventsRoot#manage"), podId = otherPodId)

    val read = assertNotNull(serviceClientDao.findByClientId(probePodId, "notes-app"))
    assertEquals(created.id, read.id, "create must return the id it stored under — the CAS delete filters on it")
    assertEquals(probePodId, read.podId)
    assertEquals("notes-app", read.clientId)
    assertEquals(SECRET_HASH, read.secretHash, "the bcrypt hash every token exchange is checked against")
    assertEquals(setOf("$eventsRoot#manage"), read.scopes)
    assertEquals("notes-app", read.label)
    assertEquals(CREATED_AT, read.createdAt)
    assertNull(read.lastUsedAt, "a client that never authenticated carries no lastUsedAt")

    assertNull(serviceClientDao.findByClientId(probePodId, "unknown"))
    assertNull(
      serviceClientDao.findByClientId(otherPodId, "reader-app"),
      "clientIds are pod-scoped — a foreign pod must not resolve one",
    )
    assertEquals(setOf("notes-app", "reader-app"), serviceClientDao.findByPod(probePodId).map { it.clientId }.toSet())
  }

  @Test
  fun `a secret is replaced only over the hash the caller read`() {
    // Two rotations that read the same secret: the first write lands, and the second — still
    // holding the hash the first replaced — writes nothing, so it cannot answer a secret that the
    // store no longer holds.
    create("notes-app", setOf("$eventsRoot#manage"))

    assertTrue(serviceClientDao.replaceSecretHash(probePodId, "notes-app", SECRET_HASH, "first"))
    assertFalse(serviceClientDao.replaceSecretHash(probePodId, "notes-app", SECRET_HASH, "second"))
    assertEquals("first", assertNotNull(serviceClientDao.findByClientId(probePodId, "notes-app")).secretHash)
  }

  @Test
  fun `scopes are added to the registration named, and removed down to none`() {
    val created = create("notes-app", emptySet())
    val id = checkNotNull(created.id)

    assertTrue(serviceClientDao.addScopes(probePodId, "notes-app", id, setOf("$notesRoot#read", "$eventsRoot#read")))
    assertFalse(serviceClientDao.addScopes(probePodId, "notes-app", org.bson.types.ObjectId(), setOf("$notesRoot#write")))
    assertEquals(
      emptySet(),
      assertNotNull(serviceClientDao.removeScopes(probePodId, "notes-app", setOf("$notesRoot#read", "$eventsRoot#read"))).scopes,
    )
    assertNull(serviceClientDao.removeScopes(otherPodId, "notes-app", setOf("$notesRoot#read")))
  }

  @Test
  fun `touchLastUsed bumps the row it finds and reports the one it does not`() {
    create("notes-app", setOf("$eventsRoot#manage"))

    assertTrue(serviceClientDao.touchLastUsed(probePodId, "notes-app", CREATED_AT.plusSeconds(60)))
    assertEquals(
      CREATED_AT.plusSeconds(60),
      assertNotNull(serviceClientDao.findByClientId(probePodId, "notes-app")).lastUsedAt,
    )
    assertFalse(
      serviceClientDao.touchLastUsed(probePodId, "absent"),
      "no row, no modification — the caller treats the bump as best-effort",
    )
  }

  @Test
  fun `what create answers is what the next read answers`() {
    // The contract the registration response rests on: `client_id_issued_at` comes from the value
    // `create` hands back, and a caller that re-reads the row must see the same one. Three
    // normalization rules meet here — a BSON date carries milliseconds, an empty collection is not
    // written, and neither is a null — so the row is built to trip all three.
    val created = serviceClientDao.create(
      PodServiceClientDbo(
        podId = probePodId,
        clientId = "round-trip",
        secretHash = SECRET_HASH,
        scopes = emptySet(),
        label = null,
        createdAt = Instant.parse("2026-08-16T10:15:30.123456789Z"),
      ),
    )

    assertEquals(created, serviceClientDao.findByClientId(probePodId, "round-trip"))
    assertEquals(Instant.parse("2026-08-16T10:15:30.123Z"), created.createdAt, "BSON has no nanoseconds")
  }

  @Test
  fun `the context cascade strips scopes and leaves the registrations it empties`() {
    create("only-events", setOf("$eventsRoot#manage"))
    create("also-notes", setOf("$eventsRoot#read", "$notesRoot#manage"))
    create("untouched", setOf("$notesRoot#manage"))
    create("other-pod", setOf("$eventsRoot#manage"), podId = otherPodId)

    assertEquals(2L, serviceClientDao.revokeByContextScope(probePodId, eventsRoot), "two lost a scope")

    assertEquals(
      emptySet(),
      assertNotNull(
        serviceClientDao.findByClientId(probePodId, "only-events"),
        "the registration stays; what it held on the deleted context is gone",
      ).scopes,
    )
    assertEquals(
      setOf("$notesRoot#manage"),
      assertNotNull(serviceClientDao.findByClientId(probePodId, "also-notes")).scopes,
      "a client keeping scopes elsewhere survives, minus the anchored ones",
    )
    assertEquals(
      setOf("$notesRoot#manage"),
      assertNotNull(serviceClientDao.findByClientId(probePodId, "untouched")).scopes,
    )
    assertNotNull(
      serviceClientDao.findByClientId(otherPodId, "other-pod"),
      "another pod's client anchored at the same URI is not touched",
    )
  }

  @Test
  fun `a registration stored with no scopes reads back with none`() {
    // A scope-less row has two spellings. Never written, the field is absent: `putStrings` omits
    // an empty set. Emptied by the cascade, it is `[]`: `$pullAll` empties the array it finds.
    // `getStringSet` answers both with an empty set, which is what makes them one state.
    create("no-grants", emptySet())

    assertEquals(emptySet(), assertNotNull(serviceClientDao.findByClientId(probePodId, "no-grants")).scopes)
    assertEquals(0L, serviceClientDao.revokeByContextScope(probePodId, eventsRoot), "nothing to strip")
    assertNotNull(serviceClientDao.findByClientId(probePodId, "no-grants"), "and no sweep behind it")
  }

  @Test
  fun `delete is conditional on the id the caller observed`() {
    val first = create("notes-app", setOf("$eventsRoot#manage"))
    // The interleaving the compare-and-swap exists for: another caller replaced the row, so the
    // id the first caller observed is no longer the one on disk and its delete must remove nothing.
    serviceClientDao.delete(probePodId, "notes-app")
    val second = create("notes-app", setOf("$eventsRoot#manage"))

    assertFalse(serviceClientDao.delete(probePodId, "notes-app", expectedId = first.id))
    assertNotNull(serviceClientDao.findByClientId(probePodId, "notes-app"))
    assertTrue(serviceClientDao.delete(probePodId, "notes-app", expectedId = second.id))
    assertNull(serviceClientDao.findByClientId(probePodId, "notes-app"))
  }

  @Test
  fun `deleteByPod removes exactly the pod's rows`() {
    create("notes-app", setOf("$eventsRoot#manage"))
    create("reader-app", setOf("$notesRoot#read"))
    create("notes-app", setOf("$eventsRoot#manage"), podId = otherPodId)

    // The pod-deletion cascade in `SempodsFacade`.
    assertEquals(2L, serviceClientDao.deleteByPod(probePodId), "deleteMany, not deleteOne")
    assertEquals(emptyList(), serviceClientDao.findByPod(probePodId))
    assertEquals(1, serviceClientDao.findByPod(otherPodId).size, "another pod's registrations are not swept")

    assertEquals(0L, serviceClientDao.deleteByPod(probePodId), "a repeated cascade is a no-op, not an error")
  }

  private fun create(
    clientId: String,
    scopes: Set<String>,
    podId: ObjectId = probePodId,
    label: String? = null,
  ): PodServiceClientDbo = serviceClientDao.create(
    PodServiceClientDbo(
      podId = podId,
      clientId = clientId,
      secretHash = SECRET_HASH,
      scopes = scopes,
      label = label,
      createdAt = CREATED_AT,
    ),
  )

  private companion object {

    /** A real bcrypt hash shape — the field is a credential, so it is not a placeholder string. */
    const val SECRET_HASH = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"

    /** Millisecond-precise on purpose: BSON has nowhere to put the nanoseconds. */
    val CREATED_AT: Instant = Instant.parse("2026-08-16T10:15:30.123Z")
  }
}
