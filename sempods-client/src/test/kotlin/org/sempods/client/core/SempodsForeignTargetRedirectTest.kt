package org.sempods.client.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What following redirects does, and what it refuses to carry along.
 *
 * One server answers two origins: `localhost` and `127.0.0.1`, on the same port, which is enough for
 * a chain to leave the origin the caller named and come back to it.
 */
class SempodsForeignTargetRedirectTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  /** The origin a caller names, and another one on the same server. */
  private val other get() = "http://127.0.0.1:${server.port}"

  private fun redirect(path: String, status: Int, vararg locations: String) {
    val response = response().withStatusCode(status).withBody("a redirect's own body")
    if (locations.isNotEmpty()) response.withHeader("Location", *locations)
    server.`when`(request().withPath(path)).respond(response)
  }

  private fun end(path: String, body: String) {
    server.`when`(request().withPath(path)).respond(response().withStatusCode(200).withBody(body))
  }

  private fun asked(): List<HttpRequest> = server.retrieveRecordedRequests(request()).toList()

  private val following get() = SempodsForeignTarget(client).followingRedirects(5)

  @Test
  fun `without the opt-in a redirect is the answer`() {
    redirect("/id/42", 303, "/doc/42")

    val answered = SempodsForeignTarget(client).getText("$origin/id/42", "text/turtle")

    assertEquals(303, answered.status)
    assertEquals("/doc/42", answered.headers["Location"])
    assertEquals("$origin/id/42", answered.url)
    assertEquals(listOf("/id/42"), asked().map { it.path.value })
  }

  @Test
  fun `a following target answers from the end of the chain, and says which URL that was`() {
    redirect("/id/42", 302, "/moved/42")
    redirect("/moved/42", 303, "$origin/doc/42")
    end("/doc/42", "the document")

    val answered = following.getText("$origin/id/42", "text/turtle")

    assertEquals(200, answered.status)
    assertEquals("the document", answered.body)
    assertEquals("$origin/doc/42", answered.url)
    assertEquals(listOf("/id/42", "/moved/42", "/doc/42"), asked().map { it.path.value })
  }

  @ParameterizedTest
  @ValueSource(ints = [301, 302, 303, 307, 308])
  fun `every redirect that names a new target is followed, as a GET`(status: Int) {
    redirect("/a", status, "/b")
    end("/b", "b")

    assertEquals("b", following.getText("$origin/a", "text/turtle").body)
    assertEquals(listOf("GET", "GET"), asked().map { it.method.value })
  }

  @ParameterizedTest
  @ValueSource(ints = [300, 304, 305, 306])
  fun `a status that is not one to follow is the answer`(status: Int) {
    redirect("/a", status, "/b")
    end("/b", "b")

    assertEquals(status, following.getText("$origin/a", "text/turtle").status)
    assertEquals(1, asked().size)
  }

  @Test
  fun `a Location is resolved against the URL that answered, relative or scheme-relative`() {
    redirect("/dir/a", 302, "../b")
    redirect("/b", 302, "//127.0.0.1:${server.port}/c")
    end("/c", "c")

    val answered = following.getText("$origin/dir/a", "text/turtle")

    assertEquals("$other/c", answered.url)
    assertEquals(listOf("/dir/a", "/b", "/c"), asked().map { it.path.value })
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "file:///etc/passwd", "urn:x:y", "http://bob:hunter2@localhost:{port}/b", "http://two/, http://locations/"])
  fun `a redirect this will not take is the answer`(location: String) {
    val locations = when {
      location.isEmpty() -> emptyArray()
      ", " in location -> location.split(", ").toTypedArray()
      else -> arrayOf(location.replace("{port}", "${server.port}"))
    }
    redirect("/a", 302, *locations)
    end("/b", "b")

    assertEquals(302, following.getText("$origin/a", "text/turtle").status)
    assertEquals(1, asked().size)
  }

  /** A client whose own interceptor sends one exact path somewhere else, as a failover or a mirror would. */
  private fun moving(from: String, to: String) = sempodsClient {
    addInterceptor { chain ->
      val request = chain.request()
      val moved = if (request.url.encodedPath == from) request.newBuilder().url(request.url.newBuilder().encodedPath(to).build()).build() else request
      chain.proceed(moved)
    }
  }

  @Test
  fun `a Location is resolved against the URL that answered, after an interceptor moved the request`() {
    redirect("/mirror/42", 303, "doc")
    end("/mirror/doc", "the mirror's document")
    end("/id/doc", "a document the mirror never pointed to")

    moving(from = "/id/42", to = "/mirror/42").closing { client ->
      val answered = SempodsForeignTarget(client).followingRedirects(5).getText("$origin/id/42", "text/turtle")

      assertEquals("the mirror's document", answered.body)
      assertEquals("$origin/mirror/doc", answered.url)
    }
    assertEquals(listOf("/mirror/42", "/mirror/doc"), asked().map { it.path.value })
  }

  @Test
  fun `a chain that points back to the URL an interceptor moved is a loop`() {
    redirect("/mirror/1", 302, "/id/1")

    moving(from = "/id/1", to = "/mirror/1").closing { client ->
      assertEquals(302, SempodsForeignTarget(client).followingRedirects(20).getText("$origin/id/1", "text/turtle").status)
    }
    assertEquals(1, asked().size, "the URL it points to was asked already, under the name the caller gave it")
  }

  @Test
  fun `the budget ends the chain at its last redirect`() {
    (1..4).forEach { redirect("/r$it", 302, "/r${it + 1}") }
    end("/r5", "never reached")

    val answered = SempodsForeignTarget(client).followingRedirects(2).getText("$origin/r1", "text/turtle")

    assertEquals(302, answered.status)
    assertEquals("$origin/r3", answered.url)
    assertEquals(listOf("/r1", "/r2", "/r3"), asked().map { it.path.value })
  }

  @Test
  fun `a loop ends at the first URL this call would ask twice`() {
    redirect("/l1", 302, "/l2")
    redirect("/l2", 302, "/l1")

    val answered = SempodsForeignTarget(client).followingRedirects(20).getText("$origin/l1", "text/turtle")

    assertEquals(302, answered.status)
    assertEquals("$origin/l2", answered.url)
    assertEquals(2, asked().size)
  }

  @Test
  fun `the credential follows a redirect that stays within the origin`() {
    redirect("/a", 302, "/b")
    end("/b", "b")

    following.getText("$origin/a", "text/turtle", SempodsRequestAuth.bearer("t-1"))

    assertEquals(listOf("Bearer t-1", "Bearer t-1"), asked().map { it.getFirstHeader("Authorization") })
  }

  @Test
  fun `the credential is dropped where the chain leaves the origin, and stays dropped when it comes back`() {
    redirect("/a", 302, "$other/b")
    redirect("/b", 302, "$origin/c")
    end("/c", "c")
    val credential = SempodsRequestAuth.bearer("t-1").andThen(SempodsRequestAuth.apiKeyHeader("X-Api-Key", "k-1"))

    val answered = following.getText("$origin/a", "text/turtle", credential)

    assertEquals("c", answered.body)
    val (named, elsewhere, back) = asked()
    assertEquals("Bearer t-1", named.getFirstHeader("Authorization"))
    assertEquals("k-1", named.getFirstHeader("X-Api-Key"))
    listOf(elsewhere, back).forEach { hop ->
      assertEquals(setOf("accept"), hop.headersBeyondTransport(), "a hop after the origin was left carries no credential")
    }
  }

  @Test
  fun `the accept the caller named travels with every hop`() {
    redirect("/a", 307, "/b")
    end("/b", "b")

    following.getText("$origin/a", "text/turtle;q=1, application/ld+json;q=0.5")

    assertEquals(
      listOf("text/turtle;q=1, application/ld+json;q=0.5", "text/turtle;q=1, application/ld+json;q=0.5"),
      asked().map { it.getFirstHeader("Accept") },
    )
  }

  @Test
  fun `each hop gives its admission slot back before the next one takes it`() {
    redirect("/a", 302, "/b")
    redirect("/b", 302, "/c")
    end("/c", "c")

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { narrow ->
      assertEquals("c", SempodsForeignTarget(narrow).followingRedirects(5).getText("$origin/a", "text/turtle").body)
    }
  }

  @Test
  fun `a streamed read sees the final body alone`() {
    redirect("/a", 302, "/b")
    end("/b", "the final body")
    val reads = AtomicInteger()

    val answered = following.getStream("$origin/a", "text/turtle", { body ->
      reads.incrementAndGet()
      String(body.readBytes())
    })

    assertEquals("the final body", answered.body)
    assertEquals(1, reads.get())
  }

  @Test
  fun `a redirect's own body never reaches the caller`() {
    redirect("/a", 302, "/gone")
    server.`when`(request().withPath("/gone")).respond(response().withStatusCode(404).withBody("nope"))

    val answered = following.getText("$origin/a", "text/turtle")

    assertEquals(404, answered.status)
    assertNull(answered.body)
  }
}
