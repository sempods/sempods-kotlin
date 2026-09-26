package org.sempods.client

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.net.SempodsRateLimitedException
import org.sempods.client.net.SempodsUrlPolicy
import org.sempods.client.net.SsrfBlockedException
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
   *   pod's host in place of the placeholder, and makes at most one resend after a lost connection
   *   ([SempodsRepeatable]) and one authentication retry. Each is a `Chain.proceed`, so every
   *   interceptor after it runs once more for each. It also holds [admission] for every call on the
   *   client, session or not.
   * - **The last application interceptor**, with a [guard]: the per-request address check and the
   *   outbound budget, on the URL the interceptors before it produced.
   * - **The last network interceptor** authenticates a session's request as it is about to be written:
   *   it confines it again, applies the session's [SempodsRequestAuth] and shows it the answer. Every
   *   request the call writes passes there, a repeat OkHttp makes on its own included — a `421` on a
   *   coalesced HTTP/2 connection, a `503` with `Retry-After: 0` — so each carries a credential applied
   *   for it.
   *
   * **The consumer's own interceptors go on the builder before this**, and for a session's call a
   * network interceptor has to: this library's is the last one, and it is where the request that goes
   * out is read. One added after this sits below it, and a request it changes there — a method, a body
   * that can be written once — is not seen, so a resend can repeat what the server already did
   * ([#236](https://github.com/sempods/sempods-kotlin/issues/236)). An application interceptor added
   * afterwards is above it and runs after the guard's address check.
   *
   * An interceptor before this may change what it passes on: the credential is applied to, and the
   * resend rules are measured against, the request as it is written. No interceptor before this sees
   * the credential, and neither does `Response.request()`; `Response.networkResponse().request()` does.
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
   * **Which request goes again is the session's call**: OkHttp's own resend after a lost connection is
   * off whatever `retryOnConnectionFailure` says, because it would send a `POST` again, and an
   * `Authenticator` on the builder is not asked, because the session's mechanism has its one retry. A
   * [SempodsForeignTarget] call keeps neither; other calls on the client keep both.
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
 * else but the credential, which [FinalTarget] applies.
 *
 * **A resend and an authentication retry are each a `Chain.proceed` on the one call**, which OkHttp
 * permits an application interceptor. So `Call.timeout()` spans every request the call writes,
 * `Call.cancel()` reaches whichever one is running, and a cancelled call writes no further request.
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
      chain.call().tag(ForeignCall::class.java)?.let { foreign -> return foreignCall(chain, foreign, request) }
      val slot = Slot(gate(chain.call()), chain.call())
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
      // OkHttp runs no network interceptor for a protocol upgrade, and that is where the request is
      // confined and authenticated.
      throw SempodsClientException("A session's request cannot upgrade the connection: '${bound.url}'.")
    }
    // The resend after a lost connection and the follow-up to a 401 are the session's own.
    return attempts(chain.withRetryOnConnectionFailure(false).withAuthenticator(Authenticator.NONE), session, bound)
  }

  /**
   * A [SempodsForeignTarget] call, as that class describes it. `retryOnConnectionFailure` is off because
   * OkHttp would also repeat a `408` under it, so the one resend a `GET` keeps after a lost connection is
   * made here ([ConnectionResend]). A client that follows redirects is refused: OkHttp would follow a
   * redirect without the rules [SempodsForeignTarget.followingRedirects] keeps.
   *
   * The call's mechanism is applied and told about each answer in [FinalTarget], and never asked to
   * recover: a foreign target's refusal is its answer.
   */
  private fun foreignCall(chain: Interceptor.Chain, foreign: ForeignCall, request: Request): Response {
    if (chain.followRedirects) {
      throw SempodsClientException(
        "A client that follows redirects cannot dereference a foreign target: a redirect would take the " +
          "call's credential with it. SempodsForeignTarget.followingRedirects follows them one vetted call at a time.",
      )
    }
    val call = chain.call()
    val quiet = chain
      .withAuthenticator(Authenticator.NONE)
      .withCookieJar(CookieJar.NO_COOKIES)
      .withRetryOnConnectionFailure(false)
    val passes = CallPasses.of(call, admission, foreign)
    val slot = Slot(gate(call), call)
    slot.take()
    val response = try {
      val before = passes.last
      try {
        quiet.proceed(request)
      } catch (failure: IOException) {
        if (call.isCanceled() || !ConnectionResend.allowed(failure, passes.since(before), repeatable = false)) throw failure
        quiet.proceed(request)
      }
    } catch (failure: Throwable) {
      slot.give()
      throw failure
    }
    return slot.holdUntilClosed(response)
  }

  /**
   * The session's own repeats, each at most once and neither while the body cannot be sent again: a
   * resend after a lost connection, for an idempotent method or a request marked [SempodsRepeatable]
   * (RFC 9110 §9.2.2) — which is how a pooled connection the server has closed fails — and an
   * authentication retry, after a refusal the session's [SempodsRequestAuth] expects another attempt
   * to change.
   *
   * Both are decided on the [NetworkPass] that went out: the request the last network interceptor
   * wrote, and the answer, if one came back. So an interceptor below this one that changes the method
   * or the body is seen, one that throws after the answer arrived is not mistaken for a lost
   * connection, and an answer no request brought — one from OkHttp's cache — earns no retry.
   *
   * The mechanism has seen every answer by then ([SempodsRequestAuth.observe]), so a refusal that
   * earns no repeat still hands on what the server said.
   *
   * The call's admission slot is held as [SempodsAdmission] describes.
   */
  private fun attempts(chain: Interceptor.Chain, session: SempodsSession, request: Request): Response {
    val call = chain.call()
    val passes = CallPasses.of(call, admission, SessionCredential(session))
    val slot = Slot(gate(call), call)
    var resent = false
    var answered: NetworkPass? = null

    fun send(): Response {
      val before = passes.last
      return try {
        chain.proceed(request).also { answered = passes.since(before) }
      } catch (failure: IOException) {
        if (resent || call.isCanceled() || !ConnectionResend.allowed(failure, passes.since(before), SempodsRepeatable.isMarked(call))) {
          throw failure
        }
        resent = true
        send()
      }
    }

    slot.take()
    try {
      val first = send()
      val pass = answered
      val refused = pass?.answer
      // A body that can be written once rules another attempt out, whatever the mechanism says: the
      // alternative is a repeat that sends nothing and is answered 200. A cancelled call is handed back
      // as it is, and OkHttp closes it and fails the call.
      val oneShot = pass?.written?.body?.isOneShot() == true
      if (first.isSuccessful || pass == null || refused == null || oneShot || call.isCanceled()) {
        return slot.holdUntilClosed(first)
      }
      val retry = try {
        passes.authenticating(pass.number) { session.auth.recover(refused, it) }
      } catch (failure: Throwable) {
        first.close()
        throw failure
      }
      if (!retry) return slot.holdUntilClosed(first)
      // The refusal is closed here rather than handed on: its body was never read, and the response
      // the caller gets is the next attempt's.
      first.close()
      return slot.holdUntilClosed(send())
    } catch (failure: Throwable) {
      slot.give()
      throw failure
    }
  }

  /** No gate for a call that credential work on this same admission lends its slot ([SempodsAuthAttempt.calls]). */
  private fun gate(call: Call): AdmissionGate? =
    if (admission != null && call.tag(SempodsAuthAttempt::class.java)?.lends(admission) == true) null else admission
}

