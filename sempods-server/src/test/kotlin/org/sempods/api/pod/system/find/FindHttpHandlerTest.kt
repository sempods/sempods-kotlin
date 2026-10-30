package org.sempods.api.pod.system.find

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.retrieval.FindAdapter
import org.sempods.retrieval.FindRequest
import org.sempods.retrieval.FindSandbox
import org.sempods.retrieval.FindService
import org.sempods.retrieval.ResourceExpander
import org.sempods.spec.PodRef
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FindHttpHandler]'s order and its use of the [FindAuthorizer], with a fake adapter
 * and no pod store. The HTTP contract of the route is `FindEndpointHttpTest`'s.
 */
class FindHttpHandlerTest {

  private val vf = SimpleValueFactory.getInstance()
  private val podUri = URI("https://example.org/t")
  private val publicContext = URI("https://example.org/t/public")
  private val privateContext = URI("https://example.org/t/private")
  private val hit = "https://example.org/t/event"
  private val nQuads = listOf(MediaType.valueOf("application/n-quads"))

  /** Returns one hit unless the sandbox matches nothing; records the sandbox it ran in. */
  private class RecordingAdapter(private val hit: Model) : FindAdapter {
    var lastSandbox: FindSandbox? = null
    override fun find(request: FindRequest, sandbox: FindSandbox): Model {
      lastSandbox = sandbox
      return if (sandbox.matchesNothing()) LinkedHashModel() else hit
    }
  }

  private val noExpansion = object : ResourceExpander {
    override fun expand(hits: Collection<IRI>, sandbox: FindSandbox): Model = LinkedHashModel()
  }

  private val adapter = RecordingAdapter(
    LinkedHashModel().apply {
      add(vf.createIRI(hit), vf.createIRI("https://schema.org/name"), vf.createLiteral("x"), vf.createIRI(publicContext.toString()))
    },
  )
  private val handler = FindHttpHandler(findService = FindService(adapters = setOf(adapter), expander = noExpansion))

  private var authorizations = 0
  private val resolved = mutableListOf<List<String>>()

  private fun authorizer(
    resolveTo: Set<URI> = setOf(publicContext),
    cacheControl: String = "private, no-store",
  ) = FindAuthorizer {
    authorizations++
    FindAuthorization(
      podUri = podUri,
      credentials = SempodsCredentials(
        pod = PodRef(uri = podUri, name = "t", owner = "o"),
        restrictedContexts = setOf(publicContext),
      ),
      visibleContexts = setOf(publicContext),
      contextResolver = { requested ->
        resolved += requested
        resolveTo
      },
      cacheControl = cacheControl,
    )
  }

  private fun body(response: Response): String {
    val out = ByteArrayOutputStream()
    (response.entity as StreamingOutput).write(out)
    return out.toString(Charsets.UTF_8)
  }

  @Test
  fun `a blank text answers 400 without authorizing`() {
    val response = handler.handleGet(
      text = "  ",
      typeParams = null,
      contextParams = null,
      includeContextsParam = null,
      limitParam = null,
      acceptableMediaTypes = nQuads,
      authorizer = authorizer(),
    )

    assertEquals(400, response.status)
    assertEquals(0, authorizations)
  }

  @Test
  fun `an invalid include_contexts throws 400 without authorizing`() {
    val thrown = assertFailsWith<WebApplicationException> {
      handler.handleGet(
        text = "x",
        typeParams = null,
        contextParams = null,
        includeContextsParam = "yes",
        limitParam = null,
        acceptableMediaTypes = nQuads,
        authorizer = authorizer(),
      )
    }

    assertEquals(400, thrown.response.status)
    assertEquals("include_contexts must be 'true' or 'false'", thrown.response.entity)
    assertEquals(0, authorizations)
  }

