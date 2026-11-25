package org.sempods.pods.oauth.didweb

import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyOperation
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

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
 * names the DID itself as its `controller`. Each is found under its full DID URL
 * (`did:web:example.org#key-1`). A key the document names under any other relationship, or only
 * under `verificationMethod`, authenticates nothing. RSA keys under 2048 bits and EC keys off P-256
 * are left out, since the algorithms accepted for them are RS256, PS256 and ES256. A key keeps the
 * limits it publishes (RFC 7517 §§4.2–4.4): a `use` other than `sig` or `key_ops` without `verify`
 * leaves it out, and an `alg` verifies that algorithm only.
 *
 * **The cache.** A document read is kept for [ttl], and one that could not be read for
 * [failureTtl], so a broken host is asked again soon but not on every request. An assertion naming
 * a key the cached document lacks reads the document again once [refreshAfter] has passed since
 * the last read: that is how a rotated key arrives without waiting out [ttl], and why an unknown
 * `kid` cannot make the pod fetch on every request. Callers that find the entry wanting at once
 * share one read. At most [maxEntries] documents are kept; the one used longest ago goes first.
 *
 * **The reads.** At most [maxConcurrentReads] run at once. A read past that is not made: the
 * lookup answers [DidWebKeyLookup.Busy], which the caller answers as retryable, and nothing is
 * cached, so the DID is read on the next request that finds room. Every read is a request the pod makes to a host a caller named; this bounds how many
 * threads and connections those can hold at once.
 */
internal class DidWebKeys(
  private val fetcher: DidDocumentFetcher,
  private val allowLoopbackHttp: Boolean,
  private val clock: Clock = Clock.systemUTC(),
  private val ttl: Duration = Duration.ofMinutes(5),
  private val failureTtl: Duration = Duration.ofSeconds(30),
  private val refreshAfter: Duration = Duration.ofMinutes(1),
  private val maxEntries: Int = 1_000,
  maxConcurrentReads: Int = 8,
) {

  private val reads = Semaphore(maxConcurrentReads)

  /** @param busy a read that was not made, for want of room; never cached. */
  private class Entry(val keys: Map<String, JWK>?, val readAt: Instant, val busy: Boolean = false)

  private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
  }

  /** [lookup]'s keys, with [DidWebKeyLookup.Busy] as none. */
  internal fun publicKeys(did: String, keyId: String, algorithm: Algorithm): List<PublicKey> =
    (lookup(did, keyId, algorithm) as? DidWebKeyLookup.Found)?.keys.orEmpty()

  /**
   * The public key [did]'s document names [keyId] for, to verify [algorithm] with, as a one-element
   * list; empty where it names none, the key's `alg` is another, or the document cannot be read.
   * [DidWebKeyLookup.Busy] where the document had to be read and there was no room.
   * [keyId] is a full DID URL or, relative to [did], `#key-1`.
   */
  internal fun lookup(did: String, keyId: String, algorithm: Algorithm): DidWebKeyLookup {
    val kid = if (keyId.startsWith("#")) did + keyId else keyId
    if (!kid.startsWith("$did#")) return DidWebKeyLookup.Found(emptyList())
    val now = clock.instant()
    var entry = cached(did)
    if (entry == null || now.isAfter(entry.readAt.plus(if (entry.keys == null) failureTtl else ttl))) {
      entry = refresh(did, entry, now)
    } else if (entry.keys?.containsKey(kid) != true && now.isAfter(entry.readAt.plus(refreshAfter))) {
      entry = refresh(did, entry, now)
    }
    if (entry.busy) return DidWebKeyLookup.Busy
    val jwk = entry.keys?.get(kid)?.takeIf { it.algorithm == null || it.algorithm.name == algorithm.name }
    return DidWebKeyLookup.Found(listOfNotNull(jwk?.let(::toPublicKey)))
  }

  private fun cached(did: String): Entry? = synchronized(entries) { entries[did] }

  /**
   * One read of [did]'s document for every caller that found [seen] wanting at once: the first
   * reads, the others wait for its answer. Without this, a burst of assertions naming one service
   * would each fetch its document, since keys are looked up before a signature is checked. A caller
   * arriving after that read finished takes the entry it left rather than reading again. No lock is
   * held across the fetch, so a slow host holds no caller asking for another DID.
   */
  private fun refresh(did: String, seen: Entry?, now: Instant): Entry {
    val mine = CompletableFuture<Entry>()
    inFlight.putIfAbsent(did, mine)?.let { return it.join() }
    try {
      val entry = cached(did)?.takeIf { it !== seen } ?: read(did, now)
      mine.complete(entry)
      return entry
    } catch (e: Throwable) {
      mine.completeExceptionally(e)
      throw e
    } finally {
      inFlight.remove(did, mine)
    }
  }

  private val inFlight = ConcurrentHashMap<String, CompletableFuture<Entry>>()

  /**
   * Reads [did]'s document now and caches what it says, a failure included. Where [reads] has no
   * room, answers no keys and caches nothing.
   */
  private fun read(did: String, now: Instant): Entry {
    if (!reads.tryAcquire()) {
      logger.info { "[oauth/did-web] DID document not read, too many reads at once: did='${LogSafeText.of(did)}'" }
      return Entry(null, now, busy = true)
    }
    val keys = try {
      val url = documentUrl(did) ?: throw IllegalArgumentException("not a did:web identifier this pod reads")
      keysOf(did, JsonMappers.default().readValue(fetcher.fetch(url), JsonUtil.dynamicTypeRef))
    } catch (e: Exception) {
      logger.info { "[oauth/did-web] DID document unreadable: did='${LogSafeText.of(did)}', reason='${LogSafeText.of(e.message ?: e.javaClass.simpleName)}'" }
      null
    } finally {
      reads.release()
    }
    return Entry(keys, now).also { synchronized(entries) { entries[did] = it } }
  }

  /**
   * Whether [did] can be named in one path segment of this pod's routes: every segment after
   * `did:web:` made of letters, digits, `.`, `-` and `_` (DID Core's `idchar` without the
   * percent-encoding), and none of them `.` or `..`. A `/` would split the path, and a `%` — a
   * port is written `%3A` — would have to travel as `%25`, which the server refuses in a path.
   * Development accepts a loopback host with a port, which cannot be withdrawn by path either: a
   * local console runs on one.
   */
  internal fun isAddressable(did: String): Boolean {
    if (!did.startsWith(DidWeb.PREFIX)) return false
    val segments = did.removePrefix(DidWeb.PREFIX).split(':')
    val host = segments.first().let { first ->
      val port = LOOPBACK_PORT.matchEntire(first)
      if (port != null && allowLoopbackHttp && isLoopback(port.groupValues[1])) port.groupValues[1] else first
    }
    return (listOf(host) + segments.drop(1)).all { it.matches(ID_SEGMENT) && it != "." && it != ".." }
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
      if (method["controller"] != did) return@mapNotNull null
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
    if (jwk.keyOperations != null && KeyOperation.VERIFY !in jwk.keyOperations) return null
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
    private val ID_SEGMENT = Regex("[A-Za-z0-9._-]+")
    private val LOOPBACK_PORT = Regex("(.+)%3[Aa][0-9]{1,5}")
  }
}

/** What [DidWebKeys.lookup] answers. */
internal sealed interface DidWebKeyLookup {

  /** The keys found, possibly none. */
  data class Found(val keys: List<PublicKey>) : DidWebKeyLookup

  /** The document had to be read, and no read could start: retry shortly. */
  data object Busy : DidWebKeyLookup
}
