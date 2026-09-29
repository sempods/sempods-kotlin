package org.sempods.client

import okhttp3.Call
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Waits until a service reaches the contexts it needs, after it sent the owner to its consent
 * ([SempodsPodServiceClients.consentUrl]).
 *
 * The consent delivers nothing back, so this asks what the service reaches, the way it would use
 * that access: a `client_credentials` token ([SempodsPodTokens]), then the context catalogue
 * ([SempodsPodContexts.listBytes]). It reads the catalogue's `sd:namedGraph` members, whose
 * canonical JSON-LD spelling the specification fixes (SPS-CRUD-024, SPS-CTX-033).
 * It checks catalogue visibility, not read/write permissions or a particular consent. Verify the
 * operations the service needs separately. A token alone proves nothing: a service
 * that already holds one context gets a token before the owner decides about the next, and the owner
 * may grant other contexts than the ones asked for.
 *
 * ```java
 * var wait = new SempodsServiceAccessWait(
 *     new SempodsSession(alice, SempodsRequestAuth.clientSecretBasic(clientId, secret)), client);
 * SempodsServiceAccessWait.Outcome outcome = wait.await(List.of(notes), Duration.ofMinutes(10));
 * ```
 *
 * | Outcome | Means |
 * |---|---|
 * | [Outcome.REACHABLE] | every context named is listed for the service's token |
 * | [Outcome.TIME_LIMIT] | the time limit ended the wait. The owner cancelled, confirmed other contexts or nothing, or has not decided: the pod does not say which |
 * | [Outcome.CANCELLED] | [cancel] ended the wait |
 * | a [SempodsStatusException] with status `401` | `invalid_client`: the registration expired or was removed, or the secret is wrong. Waiting longer changes nothing |
 *
 * `400 invalid_scope` from the token endpoint means the service holds no grant yet, pending or
 * confirmed empty, and the wait goes on. A `429` lengthens the next pause. Every other refusal, and a
 * failure of the network, ends the wait as its exception.
 *
 * **Backoff.** The first check runs at once. Each pause after it doubles, from [initialDelay] to at
 * most [maxDelay], with jitter. Neither a pause nor a call reaches past the time limit.
 *
 * **Cancellation.** [cancel] may be called from any thread: it ends a pause at once and cancels the
 * request in flight. An interrupt of the waiting thread ends the wait as an [InterruptedIOException].
 *
 * @param session the pod and the service's credential, usually [SempodsRequestAuth.clientSecretBasic].
 * @param calls the client the checks go through.
 */
