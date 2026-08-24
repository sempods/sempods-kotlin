package org.sempods.pods.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The SSRF guard as a whole, over a real socket.
 *
 * **Its own class, and that is a requirement rather than tidiness**: the roadmap asks that the part
 * with security weight not share a file with endpoint tests, so a change to either is reviewed for
 * what it is. [MediaSourceAddressGuardTest] covers the address table; this one covers everything the
 * table cannot see — the redirect chain, what happens to the body while it streams, and what is left
 * on disk afterwards.
 *
 * The source is a `com.sun.net.httpserver.HttpServer` on loopback: in the JDK, so no dependency, and
 * local, so no test here ever reaches the network. That means the fetcher runs under
 * [LoopbackOnlyAddressGuard] — the inverse of production, which is exactly what makes the
 * redirect-to-a-forbidden-address cases meaningful: `169.254.169.254` is rejected here for the same
 * structural reason it is rejected in production, namely that it is not the one address this policy
 * permits.
 */
/**
 * Serialised against [org.sempods.api.pod.system.media.PodMediaEndpointHttpTest], which fetches
 * through the same code path in the same JVM and so writes buffers into the same directory this
 * suite counts. Rung 2 of `docs/testing.md` §"When a test is not safe" — the two classes wait for
 * each other and for nothing else.
 */
@ResourceLock("sempods-media-source-buffers")
class MediaSourceFetcherTest {

  private val config = PodMediaConfig(
    maxUploadBytes = MAX_BYTES,
    // Short enough that the stalling source fails inside a test's patience; the request timeout
    // stays well above it so a read-timeout test cannot pass for the wrong reason.
    sourceReadTimeout = Duration.ofMillis(500),
    sourceRequestTimeout = Duration.ofSeconds(20),
  )

  private val fetcher = MediaSourceFetcher(httpClient, config, LoopbackOnlyAddressGuard)

  private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

  // ─── the happy path ────────────────────────────────────────────────────────

  @Test
  fun `a source is downloaded with the type it claims`() {
    val fetched = fetcher.fetch(url("/image.png"))

    try {
      assertEquals("image/png", fetched.contentType)
      assertEquals(PNG_BYTES.toList(), Files.readAllBytes(fetched.body).toList())
    } finally {
      Files.deleteIfExists(fetched.body)
    }
  }

  @Test
  fun `a relative redirect is resolved against the hop it came from`() {
    val fetched = fetcher.fetch(url("/relative-redirect"))

    try {
      assertEquals("image/png", fetched.contentType)
      assertEquals(PNG_BYTES.size, Files.readAllBytes(fetched.body).size)
    } finally {
      Files.deleteIfExists(fetched.body)
    }
  }

  // ─── the URL itself ────────────────────────────────────────────────────────

  @Test
  fun `only http and https are fetched`() {
    // Rejected before any I/O: `file:` would read the pod server's own disk, and every other scheme
    // is a protocol this class has no business speaking.
    assertFailsWith<MediaSourceException> { fetcher.fetch("file:///etc/passwd") }
    assertFailsWith<MediaSourceException> { fetcher.fetch("gopher://example.test/1") }
    assertFailsWith<MediaSourceException> { fetcher.fetch("/not/absolute") }
  }

  @Test
  fun `credentials in the source url are refused rather than used`() {
    // Stripping them silently would have the pod server authenticate somewhere on the caller's
    // behalf; carrying them would do it openly. Neither is this route's job.
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch("http://user:secret@127.0.0.1/image.png") }

