package org.sempods.client.core

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.sempods.client.core.net.SempodsOutboundGuard
import org.sempods.client.core.net.SempodsRateLimitedException
import org.sempods.client.core.net.SempodsUrlPolicy
import org.sempods.client.core.net.SsrfBlockedException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts the sempods policy on an OkHttp client, so that an ordinary `Call` carries it.
 *
 * **The engine is OkHttp, and it is not hidden.** What this adds is the part OkHttp has no opinion
 * about: pod confinement and per-attempt authentication for a [SempodsSession]'s requests, the
 * resend RFC 9110 allows, the SSRF guard, and a budget for synchronous calls, which OkHttp's
 * dispatcher bounds only for `enqueue`. Timeouts, the connection pool, event listeners and further
 * interceptors stay the consumer's, set on the same builder.
 *
 * Why OkHttp, and why blocking: `docs/pod-client.md` §"The transport".
 */
object SempodsOkHttp {

  /**
   * The host a session's request carries until the session interceptor binds it to the pod.
   *
   * Public so that whatever sees a request ahead of that interceptor — `Call.request()`, an event
   * listener's `callStart`, a metrics tag — can recognise it and read the pod from
   * `Response.request()` instead.
   */
  const val UNBOUND_HOST: String = "sempods-session.invalid"

  private val DEFAULT_CALL_TIMEOUT: Duration = Duration.ofMinutes(2)

  /**
   * Installs the sempods interceptors on [client] and returns it for chaining.
   *
   * - **The first application interceptor** runs a session's call: it confines the target, puts the
   *   pod's host in place of the placeholder, authenticates each attempt, and makes at most one
   *   resend after a lost connection ([SempodsRepeatable]) and one authentication retry. Each attempt
   *   is a `Chain.proceed`, so every interceptor after it — and every network interceptor — runs once
   *   per attempt. It also holds [admission] for every call on the client, session or not.
   * - **The last application interceptor**, with a [guard]: the per-request address check and the
   *   outbound budget, on the URL the interceptors before it produced.
   * - **The last network interceptor** confines a session's call again, on the request about to be
   *   written, and keeps OkHttp from repeating a session's `503` on its own.
   *
   * **The consumer's own interceptors go on the builder before this.** An application interceptor
   * added afterwards runs after the guard's address check, and a network interceptor added afterwards
   * after the final confinement.
   *
   * It also switches redirects off, and with a [guard] sets the guard's resolver and, unless the guard
   * says otherwise, no proxy. The guard's interceptor pins both again for every call and refuses a
   * call on a client that follows redirects, so a builder changed after this cannot shed them.
   *
   * **The deadline is OkHttp's `callTimeout`**, and it spans the whole call: the wait for admission,
   * every attempt and the body. `install` sets two minutes when the builder carries none, so no call
   * hangs on a peer that answers slowly; a deadline already on the builder stays. `Duration.ZERO`
   * set after `install` lifts it, which is what a long-lived stream needs.
   *
   * **OkHttp repeats nothing for a session's call**: its own resend is off whatever
   * `retryOnConnectionFailure` says, and an `Authenticator` on the builder is not asked, because either
   * would repeat an attempt the session did not authorize. Other calls on the client keep both.
   *
   * Refuses a builder that already carries these interceptors: two sets would nest the retries and
   * take two admission slots per call. A client derived through `newBuilder()` — OpenTelemetry's
   * `createCallFactory` derives one — keeps them, and shares their admission budget.
   */
  @JvmStatic
  @JvmOverloads
  fun install(
    client: OkHttpClient.Builder,
    guard: SempodsOutboundGuard? = null,
    admission: SempodsAdmission? = SempodsAdmission(),
  ): OkHttpClient.Builder {
    check(client.interceptors().none { it is SessionInterceptor }) {
      "The sempods interceptors are already installed on this client builder."
    }
    // A deadline the builder already carries stays. The builder has no public getter for it, so a
    // client built from it reads the value; building one starts no thread.
    if (client.build().callTimeoutMillis == 0) client.callTimeout(DEFAULT_CALL_TIMEOUT)
    client.interceptors().add(0, SessionInterceptor(admission?.let(::AdmissionGate)))
    if (guard != null) {
      val guarding = GuardInterceptor(guard)
      client.dns(guarding.dns)
      if (guard.proxyless) client.proxy(Proxy.NO_PROXY)
      client.interceptors().add(guarding)
    }
    client.networkInterceptors().add(FinalTarget)
    // No redirects, on both switches: a followed redirect reaches a target no application
    // interceptor saw — which the guard never vetted, and an IP literal needs no resolver.
    return client.followRedirects(false).followSslRedirects(false)
  }
}