class SempodsServiceAccessWait @JvmOverloads constructor(
  val session: SempodsSession,
  val calls: Call.Factory,
  val initialDelay: Duration = Duration.ofSeconds(1),
  val maxDelay: Duration = Duration.ofSeconds(30),
) {

  /** How a wait ended when it did not throw. */
  enum class Outcome { REACHABLE, TIME_LIMIT, CANCELLED }

  private val cancelled = CountDownLatch(1)

  @Volatile
  private var inFlight: Call? = null

  /** When the running [await] ends, as [System.nanoTime] counts; `null` before the first. */
  @Volatile
  private var deadline: Long? = null

  /**
   * Every call goes through here, so [cancel] reaches the one in flight, and no call outlasts the
   * time limit: its own deadline is the shorter of the client's and the time left.
   */
  private val tracked = Call.Factory { request ->
    calls.newCall(request).also { call ->
      inFlight = call
      deadline?.let { end ->
        val left = end - System.nanoTime()
        val own = call.timeout().timeoutNanos().takeIf { it > 0 } ?: Long.MAX_VALUE
        if (left < own) call.timeout().timeout(maxOf(left, 1), TimeUnit.NANOSECONDS)
      }
      if (isCancelled) call.cancel()
    }
  }

  private val tokens = SempodsPodTokens(session, tracked)

  private val isCancelled: Boolean get() = cancelled.count == 0L

  init {
    require(!initialDelay.isNegative && !initialDelay.isZero) { "initialDelay must be positive." }
    require(maxDelay >= initialDelay) { "maxDelay must not be shorter than initialDelay." }
  }

  /**
   * Blocks until the service's token reaches every context in [contexts], [timeLimit] passes, or
   * [cancel] is called.
   *
   * @param contexts context IRIs, as `GET /contexts` lists them.
   */
  @Throws(IOException::class)
  fun await(contexts: Collection<String>, timeLimit: Duration): Outcome {
    val needed = contexts.toSet()
    val deadline = System.nanoTime() + timeLimit.toNanos()
    this.deadline = deadline
    var pause = initialDelay
    var catalogue: SempodsPodContexts? = null
    while (true) {
      if (isCancelled) return Outcome.CANCELLED
      try {
        val fresh = catalogue == null
        catalogue = catalogue ?: mint()?.let { SempodsPod(SempodsSession(session.podBase, SempodsRequestAuth.bearer(it)), tracked).contexts() }
        val reached = catalogue?.let(::reachable)
        if (catalogue != null && reached == null) {
          // The pod no longer accepts the token: mint again at once, unless it was just minted.
          catalogue = null
          if (!fresh) continue
        }
        if (reached != null && reached.containsAll(needed)) return Outcome.REACHABLE
      } catch (e: SempodsStatusException) {
        if (e.status != 429) throw e
        pause = maxOf(pause, maxDelay.dividedBy(2))
      } catch (e: IOException) {
        if (isCancelled) return Outcome.CANCELLED
        // A call the time limit cut short.
        if (System.nanoTime() - deadline >= 0) return Outcome.TIME_LIMIT
        throw e
      }

      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) return Outcome.TIME_LIMIT
      val jittered = pause.toNanos() / 2 + ThreadLocalRandom.current().nextLong(pause.toNanos() / 2 + 1)
      val woken = try {
        cancelled.await(minOf(jittered, remaining), TimeUnit.NANOSECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("interrupted while waiting for access").apply { initCause(e) }
      }
      if (woken) return Outcome.CANCELLED
      pause = minOf(pause.multipliedBy(2), maxDelay)
    }
  }

  /** Ends a running or later [await] with [Outcome.CANCELLED]. Safe from any thread. */
  fun cancel() {
    cancelled.countDown()
    inFlight?.cancel()
  }

  /** A token, or `null` while the service holds no grant (`invalid_scope`). */
  private fun mint(): String? = try {
    tokens.clientCredentials().body?.accessToken
  } catch (e: SempodsStatusException) {
    if (e.status == 400 && errorOf(e) == "invalid_scope") null else throw e
  }

  /** The context IRIs the catalogue lists, or `null` where the pod no longer accepts its token. */
  private fun reachable(catalogue: SempodsPodContexts): Set<String>? {
    val answer = try {
      catalogue.listBytes()
    } catch (e: SempodsStatusException) {
      if (e.status == 401) return null else throw e
    }
    val bytes = answer.body ?: return emptySet()
    return try {
      val document = decodeObject(bytes)
      if (NAMED_GRAPH !in document.names()) emptySet() else document.objects(NAMED_GRAPH).mapTo(HashSet()) { it.string("@id") }
    } catch (violation: ProtocolViolation) {
      throw SempodsDecodingException.of("The context catalogue cannot be read: ${violation.detail}.", answer.status, answer.headers)
    }
  }

  private fun errorOf(e: SempodsStatusException): String? =
    runCatching { decodeObject(e.bodyExcerpt.toByteArray()).stringOrNull("error") }.getOrNull()

  private companion object {

    /** SPARQL 1.1 Service Description's `sd:namedGraph`, as canonical JSON-LD spells it. */
    const val NAMED_GRAPH = "http://www.w3.org/ns/sparql-service-description#namedGraph"
  }
}
