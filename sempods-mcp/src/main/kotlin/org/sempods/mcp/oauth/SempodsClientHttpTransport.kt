package org.sempods.mcp.oauth

import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Request
import org.sempods.auth.core.HttpTransport
import org.sempods.client.SempodsExchange
import org.sempods.client.SempodsStatusException

/**
 * [HttpTransport] on the client core.
 *
 * The adapter the port exists for: the flow lives in `sempods-auth-core`, which holds no HTTP
 * client of its own because the services that use it do not agree on one. What arrives here is a
 * `Call.Factory` — a client `SempodsOkHttp.install` configured, so the guard, the redirect policy
 * and the deadline are the caller's choice of client rather than this class's.
 *
 * **No `runBlocking` here, and that is the point of the change that produced this class.** The
 * predecessor bridged a synchronous port onto a `suspend`-only client and paid for it on the login
 * path. The port is synchronous, an exchange is synchronous, and the adapter is now what an adapter
 * should be — two calls, no concurrency machinery in between.
 */
class SempodsClientHttpTransport(calls: Call.Factory) : HttpTransport {

  private val exchange = SempodsExchange(calls)

  override fun get(url: String): String =
    exchange.text(Request.Builder().url(url).get().build(), 200).body.orEmpty()

  override fun postForm(url: String, form: Map<String, String>): String {
    val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
    val request = Request.Builder().url(url).post(body).build()
    // The body is returned whatever the status: an OAuth error is a JSON document carrying the
    // reason, and throwing before it is read discards the only thing that says what went wrong. The
    // core keeps a refused body as an excerpt, which is where that document is read from.
    return try {
      exchange.text(request, *SUCCESS).body.orEmpty()
    } catch (refused: SempodsStatusException) {
      refused.bodyExcerpt
    }
  }

  private companion object {

    val SUCCESS: IntArray = (200..299).toList().toIntArray()
  }
}
