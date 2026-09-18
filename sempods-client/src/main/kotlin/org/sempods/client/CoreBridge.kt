package org.sempods.client

import okhttp3.Request
import okhttp3.Response
import org.sempods.client.core.SempodsAuthAttempt
import org.sempods.client.core.SempodsRequestAuth
import org.sempods.client.core.SempodsResponseException
import org.sempods.client.core.SempodsStatusException
import java.io.IOException

/**
 * [SempodsAuth] as the core's mechanism, so a call of this surface runs on a session.
 *
 * **The bearer is asked per attempt rather than held**, which is what separates this from
 * [SempodsRequestAuth.refreshable]. That convenience caches the value it acquired and asks its
 * supplier again only after a refusal — correct for a credential that changes when it expires, and
 * wrong for this one: [SempodsAuth.token] is never cached by the client, and the pod server's own
 * test seeding re-derives per call because the scope set it needs grows as a test registers
 * contexts. Held, such a token would carry no scope for a
 * context registered after it was first acquired, and the pod would answer 403 — which no recovery
 * retries.
 *
 * What it keeps from the legacy tier is the rest of that contract: `null` is an anonymous request,
 * a 401 on one drops [SempodsAuth.invalidate] and earns one further attempt, and a 401 on a request
 * that carried no bearer earns none, because there is nothing to re-mint. A connection resend is an
 * attempt too, so it asks again without invalidating first — [SempodsAuth.token] says what that
 * costs an implementation that mints per call.
 */
internal fun SempodsAuth.asRequestAuth(): SempodsRequestAuth = LegacyRequestAuth(this)

private class LegacyRequestAuth(private val auth: SempodsAuth) : SempodsRequestAuth {

  override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) {
    auth.token()?.let { request.header(AUTHORIZATION, "Bearer $it") }
  }

  override fun recover(response: Response, attempt: SempodsAuthAttempt): Boolean {
    if (response.code != 401 || response.request.header(AUTHORIZATION) == null) return false
    auth.invalidate()
    return true
  }

  private companion object {
    const val AUTHORIZATION = "Authorization"
  }
}

/**
 * Runs [block] and reports what the core throws as this surface's own failure.
 *
 * The two hierarchies are deliberately different — the core's is an `IOException` a Java caller
 * already handles, this one is unchecked because the methods that throw it never declared it — so
 * one translation stands here rather than a `catch` per method. Status, the server's own excerpt
 * and the cause carry over; a caller classifying on any of the three sees what it saw before.
 */
internal inline fun <R> translating(block: () -> R): R =
  try {
    block()
  } catch (e: SempodsResponseException) {
    throw SempodsClientException(
      e.message ?: "The pod's answer is outside the operation's contract.",
      statusCode = e.status,
      responseBody = (e as? SempodsStatusException)?.bodyExcerpt,
      cause = e,
    )
  } catch (e: IOException) {
    throw SempodsClientException(e.message ?: "The call failed.", cause = e)
  }
