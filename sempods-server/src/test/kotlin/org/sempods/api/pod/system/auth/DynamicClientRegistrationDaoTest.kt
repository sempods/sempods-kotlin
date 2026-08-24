package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.sempods.SempodsIntegrationTest
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [DynamicClientRegistrationDao] stores, finds and sweeps.
 *
 * The widest row in the schema: twenty-one fields, most of them optional, plus the nested
 * `rawRequest` body that is the only verbatim record of what a client sent at `/register`.
 *
 * Two fixtures are raw documents rather than DAO calls, and both for the same reason: they state a
 * shape the DAO cannot produce. [minimalRow] is a registration from before the Stage-2 fields
 * existed, which is most of the collection; [rowAt] pins `registeredAt`, which `create` takes from
 * the clock.
 *
 * **Runs on a collection this test owns**, named by [TEST_COLLECTION] and dropped before each test.
 */
class DynamicClientRegistrationDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private lateinit var dcrDao: DynamicClientRegistrationDao

  /** Two pod ids — the second one is how "scoped to this pod" gets asserted. */
  private val probePodId = ObjectId()
  private val otherPodId = ObjectId()

  /**
   * Dropped rather than cleared: the collection holds nothing but fixtures, and the DAO built right
   * after it recreates all five indexes in its constructor.
   */
  @BeforeEach
  fun setUpOwnCollection() {
    db.getCollection(TEST_COLLECTION).drop()
    dcrDao = DynamicClientRegistrationDao(db, TEST_COLLECTION)
  }

  @Test
  fun `a stored registration reads back field for field, and is pod-scoped`() {
    val created = dcrDao.create(
      clientId = "dcr-9f3a",
      registeredForPodId = probePodId,
      registeredForPodName = "alice",
      redirectUris = setOf("https://app.example.org/cb"),
      clientName = "Example",
      clientUri = "https://example.org",
      logoUri = "https://example.org/logo.png",
      softwareId = "example-cli",
      softwareVersion = "1.2.3",
      contacts = listOf("ops@example.org"),
      tosUri = "https://example.org/tos",
      policyUri = "https://example.org/privacy",
      rawRequest = RAW_REQUEST,
      remoteAddr = "203.0.113.7",
      userAgent = "Example/1.2.3",
      fingerprint = "fp-1",
    )
    assertNotNull(created.id, "create must return the id it stored under, not the null it took")
    rowAt("dcr-other", podId = otherPodId)

    val read = assertNotNull(dcrDao.findByClientId(probePodId, "dcr-9f3a"))
    assertEquals(created.id, read.id)
    assertEquals("dcr-9f3a", read.clientId)
    assertEquals(probePodId, read.registeredForPodId)
    assertEquals("alice", read.registeredForPodName)
    // Truncated, not equal: `create` stamps `Instant.now()`, and BSON has nowhere to put the
    // nanoseconds — a round trip through this collection is not the identity function.
    assertEquals(created.registeredAt.truncatedTo(ChronoUnit.MILLIS), read.registeredAt)
    assertEquals(setOf("https://app.example.org/cb"), read.redirectUris)
    assertEquals("Example", read.clientName)
    assertEquals("https://example.org", read.clientUri)
    assertEquals("https://example.org/logo.png", read.logoUri)
    assertEquals("example-cli", read.softwareId)
    assertEquals("1.2.3", read.softwareVersion)
    assertEquals(listOf("ops@example.org"), read.contacts)
    assertEquals("https://example.org/tos", read.tosUri)
    assertEquals("https://example.org/privacy", read.policyUri)
    assertEquals("203.0.113.7", read.remoteAddr)
    assertEquals("Example/1.2.3", read.userAgent)
    assertEquals("fp-1", read.fingerprint)
    assertNull(read.lastAuthorizedAt)
    assertEquals(1, read.schemaVersion)

    // The verbatim body, nesting and non-string scalars included — the one record of what the
    // client actually sent.
    assertEquals("Example", read.rawRequest["client_name"])
    assertEquals(listOf("authorization_code", "refresh_token"), read.rawRequest["grant_types"])
    assertEquals(mapOf("vendor" to "acme", "retries" to 3, "beta" to true), read.rawRequest["extra"])

    assertNull(
      dcrDao.findByClientId(otherPodId, "dcr-9f3a"),
      "a clientId is pod-scoped and must not resolve at a foreign pod",
    )
    assertEquals("dcr-9f3a", assertNotNull(dcrDao.findByFingerprint(probePodId, "fp-1")).clientId)
    assertNull(dcrDao.findByFingerprint(otherPodId, "fp-1"), "the dedup lookup is pod-scoped too")
  }

  @Test
  fun `a minimal row from before the later fields existed still reads`() {
    // Most of the collection: rows written before the Stage-2 observation fields, the fingerprint
    // dedup and `schemaVersion`. Each missing field has to answer its default rather than throw —
    // `contacts` in particular is non-nullable in the DBO and absent on the wire.
    minimalRow("dcr-old")

    val read = assertNotNull(dcrDao.findByClientId(probePodId, "dcr-old"))
    assertEquals(emptyList(), read.contacts, "absent, and not an exception")
    assertNull(read.clientName)
    assertNull(read.fingerprint)
    assertNull(read.lastAuthorizedAt)
    assertEquals(1, read.schemaVersion, "the default the field was introduced with")
    assertEquals("Example", read.rawRequest["client_name"])
  }

  @Test
  fun `an empty registration body is omitted rather than stored as an empty document`() {
    // The `putNotNull` contract the whole schema is written to: an empty map is left out, the way
    // an empty collection is. A `{}` here would be the one visible difference between a row this
    // build wrote and one written before it.
    dcrDao.create(
      clientId = "dcr-empty",
      registeredForPodId = probePodId,
      registeredForPodName = "alice",
      redirectUris = setOf("https://app.example.org/cb"),
      clientName = null,
      clientUri = null,
      logoUri = null,
      softwareId = null,
      softwareVersion = null,
      contacts = emptyList(),
      tosUri = null,
      policyUri = null,
      rawRequest = emptyMap(),
    )

    val raw = db.getCollection(TEST_COLLECTION)
      .find(Filters.eq(DynamicClientRegistrationDboFields.clientId, "dcr-empty"))
      .single()
    assertTrue(DynamicClientRegistrationDboFields.rawRequest !in raw.keys, raw.toJson())
    assertEquals(emptyMap(), assertNotNull(dcrDao.findByClientId(probePodId, "dcr-empty")).rawRequest)
  }

  @Test
  fun `touchLastAuthorized lands on the row, and only on this pod's`() {
    rowAt("dcr-9f3a")

    assertTrue(dcrDao.touchLastAuthorized(probePodId, "dcr-9f3a", TOUCHED_AT))
    assertEquals(TOUCHED_AT, assertNotNull(dcrDao.findByClientId(probePodId, "dcr-9f3a")).lastAuthorizedAt)

    assertFalse(
      dcrDao.touchLastAuthorized(probePodId, "dcr-unknown", TOUCHED_AT),
      "no row, no modification — a did:web client has none and the caller treats it as best-effort",
    )
    assertFalse(
      dcrDao.touchLastAuthorized(otherPodId, "dcr-9f3a", TOUCHED_AT),
      "and the touch is pod-scoped like every other lookup here",
    )
  }

  @Test
  fun `the fingerprint lookup answers the most recent row`() {
    // Two registrations sharing a fingerprint is what the dedup index allows and the sort resolves:
    // re-registration reuses the newest clientId rather than an arbitrary one. The timestamps are
    // written directly because `create` takes `registeredAt` from the clock.
    rowAt("dcr-older", fingerprint = "fp-1", registeredAt = REGISTERED_AT)
    rowAt("dcr-newer", fingerprint = "fp-1", registeredAt = REGISTERED_AT.plusSeconds(3600))

    assertEquals("dcr-newer", assertNotNull(dcrDao.findByFingerprint(probePodId, "fp-1")).clientId)
    assertNull(dcrDao.findByFingerprint(probePodId, "fp-absent"))
  }

  @Test
  fun `deleteByPod removes exactly the pod's rows`() {
    rowAt("dcr-1")
    rowAt("dcr-2")
    rowAt("dcr-3", podId = otherPodId)

    // The pod-deletion cascade in `SempodsFacade`.
    assertEquals(2L, dcrDao.deleteByPod(probePodId), "deleteMany, not deleteOne")
    assertNull(dcrDao.findByClientId(probePodId, "dcr-1"))
    assertNotNull(dcrDao.findByClientId(otherPodId, "dcr-3"), "another pod's registrations survive")

    assertEquals(0L, dcrDao.deleteByPod(probePodId), "a repeated cascade is a no-op, not an error")
  }

  /** A full row with a stated `registeredAt`, which [DynamicClientRegistrationDao.create] does not take. */
  private fun rowAt(
    clientId: String,
    podId: ObjectId = probePodId,
    fingerprint: String? = null,
    registeredAt: Instant = REGISTERED_AT,
  ) {
    val document = baseRow(clientId, podId, registeredAt)
      .append(DynamicClientRegistrationDboFields.contacts, emptyList<String>())
      .append(DynamicClientRegistrationDboFields.schemaVersion, 1)
    if (fingerprint != null) {
      document.append(DynamicClientRegistrationDboFields.fingerprint, fingerprint)
    }
    db.getCollection(TEST_COLLECTION).insertOne(document)
  }

  /** A row from before the Stage-2 observation fields, the fingerprint dedup and `schemaVersion`. */
  private fun minimalRow(clientId: String) {
    db.getCollection(TEST_COLLECTION).insertOne(baseRow(clientId, probePodId, REGISTERED_AT))
  }

  private fun baseRow(clientId: String, podId: ObjectId, registeredAt: Instant) = Document()
    .append(DynamicClientRegistrationDboFields.id, ObjectId())
    .append(DynamicClientRegistrationDboFields.clientId, clientId)
    .append(DynamicClientRegistrationDboFields.registeredForPodId, podId)
    .append(DynamicClientRegistrationDboFields.registeredForPodName, "alice")
    .append(DynamicClientRegistrationDboFields.registeredAt, Date.from(registeredAt))
    .append(DynamicClientRegistrationDboFields.redirectUris, listOf("https://app.example.org/cb"))
    .append(DynamicClientRegistrationDboFields.rawRequest, Document(RAW_REQUEST))

  private companion object {

    /** This test's own collection, outside the `sempods.` namespace the server addresses. */
    const val TEST_COLLECTION = "test.dynamicClientRegistrations.dao"

    /** Millisecond-precise on purpose: BSON has nowhere to put the nanoseconds. */
    val REGISTERED_AT: Instant = Instant.parse("2026-08-16T10:15:30.123Z")
    val TOUCHED_AT: Instant = Instant.parse("2026-08-16T12:00:00Z")

    /** A body with nesting, an array and non-string scalars — what a real `/register` carries. */
    val RAW_REQUEST: Map<String, Any?> = linkedMapOf(
      "client_name" to "Example",
      "redirect_uris" to listOf("https://app.example.org/cb"),
      "grant_types" to listOf("authorization_code", "refresh_token"),
      "extra" to mapOf("vendor" to "acme", "retries" to 3, "beta" to true),
    )
  }
}
