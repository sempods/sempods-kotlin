package org.sempods.pods.media

import com.google.inject.Inject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Fetches the bytes a caller pointed the pod at, or refuses to.
 *
 * **The dangerous half of copy-from-URL, and the reason M3 is a milestone of its own.** Ingesting a
 * URL means the pod server issues an HTTP request to an address the caller chose, from inside the
 * network the pod server sits in — server-side request forgery. On sempods.org that network also
 * holds Mongo, the object store and a model server; in a cloud it holds a metadata service that
 * hands out credentials to anyone who asks over plain HTTP.
 *
 * The defence is a chain, and every link is load-bearing:
 *
 * 1. **Scheme and shape.** `http`/`https` only, a host, and no credentials in the URL.
 * 2. **Every resolved record is verified**, not the first — see
 *    [MediaSourceAddressGuard.verifyAllOrThrow].
 * 3. **The verified address is pinned.** Handing the *hostname* to the HTTP client after checking it
 *    is not enough: the client resolves again, and a DNS-rebinding record can change the answer
 *    between the two lookups. The per-hop client's `Dns` hook answers with the address that was
 *    checked and nothing else, so there is no second lookup to disagree with the first, while `Host`
 *    and the TLS handshake still come from the URI — so name-based virtual hosting and certificate
 *    verification keep working.
 * 4. **The socket is checked anyway.** [PinnedAddressCheck] is a network interceptor, so it sees the
 *    established connection *before the request is written*, and the invariant is asserted rather
 *    than assumed.
 * 5. **Redirects are followed by hand**, at most [MAX_REDIRECTS], with the whole chain re-run per
 *    hop. A single `302` from an allowed host is otherwise the cheapest bypass there is.
 * 6. **The size limit bites while streaming.** `Content-Length` is optional and forgeable — it comes
 *    from a server the caller picked. Reading the length from a `HEAD` and trusting it is exactly
 *    the assumption not repeated here.
 *
 * Not [org.sempods.commons.okhttp.CommonsHttpClient], deliberately: it hands back a body rather than
 * a response, so points 5 and 6 — reading a `Location` without reading a body, and stopping a body
 * mid-stream — have nowhere to happen.
 *
 * The [OkHttpClient] itself comes from `commons-okhttp`'s `OkHttpClientModule`, installed by
 * `SempodsModule` directly. Every hop derives its own from it with `newBuilder()`, which keeps the
 * shared connection pool and dispatcher.
 */
