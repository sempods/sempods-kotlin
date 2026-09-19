package org.sempods.client

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/**
 * Reads a URI outside any pod over a client [SempodsOkHttp.install] configured: its transport, guard,
 * deadline and admission, without a pod's credential.
 *
 * ```java
 * SempodsForeignTarget foreign = new SempodsForeignTarget(client);
 * SempodsResponse<String> card = foreign.getText("https://bob.example/profile", "text/turtle");
 *
 * SempodsResponse<byte[]> document = foreign.followingRedirects(5)
 *     .getBytes("https://data.example/id/42", "application/n-quads", SempodsRequestAuth.bearer(token));
 * String answeredBy = document.getUrl();
 * ```
 *
 * **The credential belongs to one call.** A call carries only the [SempodsRequestAuth] passed to it; the
 * client's `Authenticator` and `CookieJar` do not answer for it. A call with any mechanism but
 * [SempodsRequestAuth.anonymous] is held to the origin it names: an interceptor that moves it elsewhere
 * is refused before anything is written.
 *
 * **Install a guard.** This call is the one most likely to get a URI from someone else's request, and a
 * client without a [net.SempodsOutboundGuard] dials any address. With one, a name that resolves into a
 * blocked range fails with [net.SsrfBlockedException], an address literal with a
 * [SempodsClientException] caused by one.
 *
 * **Every status is an answer**, with its headers, and outside 2xx without a body. OkHttp works below
 * this library's interceptors, and changes that in these cases:
 *
 * | The server | The caller gets |
 * |---|---|
 * | answers `503` with `Retry-After: 0` | the `503`, without that header |
 * | drops the connection before any answer | the answer to one resend |
 * | answers `421` over an HTTP/2 connection OkHttp shares with another host | the answer to OkHttp's resend over its own connection ([#160](https://github.com/sempods/sempods-kotlin/issues/160)) |
 * | answers `407` over a direct connection, where only a proxy may send one | a `ProtocolException` |
 *
 * **A redirect is the caller's to follow**, unless [followingRedirects] follows it.
 *
 * **Limits.**
 * - [getText] and [getBytes] read at most 16 MiB. [getStream] and [getTo] have no limit and hold the
 *   admission slot until the reader returns.
 * - The deadline and the guard's budget apply per call, and so per redirect hop.
 * - Only `GET`. For anything else, build an `okhttp3.Request` and run it on the same client.
 */
class SempodsForeignTarget private constructor(
  /** What runs the calls: a client [SempodsOkHttp.install] configured, or a factory over one. */
  val calls: Call.Factory,
  /** How many redirects one call follows; `0` answers with whatever status comes first. */
  val maxRedirects: Int,
  private val maxBodyBytes: Long,
) {

  /** A target that follows no redirect: every status, a redirect's included, is the answer. */
  constructor(calls: Call.Factory) : this(calls, maxRedirects = 0, MAX_BODY_BYTES)

  init {
    require(maxRedirects in 0..MAX_REDIRECTS) { "A redirect budget is between 0 and $MAX_REDIRECTS, not $maxRedirects." }
  }

  private val exchange = Exchange(calls, maxBodyBytes)

  /**
   * This target, following up to [maxRedirects] redirects per call: `301`, `302`, `303`, `307` and `308`,
   * each as a `GET` and a call of its own that the guard vets. [SempodsResponse.url] names the last URL.
   *
   * The redirect itself is the answer when
   * - it has no `Location`, or two;
   * - its `Location` resolves to no http or https URL, or carries userinfo;
   * - it points to a URL this call already asked or was answered from;
   * - the budget is spent.
   *
   * `300`, `304`, `305` and `306` are always answers. The credential is dropped at the first hop that
   * leaves the origin the caller named, and stays dropped if a later hop returns. An https target that
   * redirects to http goes on without it, but in the clear.
   */
  fun followingRedirects(maxRedirects: Int): SempodsForeignTarget {
    require(maxRedirects in 1..MAX_REDIRECTS) { "A redirect budget is between 1 and $MAX_REDIRECTS, not $maxRedirects." }
    return SempodsForeignTarget(calls, maxRedirects, maxBodyBytes)
  }

  /**
   * [uri] as text, asking for [accept], with [auth] for this call only.
   *
   * [accept] goes out as given, for example `text/turtle` or `text/turtle, application/ld+json;q=0.9`. It
   * has no default: a caller who takes anything writes the wildcard.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun getText(
    uri: String,
    accept: String,
    auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
  ): SempodsResponse<String> = dereference(uri, accept, auth) { exchange.run(it, EVERY_STATUS, BodyReading.TEXT) }

  /** [uri] as the bytes it answered with. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getBytes(
    uri: String,
    accept: String,
    auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
  ): SempodsResponse<ByteArray> = dereference(uri, accept, auth) { exchange.run(it, EVERY_STATUS, BodyReading.BYTES) }

  /** [uri]'s body, read by [reader] while it arrives. Only a 2xx body reaches the reader. */
  @JvmOverloads
  @Throws(IOException::class)
  fun <T : Any> getStream(
    uri: String,
    accept: String,
    reader: SempodsBodyReader<T>,
    auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
  ): SempodsResponse<T> = dereference(uri, accept, auth) { exchange.stream(it, EVERY_STATUS, reader) }

  /**
   * [uri]'s body, written to [out] while it arrives; the body is the number of bytes written.
   *
   * **[out] is the caller's**: this writes to it and neither flushes nor closes it, however the call ends.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun getTo(
    uri: String,
    accept: String,
    out: OutputStream,
    auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
  ): SempodsResponse<Long> = getStream(uri, accept, { body -> body.copyTo(out) }, auth)

  private fun <T : Any> dereference(
    uri: String,
    accept: String,
    auth: SempodsRequestAuth,
    send: (Request) -> SempodsResponse<T>,
  ): SempodsResponse<T> {
    require(accept.isNotBlank()) { "An Accept value must not be blank: name what this call reads, or a wildcard." }
    val named = targetOf(uri)
    var target = named
    var credential: SempodsRequestAuth? = auth
    val seen = mutableSetOf<HttpUrl>()
    var redirects = 0
    while (true) {
      seen += target
      val answer = send(request(target, accept, credential))
      // Relative to the URL that answered: an interceptor may have moved the request.
      val answered = answer.url.toHttpUrl()
      seen += answered
      if (redirects == maxRedirects) return answer
      val next = redirectTarget(answer, answered) ?: return answer
      if (next in seen) return answer
      // For good: a hop back at the named origin was still chosen by another server.
      if (!sameOrigin(next, named)) credential = null
      target = next
      redirects++
    }
  }

  /** The request for one hop; the session interceptor applies its credential ([ForeignCall]). */
  private fun request(target: HttpUrl, accept: String, credential: SempodsRequestAuth?): Request =
    Request.Builder()
      .url(target)
      .get()
      .header("Accept", accept)
      .tag(ForeignCall::class.java, ForeignCall(target, credential?.takeUnless { it === SempodsRequestAuth.anonymous() }))
      .build()

  companion object {

    /** The most redirects one call may follow — OkHttp's own limit on follow-ups. */
    const val MAX_REDIRECTS: Int = 20

    @JvmSynthetic
    internal fun of(calls: Call.Factory, maxRedirects: Int, maxBodyBytes: Long): SempodsForeignTarget =
      SempodsForeignTarget(calls, maxRedirects, maxBodyBytes)
  }
}

