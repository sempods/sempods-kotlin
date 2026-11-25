package org.sempods.pods.oauth.didweb

import okhttp3.OkHttpClient
import okhttp3.Request
import org.sempods.client.SempodsOkHttp
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.net.SempodsUrlPolicy
import java.io.IOException
import java.time.Duration

/**
 * [DidDocumentFetcher] on a client of its own, behind [SempodsOutboundGuard].
 *
 * The address is a stranger's: a `did:web` names its own host. So the guard refuses every
 * non-public address unless [allowPrivateAddresses] says this is development, redirects are off,
 * the whole call is bounded by [CALL_TIMEOUT] and a body over [MAX_BODY_BYTES] is refused unread.
 * A registration can name any `did:web`, and each one is an outbound call the pod makes on a
 * caller's behalf; these limits keep that call cheap and confined.
 */
internal class OkHttpDidDocumentFetcher(base: OkHttpClient, allowPrivateAddresses: Boolean) : DidDocumentFetcher {

  private val http: OkHttpClient = SempodsOkHttp.install(
    base.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .readTimeout(Duration.ofSeconds(5)),
    guard = SempodsOutboundGuard(policy = SempodsUrlPolicy(allowPrivateAddresses = allowPrivateAddresses)),
    admission = null,
  )
    // After `install`, which would otherwise put its own deadline in.
    .callTimeout(CALL_TIMEOUT)
    .build()

  override fun fetch(url: String): String {
    val request = Request.Builder().url(url).header("Accept", "application/did+json, application/json").get().build()
    return http.newCall(request).execute().use { response ->
      if (response.code != 200) throw IOException("GET $url answered HTTP ${response.code}")
      val source = response.body.source()
      if (source.request(MAX_BODY_BYTES + 1)) throw IOException("DID document at $url exceeds $MAX_BODY_BYTES bytes")
      source.buffer.readUtf8()
    }
  }

  companion object {
    /** The whole call, connect to last byte. */
    internal val CALL_TIMEOUT: Duration = Duration.ofSeconds(5)

    /** A DID document with a handful of keys is a few kilobytes. */
    internal const val MAX_BODY_BYTES = 64L * 1024
  }
}
