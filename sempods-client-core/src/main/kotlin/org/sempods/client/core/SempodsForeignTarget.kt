package org.sempods.client.core

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/**
 * A URI outside any pod, read over a client [SempodsOkHttp.install] configured — its transport, its
 * guard, its deadline and its admission budget — and with no pod's credential.
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
 * **Not a session and not an endpoint group.** There is no pod base, and nothing is inherited from one:
 * a credential goes with the call that names it and with no other, and the client's own
 * `Authenticator` and `CookieJar` do not answer for these calls. A call that carries a credential is
 * held to the origin it names, as a session's is to its pod: an interceptor that moves it elsewhere is
 * refused on the request about to be written. The target is whatever URI a call passes, so one
 * instance serves any number of them.
 *
 * **The guard is the client's, and it is opt-in.** On a client installed with a
 * [net.SempodsOutboundGuard], every call — and every hop of a followed redirect — is vetted before it
 * connects; one installed without dials whatever it is given. This is the component most likely to be
 * handed a URI from someone else's request, so install one. A name that resolves into a blocked range
 * fails with [net.SsrfBlockedException], an address literal with a [SempodsClientException] caused by
 * one. On a client `install` did not configure, none of this applies.
 *
 * **Every status is an answer.** This library knows no contract of a foreign server's and classifies
 * nothing: a `404`, a `303` and a `500` come back as a [SempodsResponse] with their headers, `Location`
 * included, and a `408` is an answer too. So is a `503` asking to be repeated at once, which comes back
 * without its `Retry-After: 0`: OkHttp acts on that header by itself, below every interceptor of this
 * library, so it is taken off, as it is for a session's call. Outside 2xx the body is closed unread,
 * so a foreign error document never reaches the caller. What is sent again is a request whose
 * connection was lost before any answer, once, as for a session's `GET` — and a request OkHttp sent
 * over an HTTP/2 connection it shares with another host, which it sends once more over one of its own
 * when that server answers `421` (RFC 9110 §15.5.20). That answer is about the connection OkHttp
 * chose, not about the target, and it is repeated for a session's call alike
 * ([#160](https://github.com/sempods/sempods-kotlin/issues/160)).
 *
 * **A redirect is the caller's to follow, unless [followingRedirects] takes it.** Following sends each
 * hop as a call of its own, so the guard vets each. It ends at the first redirect it cannot or may not
 * take, and that redirect is the answer; only the client's policy throws. The credential stays with the
 * origin the caller named: the first hop that leaves it goes on without one, and so does every hop
 * after it, wherever it points. [SempodsResponse.url] names the URL that answered.
 *
 * **What a call holds.** The credential is applied inside the call, in its admission slot and under its
 * deadline, with two limits a session's call does not have — both of them
 * [#161](https://github.com/sempods/sempods-kotlin/issues/161)'s to lift. A mechanism that fetches a
 * token through this same client needs a slot of its own for that fetch, so under a budget of one it
 * waits until the deadline. And a call waiting while another refreshes a shared
 * [SempodsRequestAuth.refreshable] credential does not see its own cancellation, so it waits up to that
 * credential's 30 seconds, holding its slot. [getText] and [getBytes] read at most
 * 16 MiB and free the slot before they decode. [getStream] and [getTo] have no limit — the body is a
 * foreign server's, so the reader is its only bound — and hold the slot until the reader is done
 * ([SempodsBodyReader]). The deadline applies per call, so a followed chain may take one deadline per hop,
 * and a budget on the guard is charged per hop.
 *
 * **Only `GET`.** A caller that needs another method, a condition or a call to cancel builds an
 * `okhttp3.Request` and runs it on the same client, where the guard applies all the same.
 */
