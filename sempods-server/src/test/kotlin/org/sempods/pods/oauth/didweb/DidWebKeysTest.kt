package org.sempods.pods.oauth.didweb

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.junit.jupiter.api.Test
import org.sempods.commons.json.JsonMappers
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DidWebKeysTest {

  private val did = "did:web:console.example"
  private val key: ECKey = ECKeyGenerator(Curve.P_256).keyID("$did#key-1").generate()

  @Test
  fun `the document is read where the did-web method puts it`() {
    val keys = DidWebKeys(fetcher = { error("not read") }, allowLoopbackHttp = true)

    assertEquals("https://console.example/.well-known/did.json", keys.documentUrl("did:web:console.example"))
    assertEquals("https://console.example/a/b/did.json", keys.documentUrl("did:web:console.example:a:b"))
    assertEquals("https://console.example:8443/.well-known/did.json", keys.documentUrl("did:web:console.example%3A8443"))
    assertEquals("http://localhost:5173/.well-known/did.json", keys.documentUrl("did:web:localhost%3A5173"))
    assertEquals(
      "https://localhost:5173/.well-known/did.json",
      DidWebKeys(fetcher = { error("not read") }, allowLoopbackHttp = false).documentUrl("did:web:localhost%3A5173"),
      "loopback over http only where development allows it",
    )
    assertNull(keys.documentUrl("https://console.example"))
  }

  @Test
  fun `only an authentication key of the DID itself is a key`() {
    val assertionOnly = ECKeyGenerator(Curve.P_256).keyID("$did#assert").generate()
    val foreign = ECKeyGenerator(Curve.P_256).keyID("$did#foreign").generate()
    val small = RSAKeyGenerator(1024, true).keyID("$did#small").generate()
    val embedded = ECKeyGenerator(Curve.P_256).keyID("$did#embedded").generate()
    val document = mapOf(
      "id" to did,
      "verificationMethod" to listOf(
        method(key, id = "#key-1"),
        method(assertionOnly),
        method(foreign, controller = "did:web:other.example"),
        method(small),
      ),
      "authentication" to listOf("#key-1", foreign.keyID, small.keyID, method(embedded)),
      "assertionMethod" to listOf(assertionOnly.keyID),
    )
    val keys = DidWebKeys(fetcher = { json(document) }, allowLoopbackHttp = false)

    assertEquals(listOf(key.toPublicKey()), keys.publicKeys(did, "$did#key-1"), "a relative id resolves against the DID")
    assertEquals(listOf(key.toPublicKey()), keys.publicKeys(did, "#key-1"), "a relative kid too")
    assertEquals(listOf(embedded.toPublicKey()), keys.publicKeys(did, embedded.keyID), "an embedded method")
    listOf(assertionOnly, foreign, small).forEach { refused ->
      assertTrue(keys.publicKeys(did, refused.keyID).isEmpty(), refused.keyID)
    }
    assertTrue(keys.publicKeys(did, "did:web:other.example#key-1").isEmpty(), "another DID's key id")
  }

  @Test
  fun `a document for another DID names no keys`() {
    val keys = DidWebKeys(fetcher = { json(document(key, id = "did:web:other.example")) }, allowLoopbackHttp = false)

    assertTrue(keys.publicKeys(did, key.keyID).isEmpty())
  }

  @Test
  fun `a read is cached, and an unknown key reads the document again only after a minute`() {
    val clock = MutableClock()
    var published: List<JWK> = listOf(key)
    var reads = 0
    val keys = DidWebKeys(fetcher = { reads++; json(document(*published.toTypedArray())) }, allowLoopbackHttp = false, clock = clock)
    val rotated = ECKeyGenerator(Curve.P_256).keyID("$did#key-2").generate()

    assertEquals(1, keys.publicKeys(did, key.keyID).size)
    assertEquals(1, keys.publicKeys(did, key.keyID).size)
    assertEquals(1, reads, "the second lookup is the cache's")

    published = listOf(key, rotated)
    assertTrue(keys.publicKeys(did, rotated.keyID).isEmpty(), "within a minute an unknown kid is not a reason to fetch")
    assertEquals(1, reads)

    clock.advance(Duration.ofSeconds(61))
    assertEquals(listOf(rotated.toPublicKey()), keys.publicKeys(did, rotated.keyID), "after it, the rotated key arrives")
    assertEquals(2, reads)

    clock.advance(Duration.ofMinutes(6))
    published = listOf(rotated)
    assertTrue(keys.publicKeys(did, key.keyID).isEmpty(), "a key withdrawn is gone once the cache expires")
    assertEquals(3, reads)
  }

  @Test
  fun `an unreadable document is asked again after a short while`() {
    val clock = MutableClock()
    var reads = 0
    var failing = true
    val keys = DidWebKeys(
      fetcher = { reads++; if (failing) throw IOException("down") else json(document(key)) },
      allowLoopbackHttp = false,
      clock = clock,
    )

    assertTrue(keys.publicKeys(did, key.keyID).isEmpty())
    assertTrue(keys.publicKeys(did, key.keyID).isEmpty())
    assertEquals(1, reads, "a failure is cached too")

    failing = false
    clock.advance(Duration.ofSeconds(31))
    assertEquals(1, keys.publicKeys(did, key.keyID).size)
    assertEquals(2, reads)
  }

  @Test
  fun `the cache holds a bounded number of documents`() {
    var reads = 0
    val keys = DidWebKeys(fetcher = { reads++; json(document(key)) }, allowLoopbackHttp = false, maxEntries = 2)

    keys.publicKeys(did, key.keyID)
    keys.publicKeys("did:web:b.example", "did:web:b.example#k")
    keys.publicKeys("did:web:c.example", "did:web:c.example#k")
    keys.publicKeys(did, key.keyID)

    assertEquals(4, reads, "the document used longest ago was dropped")
  }

  private fun document(vararg keys: JWK, id: String = did): Map<String, Any?> = mapOf(
    "id" to id,
    "verificationMethod" to keys.map { method(it) },
    "authentication" to keys.map { it.keyID },
  )

  private fun method(key: JWK, id: String = key.keyID, controller: String = did): Map<String, Any?> =
    mapOf("id" to id, "type" to "JsonWebKey2020", "controller" to controller, "publicKeyJwk" to key.toPublicJWK().toJSONObject())

  private fun json(value: Any): String = JsonMappers.default().writeValueAsString(value)

  private class MutableClock(private var now: Instant = Instant.parse("2026-11-25T10:00:00Z")) : Clock() {
    fun advance(by: Duration) {
      now = now.plus(by)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneOffset = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
  }
}
