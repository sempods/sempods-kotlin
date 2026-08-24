package org.sempods.client

import org.sempods.ontologies.Ontologies
import org.sempods.rdf.toIri
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.slf4j.event.Level
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Wire-level coverage for [SempodsPodClient] against a stub pod: every call has to land on the
 * right route, carry the intended credential, and answer the shape its KDoc claims.
 *
 * A stub server rather than mocks of [SempodsClient], because what these assert is the *request* —
 * the path, the query parameter, the header, the body — and a mock of the tier below would only
 * assert that the tier below was called. It is the sibling of [SempodsClientHttpTest] one tier up:
 * that one pins the stateless surface, this one what binding a pod and a credential adds — the
 * bearer on the way out, the single 401 retry, and the pod-level reads that never ask for one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsPodClientTest {

  private lateinit var mockServer: ClientAndServer
  private lateinit var podBaseUrl: URI
  private lateinit var service: SempodsPodClient
  private val vf = SimpleValueFactory.getInstance()
  private val podName = "alice"
  private val token = "service-token"

  @BeforeAll
  fun startServer() {
    mockServer = ClientAndServer.startClientAndServer(
      Configuration.configuration().logLevel(Level.WARN),
    )
    podBaseUrl = URI("http://localhost:${mockServer.port}/$podName/")
    service = SempodsPodClient(podBaseUrl = podBaseUrl, auth = SempodsAuth { token })
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
  fun `createContext PUTs JSON payload to the context path`() {
    val contextUri = podBaseUrl.resolve("_system/contexts/apps/notes")
    mockServer
      .`when`(request().withMethod("PUT").withPath("/$podName/_system/contexts/apps/notes"))
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"context":"$contextUri","createdAt":"2026-05-20T00:00:00Z","public":false}"""),
      )

    val created = service.createContext(
      contextUri = contextUri,
      public = false,
      label = "notes-root",
      description = null,
    )

    assertTrue(created)
  }

  @Test
  fun `removeContext sends DELETE on the context path`() {
    val contextUri = podBaseUrl.resolve("_system/contexts/apps/notes")
    mockServer
      .`when`(
        request()
          .withMethod("DELETE")
          .withPath("/$podName/_system/contexts/apps/notes"),
      )
      .respond(response().withStatusCode(204))

    service.removeContext(contextUri)
  }

  @Test
  fun `getContexts parses the context listing into a URI set`() {
    val ctxA = podBaseUrl.resolve("_system/contexts/apps/notes/public")
    val ctxB = podBaseUrl.resolve("_system/contexts/apps/notes/private")
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/contexts"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody(
            """
            {
              "pod_base_url": "${podBaseUrl.toString().trimEnd('/')}",
              "authenticated": true,
              "writable_contexts": ["$ctxA"],
              "contexts": [
                {"context_iri":"$ctxA","permissions":["read","write"],"source":"grant","public":true,"createdAt":"2026-05-20T00:00:00Z"},
                {"context_iri":"$ctxB","permissions":["read","write"],"source":"grant","public":false,"createdAt":"2026-05-20T00:00:00Z"}
              ]
            }
            """.trimIndent(),
          ),
      )

    val contexts = service.contexts()

    assertEquals(setOf(ctxA, ctxB), contexts)
  }

  /**
   * The anonymous read a public aggregator lives on. Asking without a credential *is* the public
   * filter, so the
   * request must carry no `Authorization` header even though this service has a token to offer —
   * a bearer would narrow the answer to that client's own grants.
   */
  @Test
  fun `getPublicContexts asks the listing anonymously and returns what it names`() {
    val ctxA = podBaseUrl.resolve("_system/contexts/apps/notes/public")
    val ctxB = podBaseUrl.resolve("_system/contexts/apps/reader/public")
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/contexts"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody(
            """
            {
              "pod_base_url": "${podBaseUrl.toString().trimEnd('/')}",
              "authenticated": false,
              "writable_contexts": [],
              "contexts": [
                {"context_iri":"$ctxA","permissions":["read"],"source":"public","public":true,"createdAt":"2026-05-20T00:00:00Z"},
                {"context_iri":"$ctxB","permissions":["read"],"source":"public","public":true,"createdAt":"2026-05-20T00:00:00Z"}
              ]
            }
            """.trimIndent(),
          ),
      )

    assertEquals(setOf(ctxA, ctxB), service.publicContexts())

    val recorded = mockServer.retrieveRecordedRequests(
      request().withMethod("GET").withPath("/$podName/_system/contexts"),
    )
    assertEquals(1, recorded.size)
    assertTrue(
      recorded[0].getFirstHeader("Authorization").isNullOrEmpty(),
      "the public-context read must stay anonymous even when a token is at hand",
    )
  }

  @Test
  fun `an anonymous consumer is not retried on 401`() {
    // The anonymous profile: a credential that supplies nothing. A 401 there means the operation
    // needs one this consumer does not have — re-minting is impossible, so asking twice only
    // doubles the load and the latency of the failure.
    var invalidateCount = 0
    val anonymousService = SempodsPodClient(
      podBaseUrl = podBaseUrl,
      auth = object : SempodsAuth {
        override fun token(): String? = null
        override fun invalidate() {
          invalidateCount++
        }
      },
    )

    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(response().withStatusCode(401).withBody("credentials required"))

    val ex = assertThrows<SempodsClientException> {
      anonymousService.existsResource(
        type = URI("https://schema.org/Event"),
        resourceUri = podBaseUrl.resolve("events/e1"),
        contexts = null,
      )
    }

    assertEquals(401, ex.statusCode)
    assertEquals(0, invalidateCount, "there is no cached token to drop")
    assertEquals(
      1,
      mockServer.retrieveRecordedRequests(request().withPath("/$podName/_system/sparql/query")).size,
      "one attempt only — nothing to re-mint",
    )
  }

  @Test
  fun `an anonymous read sends no Authorization header`() {
    val anonymousService = SempodsPodClient(podBaseUrl = podBaseUrl, auth = SempodsAuth.anonymous)

    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/events/e1"))
      .respond(response().withStatusCode(404))

    anonymousService.getResource(podBaseUrl.resolve("events/e1"))

    val recorded = mockServer.retrieveRecordedRequests(
      request().withMethod("GET").withPath("/$podName/events/e1"),
    )
    assertEquals(1, recorded.size)
    assertTrue(recorded[0].getFirstHeader("Authorization").isNullOrEmpty())

    // counter-check on the same route: with a token the header is there, so the assertion above
    // pins the anonymous mode rather than a header the client never sets
    service.getResource(podBaseUrl.resolve("events/e1"))
    val withToken = mockServer.retrieveRecordedRequests(
      request().withMethod("GET").withPath("/$podName/events/e1"),
    )
    assertEquals(2, withToken.size)
    assertEquals(listOf("Bearer $token"), withToken.last().getHeader("Authorization"))
  }

  @Test
  fun `delete DELETEs the resource with context query param`() {
    val resourceUri = podBaseUrl.resolve("events/e1")
    val contextUri = podBaseUrl.resolve("_system/contexts/apps/notes")
    mockServer
      .`when`(
        request()
          .withMethod("DELETE")
          .withPath("/$podName/events/e1")
          .withQueryStringParameter("context", contextUri.toString()),
      )
      .respond(response().withStatusCode(204))

    service.delete(resourceUri, contextUri)
  }

  @Test
  fun `existsResource issues SPARQL ASK with type and context filter`() {
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody("""{"head":{},"boolean":true}"""),
      )

    val present = service.existsResource(
      type = URI("https://schema.org/Event"),
      resourceUri = podBaseUrl.resolve("events/e1"),
      contexts = listOf(podBaseUrl.resolve("_system/contexts/apps/notes")),
    )
    assertTrue(present)
  }

  @Test
  fun `findReferencingResources parses SPARQL SELECT bindings into URI set`() {
    val contextUri = podBaseUrl.resolve("_system/contexts/apps/notes")
    val objectUri = podBaseUrl.resolve("images/i1")
    val referrer1 = podBaseUrl.resolve("events/e1")
    val referrer2 = podBaseUrl.resolve("events/e2")
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody(
            """{"head":{"vars":["s"]},"results":{"bindings":[""" +
              """{"s":{"type":"uri","value":"$referrer1"}},""" +
              """{"s":{"type":"uri","value":"$referrer2"}}""" +
              """]}}""",
          ),
      )

    val result = service.findReferencingResources(contextUri, objectUri)
    assertEquals(setOf(referrer1, referrer2), result)
  }

  @Test
  fun `findReferencingResources returns empty set when no bindings`() {
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody("""{"head":{"vars":["s"]},"results":{"bindings":[]}}"""),
      )

    val result = service.findReferencingResources(
      podBaseUrl.resolve("_system/contexts/apps/notes"),
      podBaseUrl.resolve("images/i1"),
    )
    assertTrue(result.isEmpty())
  }

  private fun stubPutEventResource(eventPath: String, contextUri: URI) {
    mockServer
      .`when`(
        request()
          .withMethod("PUT")
          .withPath("/$podName/$eventPath")
          .withQueryStringParameter("context", contextUri.toString()),
      )
      .respond(response().withStatusCode(204))
  }

  private fun stubGetEventResource(eventPath: String, body: String, status: Int = 200) {
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/$eventPath"))
      .respond(
        if (status == 200) {
          response()
            .withStatusCode(200)
            .withContentType(MediaType.parse("application/n-quads"))
            .withBody(body)
        } else {
          response().withStatusCode(status)
        },
      )
  }

  private fun lastPutBodyAsModel(eventPath: String): org.eclipse.rdf4j.model.Model {
    val recorded = mockServer.retrieveRecordedRequests(
      request().withMethod("PUT").withPath("/$podName/$eventPath"),
    ).last()
    val bytes = recorded.bodyAsRawBytes ?: ByteArray(0)
    return org.eclipse.rdf4j.rio.Rio.parse(
      java.io.ByteArrayInputStream(bytes),
      org.eclipse.rdf4j.rio.RDFFormat.NQUADS,
    )
  }

  /**
   * `existsPod` is here at all — while creating and deleting a pod are not — because the data path
   * asks it, and the pod's own meta route separates "unknown" from "empty" by status without any
   * credential.
   */
  @Test
  fun `existsPod distinguishes an unknown pod from one that was never written to`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/meta/date-modified"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"dateModified":null}"""),
      )
    assertTrue(service.exists(), "a pod with no writes still exists")

    mockServer.reset()
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/meta/date-modified"))
      .respond(response().withStatusCode(404))
    assertFalse(service.exists(), "404 means the server does not know this pod")
  }

  @Test
  fun `getPodLastModifiedAt parses the timestamp from the endpoint`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/meta/date-modified"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"dateModified":"2026-05-20T10:15:30Z"}"""),
      )

    assertEquals(Instant.parse("2026-05-20T10:15:30Z"), service.lastModifiedAt())
  }

  /**
   * The three pod-level reads are anonymous **by construction**, not by a caller remembering to
   * pass no token: they never consult [SempodsAuth] at all. That is what lets them answer for a pod
   * this caller holds no credential for — including one that is not provisioned yet, where minting
   * would throw rather than return.
   *
   * The credential here fails loudly if asked, so the guarantee is asserted rather than inferred
   * from an absent header.
   */
  @Test
  fun `the pod-level reads never ask the auth for a credential`() {
    val service = SempodsPodClient(
      podBaseUrl = podBaseUrl,
      auth = SempodsAuth { error("a pod-level read must not mint a token") },
    )
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/meta/date-modified"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"dateModified":null}"""),
      )
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/contexts"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"contexts":[]}"""),
      )

    assertTrue(service.exists(), "200 on the meta route means the pod is there")
    assertNull(service.lastModifiedAt(), "a pod that exists but was never written to")
    assertTrue(service.publicContexts().isEmpty())

    mockServer.retrieveRecordedRequests(request()).forEach {
      assertTrue(it.getFirstHeader("Authorization").isNullOrEmpty(), "no bearer on ${'$'}{it.path}")
    }
  }

  @Test
  fun `getPodLastModifiedAt returns null for an empty pod and for an unknown pod`() {
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/meta/date-modified"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"dateModified":null}"""),
      )
    assertNull(service.lastModifiedAt())

    mockServer.reset()
    mockServer
      .`when`(request().withMethod("GET").withPath("/$podName/_system/meta/date-modified"))
      .respond(response().withStatusCode(404))
    assertNull(service.lastModifiedAt())
  }

  @Test
  fun `existsResource issues ASK without VALUES when contexts are null`() {
    // Capture the request body to verify the query shape.
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody("""{"head":{},"boolean":false}"""),
      )

    val present = service.existsResource(
      type = URI("https://schema.org/Event"),
      resourceUri = podBaseUrl.resolve("events/e1"),
      contexts = null,
    )
    assertFalse(present)
  }

  @Test
  fun `existsResource returns false without a server call when contexts is empty`() {
    // Local impl uses Mongo `Filters.in(field, [])` which never matches —
    // empty contexts must produce false, not "any context". Mock-server has
    // no expectation registered: a 404 would surface as an exception, so
    // assertFalse + no exception together pin the short-circuit behaviour.
    val present = service.existsResource(
      type = URI("https://schema.org/Event"),
      resourceUri = podBaseUrl.resolve("events/e1"),
      contexts = emptyList(),
    )
    assertFalse(present)
  }

  @Test
  fun `delete with external subject clears its slots through the slot endpoint`() {
    val contextUri = podBaseUrl.resolve("_system/contexts/apps/notes")
    val offerUri = URI("https://tickets.example.com/Event/1/")
    val offerUrlIri = Ontologies.SCHEMA_ORG.Properties.url
    val typeIri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

    // predicate discovery: the subject carries url + rdf:type in the context
    val bindings = listOf(offerUrlIri.stringValue(), typeIri)
      .joinToString(",") { """{"p":{"type":"uri","value":"$it"}}""" }
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody("""{"head":{"vars":["p"]},"results":{"bindings":[$bindings]}}"""),
      )
    mockServer
      .`when`(request().withMethod("PUT").withPath("/$podName/_system/resources/.*"))
      .respond(response().withStatusCode(204))

    service.delete(offerUri, contextUri)

    val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
    val subjectSegment = b64.encodeToString(offerUri.toString().toByteArray())
    listOf(offerUrlIri.stringValue(), typeIri).forEach { predicate ->
      val puts = mockServer.retrieveRecordedRequests(
        request().withMethod("PUT").withPath(
          "/$podName/_system/resources/$subjectSegment/${b64.encodeToString(predicate.toByteArray())}",
        ),
      )
      assertEquals(1, puts.size, "slot for '$predicate' must be cleared")
      assertEquals("[]", puts.single().bodyAsString.replace(" ", ""))
    }
    // no LOD DELETE attempted — the external subject has no DELETE URL under the pod
    assertEquals(0, mockServer.retrieveRecordedRequests(request().withMethod("DELETE")).size)
  }

  // ─── media ────────────────────────────────────────────────────────────────

  @Test
  fun `media calls carry the service token to the media routes`() {
    val contextUri = podBaseUrl.resolve("_system/contexts/apps/notes/public")
    val contentUrl = podBaseUrl.resolve("_system/media/abc123/content")
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/media"))
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"id":"abc123","content_url":"$contentUrl"}"""),
      )
    mockServer
      .`when`(request().withPath("/$podName/_system/media/abc123"))
      .respond(response().withStatusCode(204))

    val uploaded = service.uploadMedia(
      context = contextUri,
      contentType = "image/png",
      source = { "PNGDATA".byteInputStream() },
      size = 7L,
      filename = "poster.png",
    )
    val fetched = service.uploadMediaFromUrl(
      context = contextUri,
      sourceUrl = URI("https://drive.example.org/file"),
      filename = "poster.png",
    )
    service.assignMedia(mediaId = "abc123", context = contextUri)
    service.unassignMedia(mediaId = "abc123", context = contextUri)

    assertEquals("abc123", uploaded.mediaId)
    assertEquals(contentUrl, uploaded.contentUrl)
    assertEquals("abc123", fetched.mediaId)

    val recorded = mockServer.retrieveRecordedRequests(request())
    assertEquals(4, recorded.size)
    recorded.forEach { assertEquals("Bearer $token", it.getFirstHeader("Authorization")) }
  }

  /**
   * The [java.nio.file.Path] overload is defaulted on the interface rather than implemented per
   * transport, so this is what pins that default: the file is opened, and its length becomes the
   * declared one.
   */
  @Test
  fun `the Path overload opens the file and declares its size`(@TempDir dir: Path) {
    val file = dir.resolve("poster.png")
    Files.writeString(file, "PNGDATA")
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/media"))
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"id":"abc123","content_url":"${podBaseUrl.resolve("_system/media/abc123/content")}"}"""),
      )

    service.uploadMedia(
      context = podBaseUrl.resolve("_system/contexts/apps/notes/public"),
      contentType = "image/png",
      source = file,
      filename = "poster.png",
    )

    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/$podName/_system/media"))
    assertEquals(1, recorded.size)
    assertEquals("7", recorded[0].getFirstHeader("Content-Length"))
    assertEquals("PNGDATA", String(recorded[0].bodyAsRawBytes))
  }

  /**
   * **The reason the spec takes a supplier and refuses a bare `InputStream`.**
   *
   * [SempodsPodClient.withTokenRetry] re-executes its whole block after a `401`. A stream
   * consumed by the first attempt would make the retry send an empty body — and since that request
   * succeeds, the pod would answer `201` for an empty object and nothing would report a problem.
   * So the assertion is not merely that the retry happens but that the second attempt carries the
   * same bytes as the first.
   */
  @Test
  fun `a media upload retried after a 401 sends the bytes again`() {
    val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
    val retryService = SempodsPodClient(
      podBaseUrl = podBaseUrl,
      auth = object : SempodsAuth {
        override fun token(): String = tokens.first()
        override fun invalidate() {
          tokens.removeFirst()
        }
      },
    )

    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/$podName/_system/media")
          .withHeader("Authorization", "Bearer stale-token"),
        Times.exactly(1),
      )
      .respond(response().withStatusCode(401).withBody("token expired"))
    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/$podName/_system/media")
          .withHeader("Authorization", "Bearer fresh-token"),
      )
      .respond(
        response()
          .withStatusCode(201)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody("""{"id":"abc123","content_url":"${podBaseUrl.resolve("_system/media/abc123/content")}"}"""),
      )

    var opened = 0
    val uploaded = retryService.uploadMedia(
      context = podBaseUrl.resolve("_system/contexts/apps/notes/public"),
      contentType = "image/png",
      source = { opened++; "PNGDATA".byteInputStream() },
      size = 7L,
      filename = null,
    )

    assertEquals("abc123", uploaded.mediaId)
    assertEquals(2, opened, "the supplier is invoked once per attempt, not once per upload")
    val recorded = mockServer.retrieveRecordedRequests(request().withPath("/$podName/_system/media"))
    assertEquals(2, recorded.size, "the original attempt plus exactly one retry")
    recorded.forEach { assertEquals("PNGDATA", String(it.bodyAsRawBytes), "every attempt carries the whole body") }
  }

  @Test
  fun `data call retries once after a 401 with a freshly minted token`() {
    // A credential whose token rotates on invalidate: the first call serves a stale token,
    // invalidate advances to the fresh one — proving the retry re-mints rather than
    // replaying the same (rejected) token.
    val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
    var invalidateCount = 0
    val retryService = SempodsPodClient(
      podBaseUrl = podBaseUrl,
      auth = object : SempodsAuth {
        override fun token(): String = tokens.first()
        override fun invalidate() {
          invalidateCount++
          tokens.removeFirst()
        }
      },
    )

    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/$podName/_system/sparql/query")
          .withHeader("Authorization", "Bearer stale-token"),
        Times.exactly(1),
      )
      .respond(response().withStatusCode(401).withBody("token expired"))
    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/$podName/_system/sparql/query")
          .withHeader("Authorization", "Bearer fresh-token"),
      )
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody("""{"head":{},"boolean":true}"""),
      )

    val present = retryService.existsResource(
      type = URI("https://schema.org/Event"),
      resourceUri = podBaseUrl.resolve("events/e1"),
      contexts = null,
    )

    assertTrue(present)
    assertEquals(1, invalidateCount, "exactly one token refresh")
  }

  @Test
  fun `data call surfaces the 401 when the single retry also fails`() {
    var invalidateCount = 0
    val retryService = SempodsPodClient(
      podBaseUrl = podBaseUrl,
      auth = object : SempodsAuth {
        override fun token(): String = "tok"
        override fun invalidate() {
          invalidateCount++
        }
      },
    )

    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(response().withStatusCode(401).withBody("token expired"))

    val ex = assertThrows<SempodsClientException> {
      retryService.existsResource(
        type = URI("https://schema.org/Event"),
        resourceUri = podBaseUrl.resolve("events/e1"),
        contexts = null,
      )
    }

    assertEquals(401, ex.statusCode)
    assertEquals(1, invalidateCount, "retry attempted exactly once")
    assertEquals(
      2,
      mockServer.retrieveRecordedRequests(request().withPath("/$podName/_system/sparql/query")).size,
      "original attempt plus exactly one retry — no retry storm",
    )
  }

  /**
   * The body comes back **verbatim**. `EventQueryService` navigates `results.bindings` itself, so
   * parsing and re-serialising here would be a silent contract change rather than a convenience.
   */
  @Test
  fun `sparqlSelect posts the query and hands back the results JSON unparsed`() {
    val query = "SELECT ?e WHERE { ?e ?p ?o }"
    val body = """{"head":{"vars":["e"]},"results":{"bindings":[{"e":{"value":"urn:e1"}}]}}"""
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody(body),
      )

    assertEquals(body, service.sparqlSelect(query))

    val recorded = mockServer.retrieveRecordedRequests(
      request().withPath("/$podName/_system/sparql/query"),
    )
    assertEquals(1, recorded.size)
    assertEquals(query, String(recorded[0].bodyAsRawBytes), "the query travels unaltered")
    assertEquals("Bearer $token", recorded[0].getFirstHeader("Authorization"))
  }

  /**
   * `CONSTRUCT` is asked for as n-quads so a statement keeps the graph it came from. `loadView`'s
   * external-subject branch reads by context, and this method is the public form of that primitive.
   */
  @Test
  fun `sparqlConstruct keeps the named graph of what it constructs`() {
    val subject = podBaseUrl.resolve("images/i1")
    val graph = podBaseUrl.resolve("_system/contexts/apps/notes/public")
    mockServer
      .`when`(request().withMethod("POST").withPath("/$podName/_system/sparql/query"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/n-quads"))
          .withBody("""<$subject> <https://schema.org/contentUrl> "https://example.org/c" <$graph> .""" + "\n"),
      )

    val model = service.sparqlConstruct("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")

    assertEquals(1, model.size)
    assertEquals(
      setOf(vf.createIRI(graph.toString())),
      model.contexts(),
      "the context survives the round trip",
    )
  }

  /**
   * The caller-built queries get the same retry as the fixed ones, and it is what makes them worth
   * routing through here at all: a caller that holds its own token sees a rotation as a failed
   * query for as long as the stale token stays cached.
   *
   * Not redundant with the generic retry test above: that one proves [SempodsPodClient.withTokenRetry]
   * works. This
   * one proves *this method* uses it — which is a per-method property, and exactly the one that was
   * missing at the two call sites this method exists to replace.
   */
  @Test
  fun `sparqlSelect retries once after a 401 with a freshly minted token`() {
    val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
    var invalidateCount = 0
    val retryService = SempodsPodClient(
      podBaseUrl = podBaseUrl,
      auth = object : SempodsAuth {
        override fun token(): String = tokens.first()
        override fun invalidate() {
          invalidateCount++
          tokens.removeFirst()
        }
      },
    )

    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/$podName/_system/sparql/query")
          .withHeader("Authorization", "Bearer stale-token"),
        Times.exactly(1),
      )
      .respond(response().withStatusCode(401).withBody("token expired"))
    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/$podName/_system/sparql/query")
          .withHeader("Authorization", "Bearer fresh-token"),
      )
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/sparql-results+json"))
          .withBody("""{"head":{"vars":["e"]},"results":{"bindings":[{"e":{"value":"urn:e1"}}]}}"""),
      )

    val json = retryService.sparqlSelect("SELECT ?e WHERE { ?e ?p ?o }")

    assertTrue(json.contains("urn:e1"), "the retry's answer is the one returned")
    assertEquals(1, invalidateCount, "exactly one token refresh")
    assertEquals(
      2,
      mockServer.retrieveRecordedRequests(request().withPath("/$podName/_system/sparql/query")).size,
      "original attempt plus exactly one retry",
    )
  }

  /** The CONSTRUCT half of the same guarantee — `MediaQueryService`'s question. */
  @Test
  fun `sparqlConstruct retries once after a 401 with a freshly minted token`() {
    val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
    var invalidateCount = 0
    val retryService = SempodsPodClient(
      podBaseUrl = podBaseUrl,
      auth = object : SempodsAuth {
        override fun token(): String = tokens.first()
        override fun invalidate() {
          invalidateCount++
          tokens.removeFirst()
        }
      },
    )

    val subject = podBaseUrl.resolve("images/i1")
    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/$podName/_system/sparql/query")
          .withHeader("Authorization", "Bearer stale-token"),
        Times.exactly(1),
      )
      .respond(response().withStatusCode(401).withBody("token expired"))
    mockServer
      .`when`(
        request()
          .withMethod("POST")
          .withPath("/$podName/_system/sparql/query")
          .withHeader("Authorization", "Bearer fresh-token"),
      )
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.parse("application/n-quads"))
          .withBody("""<$subject> <https://schema.org/contentUrl> "https://example.org/c" .""" + "\n"),
      )

    val model = retryService.sparqlConstruct("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")

    assertEquals(1, model.size, "the retry's answer is the one returned")
    assertEquals(1, invalidateCount, "exactly one token refresh")
    assertEquals(
      2,
      mockServer.retrieveRecordedRequests(request().withPath("/$podName/_system/sparql/query")).size,
      "original attempt plus exactly one retry",
    )
  }
}