/**
 * The execution policy of a call: admission for every call, and for a session's call everything
 * else.
 *
 * **Every attempt is a `Chain.proceed` on the one call**, which OkHttp permits an application
 * interceptor. So `Call.timeout()` spans all of them, `Call.cancel()` reaches whichever one is
 * running, and a cancel between two attempts starts no further one.
 */
private class SessionInterceptor(private val admission: AdmissionGate?) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    // The call's tag rather than the request's: an interceptor ahead of this one may rebuild the
    // request, and the call keeps the tags it was created with.
    val session = chain.call().tag(SempodsSession::class.java)
    if (session == null) {
      if (request.url.host == SempodsOkHttp.UNBOUND_HOST) {
        throw SempodsClientException(
          "'${request.url}' was built by a SempodsSession, but this call does not carry one. " +
            "Create the call from the request the session's newRequest built.",
        )
      }
      val slot = Slot(gate(), chain.call())
      slot.take()
      val response = try {
        chain.proceed(request)
      } catch (failure: Throwable) {
        slot.give()
        throw failure
      }
      return slot.holdUntilClosed(response)
    }
    val bound = session.bind(request)
    if (bound.header("Upgrade") != null) {
      // OkHttp runs no network interceptor for a protocol upgrade, and that is where the final
      // target is confined.
      throw SempodsClientException("A session's request cannot upgrade the connection: '${bound.url}'.")
    }
    // Nothing repeats below the session: no resend after a lost connection, and no follow-up by an
    // `Authenticator` on the builder.
    return attempts(chain.withRetryOnConnectionFailure(false).withAuthenticator(Authenticator.NONE), session, bound)
  }

  /**
   * Two things can earn another attempt, each at most once and neither while the body cannot be
   * sent again: a connection lost before any response arrived, for an idempotent method or a request
   * marked [SempodsRepeatable] (RFC 9110 §9.2.2) — which is how a pooled connection the server has
   * closed fails — and a refusal the session's [SempodsRequestAuth] expects another attempt to change.
   *
   * **The call holds its admission slot from before the first attempt until its response is closed**,
   * credential work included. A call made through this client from inside that work, on this thread,
   * runs on the same slot ([CredentialWait]) instead of waiting for the one its own caller holds.
   */
  private fun attempts(chain: Interceptor.Chain, session: SempodsSession, request: Request): Response {
    val call = chain.call()
    val slot = Slot(gate(), call)
    var number = 1
    var resent = false

    fun <T> acquiring(work: () -> T): T {
      val outer = CredentialWait.current.get()
      CredentialWait.current.set(CredentialWait.Work(call, admission))
      try {
        return work()
      } finally {
        if (outer == null) CredentialWait.current.remove() else CredentialWait.current.set(outer)
      }
    }

    fun proceed(attempt: Attempt, authenticated: Request): Response {
      val outer = Attempt.current.get()
      Attempt.current.set(attempt)
      try {
        return chain.proceed(authenticated)
      } finally {
        if (outer == null) Attempt.current.remove() else Attempt.current.set(outer)
      }
    }

    fun send(): Response {
      val authenticated = acquiring { session.authenticated(request, number) }
      val attempt = Attempt()
      return try {
        proceed(attempt, authenticated)
      } catch (failure: IOException) {
        // Once the network answered, a failure is not a lost connection, whatever threw it.
        if (attempt.answered || resent || call.isCanceled() ||
          !ConnectionResend.allowed(failure, request, SempodsRepeatable.isMarked(call))
        ) {
          throw failure
        }
        resent = true
        number++
        send()
      }
    }

    slot.take()
    try {
      val first = send()
      // A body that can be written once rules another attempt out, whatever the mechanism says: the
      // alternative is a repeat that sends nothing and is answered 200. A cancelled call is handed
      // back as it is, and OkHttp closes it and fails the call.
      if (first.isSuccessful || request.body?.isOneShot() == true || call.isCanceled()) {
        return slot.holdUntilClosed(first)
      }
      val retry = try {
        acquiring { session.auth.recover(first, number) }
      } catch (failure: Throwable) {
        first.close()
        throw failure
      }
      if (!retry) return slot.holdUntilClosed(first)
      // The refusal is closed here rather than handed on: its body was never read, and the response
      // the caller gets is the next attempt's.
      first.close()
      if (call.isCanceled()) throw IOException("Canceled")
      number++
      return slot.holdUntilClosed(send())
    } catch (failure: Throwable) {
      slot.give()
      throw failure
    }
  }

  /**
   * A call made from inside another call's credential work, on its thread and through this same
   * admission, runs on that call's slot; through another client it needs a slot of that client's.
   */
  private fun gate(): AdmissionGate? =
    if (admission != null && CredentialWait.current.get()?.admission === admission) null else admission
}

