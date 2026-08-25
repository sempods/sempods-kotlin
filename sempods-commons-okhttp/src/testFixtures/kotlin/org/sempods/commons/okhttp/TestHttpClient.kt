package org.sempods.commons.okhttp

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The HTTP client an integration test drives a running server with.
 *
 * A fixture rather than [CommonsHttpClient] because the two want opposite things. The production
 * wrapper hides the response — it decides what is an error and hands back a body — while a test
 * asserts on the status, the body *and* the headers of the same response, including the ones a
 * refusal carries. It also needs a response it may read twice, which a live OkHttp body is not.
 *
 * So [TestHttpResponse] is **buffered**: the body is read once at [TestHttpRequest.execute] and the
 * connection is released there. Every later read is off the byte array.
 *
 * Redirects are **not** followed by default, which is what most of the suite asserts on — a login
 * redirect and a `Location` header are the subject of those tests, not something to be resolved
 * away.
 */
class TestHttpClient(private val base: OkHttpClient, followRedirects: Boolean = false) {

  /**
   * The same client, but following redirects — for the call sites that assert on where a redirect
   * leads rather than on the redirect itself.
   */
  val followingRedirects: TestHttpClient by lazy { TestHttpClient(base, followRedirects = true) }

  private val redirectFollowing: OkHttpClient =
    base.newBuilder().followRedirects(true).followSslRedirects(true).build()

  private val redirectRefusing: OkHttpClient =
    base.newBuilder().followRedirects(false).followSslRedirects(false).build()

  private val default: OkHttpClient = if (followRedirects) redirectFollowing else redirectRefusing

  fun prepareGet(url: String): TestHttpRequest = request("GET", url)

  fun prepareHead(url: String): TestHttpRequest = request("HEAD", url)

  fun preparePost(url: String): TestHttpRequest = request("POST", url)

  fun preparePut(url: String): TestHttpRequest = request("PUT", url)

  fun prepareDelete(url: String): TestHttpRequest = request("DELETE", url)

  fun prepareOptions(url: String): TestHttpRequest = request("OPTIONS", url)

  /** For the methods without a named helper — `PATCH`, and whatever a route grows next. */
  fun prepare(method: String, url: String): TestHttpRequest = request(method, url)

  private fun request(method: String, url: String) =
    TestHttpRequest(method, url) { follow ->
      when (follow) {
        null -> default
        true -> redirectFollowing
        false -> redirectRefusing
      }
    }
}

/** One request under construction. Every method returns `this`, so a call site reads as one chain. */
class TestHttpRequest internal constructor(
  private val method: String,
  url: String,
  private val clientFor: (Boolean?) -> OkHttpClient,
) {

  private val url = url.toHttpUrl().newBuilder()
  private val headers = Headers.Builder()
  private var body: RequestBody? = null
  private var followRedirect: Boolean? = null

  fun addHeader(name: String, value: String): TestHttpRequest = apply { headers.add(name, value) }

  fun addQueryParam(name: String, value: String): TestHttpRequest =
    apply { url.addQueryParameter(name, value) }

  fun setBody(body: String): TestHttpRequest = setBody(body.toByteArray(StandardCharsets.UTF_8))

  /**
   * No content type on the body, so the `Content-Type` a test sets with [addHeader] is what goes
   * out — OkHttp would otherwise overwrite that header from the body and append a charset the
   * endpoint's exact media types do not expect.
   */
  fun setBody(body: ByteArray): TestHttpRequest = apply { this.body = body.toRequestBody(null) }

  fun setFollowRedirect(follow: Boolean): TestHttpRequest = apply { followRedirect = follow }

  fun execute(): TestHttpResponse {
    val request = Request.Builder()
      .url(url.build())
      .headers(withoutTransparentCompression())
      .method(method, body ?: defaultBody())
      .build()
    return clientFor(followRedirect).newCall(request).execute().use { response ->
      TestHttpResponse(
        statusCode = response.code,
        responseBodyAsBytes = response.body.bytes(),
        charset = response.body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8,
        headers = response.headers,
      )
    }
  }

  /**
   * OkHttp asks for `gzip` unless the caller names an encoding, and negotiating one changes what the
   * server answers with: Jetty's `GzipHandler` appends `--gzip` to the `ETag` of a compressed
   * response, so a GET and a HEAD of the same resource stop reporting the same tag. `BaseEndpoint`
   * accommodates that suffix on `If-Match`, which is why it is a wart rather than a defect — but it
   * is not what these tests are about, and asking for no encoding keeps them asserting on the
   * representation the endpoint produced rather than on what a compressor did to it.
   *
   * TODO: nothing covers the compressed path, and a real client does negotiate it. Two tests state
   *  the stricter identity — `PodResourceEndpointHttpTest`'s "HEAD returns same headers as GET" and
   *  `PodResourceByIriEndpointHttpTest`'s echoed-create-ETag case — and both would need to say what
   *  holds *per content coding* before this default could be dropped.
   */
  private fun withoutTransparentCompression(): Headers {
    val built = headers.build()
    return if (built["Accept-Encoding"] != null) built
    else built.newBuilder().add("Accept-Encoding", "identity").build()
  }

  /**
   * `null` for the methods that must not carry one, empty otherwise — so a POST still goes out with
   * `Content-Length: 0` the way it did before.
   */
  private fun defaultBody(): RequestBody? =
    if (method == "GET" || method == "HEAD" || method == "DELETE") null else EMPTY_BODY

  private companion object {
    val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
  }
}

/** A response whose body has already been read, so a test may assert on it more than once. */
class TestHttpResponse internal constructor(
  val statusCode: Int,
  val responseBodyAsBytes: ByteArray,
  private val charset: Charset,
  val headers: Headers,
) {

  val responseBody: String by lazy { String(responseBodyAsBytes, charset) }

  val contentType: String? get() = headers["Content-Type"]

  fun getHeader(name: String): String? = headers[name]
}

/** Every value sent under [name]. `Set-Cookie` is the reason this is not just `headers[name]`. */
fun Headers.getAll(name: String): List<String> = values(name)
