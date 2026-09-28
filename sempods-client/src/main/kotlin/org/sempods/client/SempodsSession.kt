package org.sempods.client

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One pod and one credential. A session builds requests; an OkHttp client that
 * [SempodsOkHttp.install] configured runs them.
 *
 * A session is cheap and holds no connection: a validated [SempodsPodBase] and a
 * [SempodsRequestAuth]. An application serving many pods builds one per pod and owns its own
 * lookup — where the pods come from is not a question a library about a pod addressed by its own
 * URL can answer. Any number of sessions share one client.
 *
 * **The request, the call and the response are OkHttp's.** [newRequest] hands back a
 * `Request.Builder` tagged with this session, and `client.newCall(request)` is the operation — so
 * `execute`, `enqueue`, `cancel` and `timeout` are OkHttp's own, and each of them carries the
 * confinement, the authentication, the resend and the admission budget.
 *
 * ```java
 * OkHttpClient client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
 * SempodsSession alice = new SempodsSession(
 *     SempodsPodBase.of("https://pods.example/alice"),
 *     SempodsRequestAuth.apiKeyHeader("X-Api-Key", key));
 *
 * Request request = alice.newRequest("GET", "_system/contexts")
 *     .header("Accept", "application/json")
 *     .build();
 * try (Response response = client.newCall(request).execute()) {
 *   String contexts = response.body().string();
 * }
 * ```
 *
 * **This is also the extension seam.** An endpoint group, a protocol module or a consumer's own
 * route gets all of the above by building through here and running on such a client, and needs
 * nothing private. [SempodsPod]'s groups are built on exactly that.
 *
 * **A credential never leaves its pod.** The client checks the target against [podBase] — the URL,
 * and any `Host` header, which a server that routes by name follows instead — before the first
 * attempt and once more in its last network interceptor, on the request about to be written —
 * after every application interceptor, after a redirect, and after every network interceptor added
 * before [SempodsOkHttp.install]. An interceptor that moves the request therefore takes no credential
 * along.
 *
 * **A request from here cannot be sent without that policy.** Its URL carries the placeholder host
 * [SempodsOkHttp.UNBOUND_HOST] (RFC 6761 reserves `.invalid`) in place of the pod's, and the client's
 * interceptor puts the pod's host back. A client without the interceptors — a plain `OkHttpClient`,
 * or OpenTelemetry's call factory over one — fails to resolve that name instead of sending the pod
 * an anonymous request. The price: `Request.url`, `Call.request().url` and whatever runs ahead of
 * the interceptor show the placeholder; `Response.request().url` and everything below it show the
 * pod.
 */
class SempodsSession @JvmOverloads constructor(
  val podBase: SempodsPodBase,
  @get:JvmSynthetic internal val auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
) {

  /**
   * Starts a request against [podRelativePath] under this session's pod.
   *
   * [method] is any token, so HEAD, OPTIONS and a protocol extension's own verb need no change
   * here. [podRelativePath] is resolved by [SempodsPodBase.resolve], which says what it may carry.
   *
   * The URL's host is the placeholder described on this class, and the request is tagged with this
   * session. No credential is attached here: the client applies one per attempt.
   */
  fun newRequest(method: String, podRelativePath: String): Request.Builder {
    val target = podBase.resolve(podRelativePath)
    return Request.Builder()
      .url(target.newBuilder().host(SempodsOkHttp.UNBOUND_HOST).build())
      // An empty body for the verbs OkHttp requires one for, so a caller can name the method here
      // and attach the body afterwards — and so a DELETE still goes out with `Content-Length: 0`.
      .method(method, if (method in BODILESS_METHODS) null else EMPTY_BODY)
      .tag(SempodsSession::class.java, this)
  }

  /**
   * Starts a request against [route] followed by [segments], each encoded as one path segment.
   *
   * Use it for a value a caller supplies, such as a pod name or a client identifier:
   * `newRequest("DELETE", "_system/media", "a b")` addresses `_system/media/a%20b`. [route] is a
   * pod-relative path as [newRequest] takes it, already encoded. A query it carries stays after the
   * segments.
   *
   * @throws IllegalArgumentException for a segment that is empty, `.` or `..`, or holds `/` or `\`.
   * A URL builder drops or collapses the first three, so `..` would reach the route's parent, and a
   * server that decodes before it routes splits at the other two. Nothing is sent.
   */
  fun newRequest(method: String, route: String, vararg segments: String): Request.Builder {
    val path = SEGMENT_BASE.newBuilder().apply {
      segments.forEach {
        require(it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it) {
          "'$it' cannot be one path segment."
        }
        addPathSegment(it)
      }
    }.build().encodedPath.removePrefix("/")
    val end = route.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: route.length
    val routePath = route.substring(0, end)
    val joined = if (routePath.isEmpty() || routePath.endsWith('/') || path.isEmpty()) routePath + path else "$routePath/$path"
    return newRequest(method, joined + route.substring(end))
  }

  /** [request] with the pod's host in place of the placeholder, refused when it is not under this pod. */
  @JvmSynthetic
  internal fun bind(request: Request): Request {
    val bound =
      if (request.url.host != SempodsOkHttp.UNBOUND_HOST) request
      else request.newBuilder().url(request.url.newBuilder().host(podBase.url.host).build()).build()
    confine(bound)
    return bound
  }

  /**
   * The check that keeps a credential with its pod: the URL and every `Host` header, either of which
   * the caller, an interceptor or a redirect can replace after this session built the request.
   */
  @JvmSynthetic
  internal fun confine(request: Request) {
    val target = request.url
    if (target !in podBase) {
      throw SempodsClientException(
        "'$target' is not under this session's pod '$podBase'. A request built for one pod cannot " +
          "be sent to another; build it with that pod's session.",
      )
    }
    // OkHttp sends a `Host` the request carries in place of the URL's, and HTTP/2 makes it the
    // `:authority`.
    val named = request.headers.values("Host").filterNot { namesAuthorityOf(it, target) }
    if (named.isNotEmpty()) {
      throw SempodsClientException(
        "'Host: ${named.first()}' does not name this session's pod '$podBase'. A request cannot be " +
          "sent to another server under this session's credential.",
      )
    }
  }

  private companion object {

    val BODILESS_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")

    val EMPTY_BODY = ByteArray(0).toRequestBody(null)

    val SEGMENT_BASE = "http://segment.invalid/".toHttpUrl()
  }
}

/** Whether [host], a `Host` value, is [target]'s authority: with its port, or without a default one. */
@JvmSynthetic
internal fun namesAuthorityOf(host: String, target: HttpUrl): Boolean {
  val name = if (':' in target.host) "[${target.host}]" else target.host
  return host.equals("$name:${target.port}", ignoreCase = true) ||
    target.port == HttpUrl.defaultPort(target.scheme) && host.equals(name, ignoreCase = true)
}
