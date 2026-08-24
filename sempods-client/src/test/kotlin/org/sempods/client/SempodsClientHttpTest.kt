package org.sempods.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import org.sempods.media.PodMediaSource
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.slf4j.event.Level
import java.net.URI
import java.time.Instant

/**
 * Wire-format coverage for the [SempodsClient] HTTP methods. Each test pins one transport contract
 * (URL shape, headers, body encoding, status-code mapping) against a mock server so regressions
 * surface here rather than on a live pod.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsClientHttpTest {

  private lateinit var mockServer: ClientAndServer
  private lateinit var baseUrl: URI
  private val client = SempodsClient()
  private val vf = SimpleValueFactory.getInstance()

  @BeforeAll
  fun startServer() {
    mockServer = ClientAndServer.startClientAndServer(
      Configuration.configuration().logLevel(Level.WARN),
    )
    baseUrl = URI("http://localhost:${mockServer.port}/alice/")
  }

  @AfterAll
  fun stopServer() {
    mockServer.stop()
  }

  @BeforeEach
  fun resetServer() {
    mockServer.reset()
  }

  @Test
  fun `getResource returns model on 200`() {
    val resourceUri = baseUrl.resolve("events/e1")
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes/public")
    mockServer
      .`when`(request().withMethod("GET").withPath("/alice/events/e1"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/n-quads"))
          .withBody("<$resourceUri> <https://schema.org/name> \"E1\" <$contextUri> ."),
      )

    val model = client.getResource(
      podBaseUrl = baseUrl,
      resourceUri = resourceUri,
      token = "t",
    )

    assertNotNull(model)
    assertEquals(1, model!!.size)
  }

  @Test
  fun `getResource returns null on 404`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/alice/events/missing"))
      .respond(response().withStatusCode(404))

    val model = client.getResource(
      podBaseUrl = baseUrl,
      resourceUri = baseUrl.resolve("events/missing"),
      token = "t",
    )

    assertNull(model)
  }

  @Test
  fun `deleteResource passes context as query parameter`() {
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes/public")
    mockServer
      .`when`(
        request()
          .withMethod("DELETE")
          .withPath("/alice/events/e1")
          .withQueryStringParameter("context", contextUri.toString()),
      )
      .respond(response().withStatusCode(204))

    client.deleteResource(
      podBaseUrl = baseUrl,
      resourceUri = baseUrl.resolve("events/e1"),
      contextUri = contextUri,
      token = "t",
    )
    // No exception thrown means wire match succeeded.
  }

  @Test
  fun `deleteResource treats 404 as idempotent success`() {
    mockServer
      .`when`(request().withMethod("DELETE").withPath("/alice/events/gone"))
      .respond(response().withStatusCode(404))

    client.deleteResource(
      podBaseUrl = baseUrl,
      resourceUri = baseUrl.resolve("events/gone"),
      contextUri = baseUrl.resolve("_system/contexts/apps/notes/public"),
      token = "t",
    )
  }

  @Test
  fun `createContext PUTs to context path and maps 201 to true, 200 to false`() {
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes")
    mockServer
      .`when`(request().withMethod("PUT").withPath("/alice/_system/contexts/apps/notes"))
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"context":"$contextUri","createdAt":"2026-05-20T00:00:00Z","public":false}"""),
      )

    val created = client.createContext(
      podBaseUrl = baseUrl,
      contextUri = contextUri,
      public = false,
      label = "notes root",
      description = null,
      token = "t",
    )
    assertTrue(created)

    mockServer.reset()
    mockServer
      .`when`(request().withMethod("PUT").withPath("/alice/_system/contexts/apps/notes"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"context":"$contextUri","createdAt":"2026-05-20T00:00:00Z","public":false}"""),
      )

    val recreated = client.createContext(
      podBaseUrl = baseUrl,
      contextUri = contextUri,
      public = false,
      label = null,
      description = null,
      token = "t",
    )
    assertFalse(recreated)
  }

  @Test
  fun `createContext rejects non-canonical context URIs`() {
    // Includes the pre-migration shape `{pod}/contexts/…`: it is no longer a canonical context
    // IRI, and silently accepting it would have this client manage a context the server does not
    // mint any more.
    listOf(
      URI("http://localhost/alice/_system/some/thing"),
      URI("http://localhost/alice/contexts/apps/notes"),
      URI("http://localhost/alice/contacts"),
    ).forEach { nonCanonical ->
      val ex = assertThrows<SempodsClientException> {
        client.createContext(
          podBaseUrl = baseUrl,
          contextUri = nonCanonical,
          public = false,
          label = null,
          description = null,
          token = "t",
        )
      }
      assertTrue(ex.message!!.contains("_system/contexts/"), "for $nonCanonical: ${ex.message}")
    }
  }

  @Test
  fun `deleteContext sends DELETE on the context path`() {
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes")
    mockServer
      .`when`(
        request()
          .withMethod("DELETE")
          .withPath("/alice/_system/contexts/apps/notes"),
      )
      .respond(response().withStatusCode(204))

    client.deleteContext(
      podBaseUrl = baseUrl,
      contextUri = contextUri,
      token = "t",
    )
  }

  @Test
  fun `listContexts GETs the listing and extracts context_iri values`() {
    val ctxA = baseUrl.resolve("_system/contexts/apps/notes/public")
    val ctxB = baseUrl.resolve("_system/contexts/apps/notes/private")
    mockServer
      .`when`(
        request()
          .withMethod("GET")
          .withPath("/alice/_system/contexts")
          .withHeader("Authorization", "Bearer t"),
      )
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody(
            """{"pod_base_url":"${baseUrl.toString().trimEnd('/')}","authenticated":true,"writable_contexts":["$ctxA"],"contexts":[{"context_iri":"$ctxA","permissions":["read","write"],"source":"grant","public":true,"createdAt":"2026-05-20T00:00:00Z"},{"context_iri":"$ctxB","permissions":["read","write"],"source":"grant","public":false,"createdAt":"2026-05-20T00:00:00Z"}]}""",
          ),
      )

    val contexts = client.listContexts(podBaseUrl = baseUrl, token = "t")

    assertEquals(listOf(ctxA, ctxB), contexts)
  }

  @Test
  fun `listContexts returns empty list when the listing has no contexts`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/alice/_system/contexts"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"pod_base_url":"${baseUrl.toString().trimEnd('/')}","authenticated":true,"writable_contexts":[],"contexts":[]}"""),
      )

    assertTrue(client.listContexts(podBaseUrl = baseUrl, token = "t").isEmpty())
  }

  @Test
  fun `fetchPodLastModifiedAt parses timestamp, null body and 404`() {
    mockServer
      .`when`(
        request()
          .withMethod("GET")
          .withPath("/alice/_system/meta/date-modified")
          .withHeader("Authorization", "Bearer t"),
      )
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"dateModified":"2026-05-20T10:15:30Z"}"""),
      )
    assertEquals(Instant.parse("2026-05-20T10:15:30Z"), client.fetchPodLastModifiedAt(baseUrl, "t"))

    mockServer.reset()
    mockServer
      .`when`(request().withMethod("GET").withPath("/alice/_system/meta/date-modified"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"dateModified":null}"""),
      )
    assertNull(client.fetchPodLastModifiedAt(baseUrl, "t"))

    mockServer.reset()
    mockServer
      .`when`(request().withMethod("GET").withPath("/alice/_system/meta/date-modified"))
      .respond(response().withStatusCode(404))
    assertNull(client.fetchPodLastModifiedAt(baseUrl, "t"))
  }

  @Test
  fun `sparqlAsk parses boolean response`() {
    mockServer
      .`when`(request().withMethod("POST").withPath("/alice/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody("""{"head":{},"boolean":true}"""),
      )

    val result = client.sparqlAsk(baseUrl, "ASK { ?s ?p ?o }", "t")
    assertTrue(result)
  }

  @Test
  fun `sparqlSelectColumn extracts bound values and skips unbound cells`() {
    mockServer
      .`when`(request().withMethod("POST").withPath("/alice/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody(
            """{"head":{"vars":["s"]},"results":{"bindings":[""" +
              """{"s":{"type":"uri","value":"https://example.org/a"}},""" +
              """{"other":{"type":"uri","value":"https://example.org/skip"}},""" +
              """{"s":{"type":"uri","value":"https://example.org/b"}}""" +
              """]}}""",
          ),
      )

    val values = client.sparqlSelectColumn(baseUrl, "SELECT ?s WHERE { ?s ?p ?o }", "s", "t")
    assertEquals(listOf("https://example.org/a", "https://example.org/b"), values)
  }

  @Test
  fun `sparqlConstruct returns parsed model`() {
    val resourceUri = baseUrl.resolve("events/e1")
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes")
    mockServer
      .`when`(request().withMethod("POST").withPath("/alice/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/n-quads"))
          .withBody("<$resourceUri> <https://schema.org/name> \"E1\" <$contextUri> ."),
      )

    val model = client.sparqlConstruct(baseUrl, "CONSTRUCT WHERE { ?s ?p ?o }", "t")
    assertEquals(1, model.size)
  }

  @Test
  fun `putResource serialises model as n-quads and includes context`() {
    val resourceUri = baseUrl.resolve("events/e1")
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes")
    mockServer
      .`when`(
        request()
          .withMethod("PUT")
          .withPath("/alice/events/e1")
          .withQueryStringParameter("context", contextUri.toString()),
      )
      .respond(response().withStatusCode(204))

    val model = LinkedHashModel().apply {
      add(
        vf.createIRI(resourceUri.toString()),
        vf.createIRI("https://schema.org/name"),
        vf.createLiteral("E1"),
        vf.createIRI(contextUri.toString()),
      )
    }

    client.putResource(
      podBaseUrl = baseUrl,
      resourceUri = resourceUri,
      contextUri = contextUri,
      model = model,
      token = "t",
    )
  }

  @Test
  fun `mintServiceToken sends client_credentials with url-encoded Basic auth`() {
    // Secret with ':' and '+' pins the RFC 6749 §2.3.1 form-encode-before-base64 contract.
    val clientId = "notes-app"
    val secret = "se:cret+x"
    val expectedBasic = java.util.Base64.getEncoder()
      .encodeToString("notes-app:se%3Acret%2Bx".toByteArray(Charsets.UTF_8))
    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/alice/_system/auth/token")
          .withHeader("Authorization", "Basic $expectedBasic")
          .withHeader("Content-Type", "application/x-www-form-urlencoded")
          .withBody("grant_type=client_credentials"),
      )
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody(
            """{"access_token":"tok-1","token_type":"Bearer","expires_in":600,""" +
              """"scope":"${baseUrl.resolve("_system/contexts/apps/notes")}#manage"}""",
          ),
      )

    val token = client.mintServiceToken(
      podBaseUrl = baseUrl,
      clientId = clientId,
      clientSecret = secret,
    )

    assertEquals("tok-1", token.accessToken)
    assertEquals(600, token.expiresInSeconds)
    assertEquals("${baseUrl.resolve("_system/contexts/apps/notes")}#manage", token.scope)
  }

  @Test
  fun `mintServiceToken surfaces 401 with status code`() {
    mockServer
      .`when`(request().withMethod("POST").withPath("/alice/_system/auth/token"))
      .respond(response().withStatusCode(401).withBody("invalid_client"))

    val exception = assertThrows<SempodsClientException> {
      client.mintServiceToken(podBaseUrl = baseUrl, clientId = "notes-app", clientSecret = "stale")
    }

    assertEquals(401, exception.statusCode)
    assertTrue(exception.message!!.contains("invalid_client"))
  }

  // ─── media ────────────────────────────────────────────────────────────────

  @Test
  fun `uploadMedia POSTs the bytes with the context, the filename and a definite length`() {
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes/public")
    val contentUrl = baseUrl.resolve("_system/media/abc123/content")
    mockServer
      .`when`(request().withMethod("POST").withPath("/alice/_system/media"))
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"id":"abc123","content_url":"$contentUrl"}"""),
      )

    var opened = 0
    val uploaded = client.uploadMedia(
      podBaseUrl = baseUrl,
      contextUri = contextUri,
      contentType = "image/png",
      body = { opened++; "PNGDATA".byteInputStream() },
      size = 7L,
      filename = "poster.png",
      token = "t",
    )

    assertEquals("abc123", uploaded.mediaId)
    // Read from the response, not rebuilt: the pod knows the address it is published at.
    assertEquals(contentUrl, uploaded.contentUrl)
    assertEquals(1, opened, "the body is opened exactly once per attempt")

    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/alice/_system/media"))
    assertEquals(1, recorded.size)
    assertEquals(contextUri.toString(), recorded[0].getFirstQueryStringParameter("context"))
    assertEquals("poster.png", recorded[0].getFirstQueryStringParameter("filename"))
    assertEquals("image/png", recorded[0].getFirstHeader("Content-Type"))
    assertEquals("7", recorded[0].getFirstHeader("Content-Length"))
    // Raw bytes, not `bodyAsString`: a binary content type makes the latter base64.
    assertEquals("PNGDATA", String(recorded[0].bodyAsRawBytes))
  }

  /**
   * Without a size the body is framed however the transport likes — the assertion is that the bytes
   * still arrive, since the pod enforces its limit while reading and never trusts a declared length.
   */
  @Test
  fun `uploadMedia sends the bytes when no size is known`() {
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes/public")
    mockServer
      .`when`(request().withMethod("POST").withPath("/alice/_system/media"))
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"id":"abc123","content_url":"${baseUrl.resolve("_system/media/abc123/content")}"}"""),
      )

    client.uploadMedia(
      podBaseUrl = baseUrl,
      contextUri = contextUri,
      contentType = "application/octet-stream",
      body = { "STREAMED".byteInputStream() },
      size = null,
      filename = null,
      token = "t",
    )

    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/alice/_system/media"))
    assertEquals(1, recorded.size)
    assertEquals("STREAMED", String(recorded[0].bodyAsRawBytes))
    assertTrue(
      recorded[0].getFirstQueryStringParameter("filename").isEmpty(),
      "no filename means no parameter, not an empty one",
    )
  }

  @Test
  fun `uploadMediaFromUrl sends the descriptor under its own media type`() {
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes/connectors/c1")
    mockServer
      .`when`(request().withMethod("POST").withPath("/alice/_system/media"))
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"id":"abc123","content_url":"${baseUrl.resolve("_system/media/abc123/content")}"}"""),
      )

    val uploaded = client.uploadMediaFromUrl(
      podBaseUrl = baseUrl,
      contextUri = contextUri,
      sourceUrl = URI("https://drive.example.org/file?signature=s3cr3t"),
      filename = "poster.png",
      token = "t",
    )

    assertEquals("abc123", uploaded.mediaId)

    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/alice/_system/media"))
    assertEquals(1, recorded.size)
    assertEquals(PodMediaSource.MEDIA_TYPE, recorded[0].getFirstHeader("Content-Type"))
    assertEquals(contextUri.toString(), recorded[0].getFirstQueryStringParameter("context"))
    // The filename travels in the descriptor here, not as a query parameter — and neither does the
    // source URL, which is routinely a credential and must stay out of access logs.
    assertTrue(recorded[0].getFirstQueryStringParameter("filename").isEmpty())
    val descriptor = ObjectMapper().readTree(recorded[0].bodyAsString)
    assertEquals("https://drive.example.org/file?signature=s3cr3t", descriptor.path("source_url").asText())
    assertEquals("poster.png", descriptor.path("filename").asText())
  }

  @Test
  fun `an upload response without a content_url is a failure, not a half-filled result`() {
    mockServer
      .`when`(request().withMethod("POST").withPath("/alice/_system/media"))
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"id":"abc123"}"""),
      )

    val ex = assertThrows<SempodsClientException> {
      client.uploadMedia(
        podBaseUrl = baseUrl,
        contextUri = baseUrl.resolve("_system/contexts/apps/notes/public"),
        contentType = "image/png",
        body = { "PNGDATA".byteInputStream() },
        size = null,
        filename = null,
        token = "t",
      )
    }

    assertTrue(ex.message!!.contains("content_url"), ex.message!!)
  }

  @Test
  fun `assignMedia PUTs the media URL with the target context`() {
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes/public")
    mockServer
      .`when`(request().withMethod("PUT").withPath("/alice/_system/media/abc123"))
      .respond(response().withStatusCode(204))

    client.assignMedia(podBaseUrl = baseUrl, mediaId = "abc123", contextUri = contextUri, token = "t")

    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/alice/_system/media/abc123"))
    assertEquals(1, recorded.size)
    assertEquals(contextUri.toString(), recorded[0].getFirstQueryStringParameter("context"))
  }

  /**
   * A `404` here is the pod refusing — the caller may not read the media — so it must not be folded
   * into success the way an idempotent DELETE folds one.
   */
  @Test
  fun `assignMedia surfaces a 404 rather than swallowing it`() {
    mockServer
      .`when`(request().withMethod("PUT").withPath("/alice/_system/media/abc123"))
      .respond(response().withStatusCode(404))

    val ex = assertThrows<SempodsClientException> {
      client.assignMedia(
        podBaseUrl = baseUrl,
        mediaId = "abc123",
        contextUri = baseUrl.resolve("_system/contexts/apps/notes/public"),
        token = "t",
      )
    }

    assertEquals(404, ex.statusCode)
  }

  @Test
  fun `unassignMedia DELETEs the media URL with the context`() {
    val contextUri = baseUrl.resolve("_system/contexts/apps/notes/public")
    mockServer
      .`when`(request().withMethod("DELETE").withPath("/alice/_system/media/abc123"))
      .respond(response().withStatusCode(204))

    client.unassignMedia(podBaseUrl = baseUrl, mediaId = "abc123", contextUri = contextUri, token = "t")

    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/alice/_system/media/abc123"))
    assertEquals(1, recorded.size)
    assertEquals(contextUri.toString(), recorded[0].getFirstQueryStringParameter("context"))
  }

  /**
   * The route answers `204` even for a media the pod never held, so a `404` cannot mean "nothing to
   * remove" — it means the deployment serves no media surface at all. Treating it as success would
   * hide assignments nothing can reach.
   */
  @Test
  fun `unassignMedia surfaces a 404 instead of treating it as removed`() {
    mockServer
      .`when`(request().withMethod("DELETE").withPath("/alice/_system/media/abc123"))
      .respond(response().withStatusCode(404))

    val ex = assertThrows<SempodsClientException> {
      client.unassignMedia(
        podBaseUrl = baseUrl,
        mediaId = "abc123",
        contextUri = baseUrl.resolve("_system/contexts/apps/notes/public"),
        token = "t",
      )
    }

    assertEquals(404, ex.statusCode)
  }

  /** A caller-supplied id reaches the URL as one segment, whatever it contains. */
  @Test
  fun `a media id is encoded as a single path segment`() {
    mockServer
      .`when`(request().withMethod("DELETE").withPath("/alice/_system/media/..%2F..%2Fpods"))
      .respond(response().withStatusCode(204))

    client.unassignMedia(
      podBaseUrl = baseUrl,
      mediaId = "../../pods",
      contextUri = baseUrl.resolve("_system/contexts/apps/notes/public"),
      token = "t",
    )
  }

  @Test
  fun `a bound trace travels with the request`() {
    // The application-to-pod hop is the one that used to lose the trace entirely: this client
    // speaks through the JDK HttpClient, which no server-side request filter can reach.
    mockServer
      .`when`(request().withMethod("GET").withPath("/alice/events/traced"))
      .respond(response().withStatusCode(404))

    val bound = TraceContext.random()
    TraceContextHolder.with(bound) {
      client.getResource(podBaseUrl = baseUrl, resourceUri = baseUrl.resolve("events/traced"), token = "t")
    }

    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/alice/events/traced"))
    assertEquals(1, recorded.size)
    val sent = TraceContext.parse(recorded[0].getFirstHeader(TraceContext.TRACEPARENT))
    assertNotNull(sent)
    assertEquals(bound.traceId, sent!!.traceId, "the journey must survive the hop")
    assertNotEquals(bound.spanId, sent.spanId, "the hop is a span of its own, not the caller's")
  }

  @Test
  fun `without a bound trace nothing is sent`() {
    // The `tools/` mains run outside any request; inventing a trace there would be noise.
    mockServer
      .`when`(request().withMethod("GET").withPath("/alice/events/untraced"))
      .respond(response().withStatusCode(404))

    client.getResource(podBaseUrl = baseUrl, resourceUri = baseUrl.resolve("events/untraced"), token = "t")

    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/alice/events/untraced"))
    assertEquals(1, recorded.size)
    assertTrue(recorded[0].getFirstHeader(TraceContext.TRACEPARENT).isEmpty())
  }
}
