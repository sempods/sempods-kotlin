package org.sempods.auth.core

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [RefreshTokenStore] stores, rotates and revokes.
 *
 * **The rotation filter is the point of this file.** `markRotated` selects on `rotatedAt` and
 * `revokedAt` being `null`, against fields that are never *written*: a live token carries neither,
 * so both are absent on the wire. `{field: null}` is the one equality that matches a missing field
 * as well as an explicit BSON null — if it did not, every rotation would look like a replay and the
 * reuse detector would revoke the family of every legitimate client. That is a query concern with
 * no encoder in it, so only a test with a database can ask it.
 *
 * The second thing only a database can answer is the **field order**, and it is what let two live
 * collections move onto one class: both were already laid out with the owner block between
 * `familyId` and `scopes`, so the two owner shapes below are asserted against exactly the orders
 * `oauth.refreshTokens` and `oauth.refreshTokens` carry on disk.
 *
 * Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent.
 */
class RefreshTokenStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private const val COLLECTION = "refreshTokens"
    private const val MCP_COLLECTION = "refreshTokens.mcpShaped"
    private val dbName = "sempods-auth-core-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping RefreshTokenStore test")
      mongoClient = MongoClients.create(MONGO_URL).also { db = it.getDatabase(dbName) }
    }

    @AfterAll @JvmStatic
    fun teardown() {
      db?.drop(); mongoClient?.close()
    }

    private fun mongoReachable(): Boolean = runCatching {
      val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(MONGO_URL))
        .applyToClusterSettings { it.serverSelectionTimeout(1, TimeUnit.SECONDS) }
        .build()
      MongoClients.create(settings).use { it.getDatabase("admin").runCommand(Document("ping", 1)) }
      true
    }.getOrDefault(false)

    /**
     * Far enough out that Mongo's TTL reaper never prunes a fixture mid-test. Time is otherwise the
     * [clock]'s to decide, which is why it can be a date nobody will see.
     */
    private val ISSUED_AT: Instant = Instant.parse("2099-01-01T00:00:00Z")
    private val ROTATED_AT: Instant = ISSUED_AT.plusSeconds(3600)
    private val REVOKED_AT: Instant = ISSUED_AT.plusSeconds(7200)

    private val PROBE_POD = ObjectId()
    private val OTHER_POD = ObjectId()
    private const val WEB_ID = "https://id.sempods.org/e/abc"
    private const val EVENTS_ROOT = "https://sempods.org/alice/events"
  }

  /** The pod server's owner, field for field — the wider of the two, and the one with an index that is not it. */
  private data class PodOwner(val podId: ObjectId, val podName: String, val clientId: String, val webId: String)

  /** The hosted service's owner. */
  private data class McpOwner(val user: String, val profile: String, val clientId: String)

  /** The "now" of issuing, rotation and revocation — moved by hand, never waited for. */
  private var clock: Instant = ISSUED_AT

  private lateinit var store: RefreshTokenStore<PodOwner>

  @BeforeEach
  fun setUpOwnCollection() {
    // Dropped rather than cleared: the collection holds nothing but fixtures, and the store built
    // right after it recreates all four indexes in its constructor — the TTL one included.
    db!!.getCollection(COLLECTION).drop()
    clock = ISSUED_AT
    store = podStore(COLLECTION)
  }

  private fun podStore(collection: String) = RefreshTokenStore<PodOwner>(
    db = db!!,
    collectionName = collection,
    ownerIndexFields = listOf("podId", "clientId", "webId"),
    writeOwner = {
      put("podId", it.podId)
      put("podName", it.podName)
      put("clientId", it.clientId)
      put("webId", it.webId)
    },
    readOwner = {
      PodOwner(
        podId = getObjectId("podId"),
        podName = getString("podName"),
        clientId = getString("clientId"),
        webId = getString("webId"),
      )
    },
    clock = { clock },
  )

  private fun owner(podId: ObjectId = PROBE_POD, clientId: String = "notes-app", webId: String = WEB_ID) =
    PodOwner(podId = podId, podName = "alice", clientId = clientId, webId = webId)

  private fun raw(collection: String = COLLECTION) = db!!.getCollection(collection)

  private fun document(tokenHash: String, collection: String = COLLECTION): Document =
    assertNotNull(raw(collection).find(Filters.eq("tokenHash", tokenHash)).first())

  @Test
  fun `an issued token reads back field for field, and the plaintext is not on disk`() {
    val issued = store.issueNewFamily(owner(), scopes = setOf("public-read"))

    assertTrue(issued.plaintext.startsWith("rt_"), "log scrapers tell the two token kinds apart by this")
    assertEquals(sha256Hex(issued.plaintext), issued.token.tokenHash)
    assertEquals(ISSUED_AT, issued.token.issuedAt)
    assertEquals(ISSUED_AT.plusSeconds(RefreshTokenStore.DEFAULT_TTL_SECONDS), issued.token.expiresAt)

    val read = assertNotNull(store.lookup(issued.plaintext).token)
    assertEquals(issued.token, read, "the returned token is the row, not an in-memory approximation")
    assertEquals(owner(), read.owner)
    assertEquals(setOf("public-read"), read.scopes)
    assertNull(read.rotatedAt, "a live token carries neither timestamp")
    assertNull(read.revokedAt)

    val doc = document(issued.token.tokenHash)
    assertFalse(doc.toJson().contains(issued.plaintext), "only the hash is stored")
    assertNotNull(doc.getObjectId("_id"), "the row is keyed by an id the store mints and never hands out")
  }

  @Test
  fun `a token with no scopes carries no scopes field, and reads back as the empty set`() {
    // The common case wherever context permissions are resolved per request: only a feature scope
    // puts an array on the row at all, and an empty collection is omitted rather than written as [].
    val issued = store.issueNewFamily(owner(), scopes = emptySet())

    assertFalse(document(issued.token.tokenHash).containsKey("scopes"), "written")
    assertEquals(emptySet(), assertNotNull(store.lookup(issued.plaintext).token).scopes)
  }

  @Test
  fun `the owner block is written between familyId and scopes, for either owner shape`() {
    // The order two live collections are already laid out in, which is what one writer had to
    // reproduce to take both. Asserted here rather than inferred from the code.
    val pod = store.issueNewFamily(owner(), scopes = setOf("public-read"))
    assertEquals(
      listOf("_id", "tokenHash", "familyId", "podId", "podName", "clientId", "webId", "scopes", "issuedAt", "expiresAt"),
      document(pod.token.tokenHash).keys.toList(),
    )

    raw(MCP_COLLECTION).drop()
    val mcp = RefreshTokenStore<McpOwner>(
      db = db!!,
      collectionName = MCP_COLLECTION,
      ownerIndexFields = listOf("user", "profile", "clientId"),
      writeOwner = {
        put("user", it.user)
        put("profile", it.profile)
        put("clientId", it.clientId)
      },
      readOwner = { McpOwner(getString("user"), getString("profile"), getString("clientId")) },
      clock = { clock },
    )
    val issued = mcp.issueNewFamily(McpOwner("u1", "default", "claude"), scopes = setOf("mcp"))
    assertEquals(
      listOf("_id", "tokenHash", "familyId", "user", "profile", "clientId", "scopes", "issuedAt", "expiresAt"),
      document(issued.token.tokenHash, MCP_COLLECTION).keys.toList(),
    )
    assertEquals(McpOwner("u1", "default", "claude"), assertNotNull(mcp.lookup(issued.plaintext).token).owner)
  }

  @Test
  fun `a spent token writes its two timestamps last`() {
    val issued = store.issueNewFamily(owner(), scopes = setOf("public-read"))
    clock = ROTATED_AT
    assertTrue(store.markRotated(issued.token.tokenHash))
    clock = REVOKED_AT
    store.revokeFamily(issued.token.familyId)

    assertEquals(
      listOf(
        "_id", "tokenHash", "familyId", "podId", "podName", "clientId", "webId", "scopes",
        "issuedAt", "expiresAt", "rotatedAt", "revokedAt",
      ),
      document(issued.token.tokenHash).keys.toList(),
    )
  }

  @Test
  fun `lookup names every state the exchange has to tell apart`() {
    assertEquals(RefreshTokenStore.LookupState.NOT_FOUND, store.lookup("rt_nothing").state)

    val active = store.issueNewFamily(owner(), scopes = emptySet())
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(active.plaintext).state)

    val rotated = store.issueNewFamily(owner(), scopes = emptySet())
    clock = ROTATED_AT
    assertTrue(store.markRotated(rotated.token.tokenHash))
    assertEquals(RefreshTokenStore.LookupState.REUSED, store.lookup(rotated.plaintext).state)

    val revoked = store.issueNewFamily(owner(), scopes = emptySet())
    store.revokeFamily(revoked.token.familyId)
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(revoked.plaintext).state)

    // Revoked wins over rotated, and both win over expired: the answer has to name the reason the
    // caller acts on, and a replayed token is a reuse event whether or not it also aged out.
    clock = active.token.expiresAt.plusSeconds(1)
    assertEquals(RefreshTokenStore.LookupState.EXPIRED, store.lookup(active.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.REUSED, store.lookup(rotated.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.REVOKED, store.lookup(revoked.plaintext).state)
  }

  @Test
  fun `every lookup names the presented token by a prefix of its hash`() {
    // What a log line may carry: enough to tell one failing token from a hundred thousand, and not
    // enough to be the key a row is found by.
    val issued = store.issueNewFamily(owner(), scopes = emptySet())
    val expected = sha256Hex(issued.plaintext).take(RefreshTokenStore.FINGERPRINT_LENGTH)

    assertEquals(expected, store.lookup(issued.plaintext).fingerprint)
    assertTrue(expected.length < sha256Hex(issued.plaintext).length, "a prefix, not the digest")

    // The same token keeps the same fingerprint through every state it passes — which is what
    // makes it usable for counting one client's retries across hours of log.
    clock = ROTATED_AT
    assertTrue(store.markRotated(issued.token.tokenHash))
    assertEquals(expected, store.lookup(issued.plaintext).fingerprint)
    store.revokeFamily(issued.token.familyId)
    assertEquals(expected, store.lookup(issued.plaintext).fingerprint)

    // And NOT_FOUND is the branch that needs it: no token, so no family id and no owner to name.
    val miss = store.lookup("rt_never-issued-here")
    assertEquals(RefreshTokenStore.LookupState.NOT_FOUND, miss.state)
    assertNull(miss.token)
    assertEquals(
      sha256Hex("rt_never-issued-here").take(RefreshTokenStore.FINGERPRINT_LENGTH),
      miss.fingerprint,
    )
    assertNotEquals(expected, miss.fingerprint, "two tokens are told apart")
  }

  @Test
  fun `markRotated matches a token whose rotatedAt was never written`() {
    val issued = store.issueNewFamily(owner(), scopes = emptySet())
    val hash = issued.token.tokenHash

    clock = ROTATED_AT
    assertTrue(store.markRotated(hash), "the missing field is matched")
    assertEquals(ROTATED_AT, assertNotNull(store.lookup(issued.plaintext).token).rotatedAt)

    // And the second exchange of the same token is the replay the caller must treat as hostile.
    clock = ROTATED_AT.plusSeconds(1)
    assertFalse(store.markRotated(hash))
    assertEquals(ROTATED_AT, assertNotNull(store.lookup(issued.plaintext).token).rotatedAt, "the first rotation stands")

    // A revoked-but-unrotated token is likewise not rotatable — the other half of the filter.
    val second = store.issueNewFamily(owner(), scopes = emptySet())
    clock = REVOKED_AT
    store.revokeFamily(second.token.familyId)
    assertFalse(store.markRotated(second.token.tokenHash))
  }

  @Test
  fun `an explicit null is matched too, not only a missing field`() {
    // Why the filter stays `eq(field, null)` rather than becoming `exists(false)`: the two agree on
    // every row the store writes and disagree on a row touched by a migration or by hand, and the
    // safe direction is the one that still lets such a token rotate instead of reporting a replay.
    raw().insertOne(
      Document()
        .append("tokenHash", "hash-null")
        .append("familyId", "fam-null")
        .append("podId", PROBE_POD)
        .append("podName", "alice")
        .append("clientId", "notes-app")
        .append("webId", WEB_ID)
        .append("issuedAt", Date.from(ISSUED_AT))
        .append("expiresAt", Date.from(ISSUED_AT.plusSeconds(3600)))
        .append("rotatedAt", null)
        .append("revokedAt", null),
    )

    clock = ROTATED_AT
    assertTrue(store.markRotated("hash-null"))
    assertNull(store.findByFamily("fam-null").single().revokedAt)
  }

  @Test
  fun `a row written the old way still reads`() {
    // The hosted service's hand-written DAO wrote BSON `null` for the two timestamps and `[]` for an
    // empty `scopes`, against the contract the shared helpers keep. Its rows outlive the change, so
    // the reader has to answer for them exactly as it answers for a row it wrote itself — otherwise
    // every connection made before the fold would need a reconnect.
    raw().insertOne(
      Document()
        .append("tokenHash", sha256Hex("rt_legacy"))
        .append("familyId", "fam-legacy")
        .append("podId", PROBE_POD)
        .append("podName", "alice")
        .append("clientId", "notes-app")
        .append("webId", WEB_ID)
        .append("scopes", emptyList<String>())
        .append("issuedAt", Date.from(ISSUED_AT))
        .append("expiresAt", Date.from(ISSUED_AT.plusSeconds(3600)))
        .append("rotatedAt", null)
        .append("revokedAt", null),
    )

    val lookup = store.lookup("rt_legacy")
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, lookup.state)
    val token = assertNotNull(lookup.token)
    assertEquals(owner(), token.owner)
    assertEquals(emptySet(), token.scopes, "an empty array reads as the empty set, like a missing field")
    assertEquals(ISSUED_AT, token.issuedAt)
    assertNull(token.rotatedAt, "an explicit BSON null reads as absent")
    assertNull(token.revokedAt)
  }

  @Test
  fun `revokeFamily touches every row of the family and nothing beside it`() {
    val first = store.issueNewFamily(owner(), scopes = emptySet())
    val second = store.issueInFamily(first.token, scopes = emptySet())
    val otherFamily = store.issueNewFamily(owner(), scopes = emptySet())
    val otherPod = store.issueNewFamily(owner(podId = OTHER_POD), scopes = emptySet())

    assertEquals(first.token.familyId, second.token.familyId, "a successor stays in its family")
    assertEquals(first.token.owner, second.token.owner, "and keeps the owner it inherited")

    // `updateMany`: the whole family, not merely the first row of it — an `updateOne` here would
    // leave the thief's token alive, which is the one this exists to kill.
    clock = REVOKED_AT
    assertEquals(2L, store.revokeFamily(first.token.familyId))
    assertEquals(listOf(REVOKED_AT, REVOKED_AT), store.findByFamily(first.token.familyId).map { it.revokedAt })
    assertNull(store.lookup(otherFamily.plaintext).token?.revokedAt, "another family is untouched")
    assertNull(store.lookup(otherPod.plaintext).token?.revokedAt, "another pod is untouched")
  }

  @Test
  fun `revokeWhere carries a filter the store itself could not express`() {
    // A filter over the owner's fields and over `scopes`, neither of which this class knows —
    // the shape the pod server's revocations are built in. `Filters.in` against an array matches
    // when ANY element is in the list.
    val readGrant = store.issueNewFamily(owner(), scopes = setOf("public-read", "$EVENTS_ROOT#read"))
    val manageGrant = store.issueNewFamily(owner(clientId = "other-app"), scopes = setOf("$EVENTS_ROOT#manage"))
    val featureOnly = store.issueNewFamily(owner(), scopes = setOf("public-read"))
    val otherPod = store.issueNewFamily(owner(podId = OTHER_POD), scopes = setOf("$EVENTS_ROOT#read"))

    clock = REVOKED_AT
    val revoked = store.revokeWhere(
      Filters.and(
        Filters.eq("podId", PROBE_POD),
        Filters.`in`(
          RefreshTokenStore.Field.SCOPES,
          listOf("$EVENTS_ROOT#read", "$EVENTS_ROOT#write", "$EVENTS_ROOT#manage"),
        ),
      ),
    )
    assertEquals(2L, revoked)
    assertEquals(REVOKED_AT, store.lookup(readGrant.plaintext).token?.revokedAt)
    assertEquals(REVOKED_AT, store.lookup(manageGrant.plaintext).token?.revokedAt)
    assertNull(store.lookup(featureOnly.plaintext).token?.revokedAt, "a token holding no grant on the context survives")
    assertNull(store.lookup(otherPod.plaintext).token?.revokedAt, "another pod's token is not swept")

    // And the narrower one, which is what a withdrawn consent uses.
    assertEquals(
      1L,
      store.revokeWhere(
        Filters.and(
          Filters.eq("podId", PROBE_POD),
          Filters.eq("clientId", "notes-app"),
          Filters.eq("webId", WEB_ID),
        ),
      ),
      "only the one row left unrevoked for this (pod, client, webId)",
    )
  }

  @Test
  fun `familiesWhere cannot name a family minted after it was asked`() {
    // What a caller who revokes what it supersedes needs, and the reason the read comes before the
    // insert rather than after it. Two exchanges can run under one standing consent, and a sweep
    // phrased as "everything but my own family" has each of them revoke the other's — both clients
    // then walk away with a refresh token that is already dead. A set measured beforehand cannot
    // reach what appears afterwards.
    val superseded = store.issueNewFamily(owner(), scopes = emptySet())
    val measured = store.familiesWhere(
      Filters.and(Filters.eq("podId", PROBE_POD), Filters.eq("clientId", "notes-app"), Filters.eq("webId", WEB_ID)),
    )
    val concurrent = store.issueNewFamily(owner(), scopes = emptySet())

    assertEquals(setOf(superseded.token.familyId), measured, "only what existed when it was asked")

    clock = REVOKED_AT
    assertEquals(1L, store.revokeWhere(Filters.`in`(RefreshTokenStore.Field.FAMILY_ID, measured)))
    assertEquals(REVOKED_AT, store.lookup(superseded.plaintext).token?.revokedAt)
    assertNull(store.lookup(concurrent.plaintext).token?.revokedAt, "the racing exchange's family survives")

    assertTrue(
      store.familiesWhere(Filters.eq("podId", PROBE_POD)).none { it == superseded.token.familyId },
      "and a revoked family is no longer something to supersede",
    )
  }

  @Test
  fun `deleteWhere removes exactly the matching rows, and repeating it is a no-op`() {
    val mine = store.issueNewFamily(owner(), scopes = emptySet())
    store.issueInFamily(mine.token, scopes = emptySet())
    val theirs = store.issueNewFamily(owner(podId = OTHER_POD), scopes = emptySet())

    // The pod-deletion cascade: once the pod is gone, revocation is moot and the rows have no
    // remaining purpose.
    assertEquals(2L, store.deleteWhere(Filters.eq("podId", PROBE_POD)), "deleteMany, not deleteOne")
    assertEquals(RefreshTokenStore.LookupState.NOT_FOUND, store.lookup(mine.plaintext).state)
    assertEquals(RefreshTokenStore.LookupState.ACTIVE, store.lookup(theirs.plaintext).state, "another pod's tokens are not swept")

    assertEquals(0L, store.deleteWhere(Filters.eq("podId", PROBE_POD)), "a repeated cascade is a no-op, not an error")
  }

  @Test
  fun `an owner with no indexed field is refused at construction`() {
    // Not a style rule: every bulk revocation this store hands out is a filter over the owner, and
    // an unindexed one is a collection scan on the OAuth path.
    val thrown = runCatching {
      RefreshTokenStore<McpOwner>(
        db = db!!,
        collectionName = "refreshTokens.unindexed",
        ownerIndexFields = emptyList(),
        writeOwner = { put("user", it.user) },
        readOwner = { McpOwner(getString("user"), "", "") },
      )
    }.exceptionOrNull()
    assertTrue(thrown is IllegalArgumentException, "was $thrown")
  }
}
