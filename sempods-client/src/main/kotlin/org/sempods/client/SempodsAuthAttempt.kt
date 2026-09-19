package org.sempods.client

import okhttp3.Call

/**
 * The credential work for one attempt of a call: what [SempodsRequestAuth.apply],
 * [SempodsRequestAuth.observe], [SempodsRequestAuth.recover] and [SempodsCredentialSupplier.get]
 * receive.
 *
 * The work lasts until the method that received it returns.
 */
class SempodsAuthAttempt private constructor(
  /** `1` for a call's first attempt. A resend or an authentication retry counts one more. */
  val number: Int,
  /**
   * The call being authenticated. Its deadline cancels it, so a mechanism that waits stops once
   * `call.isCanceled()` is `true`, as [SempodsRequestAuth.refreshable] does.
   */
  val call: Call,
  /** The admission whose slot [call] holds, or null on a client without one. */
  private val admission: Any?,
) {

  @Volatile
  private var ended = false

  /**
   * [calls], whose calls run on the admission slot [call] holds while this work lasts.
   *
   * ```java
   * SempodsRequestAuth auth = SempodsRequestAuth.refreshable((forceRefresh, attempt) -> {
   *   try (Response minted = attempt.calls(client).newCall(tokenRequest).execute()) {
   *     return minted.body().string();
   *   }
   * });
   * ```
   *
   * [call] holds its slot while it waits for its credential. On a client with `maxActive = 1`, a token
   * fetched on a slot of its own would wait for that one slot until the deadline.
   *
   * | A call from the work | Its admission |
   * |---|---|
   * | through `calls(client)`, on any thread, `execute` or `enqueue` | none of its own: it runs on [call]'s slot |
   * | through `calls(client)`, starting after the work ended | a slot of its own |
   * | through `client` directly | a slot of its own |
   * | through a client with another [SempodsAdmission] | a slot of that client |
   *
   * `client` is the client [call] runs on, or one derived from it with `newBuilder()`. Any factory that
   * delegates to such a client serves as well, so `new SempodsPod(session, attempt.calls(client))` and
   * `new SempodsForeignTarget(attempt.calls(client))` run their calls on the slot too. Cancelling [call]
   * does not cancel them.
   */
  fun calls(calls: Call.Factory): Call.Factory = Call.Factory { request ->
    calls.newCall(request.newBuilder().tag(SempodsAuthAttempt::class.java, this).build())
  }

  /** Whether a call made through [calls] runs on this work's slot under [gate], which is not null. */
  @JvmSynthetic
  internal fun lends(gate: Any): Boolean = !ended && gate === admission

  /** Ends the work: a call through [calls] that starts afterwards takes a slot of its own. */
  @JvmSynthetic
  internal fun end() {
    ended = true
  }

  internal companion object {

    @JvmSynthetic
    internal fun of(number: Int, call: Call, admission: Any?): SempodsAuthAttempt =
      SempodsAuthAttempt(number, call, admission)
  }
}
