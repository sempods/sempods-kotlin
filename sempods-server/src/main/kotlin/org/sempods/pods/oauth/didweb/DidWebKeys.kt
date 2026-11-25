package org.sempods.pods.oauth.didweb

import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.DidWeb
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import org.sempods.commons.logging.LogSafeText
import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Reads a DID document over HTTP: the body of a `200`, or an exception for anything else. A port,
 * so the outbound policy is the binding's and a test can serve a document from loopback.
 */
internal fun interface DidDocumentFetcher {

  /** @throws Exception on a transport failure, a status other than `200` or an oversized body. */
  fun fetch(url: String): String
}

/**
 * The keys a `did:web` client authenticates with, read from its DID document.
 *
 * **Where the document is.** The did:web method's read algorithm: `https://<host>/.well-known/did.json`
 * for a host-only identifier, `https://<host>/<path…>/did.json` for one with path segments.
 * [allowLoopbackHttp] lets a loopback host be read over `http`, for development and the test run;
 * the method itself has no `http`.
 *
 * **Which keys.** A verification method the document names under `authentication` — the
 * relationship for proving control of the DID — that carries a public `publicKeyJwk` and is
 * controlled by the DID itself. Each is found under its full DID URL (`did:web:example.org#key-1`).
 * A key the document names under any other relationship, or only under `verificationMethod`,
 * authenticates nothing. RSA keys under 2048 bits and EC keys off P-256 are left out, since the
 * algorithms accepted for them are RS256, PS256 and ES256.
 *
 * **The cache.** A document read is kept for [ttl], and one that could not be read for
 * [failureTtl], so a broken host is asked again soon but not on every request. An assertion naming
 * a key the cached document lacks reads the document again once [refreshAfter] has passed since
 * the last read: that is how a rotated key arrives without waiting out [ttl], and why an unknown
 * `kid` cannot make the pod fetch on every request. At most [maxEntries] documents are kept; the
 * one used longest ago goes first.
 */
internal class DidWebKeys(
  private val fetcher: DidDocumentFetcher,
  private val allowLoopbackHttp: Boolean,
  private val clock: Clock = Clock.systemUTC(),
  private val ttl: Duration = Duration.ofMinutes(5),
  private val failureTtl: Duration = Duration.ofSeconds(30),
  private val refreshAfter: Duration = Duration.ofMinutes(1),
  private val maxEntries: Int = 1_000,
) {

  private class Entry(val keys: Map<String, JWK>?, val readAt: Instant)

  private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
  }

  /**
   * The public key [did]'s document names [keyId] for, as a one-element list; empty where it names
   * none, or the document cannot be read. [keyId] is a full DID URL or, relative to [did], `#key-1`.
   */
  internal fun publicKeys(did: String, keyId: String): List<PublicKey> {
    val kid = if (keyId.startsWith("#")) did + keyId else keyId
    if (!kid.startsWith("$did#")) return emptyList()
    val now = clock.instant()
    var entry = cached(did)
    if (entry == null || now.isAfter(entry.readAt.plus(if (entry.keys == null) failureTtl else ttl))) {
      entry = read(did, now)
    } else if (entry.keys?.containsKey(kid) != true && now.isAfter(entry.readAt.plus(refreshAfter))) {
      entry = read(did, now)
    }
    return listOfNotNull(entry.keys?.get(kid)?.let(::toPublicKey))
  }

  private fun cached(did: String): Entry? = synchronized(entries) { entries[did] }

  /** Reads [did]'s document now and caches what it says, a failure included. Outside the lock: a slow host holds no other caller. */
  private fun read(did: String, now: Instant): Entry {
    val keys = try {
      val url = documentUrl(did) ?: throw IllegalArgumentException("not a did:web identifier this pod reads")
      keysOf(did, JsonMappers.default().readValue(fetcher.fetch(url), JsonUtil.dynamicTypeRef))
    } catch (e: Exception) {
      logger.info { "[oauth/did-web] DID document unreadable: did='${LogSafeText.of(did)}', reason='${LogSafeText.of(e.message ?: e.javaClass.simpleName)}'" }
      null
    }
    return Entry(keys, now).also { synchronized(entries) { entries[did] = it } }
  }

  /** Where the did:web method puts [did]'s document; `null` for an identifier this pod does not read. */
  internal fun documentUrl(did: String): String? {
    val target = DidWeb.targetOf(did) ?: return null
    val scheme = if (allowLoopbackHttp && isLoopback(target.host)) "http" else "https"
    val authority = if (target.port == DEFAULT_HTTPS_PORT) target.host else "${target.host}:${target.port}"
    val path = if (target.pathPrefix == "/") "/.well-known/did.json" else "${target.pathPrefix}/did.json"
    return "$scheme://$authority$path"
  }

  /**
   * The verification methods [document] names under `authentication`, by full DID URL. A document
   * for another DID names none.
   */
  private fun keysOf(did: String, document: Map<String, Any?>?): Map<String, JWK> {
    require(document?.get("id") == did) { "the document's id is not '$did'" }
    val declared = (document["verificationMethod"] as? List<*>).orEmpty()
      .filterIsInstance<Map<*, *>>()
      .mapNotNull { method -> (method["id"] as? String)?.let { absolute(did, it) to method } }
      .toMap()
    return (document["authentication"] as? List<*>).orEmpty().mapNotNull { reference ->
      val method = when (reference) {
        is String -> declared[absolute(did, reference)]
        is Map<*, *> -> reference
        else -> null
      } ?: return@mapNotNull null
      val id = (method["id"] as? String)?.let { absolute(did, it) } ?: return@mapNotNull null
      val controller = method["controller"] as? String
      if (controller != null && controller != did) return@mapNotNull null
      val jwk = (method["publicKeyJwk"] as? Map<*, *>)?.let(::jwkOf) ?: return@mapNotNull null
      id to jwk
    }.toMap()
  }

  private fun absolute(did: String, id: String): String = if (id.startsWith("#")) did + id else id

  /** A public signing key of a size and curve the accepted algorithms use, or `null`. */
  private fun jwkOf(json: Map<*, *>): JWK? {
    @Suppress("UNCHECKED_CAST")
    val jwk = runCatching { JWK.parse(json as Map<String, Any>) }.getOrNull() ?: return null
    if (jwk.isPrivate) return null
    if (jwk.keyUse != null && jwk.keyUse != KeyUse.SIGNATURE) return null
    return when (jwk) {
      is RSAKey -> jwk.takeIf { it.size() >= MIN_RSA_BITS }
      is ECKey -> jwk.takeIf { it.curve == com.nimbusds.jose.jwk.Curve.P_256 }
      else -> null
    }
  }

  private fun toPublicKey(jwk: JWK): PublicKey? = when (jwk) {
    is RSAKey -> jwk.toPublicKey()
    is ECKey -> jwk.toPublicKey()
    else -> null
  }

  private fun isLoopback(host: String): Boolean =
    host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "[::1]"

  private companion object {
    private val logger = KotlinLogging.logger {}
    private const val DEFAULT_HTTPS_PORT = 443
    private const val MIN_RSA_BITS = 2048
  }
}
