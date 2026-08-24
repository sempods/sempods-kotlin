package org.sempods.mcp.oauth

import org.sempods.auth.core.HttpTransport
import org.sempods.client.SempodsBody
import org.sempods.client.SempodsHttpTransport
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * [HttpTransport] on `:sempods-client`'s transport.
 *
 * The adapter the port exists for: the flow lives in `sempods-auth-core`, which holds no HTTP
 * client of its own because the services that use it do not agree on one.
 *
 * **No `runBlocking` here, and that is the point of the change that produced this class.** The
 * predecessor bridged a synchronous port onto a `suspend`-only client and paid for it on the login
 * path. The port is synchronous, this transport is synchronous, and the adapter is now what an
 * adapter should be — two calls, no concurrency machinery in between.
 */
class SempodsClientHttpTransport(private val transport: SempodsHttpTransport) : HttpTransport {

  override fun get(url: String): String {
    val response = transport.send(transport.newRequest(URI(url)).GET().build())
    check(response.statusCode == 200) { "GET $url failed: HTTP ${response.statusCode}" }
    return response.body
  }

  override fun postForm(url: String, form: Map<String, String>): String {
    val body = form.entries.joinToString("&") { (name, value) -> "${enc(name)}=${enc(value)}" }
    // The body is returned whatever the status: an OAuth error is a JSON document carrying the
    // reason, and throwing before it is read discards the only thing that says what went wrong.
    return transport.send(
      transport.newRequest(URI(url))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(SempodsBody.text(body))
        .build(),
    ).body
  }

  private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
