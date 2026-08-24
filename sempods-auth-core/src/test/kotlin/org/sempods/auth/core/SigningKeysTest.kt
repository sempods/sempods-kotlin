package org.sempods.auth.core

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bootstrap contract every adapter has to honour.
 *
 * The interesting case is the one no single-process test would find: two replicas booting against
 * an empty store. Only one key may end up stored, and the replica whose insert lost has to adopt
 * the winner's key rather than sign with the candidate it just generated — two replicas signing
 * with different keys reject each other's tokens behind a load balancer, and each publishes a JWKS
 * the other's tokens are not in. Here that race is a fake store returning `false`; the Mongo
 * adapters prove the atomicity itself (`TokenIssuerBootstrapTest`, `PodSigningKeyBootstrapTest`).
 */
class SigningKeysTest {

  /** Stands in for the database: the first bootstrap wins, every later one loses. */
  private class InMemoryStore(seed: List<String> = emptyList()) : SigningKeyStore {
    private val stored = seed.toMutableList()
    var writes = 0
      private set

    override fun loadAll(): List<String> = stored.toList()

    override fun createInitial(jwk: String, kid: String, algorithm: String): Boolean {
      if (stored.isNotEmpty()) return false
      stored.add(0, jwk)
      writes++
      return true
    }
  }

  /**
   * A replica that always loses: its `createInitial` refuses, and by the time it re-reads, the
   * winner's key is there. [everEmpty] is the pathological adapter that reports a loss it did not
   * have.
   */
  private class LosingStore(private val winner: String?, private val everEmpty: Boolean = false) : SigningKeyStore {
    private var read = 0

    override fun loadAll(): List<String> {
      // Empty on the first read so the bootstrap runs at all; the winner is visible afterwards.
      val first = read++ == 0
      return if (first || everEmpty || winner == null) emptyList() else listOf(winner)
    }

    override fun createInitial(jwk: String, kid: String, algorithm: String): Boolean = false
  }

  private fun generateKey(): RSAKey = RSAKeyGenerator(2048)
    .keyID(UUID.randomUUID().toString())
    .keyUse(KeyUse.SIGNATURE)
    .algorithm(JWSAlgorithm.RS256)
    .generate()

  @Test
  fun `a first boot mints exactly one key and publishes it`() {
    val store = InMemoryStore()

    val keys = SigningKeys(store)

    assertEquals(1, store.writes)
    assertEquals(keys.signingKey().keyID, keys.keyId)
    assertEquals(JWSAlgorithm.RS256, keys.algorithm)
    val published = JWKSet.parse(keys.jwksJson)
    assertEquals(1, published.keys.size)
    assertNotNull(published.getKeyByKeyId(keys.keyId))
    assertTrue(published.keys.none { it.isPrivate }, "the JWKS must publish public halves only")
  }

  @Test
  fun `a restart signs with the stored key and writes nothing`() {
    val store = InMemoryStore()
    val first = SigningKeys(store)

    val afterRestart = SigningKeys(store)

    assertEquals(1, store.writes, "a restart must not mint a second key")
    assertEquals(first.keyId, afterRestart.keyId)
  }

  @Test
  fun `losing the bootstrap race adopts the winner's key rather than the candidate`() {
    val winner = generateKey()

    val keys = SigningKeys(LosingStore(winner.toJSONString()))

    assertEquals(winner.keyID, keys.keyId, "the loser must sign with the key that was actually stored")
    assertNotNull(JWKSet.parse(keys.jwksJson).getKeyByKeyId(winner.keyID))
  }

  @Test
  fun `a store that refuses the bootstrap and stays empty fails at startup`() {
    // Not reachable through the Mongo adapters — a losing insert means somebody else's landed. It
    // is here so a future adapter that reports a loss it did not have fails with a sentence rather
    // than a NoSuchElementException from somewhere inside the key handling.
    val failure = assertFailsWith<IllegalStateException> { SigningKeys(LosingStore(null, everEmpty = true)) }

    assertTrue(failure.message!!.contains("bootstrap race"), "message was: ${failure.message}")
  }

  @Test
  fun `every known key is published but only the newest signs`() {
    val newest = generateKey()
    val predecessor = generateKey()
    // Newest first, which is the order every adapter's `loadAll` promises.
    val store = InMemoryStore(listOf(newest.toJSONString(), predecessor.toJSONString()))

    val keys = SigningKeys(store)

    assertEquals(newest.keyID, keys.keyId)
    assertEquals(
      setOf(newest.keyID, predecessor.keyID),
      JWKSet.parse(keys.jwksJson).keys.map { it.keyID }.toSet(),
      "a retired key stays in the JWKS so the tokens it signed keep verifying",
    )
  }
}