/**
 * What the last network interceptor does to every request of a call that carries a credential, or
 * may: hold it to its target, authenticate it, and show the mechanism the answer.
 */
internal interface CallCredential {

  /** Throws when [request] may not go where it points under this credential. */
  fun confine(request: Request)

  /** [request] with the credential applied as [attempt]. */
  fun authenticate(request: Request, attempt: SempodsAuthAttempt): Request

  /** Shows [facts], the answer to [attempt], to the mechanism. */
  fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt)
}

/** A session's call: held to its pod, with the session's [SempodsRequestAuth]. */
private class SessionCredential(private val session: SempodsSession) : CallCredential {

  override fun confine(request: Request) = session.confine(request)

  override fun authenticate(request: Request, attempt: SempodsAuthAttempt) = session.authenticated(request, attempt)

  override fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) = session.auth.observe(facts, attempt)
}

/**
 * The requests one call writes, numbered in order, and the credential work for each.
 *
 * The session's interceptor puts it on the call, and [FinalTarget] writes every request through it —
 * the one place each passes, a repeat OkHttp makes below the session's interceptor included. Kept as
 * a call tag: an interceptor that builds a request of its own cannot drop it, and no thread-local is
 * needed.
 */
private class CallPasses private constructor(
  private val call: Call,
  private val admission: AdmissionGate?,
  private val credential: CallCredential,
) {

  /** The pass started most recently, or null before the first. */
  @Volatile
  var last: NetworkPass? = null
    private set

  /** Passes started. A call writes one request at a time. */
  private var started = 0

  /** The pass started since [before] was the last one, or null when none was. */
  fun since(before: NetworkPass?): NetworkPass? = last?.takeUnless { it === before }

  /**
   * [chain]'s request, written as the call's next pass: confined before any credential work and again
   * after it, since a mechanism may set `Host`; authenticated; and its answer shown to the mechanism,
   * whose failure closes the answer.
   */
  fun write(chain: Interceptor.Chain): Response {
    if (call.isCanceled()) throw IOException("Canceled")
    val request = chain.request()
    credential.confine(request)
    val pass = NetworkPass(++started)
    last = pass
    val sent = authenticating(pass.number) { credential.authenticate(request, it) }
    credential.confine(sent)
    pass.written = sent
    val response = chain.proceed(sent)
    // The network's answer, whose request is the one written: a mechanism finds its credential there.
    val facts = SempodsResponseFacts.of(response)
    pass.answer = facts
    try {
      authenticating(pass.number) { credential.observe(facts, it) }
    } catch (failure: Throwable) {
      response.close()
      throw failure
    }
    return response
  }

  /** Runs [work] as attempt [number], and ends the attempt when [work] returns. */
  fun <T> authenticating(number: Int, work: (SempodsAuthAttempt) -> T): T {
    val attempt = SempodsAuthAttempt.of(number, call, admission)
    try {
      return work(attempt)
    } finally {
      attempt.end()
    }
  }

  companion object {

    /** [call]'s record, put on it the first time. */
    fun of(call: Call, admission: AdmissionGate?, credential: CallCredential): CallPasses =
      call.tag(CallPasses::class.java) { CallPasses(call, admission, credential) }
  }
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

/**
 * The last network interceptor installed, where a session's or a foreign target's request is written
 * ([CallPasses]): after every application interceptor, after a redirect, and for each repeat OkHttp
 * makes on its own. Other calls pass untouched.
 */
private object FinalTarget : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response =
    chain.call().tag(CallPasses::class.java)?.write(chain) ?: chain.proceed(chain.request())
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
   * responses exist under a limit that says otherwise. The release rides on the body's close
   * ([ClosingBody]).
   *
   * A `101` is released at once: the connection now belongs to a WebSocket, which never closes the
   * upgrade response.
   */
  fun holdUntilClosed(response: Response): Response {
    if (response.code == 101) {
      release()
      return response
    }
    return response.newBuilder().body(ClosingBody(response.body, ::release)).build()
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

/**
 * [delegate], running [onClose] once, on the first close OkHttp counts as closing a body: `Response.close()`,
 * and the source that `string()`, `bytes()` and a closed `byteStream()` close.
 */
internal class ClosingBody(private val delegate: ResponseBody, private val onClose: Runnable) : ResponseBody() {

  private val closed = AtomicBoolean(false)

  private val closingSource: BufferedSource = object : ForwardingSource(delegate.source()) {
    override fun close() {
      try {
        super.close()
      } finally {
        closeOnce()
      }
    }
  }.buffer()

  override fun contentType() = delegate.contentType()

  override fun contentLength() = delegate.contentLength()

  override fun source() = closingSource

  override fun close() {
    try {
      delegate.close()
    } finally {
      closeOnce()
    }
  }

  private fun closeOnce() {
    if (closed.compareAndSet(false, true)) onClose.run()
  }
}
