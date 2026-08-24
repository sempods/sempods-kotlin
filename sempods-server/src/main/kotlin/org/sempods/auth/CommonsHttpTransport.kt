package org.sempods.auth

import com.google.inject.Inject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.sempods.auth.core.HttpTransport
import java.time.Duration

/**
 * [HttpTransport] on this server's shared OkHttp client.
 *
 * The adapter the port exists for: the OIDC flow lives in `sempods-auth-core`, which holds no HTTP
 * client of its own because the services that use it do not agree on one. This one shares the
 * server's client rather than opening a second — the id-server is a configured host, not user
 * input, so there is no policy here that the shared client would be the wrong place for.
 *
 * Blocking, with a deadline: the port is synchronous, and every call it makes is on the login path,
 * where a blocked request thread is that login's own latency. Without the deadline a hanging
 * id-server would hold a JAX-RS thread until the container gave up on it.
 *
 * [TIMEOUT] is that deadline, well inside the shared client's own budgets (5 s connect, 60 s
 * socket, 60 s call). It is expressed as OkHttp's `callTimeout`, which **cancels the call** when it
 * expires rather than merely abandoning the wait — otherwise an id-server outage would leave every
 * abandoned login holding a connection for the remainder of the client's budget.
 * `docs/auth/oauth.md` quotes the figure; [CommonsHttpTransportTest] pins it and the
 * cancellation, because that paragraph has been wrong about this leg twice.
 */
class CommonsHttpTransport internal constructor(
  client: OkHttpClient,
  timeout: Duration,
) : HttpTransport {

  @Inject
  constructor(client: OkHttpClient) : this(client, TIMEOUT)

  private val http = client.newBuilder().callTimeout(timeout).build()

  override fun get(url: String): String =
    http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
      val body = response.body.string()
      check(response.code == 200) { "GET $url failed: HTTP ${response.code}" }
      body
    }

  override fun postForm(url: String, form: Map<String, String>): String {
    val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
    val request = Request.Builder().url(url).header("Accept", "application/json").post(body).build()
    // The body is returned whatever the status: an OAuth error is a JSON document carrying the
    // reason, and throwing before it is read discards the only thing that says what went wrong.
    return http.newCall(request).execute().use { it.body.string() }
  }

  companion object {
    /**
     * Internal rather than private so a test can pin the number the documentation quotes — the
     * same reason `SempodsModule`'s defaults are named constants.
     */
    internal val TIMEOUT: Duration = Duration.ofSeconds(10)
  }
}
