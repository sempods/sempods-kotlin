package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsIntegrationTest
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What [OAuthSigningKeyDao] stores, orders and filters.
 *
 * `PodTokenIssuerPersistenceTest` covers the issuer's use of it — one key on first boot, the same
 * key after a restart. This covers the DAO's own surface, and in particular **which key still
 * signs**: [OAuthSigningKeyDao.findActive] selects on `retiredAt` being null, and on disk that
 * field is *absent* rather than an explicit BSON null. `{field: null}` is the one equality that
 * matches both, which is why the filter is spelled that way and must not be tidied into
 * `exists(false)`.
 *
 * **Runs on a store of its own** ([SempodsIntegrationTest.ownStore]). That is what lets it say
 * anything about the *whole* collection: `findAll()` returning exactly two keys means something
 * only when nothing else can have put one there — and a signing key has no scope to narrow by, so
 * arranging it on the shared one would have meant deleting the developer's own keys.
 */
class OAuthSigningKeyDaoTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private lateinit var signingKeyDao: OAuthSigningKeyDao

  private val collection = ownStore("oauthSigningKeys")

  @BeforeEach
  fun setUpOwnCollection() {
    signingKeyDao = OAuthSigningKeyDao(db, collection)
  }


  @Test
  fun `create returns the id it stored under, and the row reads back field for field`() {
    val stored = signingKeyDao.create(
      OAuthSigningKeyDbo(
        kid = "driver-written",
        algorithm = "RS256",
        jwk = """{"kty":"RSA","kid":"driver-written"}""",
        createdAt = CREATED_AT,
      ),
    )
    assertNotNull(stored.id, "create must return the id it stored under, not the null it took")

    val read = signingKeyDao.findAll().single()
    assertEquals(stored.id, read.id)
    assertEquals("driver-written", read.kid)
    assertEquals("RS256", read.algorithm)
    assertEquals(stored.jwk, read.jwk)
    assertEquals(CREATED_AT, read.createdAt)
    assertNull(read.retiredAt, "an unretired key stores no field, and must read back as null")
  }

  @Test
  fun `findAll answers newest first`() {
    signingKeyDao.create(key("older", createdAt = CREATED_AT.minusSeconds(86_400)))
    signingKeyDao.create(key("newer", createdAt = CREATED_AT))

    assertEquals(listOf("newer", "older"), signingKeyDao.findAll().map { it.kid })
  }

  @Test
  fun `findActive selects the keys whose retiredAt is absent as well as those where it is null`() {
    // The three shapes the field takes on disk. `{retiredAt: null}` must match the first two and
    // miss the third; `exists(false)` would miss the second, which is the row a migration touched
    // by hand.
    signingKeyDao.create(key("absent", createdAt = CREATED_AT))
    rawRow(kid = "explicit-null", retiredAt = null, writeRetiredAt = true)
    rawRow(kid = "retired", retiredAt = Instant.parse("2026-08-09T00:00:00Z"), writeRetiredAt = true)

    assertEquals(3, signingKeyDao.findAll().size)
    assertEquals(setOf("absent", "explicit-null"), signingKeyDao.findActive().map { it.kid }.toSet())
  }

  private fun key(kid: String, createdAt: Instant) = OAuthSigningKeyDbo(
    kid = kid,
    algorithm = "RS256",
    jwk = """{"kty":"RSA","kid":"$kid"}""",
    createdAt = createdAt,
  )

  /** A row exactly as it sits on disk, so the shape of `retiredAt` is stated rather than arranged. */
  private fun rawRow(kid: String, retiredAt: Instant?, writeRetiredAt: Boolean) {
    val document = Document()
      .append(OAuthSigningKeyDboFields.id, ObjectId())
      .append(OAuthSigningKeyDboFields.kid, kid)
      .append(OAuthSigningKeyDboFields.algorithm, "RS256")
      .append(OAuthSigningKeyDboFields.jwk, """{"kty":"RSA","kid":"$kid"}""")
      .append(OAuthSigningKeyDboFields.createdAt, Date.from(CREATED_AT))
    if (writeRetiredAt) {
      document.append(OAuthSigningKeyDboFields.retiredAt, retiredAt?.let(Date::from))
    }
    db.getCollection(collection).insertOne(document)
  }

  private companion object {

    /** Millisecond-precise on purpose: BSON has nowhere to put the nanoseconds. */
    val CREATED_AT: Instant = Instant.parse("2026-08-09T10:15:30.123Z")
  }
}
