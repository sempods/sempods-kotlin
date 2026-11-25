package org.sempods.api.pod.system.auth

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import org.sempods.SempodsModule
import org.sempods.commons.json.JsonMappers
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.didweb.DidDocumentFetcher
import java.io.IOException
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `did:web` services for the pod to read keys from: DID documents this process holds, and the
 * private keys that go with them.
 *
 * Each [DidWebService] has an identifier of its own, `did:web:did.test:<random>`, whose document
 * the method puts at `https://did.test/<random>/did.json`. [fetcher] answers that address from
 * memory, and `SempodsTestModule` hands it to the pod's `DidWebKeys`: the identifiers carry no port,
 * as a deployed service's does not, so the owner's routes can name them in a path. The fetch over
 * HTTP behind the outbound guard is `OkHttpDidDocumentFetcherTest`'s. A fresh identifier per test
 * keeps the key cache and the spent `jti`s from carrying over between tests.
 */
internal object DidWebServiceFixture {

  private val documents = ConcurrentHashMap<String, String>()
  private val reads = ConcurrentHashMap<String, AtomicInteger>()

  /** Serves what [DidWebService.publish] put up; anything else is a 404. */
  val fetcher = DidDocumentFetcher { url ->
    reads.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
    documents[url] ?: throw IOException("GET $url answered HTTP 404")
  }

  /**
   * A service whose document names an EC P-256 and an RSA key under `authentication`; with
   * [port], one whose identifier carries it (`did:web:did.test%3A8443:…`).
   */
  fun newService(port: Int? = null): DidWebService {
    val segment = "svc-${UUID.randomUUID()}"
    val host = if (port == null) HOST else "$HOST%3A$port"
    val url = "https://$HOST${port?.let { ":$it" } ?: ""}/$segment/did.json"
    return DidWebService("did:web:$host:$segment", reads = { reads[url]?.get() ?: 0 }) { document -> documents[url] = document }
  }

  private const val HOST = "did.test"
}

/** One `did:web` identity: its identifier, its keys, and what it signs with them. */
internal class DidWebService(val did: String, val reads: () -> Int = { 0 }, private val serve: (String) -> Unit) {

  val ecKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("$did#key-ec").generate()
  val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("$did#key-rsa").generate()

  init {
    publish(listOf(ecKey, rsaKey))
  }

  /** Serves a document naming [keys] under `authentication`, each a `JsonWebKey2020` method. */
  fun publish(keys: List<JWK>) {
    val methods = keys.map { key ->
      linkedMapOf("id" to key.keyID, "type" to "JsonWebKey2020", "controller" to did, "publicKeyJwk" to key.toPublicJWK().toJSONObject())
    }
    serve(
      JsonMappers.default().writeValueAsString(
        linkedMapOf(
          "@context" to listOf("https://www.w3.org/ns/did/v1"),
          "id" to did,
          "verificationMethod" to methods,
          "authentication" to keys.map { it.keyID },
        ),
      ),
    )
  }

  /**
   * A JWT signed with [key]: `iss` and `sub` this identifier, [audience] — or every one of
   * [audiences], as a list — a fresh `jti`, and `exp` [lifetimeSeconds] from now. [claims] are
   * added or, as `null`, removed; [nullClaims] are sent as JSON `null`.
   */
  fun sign(
    audience: String,
    audiences: List<String>? = null,
    key: JWK = ecKey,
    jti: String = UUID.randomUUID().toString(),
    lifetimeSeconds: Long = 60,
    keyId: String? = key.keyID,
    claims: Map<String, Any?> = emptyMap(),
    nullClaims: Set<String> = emptySet(),
  ): String {
    val now = Instant.now()
    val builder = JWTClaimsSet.Builder()
      .issuer(did)
      .subject(did)
      .apply { if (audiences == null) audience(audience) else audience(audiences) }
      .jwtID(jti)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(lifetimeSeconds)))
    claims.forEach { (name, value) -> builder.claim(name, value) }
    val algorithm = if (key is RSAKey) JWSAlgorithm.RS256 else JWSAlgorithm.ES256
    val payload = builder.build().toJSONObject() + nullClaims.associateWith { null }
    val jwt = JWSObject(JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).keyID(keyId).build(), Payload(payload))
    jwt.sign(if (key is RSAKey) RSASSASigner(key) else ECDSASigner(key as ECKey))
    return jwt.serialize()
  }

  /** An assertion for [pod]'s token endpoint. */
  fun assertion(pod: PodDbo, key: JWK = ecKey, jti: String = UUID.randomUUID().toString(), lifetimeSeconds: Long = 60): String =
    sign(tokenEndpoint(pod), key = key, jti = jti, lifetimeSeconds = lifetimeSeconds)

  /** A software statement for [pod]'s registration endpoint, stating [claims]. */
  fun statement(pod: PodDbo, claims: Map<String, Any?> = emptyMap(), nullClaims: Set<String> = emptySet()): String =
    sign("${issuer(pod)}/_system/auth/register", claims = claims, nullClaims = nullClaims)

  companion object {
    fun issuer(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}"
    fun tokenEndpoint(pod: PodDbo) = "${issuer(pod)}/_system/auth/token"
  }
}
