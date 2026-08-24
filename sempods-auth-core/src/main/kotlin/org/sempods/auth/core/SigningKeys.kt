package org.sempods.auth.core

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

/**
 * Where an issuer's signing keys are kept between restarts.
 *
 * A port rather than a concrete store: this module has no database, and the three services that
 * need one already have their own collections. Each supplies the adapter; the lifecycle below is
 * the same everywhere.
 */
interface SigningKeyStore {

  /** All keys, newest first. Each entry is a JWK **including** its private parameters. */
  fun loadAll(): List<String>

  /**
   * Store the **first** key, and answer whether this insert was the one that won.
   *
   * The only write on this port, and conditional on purpose. N replicas booting against an empty
   * store each generate a candidate, and only one of them may end up stored: two replicas signing
   * with different keys reject each other's tokens behind a load balancer, and each publishes a
   * JWKS the other's tokens are not in. An unconditional write cannot express that — whoever calls
   * it gets a key stored, whether or not another replica already stored one.
   *
   * How an adapter makes it atomic is its own business; all three write into a singleton slot (a
   * fixed `_id`), so the losing insert is a duplicate-key error rather than a second key. A loser
   * returns `false` and [SigningKeys] re-reads the winner's key.
   *
   * Rotation is not this method. It appends *beside* the bootstrap slot and has to retire the
   * predecessor in the same step, so it brings its own operation when something performs one.
   */
  fun createInitial(jwk: String, kid: String, algorithm: String): Boolean
}

/**
 * The signing key an issuer uses, and the JWKS it publishes.
 *
 * The reason this is not a generated key held in a field: a key that lives only in memory is
 * regenerated on every boot, so every token minted before a restart stops verifying. On the pod
 * side that was observed in production as `Token signature verification failed` mid-session; on
 * the identity side it means every deploy silently logs everyone out. Persisting costs one read
 * at startup.
 *
 * All known keys are published in the JWKS, only the newest signs. That is what makes rotation a
 * change to this class rather than a migration: mint a successor, keep the predecessor in the set
 * until the tokens it signed have expired.
 *
 * **One instance per process, and everything that touches keys takes it.** An issuer and a
 * verifier that each read the store for themselves are two answers to one question, and on a first
 * boot they are different answers: whichever ran first found an empty store. Passing this object
 * around is what makes "the key this process signs with" and "the keys this process accepts" the
 * same fact.
 */
class SigningKeys(store: SigningKeyStore) {

  private val active: RSAKey

  /**
   * The public half of every known key — what a verifier checks a signature against, already
   * parsed. Handing these out rather than re-parsing [jwksJson] per request is the difference
   * between a boot-time cost and a hot-path one.
   */
  val publicKeys: List<RSAKey>

  init {
    val existing = store.loadAll()
    val keys = if (existing.isEmpty()) listOf(bootstrap(store)) else existing.map(RSAKey::parse)
    active = keys.first()
    publicKeys = keys.map(RSAKey::toPublicJWK)
    logger.info { "Signing key ready: kid='${active.keyID}', keysInJwks=${publicKeys.size}" }
  }

  /** The key to sign with. */
  fun signingKey(): RSAKey = active

  val keyId: String get() = active.keyID

  val algorithm: JWSAlgorithm get() = JWSAlgorithm.RS256

  /** The public half of every known key, as served at the issuer's `jwks_uri`. */
  val jwksJson: String = JWKSet(publicKeys).toString()

  /**
   * First-boot key creation, race-safe across replicas: generate a candidate, offer it to the
   * store's singleton slot, and if another replica got there first discard the candidate and use
   * theirs.
   */
  private fun bootstrap(store: SigningKeyStore): RSAKey {
    val generated = RSAKeyGenerator(2048)
      .keyID(UUID.randomUUID().toString())
      .keyUse(KeyUse.SIGNATURE)
      .algorithm(JWSAlgorithm.RS256)
      .generate()
    val won = store.createInitial(
      jwk = generated.toJSONString(),
      kid = generated.keyID,
      algorithm = JWSAlgorithm.RS256.name,
    )
    if (won) {
      logger.info { "No signing key found — generated and persisted a new one: kid='${generated.keyID}'" }
      return generated
    }
    // The winner's insert completed before ours failed, so the store is no longer empty. Saying so
    // beats `first()`'s NoSuchElementException if an adapter ever reports a loss it did not have.
    val winner = store.loadAll().firstOrNull()
      ?: error("lost the signing-key bootstrap race, but the store is still empty")
    val key = RSAKey.parse(winner)
    logger.info { "Lost the signing-key bootstrap race — using kid='${key.keyID}' from the winner" }
    return key
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