/** The admission slot of one call: taken before its first attempt and handed on to its response, whose close releases it. */
private class Slot(private val gate: AdmissionGate?, private val call: Call) {

  private var held = false

  fun take() {
    if (gate == null || held) return
    gate.admit(call)
    held = true
  }

  fun give() {
    if (gate == null || !held) return
    held = false
    gate.release()
  }

  fun holdUntilClosed(response: Response): Response {
    if (gate == null || !held) return response
    held = false
    return gate.holdUntilClosed(response)
  }
}

/** The attempt a session's interceptor is sending on this thread, so [FinalTarget] can record that the network answered it. */
private class Attempt {

  var answered = false

  companion object {
    val current = ThreadLocal<Attempt?>()
  }
}

/**
 * The pod confinement once more, as the last network interceptor installed: on the request as it is
 * about to be written, after every application interceptor and after a redirect.
 *
 * It also takes `Retry-After: 0` off a session's `503`. OkHttp repeats such an answer by itself,
 * below the session's interceptor, for any method, a one-shot body included, and with this attempt's
 * credential; without the header the caller gets the `503` and decides.
 */
private object FinalTarget : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val session = chain.call().tag(SempodsSession::class.java) ?: return chain.proceed(chain.request())
    session.confine(chain.request().url)
    val response = chain.proceed(chain.request())
    Attempt.current.get()?.answered = true
    if (response.code != 503 || response.header("Retry-After")?.trim()?.toIntOrNull() != 0) return response
    return response.newBuilder().removeHeader("Retry-After").build()
  }
}

/**
 * The slots of one [SempodsAdmission], shared by every client derived from the one it was installed
 * on.
 */
private class AdmissionGate(private val limits: SempodsAdmission) {

  private val active = Semaphore(limits.maxActive)

  private val waiting = Semaphore(limits.maxWaiting)

