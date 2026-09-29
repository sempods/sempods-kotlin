package org.sempods.pods.oauth.serviceclients.persist

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.sempods.SempodsCollections
import org.sempods.SempodsIntegrationTest
import org.bson.Document
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

  @Inject
  private lateinit var db: MongoDatabase

  /** The rows as stored, for what the DAO's own reads normalize away. */
  private val rows by lazy { db.getCollection(SempodsCollections.OAUTH_SERVICE_CLIENTS) }

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
  fun `scopes are replaced on the registration named, and removed down to none`() {
    val created = create("notes-app", emptySet())
    val id = checkNotNull(created.id)

    assertTrue(serviceClientDao.replaceScopes(probePodId, "notes-app", id, 0L, setOf("$notesRoot#read", "$eventsRoot#read"), changedBy = OWNER))
    assertFalse(serviceClientDao.replaceScopes(probePodId, "notes-app", org.bson.types.ObjectId(), 1L, setOf("$notesRoot#write"), changedBy = OWNER))
    assertEquals(
      emptySet(),
      assertNotNull(serviceClientDao.removeScopes(probePodId, "notes-app", setOf("$notesRoot#read", "$eventsRoot#read"), OWNER)).scopes,
    )
    assertNull(serviceClientDao.removeScopes(otherPodId, "notes-app", setOf("$notesRoot#read"), OWNER))
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
    // an empty set. Emptied by an update, it is `[]`: `$pullAll` empties the array it finds.
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

  // ── the grants version ────────────────────────────────────────────────────────

  @Test
  fun `every write to the grants moves the version by one, and nothing else moves it`() {
    val id = checkNotNull(create("notes-app", setOf("$eventsRoot#manage")).id)
    create("elsewhere", setOf("$notesRoot#read"))
    create("notes-app", setOf("$eventsRoot#manage"), podId = otherPodId)
    fun version() = assertNotNull(serviceClientDao.findByClientId(probePodId, "notes-app")).grantsVersion

    assertEquals(0L, version(), "a new row has never had its grants written")
    assertFalse(rawRow(probePodId, "notes-app").containsKey(PodServiceClientDboFields.grantsVersion), "absent spells 0")

    assertTrue(serviceClientDao.replaceScopes(probePodId, "notes-app", id, 0L, setOf("$notesRoot#read", "$eventsRoot#manage"), changedBy = OWNER))
    assertEquals(1L, version(), "a replace")
    serviceClientDao.removeScopes(probePodId, "notes-app", setOf("$notesRoot#read"), changedBy = OWNER)
    assertEquals(2L, version(), "a removal")
    serviceClientDao.revokeByContextScope(probePodId, eventsRoot)
    assertEquals(3L, version(), "a context deletion")
    assertTrue(serviceClientDao.replaceScopes(probePodId, "notes-app", id, 3L, setOf("$notesRoot#write"), changedBy = OWNER))
    assertEquals(4L, version(), "another replace")

    serviceClientDao.replaceSecretHash(probePodId, "notes-app", SECRET_HASH, "rotated")
    serviceClientDao.touchLastUsed(probePodId, "notes-app")
    assertEquals(4L, version(), "a rotation and a token issuance change no grant")

    assertEquals(0L, assertNotNull(serviceClientDao.findByClientId(probePodId, "elsewhere")).grantsVersion,
      "the cascade moves only the rows it strips")
    assertEquals(0L, assertNotNull(serviceClientDao.findByClientId(otherPodId, "notes-app")).grantsVersion,
      "nor another pod's")
  }

  @Test
  fun `a row from before the version reads as 0, and a replace lands only there`() {
    val id = ObjectId()
    rows.insertOne(
      Document("_id", id)
        .append(PodServiceClientDboFields.podId, probePodId)
        .append(PodServiceClientDboFields.clientId, "legacy")
        .append(PodServiceClientDboFields.secretHash, SECRET_HASH)
        .append(PodServiceClientDboFields.scopes, listOf("$eventsRoot#manage"))
        .append(PodServiceClientDboFields.createdAt, java.util.Date.from(CREATED_AT)),
    )
    assertEquals(0L, assertNotNull(serviceClientDao.findByClientId(probePodId, "legacy")).grantsVersion)

    // `{grantsVersion: 0}` alone matches no such row: the replace at 0 has to name the absent field.
    assertFalse(serviceClientDao.replaceScopes(probePodId, "legacy", id, 1L, setOf("$notesRoot#read"), changedBy = OWNER))
    assertTrue(serviceClientDao.replaceScopes(probePodId, "legacy", id, 0L, setOf("$notesRoot#read"), changedBy = OWNER, at = CHANGED_AT))
    assertFalse(
      serviceClientDao.replaceScopes(probePodId, "legacy", id, 0L, setOf("$eventsRoot#read"), changedBy = OWNER),
      "a second replace prepared at the same version writes nothing",
    )

    val read = assertNotNull(serviceClientDao.findByClientId(probePodId, "legacy"))
    assertEquals(setOf("$notesRoot#read"), read.scopes)
    assertEquals(1L, read.grantsVersion)
    assertEquals(CHANGED_AT, read.grantsChangedAt)
    assertEquals(OWNER, read.grantsChangedBy)
  }

  @Test
  fun `a replace and a bound drop leave a registration re-created under the same clientId alone`() {
    val first = checkNotNull(create("notes-app", setOf("$eventsRoot#manage")).id)
    serviceClientDao.delete(probePodId, "notes-app")
    create("notes-app", setOf("$eventsRoot#manage"))

    assertFalse(serviceClientDao.replaceScopes(probePodId, "notes-app", first, 0L, emptySet(), changedBy = OWNER))
    assertFalse(serviceClientDao.exists(probePodId, "notes-app", first))
    assertFalse(serviceClientDao.dropScopes(probePodId, "notes-app", first, setOf("$eventsRoot#manage")))

    val current = assertNotNull(serviceClientDao.findByClientId(probePodId, "notes-app"))
    assertEquals(setOf("$eventsRoot#manage"), current.scopes)
    assertEquals(0L, current.grantsVersion)
  }

  @Test
  fun `a new row keeps the declared order, and a replace appends the fields it adds`() {
    val id = checkNotNull(create("notes-app", setOf("$eventsRoot#manage"), label = "notes").id)
    val inserted = listOf("_id", "podId", "clientId", "secretHash", "scopes", "label", "createdAt")
    assertEquals(inserted, rawRow(probePodId, "notes-app").keys.toList())

    serviceClientDao.replaceScopes(probePodId, "notes-app", id, 0L, emptySet(), changedBy = OWNER)

    // The inserted fields keep their place; the ones the update adds follow in an order the server
    // picks, so what holds for them is the set.
    val replaced = rawRow(probePodId, "notes-app")
    val keys = replaced.keys.toList()
    assertEquals(inserted, keys.take(inserted.size))
    assertEquals(setOf("grantsVersion", "grantsChangedAt", "grantsChangedBy"), keys.drop(inserted.size).toSet())
    assertEquals(emptyList<String>(), replaced.getList(PodServiceClientDboFields.scopes, String::class.java),
      "an emptying update leaves `[]`")
  }

  @Test
  fun `a provisional row stores its redirects and deadline after the declared fields`() {
    val deadline = Instant.now().plusSeconds(3_600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
    create("pending-app", emptySet(), redirectUris = listOf("http://127.0.0.1/cb"), pendingUntil = deadline)

    assertEquals(
      listOf("_id", "podId", "clientId", "secretHash", "createdAt", "redirectUris", "pendingUntil"),
      rawRow(probePodId, "pending-app").keys.toList(),
    )
    val read = assertNotNull(serviceClientDao.findByClientId(probePodId, "pending-app"))
    assertEquals(listOf("http://127.0.0.1/cb"), read.redirectUris)
    assertEquals(deadline, read.pendingUntil)
  }

  @Test
  fun `a row past its deadline is absent to every read and every write but a delete`() {
    // The TTL monitor removes it on its own schedule; until it does, the deadline decides.
    val id = checkNotNull(create("expired-app", emptySet(), pendingUntil = Instant.now().minusSeconds(60)).id)

    assertNull(serviceClientDao.findByClientId(probePodId, "expired-app"))
    assertTrue(serviceClientDao.findByPod(probePodId).none { it.clientId == "expired-app" })
    assertFalse(serviceClientDao.exists(probePodId, "expired-app", id))
    assertFalse(serviceClientDao.replaceScopes(probePodId, "expired-app", id, 0L, setOf("$notesRoot#read"), changedBy = OWNER))
    assertFalse(serviceClientDao.replaceSecretHash(probePodId, "expired-app", SECRET_HASH, "revived"))
    assertNull(serviceClientDao.removeScopes(probePodId, "expired-app", setOf("$notesRoot#read"), OWNER))
    assertNotNull(rawRow(probePodId, "expired-app").getDate(PodServiceClientDboFields.pendingUntil), "nothing revived it")
    assertTrue(serviceClientDao.delete(probePodId, "expired-app"), "a delete still reaches it")
  }

  @Test
  fun `a grant write activates a provisional row, and a removal does not`() {
    val pending = Instant.now().plusSeconds(3_600)
    val replaced = checkNotNull(create("replaced-app", emptySet(), pendingUntil = pending).id)
    val emptied = checkNotNull(create("emptied-app", emptySet(), pendingUntil = pending).id)
    create("removed-app", setOf("$notesRoot#read"), pendingUntil = pending)

    assertNotNull(serviceClientDao.removeScopes(probePodId, "removed-app", setOf("$notesRoot#read"), OWNER))
    assertTrue(serviceClientDao.replaceScopes(probePodId, "replaced-app", replaced, 0L, setOf("$notesRoot#read"), changedBy = OWNER))
    assertTrue(serviceClientDao.replaceScopes(probePodId, "emptied-app", emptied, 0L, emptySet(), changedBy = OWNER))

    assertNotNull(assertNotNull(serviceClientDao.findByClientId(probePodId, "removed-app")).pendingUntil)
    assertNull(assertNotNull(serviceClientDao.findByClientId(probePodId, "replaced-app")).pendingUntil)
    assertNull(
      assertNotNull(serviceClientDao.findByClientId(probePodId, "emptied-app")).pendingUntil,
      "an empty selection activates too",
    )
  }

  @Test
  fun `the deadline is a TTL index`() {
    val index = rows.listIndexes().single { it.get("key", Document::class.java).containsKey(PodServiceClientDboFields.pendingUntil) }

    assertEquals(0, (index["expireAfterSeconds"] as Number).toInt())
  }

  private fun rawRow(podId: ObjectId, clientId: String): Document =
    rows.find(
      Filters.and(
        Filters.eq(PodServiceClientDboFields.podId, podId),
        Filters.eq(PodServiceClientDboFields.clientId, clientId),
      ),
    ).single()

  private fun create(
    clientId: String,
    scopes: Set<String>,
    podId: ObjectId = probePodId,
    label: String? = null,
    redirectUris: List<String> = emptyList(),
    pendingUntil: Instant? = null,
  ): PodServiceClientDbo = serviceClientDao.create(
    PodServiceClientDbo(
      podId = podId,
      clientId = clientId,
      secretHash = SECRET_HASH,
      scopes = scopes,
      label = label,
      createdAt = CREATED_AT,
      redirectUris = redirectUris,
      pendingUntil = pendingUntil,
    ),
  )

  private companion object {

    /** A real bcrypt hash shape — the field is a credential, so it is not a placeholder string. */
    const val SECRET_HASH = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"

    /** Millisecond-precise on purpose: BSON has nowhere to put the nanoseconds. */
    val CREATED_AT: Instant = Instant.parse("2026-08-16T10:15:30.123Z")

    val CHANGED_AT: Instant = Instant.parse("2026-09-28T12:00:00.456Z")

    const val OWNER = "https://id.sempods.org/e/owner"
  }
}
