package org.sempods.client.core

import okhttp3.HttpUrl
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
 * nothing private.
 *
 * **A credential never leaves its pod.** The client checks the target against [podBase] before the
 * first attempt and once more in its last network interceptor, on the request about to be written —
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
  internal val auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
) {

  /**
   * Starts a request against [podRelativePath] under this session's pod.
   *
   * [method] is any token, so HEAD, OPTIONS and a protocol extension's own verb need no change
   * here. The path is already percent-encoded; `HttpUrl.Builder.addPathSegment` encodes one
   * segment. A query may be attached after `?`.
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

  /** [request] with the pod's host in place of the placeholder, refused when it is not under this pod. */
  internal fun bind(request: Request): Request {
    val bound =
      if (request.url.host != SempodsOkHttp.UNBOUND_HOST) request
      else request.newBuilder().url(request.url.newBuilder().host(podBase.url.host).build()).build()
    confine(bound.url)
    return bound
  }

  /**
   * The check that keeps a credential with its pod.
   *
   * A `Request` is a plain object whose URL can be replaced after this session built it — by the
   * caller, by an interceptor, by a redirect. Without this, each of those would carry this session's
   * credential wherever the URL now points: a same-host sibling path, a traversal or an outright
   * foreign target all arrive the same way.
   */
  internal fun confine(target: HttpUrl) {
    if (target in podBase) return
    throw SempodsClientException(
      "'$target' is not under this session's pod '$podBase'. A request built for one pod cannot " +
        "be sent to another; build it with that pod's session.",
    )
  }

  internal fun authenticated(request: Request, attempt: Int): Request {
    val builder = request.newBuilder()
    auth.apply(builder, attempt)
    val authenticated = builder.build()
    // Asked again after authentication: a mechanism is meant to set headers. One that rewrote the
    // URL would carry this session's credential to another authority, and one that changed the
    // method or the body would send a request the caller never built, under the caller's credential.
    val changed = listOfNotNull(
      "target".takeIf { authenticated.url != request.url },
      "method".takeIf { authenticated.method != request.method },
      "body".takeIf { authenticated.body !== request.body },
    )
    if (changed.isNotEmpty()) {
      throw SempodsClientException(
        "Authentication changed the ${changed.joinToString(" and ")} of '${request.method} ${request.url}'. " +
          "A mechanism may set headers and nothing else.",
      )
    }
    return authenticated
  }

  private companion object {

    val BODILESS_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")

    val EMPTY_BODY = ByteArray(0).toRequestBody(null)
  }
}
