package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsIntegrationTest
import org.sempods.auth.core.SigningKeys
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That the issuer generates a signing key once and reads the same one back after a restart.
 *
 * **Both assertions are about how many keys exist**, so it runs on a store of its own
 * ([SempodsIntegrationTest.ownStore]): "first boot" means an empty one.
 */
class PodTokenIssuerPersistenceTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var db: MongoDatabase

  private lateinit var signingKeyDao: OAuthSigningKeyDao

  private val collection = ownStore("oauthSigningKeys.issuer")

  @BeforeEach
  fun setUpOwnCollection() {
    signingKeyDao = OAuthSigningKeyDao(db, collection)
  }


  /** A fresh issuer over the same collection — a restarted process, in other words. */
  private fun bootIssuer() = PodTokenIssuer(
    apiBaseUrl = "http://test.local/",
    signingKeys = SigningKeys(PodSigningKeyStore(signingKeyDao)),
  )

  @Test
  fun `issuer should generate and persist a signing key on first boot`() {
    val issuer = bootIssuer()

    val persisted = signingKeyDao.findAll()
    assertEquals(1, persisted.size)
    val jwks = JWKSet.parse(issuer.jwksJson)
    assertEquals(persisted.first().kid, jwks.keys.single().keyID)
  }

  @Test
  fun `token signed by first issuer should verify with a fresh issuer reading the same key from mongo`() {
    val issuerA = bootIssuer()
    val token = issuerA.issue(
      pod = "alice",
      webId = "https://id.test/alice",
      clientId = "dyn:test-client",
      scopes = setOf("https://test.local/alice/ctx#read"),
    )

    // Simulates a process restart — same Mongo, fresh issuer instance.
    val issuerB = bootIssuer()
    assertEquals(1, signingKeyDao.findAll().size, "restart must not generate a second key")

    val signed = SignedJWT.parse(token)
    val kid = signed.header.keyID
    assertNotNull(kid)

    val jwks = JWKSet.parse(issuerB.jwksJson)
    val matching = jwks.keys.single { it.keyID == kid } as RSAKey
    assertTrue(
      signed.verify(RSASSAVerifier(matching.toRSAPublicKey())),
      "token signed by issuerA must verify against issuerB's JWKS after a simulated restart",
    )
  }

  private companion object {
  }
}