/** The redirects a following target takes, each as a `GET`. */
private val FOLLOWED = setOf(301, 302, 303, 307, 308)

/** An absolute http or https URL without its fragment, which a dereference never sends. */
private fun targetOf(uri: String): HttpUrl {
  require(uri.isNotBlank()) { "A URI to dereference must not be blank." }
  val url = uri.toHttpUrlOrNull()
  requireNotNull(url) {
    // Only the scheme: the rest of a string that is no URL may still hold a password.
    val scheme = uri.substringBefore(':', missingDelimiterValue = "")
      .takeIf { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char in "+-." } }
    "${scheme?.let { "A '$it:' URI" } ?: "A reference without a scheme"} is not dereferenced: this reads absolute http and https URLs."
  }
  require(url.username.isEmpty() && url.password.isEmpty()) {
    "A URI with userinfo is not dereferenced: '${url.host}' would never see it, and it is how one host is made to read as another."
  }
  return url.newBuilder().fragment(null).build()
}

/** The next hop's URL, or null when [answer] is not a redirect this may take. */
private fun redirectTarget(answer: SempodsResponse<*>, sent: HttpUrl): HttpUrl? {
  if (answer.status !in FOLLOWED) return null
  val location = answer.headers.values("Location").singleOrNull() ?: return null
  val next = sent.resolve(location) ?: return null
  if (next.username.isNotEmpty() || next.password.isNotEmpty()) return null
  return next.newBuilder().fragment(null).build()
}

/** Scheme, host and port, as the parsed URL spells them: `:80` and no port are one origin. */
private fun sameOrigin(one: HttpUrl, other: HttpUrl): Boolean =
  one.scheme == other.scheme && one.host == other.host && one.port == other.port

/**
 * What a [SempodsForeignTarget] call tells the client's interceptors: the URL it was built for, and its
 * mechanism, null for [SempodsRequestAuth.anonymous].
 */
internal class ForeignCall(private val named: HttpUrl, private val auth: SempodsRequestAuth?) {

  @Volatile
  private var credentialedFor: HttpUrl? = null

  /** [request] with this call's credential, applied as [attempt], the call's first. */
  @Throws(IOException::class)
  fun authenticate(request: Request, attempt: SempodsAuthAttempt): Request {
    val mechanism = auth ?: return request
    // Checked before the mechanism runs, whatever it will set: an interceptor ahead of this one may have
    // moved the request already.
    if (!sameOrigin(request.url, named)) throw movedAway(request.url)
    credentialedFor = named
    return mechanism.authenticate(request, attempt)
  }

  /** Shows [attempt]'s answer to this call's mechanism, where it carries one. */
  @Throws(IOException::class)
  fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) {
    auth?.observe(facts, attempt)
  }

  /**
   * Throws when [request], about to be written, names another authority than the credential's: by its URL,
   * or by a `Host` header, which OkHttp sends in place of the URL's.
   */
  fun confine(request: Request) {
    val origin = credentialedFor ?: return
    val target = request.url
    if (!sameOrigin(target, origin)) throw movedAway(target)
    val named = request.headers.values("Host").filterNot { namesAuthorityOf(it, target) }
    if (named.isNotEmpty()) {
      throw SempodsClientException(
        "'Host: ${named.first()}' does not name the origin this call's credential was applied for.",
      )
    }
  }

  private fun movedAway(target: HttpUrl) = SempodsClientException(
    "'${target.newBuilder().query(null).fragment(null).build()}' is not the origin the caller named for this call's " +
      "credential. An interceptor that moves a foreign target's request cannot take its credential along.",
  )
}