class SempodsForeignTarget internal constructor(
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
   * The same target, following up to [maxRedirects] redirects per call: `301`, `302`, `303`, `307` and
   * `308`, each as a `GET`. `300`, `304`, `305` and `306` are answers — `305` names a proxy the server
   * chose, which is never taken.
   *
   * A `Location` is resolved against the URL that answered — the request as the server received it, after
   * whatever an interceptor on the client made of it. A redirect with no `Location` or with two, one that
   * does not resolve to an http or https URL, one carrying userinfo, one back to a URL this call has
   * already asked or been answered from, and the one that would exceed the budget, are the answer. An https target may redirect to http; the hop that does leaves the origin, so it goes on
   * without a credential, but its answer travels in the clear.
   */
  fun followingRedirects(maxRedirects: Int): SempodsForeignTarget {
    require(maxRedirects in 1..MAX_REDIRECTS) { "A redirect budget is between 1 and $MAX_REDIRECTS, not $maxRedirects." }
    return SempodsForeignTarget(calls, maxRedirects, maxBodyBytes)
  }

  /**
   * [uri] as the text it answered with, asking for [accept] and authenticated by [auth] for this call
   * alone.
   *
   * [accept] is sent as it is — one media type, or a list with weights — and has no default: nothing
   * says what a foreign server offers, so a wildcard is something a caller writes.
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
      // An interceptor on the client may have moved the request, and what the server received is what its
      // `Location` is relative to. A chain that comes back to a URL it asked, or was answered from, is a loop.
      val answered = answer.url.toHttpUrl()
      seen += answered
      if (redirects == maxRedirects) return answer
      val next = redirectTarget(answer, answered) ?: return answer
      if (next in seen) return answer
      // Once the chain leaves the origin the caller named, no hop is the caller's to authenticate — not
      // even one that comes back: that URL was chosen by a server the credential was never meant for.
      if (!sameOrigin(next, named)) credential = null
      target = next
      redirects++
    }
  }

  /** The request for one hop. Its credential is applied by the call itself, inside the call's slot and deadline. */
  private fun request(target: HttpUrl, accept: String, credential: SempodsRequestAuth?): Request =
    Request.Builder().url(target).get().header("Accept", accept).tag(ForeignCall::class.java, ForeignCall(credential)).build()

  companion object {

    /** The most redirects one call may follow — OkHttp's own limit on follow-ups. */
    const val MAX_REDIRECTS: Int = 20
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
 * What a [SempodsForeignTarget]'s call tells the client's interceptors: that it is one, the mechanism
 * that authenticates it, and — once that mechanism has put anything on it — the origin it was put there
 * for.
 */
internal class ForeignCall(private val auth: SempodsRequestAuth?) {

  @Volatile
  private var credentialedFor: HttpUrl? = null

  /**
   * [request] with this call's credential, applied once as the first attempt. The session interceptor asks
   * this after the call has its admission slot, so credential work is inside the slot and under the deadline,
   * as a session's is.
   */
  @Throws(IOException::class)
  fun authenticate(request: Request): Request {
    val authenticated = auth?.authenticate(request, attempt = 1) ?: return request
    // What a mechanism put on the request is held to the origin it was put there for; a call it left as it
    // was carries nothing an interceptor could take elsewhere.
    if (authenticated.headers != request.headers) credentialedFor = request.url
    return authenticated
  }

  /**
   * Throws when [request], as it is about to be written, would take this call's credential to another
   * authority: a URL an interceptor moved, or a `Host` it named, which OkHttp sends in place of the URL's.
   */
  fun confine(request: Request) {
    val origin = credentialedFor ?: return
    val target = request.url
    if (!sameOrigin(target, origin)) {
      throw SempodsClientException(
        "'${target.newBuilder().query(null).fragment(null).build()}' is not the origin this call's credential was " +
          "applied for. An interceptor that moves a foreign target's request cannot take its credential along.",
      )
    }
    val named = request.headers.values("Host").filterNot { namesAuthorityOf(it, target) }
    if (named.isNotEmpty()) {
      throw SempodsClientException(
        "'Host: ${named.first()}' does not name the origin this call's credential was applied for.",
      )
    }
  }
}