    assertContains(e.message.orEmpty(), "credentials")
  }

  @Test
  fun `a host that does not resolve fails without reaching the network`() {
    assertFailsWith<MediaSourceException> { fetcher.fetch("http://no-such-host.invalid/image.png") }
  }

  // ─── the guard, per hop ────────────────────────────────────────────────────

  @Test
  fun `a forbidden address is refused outright`() {
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch("http://169.254.169.254/latest/meta-data/") }

    assertContains(e.message.orEmpty(), "169.254.169.254")
  }

  @Test
  fun `a redirect to a forbidden address is refused at the second hop`() {
    // The cheapest bypass there is: the first hop is a host the guard allows, and the answer points
    // somewhere it does not. Validating only what the caller wrote down would let this through.
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch(url("/redirect-to-metadata")) }

    assertContains(e.message.orEmpty(), "169.254.169.254")
  }

  @Test
  fun `a redirect to another scheme is refused at the second hop`() {
    assertFailsWith<MediaSourceException> { fetcher.fetch(url("/redirect-to-file")) }
  }

  @Test
  fun `a redirect chain longer than the limit is refused`() {
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch(url("/redirect-loop")) }

    assertContains(e.message.orEmpty(), "more than ${MediaSourceFetcher.MAX_REDIRECTS} redirects")
  }

  @Test
  fun `a redirect without a Location is refused`() {
    assertFailsWith<MediaSourceException> { fetcher.fetch(url("/redirect-nowhere")) }
  }

  // ─── what comes back ───────────────────────────────────────────────────────

  @Test
  fun `a source that declares no length and sends more than the cap is aborted`() {
    // The response is chunked, so there is no `Content-Length` to consult even if this class wanted
    // to — which is the point. Deciding in advance from a `Content-Length` header means trusting a
    // value the source controls; here the count runs while the bytes arrive.
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch(url("/oversized-chunked")) }

    assertTrue(e.tooLarge, "the size refusal is the one that gets its own status")
  }

  @Test
  fun `a source that declares an oversized length is aborted the same way`() {
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch(url("/oversized-declared")) }

    assertTrue(e.tooLarge)
  }

  @Test
  fun `a source without a Content-Type is refused rather than guessed`() {
    // `application/octet-stream` would be a lie the registry then records and the content route then
    // serves, with nothing to say later that it was invented.
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch(url("/no-type")) }

    assertContains(e.message.orEmpty(), "Content-Type")
  }

  @Test
  fun `a source whose Content-Type cannot be parsed is refused`() {
    // The trap this closes is the distance between the two events: an unservable type is recorded at
    // *upload* and only breaks at *read*, so the 201 comes back clean and every later GET on that
    // assignment is a 500. Jersey stops a malformed type on the raw upload route before the endpoint
    // runs; nothing stops this one, because it arrives from a server the caller chose.
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch(url("/broken-type")) }

    assertContains(e.message.orEmpty(), "Content-Type")
  }

  @Test
  fun `a source answering an error status is refused`() {
    assertFailsWith<MediaSourceException> { fetcher.fetch(url("/not-found")) }
  }

  @Test
  fun `an empty body is refused`() {
    // `PodMediaFacade.store` refuses a zero-byte media anyway; catching it here keeps the failure a
    // 400 about the source rather than a 500 about an invariant.
    assertFailsWith<MediaSourceException> { fetcher.fetch(url("/empty")) }
  }

  @Test
  fun `a source that stalls after the headers hits the read timeout`() {
    val e = assertFailsWith<MediaSourceException> { fetcher.fetch(url("/stall")) }

    assertContains(e.message.orEmpty(), "could not fetch")
  }

  // ─── the temporary file ────────────────────────────────────────────────────

  @Test
  fun `every refusal leaves nothing behind`() {
    // The buffer is created before the first request, so each of these failures passes through the
    // cleanup path — and a fetcher that leaked one file per rejected URL would hand anyone holding
    // write on a context a way to fill the disk.
    val before = sourceBuffers()

    listOf(
      "file:///etc/passwd",
      "http://169.254.169.254/",
      url("/redirect-to-metadata"),
      url("/redirect-loop"),
      url("/oversized-chunked"),
      url("/no-type"),
      url("/broken-type"),
      url("/not-found"),
    ).forEach { source ->
      assertFailsWith<MediaSourceException>("expected $source to be refused") { fetcher.fetch(source) }
    }

    assertEquals(before, sourceBuffers())
  }

  /**
   * The fetcher's buffers currently lying in the JVM's temp directory, by name.
   *
   * This reads the process-wide temp directory, so it is not parallel-safe on its own: a buffer
   * `PodMediaEndpointHttpTest` creates or removes between the two calls in
   * `every refusal leaves nothing behind` makes that test fail with a diff naming neither culprit.
   * The `@ResourceLock` on both classes is what holds it today — and holds it only by keeping them
   * apart, so a third caller of this code path has to take the same lock.
   *
   * TODO: give the fetcher a configurable buffer directory and point this suite at a temporary one,
   *  the way the media store tests already do. That retires the lock rather than adding to it. A
   *  category for the maintainer's internal roadmap: shared filesystem locations, alongside
   *  the process-wide caches it already lists.
   */
  private fun sourceBuffers(): Set<String> =
    Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "sempods-media-source-*")
      .use { entries -> entries.mapTo(HashSet()) { it.fileName.toString() } }

  companion object {

    /** Small on purpose, so an "oversized" body is a string literal rather than an allocation. */
    private const val MAX_BYTES = 2048L

    private val PNG_BYTES = ByteArray(512) { (it % 251).toByte() }

    private lateinit var httpClient: OkHttpClient
    private lateinit var server: HttpServer

    @BeforeAll
    @JvmStatic
    fun startSource() {
      httpClient = OkHttpClient()
      server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
      server.executor = Executors.newCachedThreadPool()

      server.handle("/image.png") { exchange ->
        exchange.responseHeaders.add("Content-Type", "image/png")
        exchange.sendResponseHeaders(200, PNG_BYTES.size.toLong())
        exchange.responseBody.write(PNG_BYTES)
      }

      server.handle("/no-type") { exchange ->
        // `com.sun.net.httpserver` adds no Content-Type of its own, which is what this needs.
        exchange.sendResponseHeaders(200, PNG_BYTES.size.toLong())
        exchange.responseBody.write(PNG_BYTES)
      }

      server.handle("/empty") { exchange ->
        exchange.responseHeaders.add("Content-Type", "image/png")
        exchange.sendResponseHeaders(200, -1)
      }

      server.handle("/broken-type") { exchange ->
        exchange.responseHeaders.add("Content-Type", "not-a-media-type")
        exchange.sendResponseHeaders(200, PNG_BYTES.size.toLong())
        exchange.responseBody.write(PNG_BYTES)
      }

      server.handle("/not-found") { exchange -> exchange.sendResponseHeaders(404, -1) }

      // Chunked: `0` means "unknown length", so the response carries no Content-Length at all.
      server.handle("/oversized-chunked") { exchange ->
        exchange.responseHeaders.add("Content-Type", "image/png")
        exchange.sendResponseHeaders(200, 0)
        val chunk = ByteArray(512)
        repeat(((MAX_BYTES / chunk.size) * 3).toInt()) { exchange.responseBody.write(chunk) }
      }

      server.handle("/oversized-declared") { exchange ->
        val body = ByteArray((MAX_BYTES * 2).toInt())
        exchange.responseHeaders.add("Content-Type", "image/png")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
      }

      server.handle("/stall") { exchange ->
        exchange.responseHeaders.add("Content-Type", "image/png")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.write(ByteArray(16))
        exchange.responseBody.flush()
        Thread.sleep(3_000)
      }

      server.handle("/relative-redirect") { exchange -> exchange.redirect("/image.png") }
      server.handle("/redirect-to-metadata") { exchange ->
        exchange.redirect("http://169.254.169.254/latest/meta-data/")
      }
      server.handle("/redirect-to-file") { exchange -> exchange.redirect("file:///etc/passwd") }
      server.handle("/redirect-loop") { exchange -> exchange.redirect("/redirect-loop") }
      server.handle("/redirect-nowhere") { exchange -> exchange.sendResponseHeaders(302, -1) }

      server.start()
    }

    @AfterAll
    @JvmStatic
    fun stopSource() {
      server.stop(0)
      // No `close()` to call, and nothing to shut down: a blocking-only OkHttp client starts no
      // thread of its own. Evicting the pool just returns the sockets this class opened.
      httpClient.connectionPool.evictAll()
    }

    private fun HttpServer.handle(path: String, handler: (HttpExchange) -> Unit) {
      createContext(path) { exchange ->
        try {
          handler(exchange)
        } finally {
          exchange.close()
        }
      }
    }

    private fun HttpExchange.redirect(location: String) {
      responseHeaders.add("Location", location)
      sendResponseHeaders(302, -1)
    }
  }
}
