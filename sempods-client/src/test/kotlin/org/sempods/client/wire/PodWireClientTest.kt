package org.sempods.client.wire

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.JsonBody
import org.mockserver.model.MediaType
import org.sempods.client.SempodsClientException
import org.sempods.client.SempodsHttpTransport
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.net.SempodsUrlPolicy
import org.sempods.commons.utils.UriEncodingUtil
import org.slf4j.event.Level
import java.net.URI

/**
 * Wire coverage for [PodWireClient] against a MockServer-simulated pod System layer: the exact path
 * templates, content types, bearer header, base64url slot encoding, precondition headers and ETag
 * capture. What used to be `sempods-mcp`'s `PodApiClientTest`, minus the coroutines.
 */
class PodWireClientTest {

  private lateinit var server: ClientAndServer
  private lateinit var pod: URI
  private val client = PodWireClient()
  private val mapper = ObjectMapper()

  private fun b64(iri: String) = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(iri))

  @BeforeEach
  fun setup() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    pod = URI("http://localhost:${server.port}/pod")

    server.`when`(request().withMethod("GET").withPath("/pod/_system/contexts").withHeader("Authorization", "Bearer at-1"))
      .respond(response().withStatusCode(200).withBody("""{"contexts":[{"context_iri":"$pod/main","permissions":["read"]}],"writable_contexts":[]}"""))

    server.`when`(request().withMethod("POST").withPath("/pod/_system/sparql/query"))
      .respond(response().withStatusCode(200).withBody("""{"head":{"vars":["s"]},"results":{"bindings":[]}}"""))

    server.`when`(request().withMethod("POST").withPath("/pod/_system/find"))
      .respond(response().withStatusCode(200).withBody("""{"@graph":[]}"""))

    server.`when`(request().withMethod("GET").withPath("/pod/_system/resources/${b64("$pod/thing")}"))
      .respond(response().withStatusCode(200).withHeader("ETag", "\"v1\"").withBody("""{"@id":"$pod/thing"}"""))

    server.`when`(request().withMethod("GET").withPath("/pod/_system/resources/${b64("$pod/thing")}/${b64("https://schema.org/name")}"))
      .respond(response().withStatusCode(200).withHeader("ETag", "\"slot-v1\"").withBody("""[{"@value":"Thing"}]"""))
  }

  @AfterEach
  fun teardown() = server.stop()

  @Test
  fun `list_contexts returns the pod's context document`() {
    val body = client.listContexts(pod, "at-1")
    assertEquals("$pod/main", body["contexts"][0]["context_iri"].asText())
  }

  @Test
  fun `sparql_select posts the query with the sparql-query content type and no dataset params`() {
    val body = client.sparqlSelect(pod, "SELECT * WHERE { ?s ?p ?o }", token = "at-1")
    assertTrue(body["results"]["bindings"].isArray)
    server.verify(
      request().withMethod("POST").withPath("/pod/_system/sparql/query")
        .withContentType(MediaType.parse("application/sparql-query")),
    )
  }

  @Test
  fun `sparql sends each context as both default-graph-uri and named-graph-uri`() {
    // Both, so a narrowed query keeps full reach *within* those graphs. The pod then intersects
    // with the readable set, which is why this can only narrow and never broaden.
    client.sparqlSelect(
      pod, "SELECT * WHERE { ?s ?p ?o }",
      contextIris = listOf(URI("$pod/main"), URI("$pod/other")), token = "at-1",
    )
    server.verify(
      request().withMethod("POST").withPath("/pod/_system/sparql/query")
        .withQueryStringParameter("default-graph-uri", "$pod/main", "$pod/other")
        .withQueryStringParameter("named-graph-uri", "$pod/main", "$pod/other"),
    )
  }

  @Test
  fun `find posts a JSON body and returns the subgraph`() {
    val body = client.find(
      pod, "berlin", types = listOf(URI("https://schema.org/Event")),
      includeContexts = true, limit = 5, token = "at-1",
    )
    assertTrue(body.has("@graph"))
    server.verify(
      request().withMethod("POST").withPath("/pod/_system/find")
        .withBody(JsonBody.json("""{"text":"berlin","type":["https://schema.org/Event"],"include_contexts":true,"limit":5}""")),
    )
  }

  @Test
  fun `get_resource encodes the IRI as a base64url slot and captures the ETag`() {
    val r = client.getResource(pod, URI("$pod/thing"), token = "at-1")
    assertEquals("\"v1\"", r.etag)
    assertEquals("$pod/thing", r.jsonld["@id"].asText())
  }

  @Test
  fun `get_property_values encodes both slot segments and captures the slot ETag`() {
    val s = client.getPropertyValues(pod, URI("$pod/thing"), URI("https://schema.org/name"), token = "at-1")
    assertEquals("\"slot-v1\"", s.etag)
    assertEquals("Thing", s.values[0]["@value"].asText())
  }

  @Test
  fun `create sends If-None-Match and returns the post-write ETag`() {
    server.`when`(request().withMethod("PUT").withPath("/pod/_system/resources/${b64("$pod/new")}"))
      .respond(response().withStatusCode(201).withHeader("ETag", "\"v2\""))

    val result = client.createResource(
      pod, contextIri = URI("$pod/main"), resourceIri = URI("$pod/new"),
      jsonld = mapper.readTree("""{"@id":"$pod/new"}"""), ifNoneMatch = "*", token = "at-1",
    )
    assertEquals(201, result.status)
    assertEquals("\"v2\"", result.etag, "the next precondition arrives with the write, not a re-read")
    assertNull(result.body)
    server.verify(
      request().withMethod("PUT").withPath("/pod/_system/resources/${b64("$pod/new")}")
        .withQueryStringParameter("context", "$pod/main")
        .withHeader("If-None-Match", "\\*")
        .withContentType(MediaType.parse("application/ld+json")),
    )
  }

  @Test
  fun `update sends a merge-patch body under If-Match`() {
    server.`when`(request().withMethod("PATCH").withPath("/pod/_system/resources/${b64("$pod/thing")}"))
      .respond(response().withStatusCode(200).withHeader("ETag", "\"v3\""))

    val result = client.updateResource(
      pod, contextIri = URI("$pod/main"), resourceIri = URI("$pod/thing"),
      jsonldPatch = mapper.readTree("""{"https://schema.org/name":"New"}"""), ifMatch = "\"v1\"", token = "at-1",
    )
    assertEquals("\"v3\"", result.etag)
    server.verify(
      request().withMethod("PATCH").withPath("/pod/_system/resources/${b64("$pod/thing")}")
        .withHeader("If-Match", "\"v1\"")
        .withContentType(MediaType.parse("application/merge-patch+json")),
    )
  }

  @Test
  fun `a failed precondition surfaces with the pod's status and reason`() {
    server.`when`(request().withMethod("PATCH").withPath("/pod/_system/resources/${b64("$pod/stale")}"))
      .respond(response().withStatusCode(412).withBody("etag mismatch"))

    val ex = assertThrows<SempodsClientException> {
      client.updateResource(
        pod, contextIri = URI("$pod/main"), resourceIri = URI("$pod/stale"),
        jsonldPatch = mapper.readTree("{}"), ifMatch = "\"old\"", token = "at-1",
      )
    }
    assertEquals(412, ex.statusCode, "the status must survive structurally, not only in the text")
    assertTrue(ex.message!!.contains("etag mismatch"), ex.message)
  }

  @Test
  fun `slot value removal addresses the value itself and is idempotent`() {
    val path = "/pod/_system/resources/${b64("$pod/thing")}/${b64("https://schema.org/about")}/${b64("$pod/topic")}"
    server.`when`(request().withMethod("DELETE").withPath(path))
      .respond(response().withStatusCode(200).withBody("""{"outcome":"absent"}"""))

    val result = client.removePropertyValue(
      pod, contextIri = URI("$pod/main"), subjectIri = URI("$pod/thing"),
      predicateIri = URI("https://schema.org/about"), targetIri = URI("$pod/topic"), token = "at-1",
    )
    assertEquals("absent", result.body!!["outcome"].asText())
  }

  @Test
  fun `a pod error carries its status`() {
    server.`when`(request().withMethod("GET").withPath("/pod2/_system/contexts"))
      .respond(response().withStatusCode(502))
    val ex = assertThrows<SempodsClientException> {
      client.listContexts(URI("http://localhost:${server.port}/pod2"), "at-1")
    }
    assertEquals(502, ex.statusCode)
  }

  @Test
  fun `the transport's guard refuses a loopback pod under a strict policy`() {
    // The guard is the transport's, not this client's — one place decides what may be dialled.
    val guarded = PodWireClient(
      SempodsHttpTransport(guard = SempodsOutboundGuard(SempodsUrlPolicy(allowPrivateAddresses = false))),
    )
    val ex = assertThrows<SempodsClientException> { guarded.listContexts(pod, "at-1") }
    assertTrue(ex.message!!.contains("not publicly addressable"), ex.message)
  }
}
