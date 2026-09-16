package org.sempods.client.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.matchers.Times
import org.mockserver.model.HttpError
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** What a foreign target puts on the wire, what it takes back, and what it never inherits. */
class SempodsForeignTargetContractTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val card get() = "$origin/people/bob/card"

  private fun answer(status: Int, body: String = "", vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status)
    if (body.isNotEmpty()) response.withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request()).respond(response)
  }

  private fun recorded() = server.retrieveRecordedRequests(request()).toList()

  @Test
  fun `a dereference is a GET asking for what the caller named, and nothing more`() {
    answer(200, "<urn:s> <urn:p> <urn:o> .")

    val read = SempodsForeignTarget(client).getText(card, "text/turtle, application/n-quads;q=0.9")

    val sent = recorded().single()
    assertEquals("GET", sent.method.value)
    assertEquals("/people/bob/card", sent.path.value)
    assertEquals(setOf("accept"), sent.headersBeyondTransport())
    assertEquals("text/turtle, application/n-quads;q=0.9", sent.getFirstHeader("Accept"))
    assertEquals(200, read.status)
    assertEquals("<urn:s> <urn:p> <urn:o> .", read.body)
    assertEquals(card, read.url)
  }

  @Test
  fun `a credential goes with the call that names it and with no other`() {
    answer(200, "ok")
    val foreign = SempodsForeignTarget(client)

    foreign.getText(card, "text/turtle", SempodsRequestAuth.bearer("t-1"))
    foreign.getText(card, "text/turtle")

    assertEquals(listOf("Bearer t-1", ""), recorded().map { it.getFirstHeader("Authorization") })
  }

  @Test
  fun `a pod session's credential never reaches a foreign target on the same client`() {
    answer(200, """{"dateModified":null}""")
    val alice = SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice"), SempodsRequestAuth.bearer("pod")), client)

    alice.metadata().dateModified()
    SempodsForeignTarget(client).getText(card, "text/turtle")

    val (pod, foreign) = recorded()
    assertEquals("Bearer pod", pod.getFirstHeader("Authorization"))
    assertFalse(foreign.containsHeader("Authorization"), "${foreign.headerList}")
  }

  @Test
  fun `a mechanism is applied once, as the first attempt, and never asked to recover`() {
    answer(401, "", "WWW-Authenticate" to "Bearer")
    val attempts = CopyOnWriteArrayList<Int>()
    val recovered = AtomicBoolean()
    val mechanism = object : SempodsRequestAuth {
      override fun apply(request: Request.Builder, attempt: Int) {
        attempts += attempt
        request.header("Authorization", "Bearer refused")
      }

      override fun recover(response: Response, attempt: Int): Boolean {
        recovered.set(true)
        return true
      }
    }

    val refused = SempodsForeignTarget(client).getText(card, "text/turtle", mechanism)

    assertEquals(401, refused.status)
    assertEquals("Bearer", refused.headers["WWW-Authenticate"])
    assertEquals(listOf(1), attempts)
    assertFalse(recovered.get(), "a refusal is an answer here, not a reason to ask again")
    assertEquals(1, recorded().size)
  }

  @Test
  fun `a mechanism may set headers and nothing else, and the refusal names no query`() {
    val moving = SempodsRequestAuth { request, _ -> request.url("$origin/elsewhere") }

    val refused = assertThrows<SempodsClientException> {
      SempodsForeignTarget(client).getText("$card?access_token=secret", "text/turtle", moving)
    }

    assertTrue(refused.message!!.contains("Authentication changed the target"), refused.message)
    assertFalse(refused.message!!.contains("secret"), refused.message)
    assertTrue(recorded().isEmpty())
  }

  @ParameterizedTest
  @ValueSource(ints = [302, 304, 400, 401, 403, 404, 410, 500, 503])
  fun `every status is an answer, with its headers and without a body`(status: Int) {
    answer(status, if (status == 304) "" else "a foreign error document", "X-Trace" to "t-7", "Location" to "$origin/elsewhere")

    val answered = SempodsForeignTarget(client).getText(card, "text/turtle")

    assertEquals(status, answered.status)
    assertEquals("t-7", answered.headers["X-Trace"])
    assertEquals("$origin/elsewhere", answered.headers["Location"])
    assertNull(answered.body)
    assertEquals(1, recorded().size, "nothing is followed without the opt-in")
  }

  @Test
  fun `a 503 asking to be repeated at once is the answer, not a reason to ask again`() {
    server.`when`(request(), Times.once()).respond(response().withStatusCode(503).withHeader("Retry-After", "0"))
    server.`when`(request()).respond(response().withStatusCode(200).withBody("a second answer nobody asked for"))

    assertEquals(503, SempodsForeignTarget(client).getText(card, "text/turtle").status)
    assertEquals(1, recorded().size)
  }

  @Test
  fun `a 408 is the answer, not a reason to ask again`() {
    server.`when`(request(), Times.once()).respond(response().withStatusCode(408))
    server.`when`(request()).respond(response().withStatusCode(200).withBody("a second answer nobody asked for"))

    assertEquals(408, SempodsForeignTarget(client).getText(card, "text/turtle").status)
    assertEquals(1, recorded().size)
  }

  @Test
  fun `a connection lost before any answer is sent once more, with the credential it had`() {
    server.`when`(request(), Times.once()).error(HttpError.error().withDropConnection(true))
    server.`when`(request()).respond(response().withStatusCode(200).withBody("the answer"))
    val applied = AtomicInteger()
    val counting = SempodsRequestAuth { request, _ ->
      applied.incrementAndGet()
      request.header("Authorization", "Bearer t-1")
    }

    val answered = SempodsForeignTarget(client).getText(card, "text/turtle", counting)

    assertEquals("the answer", answered.body)
    assertEquals(1, applied.get(), "the same request goes out again; its credential is not asked twice")
    assertEquals("Bearer t-1", recorded().last().getFirstHeader("Authorization"))
  }

  /** An output stream the caller owns, which records whether it was closed. */
  private class Owned : OutputStream() {
    val written = ByteArrayOutputStream()
    val closed = AtomicBoolean()

    override fun write(byte: Int) = written.write(byte)

    override fun write(bytes: ByteArray, offset: Int, length: Int) = written.write(bytes, offset, length)

    override fun close() {
      closed.set(true)
    }
  }

  @Test
  fun `text, bytes, a streamed read and a copy answer the same body`() {
    val body = "<https://bob.example/#me> <http://xmlns.com/foaf/0.1/name> \"Bob\" .\n"
    answer(200, body)
    val foreign = SempodsForeignTarget(client)
    val owned = Owned()

    assertEquals(body, foreign.getText(card, "application/n-quads").body)
    assertContentEquals(body.toByteArray(), foreign.getBytes(card, "application/n-quads").body)
    assertContentEquals(body.toByteArray(), foreign.getStream(card, "application/n-quads", { it.readBytes() }).body)
    assertEquals(body.length.toLong(), foreign.getTo(card, "application/n-quads", owned).body)

    assertEquals(body, owned.written.toString(Charsets.UTF_8))
    assertFalse(owned.closed.get(), "the stream is the caller's to close")
  }

  @Test
  fun `a streamed read is bounded by its reader alone, and a buffered one by the limit`() {
    answer(200, "x".repeat(65))
    val narrow = SempodsForeignTarget(client, maxRedirects = 0, maxBodyBytes = 64)

    assertThrows<SempodsDecodingException> { narrow.getText(card, "text/plain") }
    assertEquals(65, narrow.getStream(card, "text/plain", { it.readBytes() }).body?.size)
  }

  @Test
  fun `a fragment stays with the caller, and a query goes out as it stands`() {
    answer(200, "ok")

    val read = SempodsForeignTarget(client).getText("$card?lang=de&v=1#me", "text/turtle")

    assertEquals("$card?lang=de&v=1", read.url)
    val sent = recorded().single()
    assertEquals("/people/bob/card", sent.path.value)
    assertEquals(listOf("de"), sent.queryStringParameters.getValues("lang"))
  }

  @Test
  fun `an Authenticator on the client does not answer a foreign target's challenge`() {
    answer(401, "", "WWW-Authenticate" to "Bearer")
    val asked = AtomicInteger()

    sempodsClient {
      authenticator { _, refused ->
        asked.incrementAndGet()
        refused.request.newBuilder().header("Authorization", "Bearer the-pods-own").build()
      }
    }.closing { withAuthenticator ->
      assertEquals(401, SempodsForeignTarget(withAuthenticator).getText(card, "text/turtle").status)
    }

    assertEquals(0, asked.get())
    assertFalse(recorded().single().containsHeader("Authorization"))
  }

  @Test
  fun `a CookieJar on the client sends a foreign target nothing and keeps nothing from it`() {
    answer(200, "ok", "Set-Cookie" to "tracker=1; Path=/")
    val kept = CopyOnWriteArrayList<Cookie>()
    val jar = object : CookieJar {
      override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("session").value("pod").domain("localhost").build())

      override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        kept += cookies
      }
    }

    sempodsClient { cookieJar(jar) }.closing { withJar -> SempodsForeignTarget(withJar).getText(card, "text/turtle") }

    assertFalse(recorded().single().containsHeader("Cookie"))
    assertTrue(kept.isEmpty(), "$kept")
  }

  @Test
  fun `a client that follows redirects is refused before anything is sent`() {
    answer(200, "ok")
    val following: OkHttpClient = client.newBuilder().followRedirects(true).build()

    val refused = assertThrows<SempodsClientException> { SempodsForeignTarget(following).getText(card, "text/turtle") }

    assertTrue(refused.message!!.contains("follows redirects"), refused.message)
    assertTrue(recorded().isEmpty())
  }

  @Test
  fun `an interceptor that moves a credentialed call to another origin is refused before it is written`() {
    answer(200, "ok")
    val failover = sempodsClient {
      addInterceptor { chain ->
        val request = chain.request()
        chain.proceed(request.newBuilder().url(request.url.newBuilder().host("127.0.0.1").build()).build())
      }
    }

    failover.closing { client ->
      val refused = assertThrows<SempodsClientException> {
        SempodsForeignTarget(client).getText("$card?access_token=secret", "text/turtle", SempodsRequestAuth.bearer("t-1"))
      }
      assertTrue(refused.message!!.contains("127.0.0.1"), refused.message)
      assertFalse(refused.message!!.contains("secret"), refused.message)
      assertTrue(recorded().isEmpty(), "nothing was written, the credential least of all")

      // Without a credential there is nothing to take along, and where the request goes is the interceptor's say.
      assertEquals("ok", SempodsForeignTarget(client).getText(card, "text/turtle").body)
      assertEquals("127.0.0.1:${server.port}", recorded().single().getFirstHeader("Host"))
    }
  }

  @Test
  fun `a Host naming another authority is refused for a credentialed call`() {
    answer(200, "ok")

    sempodsClient { addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Host", "evil.example").build()) } }
      .closing { client ->
        assertThrows<SempodsClientException> {
          SempodsForeignTarget(client).getText(card, "text/turtle", SempodsRequestAuth.apiKeyHeader("X-Api-Key", "k-1"))
        }
      }

    assertTrue(recorded().isEmpty())
  }

  @Test
  fun `credential work holds the call's admission slot`() {
    answer(200, "ok")
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val slow = SempodsRequestAuth { request, _ ->
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
      request.header("Authorization", "Bearer fetched")
    }

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { narrow ->
      val first = Executors.newSingleThreadExecutor().let { pool ->
        pool.submit<SempodsResponse<String>> { SempodsForeignTarget(narrow).getText(card, "text/turtle", slow) }.also { pool.shutdown() }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))

      // The one slot is taken while the credential is still being fetched.
      assertThrows<SempodsClientException> { SempodsForeignTarget(narrow).getText(card, "text/turtle") }

      release.countDown()
      assertEquals(200, first.get(5, TimeUnit.SECONDS).status)
    }
  }

  @Test
  fun `a credential that arrives after the deadline gets no deadline of its own`() {
    answer(200, "ok")
    val late = SempodsRequestAuth { request, _ ->
      Thread.sleep(600)
      request.header("Authorization", "Bearer late")
    }

    sempodsClient { callTimeout(Duration.ofMillis(200)) }.closing { impatient ->
      assertThrows<IOException> { SempodsForeignTarget(impatient).getText(card, "text/turtle", late) }
    }

    assertTrue(recorded().isEmpty(), "the deadline ran out while the credential was fetched, so nothing was sent")
  }

  @Test
  fun `a reader's own failure reaches the caller as it is`() {
    answer(200, "ok")
    val mine = IOException("the disk is full")

    assertEquals(mine, assertThrows<IOException> { SempodsForeignTarget(client).getStream(card, "text/turtle", { throw mine }) })
  }
}
