package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.sempods.SempodsModule
import org.sempods.SempodsStoreTest
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.commons.logging.CapturedLog
import org.sempods.pods.PodId
import org.sempods.pods.oauth.PodRefreshTokenStore.RevocationReason
import org.sempods.pods.mongo.persist.toPodId
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Family limits and revocation diagnostics against the real token collection. */
class PodRefreshTokenStoreTest : SempodsStoreTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private val collection = "test.familyLimits.${ObjectId()}"
  private lateinit var store: PodRefreshTokenStore
  private val pod = ObjectId().toPodId()
  private val client = "did:web:app.example"
  private val person = "https://id.example/e/person"
  private val alias = "urn:sempods:e:person"

  @BeforeEach
  fun setUp() {
    store = PodRefreshTokenStore(db, collection, SempodsModule.config.copy(connectionMaxFamilies = 1))
  }

  @AfterEach
  fun dropCollection() {
    db.getCollection(collection).drop()
    db.getCollection("$collection.sequence").drop()
  }

  private fun issue(
    pod: PodId = this.pod,
    clientId: String = client,
    webId: String = person,
  ) = store.issueNewFamily(
    pod = pod,
    podName = "test",
    clientId = clientId,
    webId = webId,
    scopes = emptySet(),
    lifetime = PodRefreshTokenStore.Lifetime.DURABLE,
  )

  @Test
  fun `the configured limit groups aliases but isolates other pods clients and people`() {
    val old = issue(webId = alias)
    db.getCollection(collection).updateOne(
      Filters.eq(RefreshTokenStore.Field.TOKEN_HASH, old.token.tokenHash),
      Updates.set(RefreshTokenStore.Field.ISSUED_AT, Instant.now().minusSeconds(60)),
    )
    val unrelated = listOf(issue(pod = ObjectId().toPodId()), issue(clientId = "other"), issue(webId = "other"))
    val newest = issue()

    val lines = CapturedLog.linesFrom(PodRefreshTokenStore::class.java) {
      store.enforceConnectionLimit(pod, client, listOf(person, alias), newFamilyId = newest.token.familyId)
    }

    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(old.plaintext).state)
    assertEquals("refresh token retired: connection limit", store.revocationDescription(old.token.familyId))
    for (survivor in unrelated + newest) {
      assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(survivor.plaintext).state)
    }
    assertTrue(lines.any { "cause='limit'" in it && "clientId='$client'" in it && "familyId='${old.token.familyId}'" in it })
  }

  @Test
  fun `expired and revoked families do not displace a live connection`() {
    store = PodRefreshTokenStore(db, collection, SempodsModule.config.copy(connectionMaxFamilies = 2))
    val expired = issue()
    db.getCollection(collection).updateOne(
      Filters.eq(RefreshTokenStore.Field.TOKEN_HASH, expired.token.tokenHash),
      Updates.set(RefreshTokenStore.Field.EXPIRES_AT, Instant.now().minusSeconds(1)),
    )
    val revoked = issue()
    store.revokeFamily(revoked.token.familyId, reason = RevocationReason.REUSE)
    val live = issue()
    val newest = issue()

    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = newest.token.familyId)

    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(live.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
  }

  @Test
  fun `a revoked tip does not count its rotated predecessors as a live family`() {
    store = PodRefreshTokenStore(db, collection, SempodsModule.config.copy(connectionMaxFamilies = 2))
    val first = issue()
    assertTrue(store.markRotated(first.token.tokenHash))
    val successor = store.issueInFamily(first.token, emptySet())
    store.revokeTokens(listOf(successor.token.tokenHash))
    assertEquals(RefreshTokenStore.LookupState.REUSED, store.lookup(first.plaintext).state)
    val live = issue()
    val newest = issue()
    // Make the live family older so incorrectly counting the revoked one would evict it.
    db.getCollection(collection).updateOne(
      Filters.eq(RefreshTokenStore.Field.TOKEN_HASH, live.token.tokenHash),
      Updates.set(RefreshTokenStore.Field.ISSUED_AT, Instant.now().minusSeconds(60)),
    )

    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = newest.token.familyId)

    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(live.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
  }

  @Test
  fun `rotated rows count as one family and an in-flight successor inherits its retirement cause`() {
    val first = issue()
    assertTrue(store.markRotated(first.token.tokenHash))
    val second = store.issueInFamily(first.token, emptySet())
    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = first.token.familyId)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(second.plaintext).state)

    assertTrue(store.markRotated(second.token.tokenHash))
    db.getCollection(collection).updateMany(
      Filters.eq(RefreshTokenStore.Field.FAMILY_ID, first.token.familyId),
      Updates.combine(
        Updates.set(RefreshTokenStore.Field.ISSUED_AT, Instant.now().minusSeconds(60)),
        Updates.set(RefreshTokenStore.Field.ROTATED_AT, Instant.now().minusSeconds(30)),
      ),
    )
    val newest = issue()
    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = newest.token.familyId)
    val inFlight = store.issueInFamily(second.token, emptySet())
    assertTrue(store.noLongerStands(second.token.tokenHash))
    store.revokeFamily(inFlight.token.familyId, reason = assertNotNull(store.revocationReason(first.token.familyId)))

    assertEquals("refresh token retired: connection limit", store.revocationDescription(inFlight.token.familyId))
    assertTrue(store.findByFamily(first.token.familyId).all { it.revokedAt != null })
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
  }

  @Test
  fun `a rotation after issuance cannot make the new family retire itself`() {
    val existing = issue()
    val newest = issue()
    db.getCollection(collection).updateOne(
      Filters.eq(RefreshTokenStore.Field.TOKEN_HASH, newest.token.tokenHash),
      Updates.set(RefreshTokenStore.Field.ISSUED_AT, Instant.now().minusSeconds(60)),
    )
    assertTrue(store.markRotated(existing.token.tokenHash))
    val rotated = store.issueInFamily(existing.token, emptySet())

    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = newest.token.familyId)

    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(rotated.plaintext).state)
    assertEquals("refresh token retired: connection limit", store.revocationDescription(existing.token.familyId))
    assertEquals(setOf(newest.token.familyId), store.liveFamilies(pod, client, listOf(person)))
  }

  @Test
  fun `equal timestamps cannot make the new family retire itself through the id tie break`() {
    val existing = issue()
    val newest = issue()
    val newFamilyId = "00000000-0000-0000-0000-000000000000"
    db.getCollection(collection).updateOne(
      Filters.eq(RefreshTokenStore.Field.TOKEN_HASH, newest.token.tokenHash),
      Updates.combine(
        Updates.set(RefreshTokenStore.Field.FAMILY_ID, newFamilyId),
        Updates.set(RefreshTokenStore.Field.ISSUED_AT, existing.token.issuedAt),
      ),
    )

    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = newFamilyId)

    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(existing.plaintext).state)
    assertEquals("refresh token retired: connection limit", store.revocationDescription(existing.token.familyId))
    assertEquals(setOf(newFamilyId), store.liveFamilies(pod, client, listOf(person)))
  }

  @Test
  fun `a sweep whose new family has already ended leaves other connections alone`() {
    val first = issue()
    val second = issue()
    val newest = issue()
    store.revokeFamily(newest.token.familyId, reason = RevocationReason.LIMIT)

    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = newest.token.familyId)

    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(first.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(second.plaintext).state)
  }

  @Test
  fun `two replicas selecting before either retires keep the same newest family`() {
    val replica = PodRefreshTokenStore(db, collection, SempodsModule.config.copy(connectionMaxFamilies = 1))
    val first = issue(webId = alias)
    val newest = replica.issueNewFamily(pod, "test", client, person, emptySet(), PodRefreshTokenStore.Lifetime.DURABLE)
    // Both snapshots precede both writes, exactly the interleaving that could retire both families.
    val firstVictims = store.connectionLimitVictims(pod, client, listOf(person, alias), first.token.familyId)
    val secondVictims = replica.connectionLimitVictims(pod, client, listOf(person, alias), newest.token.familyId)

    firstVictims.forEach { store.revokeFamily(it, reason = RevocationReason.LIMIT) }
    secondVictims.forEach { replica.revokeFamily(it, reason = RevocationReason.LIMIT) }

    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(first.plaintext).state)
    assertEquals(setOf(newest.token.familyId), store.liveFamilies(pod, client, listOf(person, alias)))
  }

  @Test
  fun `replicas allocate distinct family orders from the same empty counter`() {
    val replica = PodRefreshTokenStore(db, collection, SempodsModule.config.copy(connectionMaxFamilies = 1))
    val start = CyclicBarrier(8)
    val pool = Executors.newFixedThreadPool(8)
    try {
      val calls = (0 until 8).map { index -> Callable {
        start.await(10, TimeUnit.SECONDS)
        val writer = if (index % 2 == 0) store else replica
        writer.issueNewFamily(pod, "test", client, person, emptySet(), PodRefreshTokenStore.Lifetime.DURABLE)
      } }
      val issued = pool.invokeAll(calls).map { it.get(10, TimeUnit.SECONDS) }
      assertEquals((1L..8L).toList(), issued.map { it.token.owner.familyOrder }.sorted())
      issued.forEach {
        assertEquals(it.token.owner.familyOrder, assertNotNull(store.lookup(it.plaintext).token).owner.familyOrder)
      }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `rotation retains family order after the original row is reaped`() {
    val original = issue()
    val newest = issue()
    assertTrue(store.markRotated(original.token.tokenHash))
    val rotated = store.issueInFamily(original.token, emptySet())
    db.getCollection(collection).deleteOne(Filters.eq(RefreshTokenStore.Field.TOKEN_HASH, original.token.tokenHash))

    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = newest.token.familyId)

    assertEquals(original.token.owner.familyOrder, rotated.token.owner.familyOrder)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(rotated.plaintext).state)
  }

  @Test
  fun `legacy rows without issuance order remain readable and yield to a new family`() {
    val legacy = issue()
    db.getCollection(collection).updateOne(
      Filters.eq(RefreshTokenStore.Field.TOKEN_HASH, legacy.token.tokenHash),
      Updates.unset("familyOrder"),
    )
    val read = assertNotNull(store.lookup(legacy.plaintext).token)
    assertEquals(0L, read.owner.familyOrder)
    val newest = issue()

    store.enforceConnectionLimit(pod, client, listOf(person), newFamilyId = newest.token.familyId)

    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(legacy.plaintext).state)
  }

  @Test
  fun `overlapping revocations in the same millisecond log only the rows each changed`() {
    val now = Instant.parse("2035-01-01T00:00:00Z")
    store = PodRefreshTokenStore(db, collection, SempodsModule.config, clock = { now })
    val first = issue()
    val second = issue()

    val firstLines = CapturedLog.linesFrom(PodRefreshTokenStore::class.java) {
      assertEquals(1L, store.revokeFamily(first.token.familyId, reason = RevocationReason.LIMIT))
    }
    val secondLines = CapturedLog.linesFrom(PodRefreshTokenStore::class.java) {
      assertEquals(1L, store.revokeFamilies(listOf(first.token.familyId, second.token.familyId), reason = RevocationReason.LIMIT))
    }

    val familyIds = setOf(first.token.familyId, second.token.familyId)
    assertEquals(1, firstLines.count { line -> familyIds.any { "familyId='$it'" in line } })
    assertEquals(1, secondLines.count { line -> familyIds.any { "familyId='$it'" in line } })
    assertTrue(secondLines.any { "familyId='${second.token.familyId}'" in it })
    assertTrue(secondLines.none { "familyId='${first.token.familyId}'" in it })
    assertEquals(now, store.findByFamily(second.token.familyId).single().revokedAt)
  }

  @Test
  fun `a lowered limit retires all excess families and logs each once`() {
    val families = (1..10).map { issue() }
    store = PodRefreshTokenStore(db, collection, SempodsModule.config.copy(connectionMaxFamilies = 2))
    val newest = issue()

    val lines = CapturedLog.linesFrom(PodRefreshTokenStore::class.java) {
      store.enforceConnectionLimit(pod, client, listOf(person), newest.token.familyId)
    }

    val retired = families.filter { store.lookup(it.plaintext).state == RefreshTokenStore.LookupState.REVOKED }
    assertEquals(9, retired.size)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
    assertEquals(2, store.liveFamilies(pod, client, listOf(person)).size)
    val familyIds = (families + newest).map { it.token.familyId }.toSet()
    assertEquals(9, lines.count { line -> familyIds.any { "familyId='$it'" in line } })
    retired.forEach { family ->
      assertEquals(1, lines.count { "cause='limit'" in it && "familyId='${family.token.familyId}'" in it })
    }
  }

  @Test
  fun `lookup family selection and revocation share the injected clock`() {
    var now = Instant.parse("2035-01-01T00:00:00Z")
    store = PodRefreshTokenStore(db, collection, SempodsModule.config.copy(connectionMaxFamilies = 2), clock = { now })
    val expired = issue()
    now = expired.token.expiresAt.plusSeconds(1)
    val live = issue()
    val newest = issue()

    assertEquals(RefreshTokenStore.LookupState.EXPIRED, store.lookup(expired.plaintext).state)
    store.enforceConnectionLimit(pod, client, listOf(person), newest.token.familyId)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(live.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
    assertEquals(now, live.token.issuedAt)
    assertEquals(now.plus(store.termsOf(PodRefreshTokenStore.Lifetime.DURABLE).absolute), live.token.endsAt)

    store.revokeFamily(live.token.familyId, reason = RevocationReason.DISCONNECT)
    assertEquals(now, store.findByFamily(live.token.familyId).single().revokedAt)
  }

  @Test
  fun `revocation codes stay compatible with stored rows and unknown causes remain revoked`() {
    val codes = listOf("limit", "reuse", "withdrawn grant", "consent changed", "retired", "sign-out", "reauthorization", "disconnect")
    assertEquals(codes, RevocationReason.entries.map { it.code })
    for (reason in RevocationReason.entries) {
      val family = issue()
      store.revokeFamily(family.token.familyId, reason)
      val row = assertNotNull(db.getCollection(collection).find(Filters.eq("familyId", family.token.familyId)).first())
      assertEquals(reason.code, row.getString("revocationReason"))
      assertEquals(reason, store.revocationReason(family.token.familyId))
      store.revokeFamily(family.token.familyId, RevocationReason.RETIRED)
      assertEquals(reason, store.revocationReason(family.token.familyId), "later calls preserve the first cause")
    }
    val unknown = issue()
    db.getCollection(collection).updateOne(
      Filters.eq("familyId", unknown.token.familyId),
      Updates.combine(Updates.set("revokedAt", Instant.now()), Updates.set("revocationReason", "future cause")),
    )
    assertEquals(RevocationReason.RETIRED, store.revocationReason(unknown.token.familyId))
    assertEquals("refresh token revoked", store.revocationDescription(unknown.token.familyId))
    db.getCollection(collection).updateOne(Filters.eq("familyId", unknown.token.familyId), Updates.unset("revocationReason"))
    assertEquals(null, store.revocationReason(unknown.token.familyId))
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(unknown.plaintext).state)
    assertEquals("refresh token revoked", store.revocationDescription(unknown.token.familyId))
  }

  @Test
  fun `superseding a sibling neither excludes its family from the cap nor hides its retirement cause`() {
    val original = issue()
    assertEquals(RefreshTokenStore.RotationResult.ROTATED, store.rotateWithGrace(original.token.tokenHash))
    val first = store.issueInFamily(original.token, emptySet())
    assertEquals(RefreshTokenStore.RotationResult.GRACE, store.rotateWithGrace(original.token.tokenHash))
    val retry = store.issueInFamily(original.token, emptySet(), RefreshTokenStore.RotationResult.GRACE)
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(first.plaintext).state)
    val newest = issue()

    store.enforceConnectionLimit(pod, client, listOf(person), newest.token.familyId)

    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(retry.plaintext).state)
    assertEquals(RevocationReason.LIMIT, store.revocationReason(original.token.familyId))
    assertEquals("refresh token retired: connection limit", store.revocationDescription(original.token.familyId))
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(newest.plaintext).state)
  }

  @Test
  fun `bulk revocations log each family with client and cause`() {
    for (cause in listOf(RevocationReason.SIGN_OUT, RevocationReason.REUSE, RevocationReason.WITHDRAWN_GRANT, RevocationReason.DISCONNECT)) {
      val first = issue()
      val second = issue()
      val lines = CapturedLog.linesFrom(PodRefreshTokenStore::class.java) {
        when (cause) {
          RevocationReason.SIGN_OUT -> store.revokeForPerson(pod, listOf(person))
          RevocationReason.REUSE -> listOf(first, second).forEach { store.revokeFamily(it.token.familyId, reason = cause) }
          else -> store.revokeForUser(pod, client, person, reason = cause)
        }
      }
      for (issued in listOf(first, second)) {
        assertTrue(lines.any { "cause='${cause.code}'" in it && "clientId='$client'" in it && "familyId='${issued.token.familyId}'" in it })
      }
    }
  }
}