  /**
   * Takes a slot for [call], waiting while the call is neither cancelled nor out of time.
   *
   * The call deadline cancels the call, so watching for the cancel is watching for both.
   */
  fun admit(call: Call) {
    // A slot that is free is taken without entering the waiting room at all. Asking for a waiting
    // permit first would make `maxWaiting = 0` mean "no operations", not "no queue".
    if (active.tryAcquire()) return

    if (!waiting.tryAcquire()) {
      throw SempodsClientException(
        "Refused: ${limits.maxActive} operations are in flight and ${limits.maxWaiting} are already waiting.",
      )
    }
    try {
      while (!active.tryAcquire(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
        if (call.isCanceled()) throw IOException("Canceled while waiting for an execution slot.")
      }
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw InterruptedIOException("Interrupted while waiting for an execution slot.")
    } finally {
      waiting.release()
    }
  }

  fun release() {
    active.release()
  }

  /**
   * Holds the slot until the response body is closed.
   *
   * The bytes are still arriving while a caller reads them and the connection is still held, so
   * releasing the slot when the status line arrived would let an unbounded number of half-read
   * responses exist under a limit that says otherwise. The release rides on every close OkHttp counts
   * as closing the body: `Response.close()`, and the source that `string()`, `bytes()` and a closed
   * `byteStream()` close.
   *
   * A `101` is released at once: the connection now belongs to a WebSocket, which never closes the
   * upgrade response.
   */
  fun holdUntilClosed(response: Response): Response {
    if (response.code == 101) {
      release()
      return response
    }
    return response.newBuilder().body(ReleasingBody(response.body)).build()
  }

  private inner class ReleasingBody(private val delegate: ResponseBody) : ResponseBody() {

    private val released = AtomicBoolean(false)

    private val releasingSource: BufferedSource = object : ForwardingSource(delegate.source()) {
      override fun close() {
        try {
          super.close()
        } finally {
          releaseOnce()
        }
      }
    }.buffer()

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun source() = releasingSource

    override fun close() {
      try {
        delegate.close()
      } finally {
        releaseOnce()
      }
    }

    private fun releaseOnce() {
      if (released.compareAndSet(false, true)) release()
    }
  }

  private companion object {
    const val CANCEL_POLL_MILLIS = 20L
  }
}

/**
 * The address checks, as an interceptor, so they apply to every call on the client — a session's or
 * not.
 *
 * The `Dns` hook alone is half the defence: an engine handed an IP-literal host has nothing to
 * resolve and never asks a resolver, so `http://169.254.169.254/` would walk straight past it. This
 * is the other half, plus the outbound budget.
 */
private class GuardInterceptor(private val guard: SempodsOutboundGuard) : Interceptor {

  /** One instance, because OkHttp pools connections per `Dns` instance. */
  val dns: Dns = guard.dns()

  override fun intercept(chain: Interceptor.Chain): Response {
    if (chain.followRedirects) {
      throw SempodsClientException("A guarded client must not follow redirects: no redirect target is vetted per request.")
    }
    val target = chain.request().url.toUri()
    guard.refuseTarget(target)?.let { refusal ->
      throw when (refusal.kind) {
        SempodsUrlPolicy.Refusal.Kind.SCHEME -> SempodsClientException("Not an HTTP(S) URI: '$target'")
        SempodsUrlPolicy.Refusal.Kind.NO_HOST -> SempodsClientException("URI has no host: '$target'")
        // The cause travels: a blocked address means "this pod URL is wrong", never "this token
        // is dead", and a consumer that tells the two apart does it by walking the cause chain —
        // `PodFailures.isRetryablePodFailure` in `:sempods-mcp` is written that way.
        SempodsUrlPolicy.Refusal.Kind.NON_GLOBAL_HOST -> SempodsClientException(
          "Host '${target.host}' of '$target' is not publicly addressable — ${refusal.detail}",
          SsrfBlockedException(refusal.detail),
        )
      }
    }
    if (!guard.allows(target)) {
      throw SempodsRateLimitedException("outbound rate limit exceeded for $target")
    }
    var pinned = chain
    if (pinned.dns !== dns) pinned = pinned.withDns(dns)
    if (guard.proxyless && pinned.proxy != Proxy.NO_PROXY) pinned = pinned.withProxy(Proxy.NO_PROXY)
    return pinned.proceed(chain.request())
  }
}