class MediaSourceFetcher @Inject constructor(
  private val httpClient: OkHttpClient,
  private val config: PodMediaConfig,
  private val guard: MediaSourceAddressGuard,
) {

  /**
   * Downloads [sourceUrl] into a temporary file.
   *
   * **The caller owns the returned file and deletes it**, the same contract `PodMediaEndpoint`'s
   * raw-body path already has with its own buffer — and for the same reason: the bytes have to be on
   * disk before their hash, and therefore their media id, is known.
   *
   * @throws MediaSourceException for every refusal, with the reason meant for the log rather than
   *   the caller.
   */
  fun fetch(sourceUrl: String): FetchedMediaSource {
    val target = Files.createTempFile("sempods-media-source-", ".download")
    try {
      val initial = parseOrThrow(sourceUrl, "source_url")
      var url = initial
      repeat(MAX_REDIRECTS + 1) {
        when (val hop = requestOnce(url, target)) {
          is HopResult.Body -> return FetchedMediaSource(target, hop.contentType)
          is HopResult.Redirect -> url = parseOrThrow(resolveLocation(url, hop.location), "redirect target")
        }
      }
      throw MediaSourceException("more than $MAX_REDIRECTS redirects starting at '${initial.forLog()}'")
    } catch (e: Throwable) {
      Files.deleteIfExists(target)
      throw e
    }
  }

  /** One hop: verify, pin, request, and report what came back. */
  private fun requestOnce(url: URI, target: Path): HopResult {
    val pinned = resolveAndVerify(url)
    val request = try {
      Request.Builder().url(url.toString()).header("Accept", "*/*").get().build()
    } catch (e: IllegalArgumentException) {
      // `parseOrThrow` accepts every URI the JDK parses; OkHttp's is the narrower grammar of a URL
      // that can actually be dialled. A source it will not take is the caller's mistake, not ours.
      throw MediaSourceException("'${url.forLog()}' is not a URL that can be fetched", cause = e)
    }
    return try {
      clientFor(pinned).newCall(request).execute().use { readHop(it, target) }
    } catch (e: IOException) {
      // Transport failures — refused connection, TLS mismatch, timeout, and the pinning check in
      // [PinnedAddressCheck]. The `try` spans the body read as well as the call: with a streamed
      // response a read timeout arrives while copying, not while connecting. The cause carries what
      // happened for the log; the caller learns only that the source could not be fetched.
      throw MediaSourceException("could not fetch '${url.forLog()}': ${e.message}", cause = e)
    }
  }

  /**
   * The client for one hop: pinned, redirect-refusing, and on this fetch's own budgets.
   *
   * `retryOnConnectionFailure(false)` because there is nothing to fail over to — one address means
   * one route — and because a replay would make [PinnedAddressCheck]'s refusal look like a
   * connection that is merely worth trying again.
   */
  // TODO: the caller's `traceparent` still travels to a host the caller chose, because the shared
  //  client's interceptor is inherited here. This is the one place it could be dropped.
  private fun clientFor(pinned: InetAddress): OkHttpClient =
    httpClient.newBuilder()
      .dns { listOf(pinned) }
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .readTimeout(config.sourceReadTimeout)
      .callTimeout(config.sourceRequestTimeout)
      .addNetworkInterceptor(PinnedAddressCheck(pinned))
      .build()

  /**
   * What one hop produced, with everything that is not a body or a redirect thrown rather than
   * returned — a refusal has a reason, and a reason travels in an exception.
   */
  private fun readHop(response: Response, target: Path): HopResult {
    if (response.code in REDIRECT_CODES) {
      // A redirect's body is never read: it is not the media, and reading it would spend the size
      // budget on a response that only points somewhere else.
      val location = response.header("Location")?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw MediaSourceException("source answered ${response.code} without a Location")
      return HopResult.Redirect(location)
    }
    if (response.code != 200) throw MediaSourceException("source answered ${response.code}")

    val claimed = response.header("Content-Type")?.trim()?.takeIf { it.isNotEmpty() }
      // Not a fallback to `application/octet-stream`: the registry records this type and the
      // content route serves it, so a guessed value produces a media that downloads instead of
      // rendering — with no way to tell later that it was guessed.
      ?: throw MediaSourceException("source answered without a Content-Type")

    // A *present* type still has to be a servable one. Jersey rejects a malformed `Content-Type`
    // on the raw upload route before the endpoint ever runs, but nothing parses this one: it comes
    // from a server the caller chose, over a connection Jersey knows nothing about. Storing
    // `not-a-media-type` would answer 201 here and then turn every later read of that assignment
    // into a 500, with the two events a milestone apart for whoever has to diagnose it.
    if (!isServableMediaType(claimed)) throw MediaSourceException("source answered an unusable Content-Type")

    if (copyCapped(response.body.byteStream(), target) == 0L) {
      throw MediaSourceException("source answered an empty body")
    }
    return HopResult.Body(claimed)
  }

  /**
   * Writes the body to [target] as it arrives, and stops the moment it is too big.
   *
   * The limit is checked **before** the write, so at most [PodMediaConfig.maxUploadBytes] ever reach
   * disk. Leaving the loop closes the response, which closes the socket — an oversized source stops
   * being read rather than being read and then discarded.
   */
  private fun copyCapped(source: InputStream, target: Path): Long {
    val maxBytes = config.maxUploadBytes
    var written = 0L
    Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
      val buffer = ByteArray(BUFFER_BYTES)
      while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        written += read
        if (written > maxBytes) {
          throw MediaSourceException("source exceeds the maximum of $maxBytes bytes", tooLarge = true)
        }
        out.write(buffer, 0, read)
      }
    }
    return written
  }

  /**
   * The URL as it may appear in a log: scheme, host, port and path — no credentials, no query.
   *
   * The same reasoning that keeps the source URL out of the query string of *this* API applies to
   * the pod's own log: a signed Drive or S3 link carries its authorisation in the query, so writing
   * one down turns a diagnostic line into a credential with a shelf life. What is left still says
   * which host was refused, which is what a refusal has to be diagnosable by.
   */
  private fun URI.forLog(): String = buildString {
    append(scheme).append("://").append(host)
    if (port != -1) append(':').append(port)
    append(rawPath.orEmpty())
  }

  /**
   * The single address this hop will connect to, after every record of the host has passed the
   * guard.
   *
   * The first record is picked rather than a random one: with all of them verified the choice is
   * arbitrary, and a deterministic one is easier to read in a log.
   */
  private fun resolveAndVerify(url: URI): InetAddress {
    val host = checkNotNull(url.host) { "parseOrThrow guarantees a host" }
    val addresses = try {
      InetAddress.getAllByName(host).toList()
    } catch (e: UnknownHostException) {
      throw MediaSourceException("'$host' does not resolve", cause = e)
    }
    guard.verifyAllOrThrow(host, addresses)
    return addresses.first()
  }

  private fun resolveLocation(current: URI, location: String): String = try {
    current.resolve(location.trim()).toString()
  } catch (e: IllegalArgumentException) {
    // The `Location` comes from a server the caller chose, so it gets the same treatment as the
    // source URL: everything up to the query, which is enough to see where it pointed.
    throw MediaSourceException(
      "unusable Location '${location.substringBefore('?')}' from '${current.forLog()}'",
      cause = e,
    )
  }

  /**
   * The URL as something this class is willing to fetch.
   *
   * Credentials are refused rather than stripped: a `source_url` carrying them is either a mistake
   * worth reporting or an attempt to have the pod server authenticate somewhere on the caller's
   * behalf, and neither is served by quietly continuing.
   */
  private fun parseOrThrow(raw: String, what: String): URI {
    val uri = try {
      URI(raw.trim())
    } catch (e: URISyntaxException) {
      throw MediaSourceException("$what is not a URL", cause = e)
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme == null || scheme !in ALLOWED_SCHEMES) {
      throw MediaSourceException("$what has scheme '${uri.scheme}' (supported: ${ALLOWED_SCHEMES.joinToString()})")
    }
    if (uri.host.isNullOrEmpty()) throw MediaSourceException("$what names no host")
    if (uri.userInfo != null) throw MediaSourceException("$what carries credentials")
    return uri
  }

  /**
   * Point 4 of the chain: what the socket is actually connected to.
   *
   * A network interceptor rather than a check on the response, because it runs once the connection
   * is established and *before the request is written* — so a misdirected hop never receives the
   * `Accept` header, let alone a body. With the `Dns` hook in place this cannot differ, which is why
   * a mismatch is worth catching rather than trusting: it would mean the pinning silently stopped
   * working.
   */
  private class PinnedAddressCheck(private val pinned: InetAddress) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
      val actual = chain.connection()?.route()?.socketAddress?.address
      if (actual != null && actual != pinned) {
        throw IOException("connected to ${actual.hostAddress} rather than the verified ${pinned.hostAddress}")
      }
      return chain.proceed(chain.request())
    }
  }

  /** What one hop produced. */
  private sealed interface HopResult {
    data class Body(val contentType: String) : HopResult
    data class Redirect(val location: String) : HopResult
  }

  companion object {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    private const val BUFFER_BYTES = 8 * 1024

    /**
     * How many hops a source may take. The number matters less than that there is one, since each
     * hop is a fresh chance to be pointed somewhere else.
     */
    internal const val MAX_REDIRECTS = 5

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
  }
}

/**
 * A fetched source: the bytes on disk, and the type the source claimed for them.
 *
 * @property body a temporary file **the caller deletes**.
 * @property contentType the source's `Content-Type`, verbatim. It becomes the claim recorded on the
 *   assignment, which is what makes it the uploader's statement rather than the pod's.
 */
data class FetchedMediaSource(
  val body: Path,
  val contentType: String,
)