  @Test
  fun `a POST body with an unknown field answers 400 without authorizing`() {
    val response = handler.handlePost(
      body = """{"text":"x","filter":{}}""",
      acceptableMediaTypes = nQuads,
      authorizer = authorizer(),
    )

    assertEquals(400, response.status)
    assertEquals(0, authorizations)
  }

  @Test
  fun `an unacceptable Accept answers 406 without authorizing`() {
    val response = handler.handlePost(
      body = """{"text":"x"}""",
      acceptableMediaTypes = listOf(MediaType.TEXT_HTML_TYPE),
      authorizer = authorizer(),
    )

    assertEquals(406, response.status)
    assertEquals(0, authorizations)
  }

  @Test
  fun `an exception from the authorizer propagates`() {
    val unauthorized = WebApplicationException(401)

    val thrown = assertFailsWith<WebApplicationException> {
      handler.handlePost(
        body = """{"text":"x"}""",
        acceptableMediaTypes = nQuads,
        authorizer = { throw unauthorized },
      )
    }

    assertEquals(unauthorized, thrown)
  }

  @Test
  fun `a blank-only downscope is an empty result and never reaches the resolver`() {
    val response = handler.handleGet(
      text = "x",
      typeParams = null,
      contextParams = listOf(""),
      includeContextsParam = null,
      limitParam = null,
      acceptableMediaTypes = nQuads,
      authorizer = authorizer(),
    )

    assertEquals(200, response.status)
    assertEquals("", body(response).trim())
    assertEquals(emptySet(), adapter.lastSandbox?.contextFilter)
    assertTrue(resolved.isEmpty())
  }

  @Test
  fun `an empty POST contexts list is an empty result and never reaches the resolver`() {
    val response = handler.handlePost(
      body = """{"text":"x","contexts":[]}""",
      acceptableMediaTypes = nQuads,
      authorizer = authorizer(),
    )

    assertEquals(200, response.status)
    assertEquals("", body(response).trim())
    assertTrue(resolved.isEmpty())
  }

  @Test
  fun `an absent downscope searches every readable context`() {
    val response = handler.handleGet(
      text = "x",
      typeParams = null,
      contextParams = emptyList(),
      includeContextsParam = null,
      limitParam = null,
      acceptableMediaTypes = nQuads,
      authorizer = authorizer(),
    )

    assertEquals(200, response.status)
    assertNull(adapter.lastSandbox?.contextFilter)
    assertEquals(setOf(publicContext), adapter.lastSandbox?.visibleContexts)
    assertTrue(resolved.isEmpty())
  }

  @Test
  fun `the resolver gets the non-blank contexts and its answer stays inside the ceiling`() {
    handler.handleGet(
      text = "x",
      typeParams = null,
      contextParams = listOf("", publicContext.toString(), privateContext.toString()),
      includeContextsParam = null,
      limitParam = null,
      acceptableMediaTypes = nQuads,
      authorizer = authorizer(resolveTo = setOf(publicContext, privateContext)),
    )

    assertEquals(listOf(listOf(publicContext.toString(), privateContext.toString())), resolved)
    assertEquals(setOf(publicContext), adapter.lastSandbox?.contextFilter)
  }

  @Test
  fun `a result carries the supplied Cache-Control and Vary on Accept and Authorization`() {
    val response = handler.handleGet(
      text = "x",
      typeParams = null,
      contextParams = null,
      includeContextsParam = "true",
      limitParam = null,
      acceptableMediaTypes = nQuads,
      authorizer = authorizer(cacheControl = "public, max-age=60"),
    )

    assertEquals(200, response.status)
    assertEquals(1, authorizations)
    assertEquals("public, max-age=60", response.getHeaderString(HttpHeaders.CACHE_CONTROL))
    assertEquals("Accept, Authorization", response.getHeaderString(HttpHeaders.VARY))
    assertEquals("application/n-quads", response.mediaType.toString())
    assertTrue(body(response).contains("<$hit>"))
    assertTrue(body(response).contains("<$publicContext>"))
  }
}
