package org.sempods.api.pod.system.find

import com.google.inject.Inject
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.tests.TestUtil
import org.sempods.pods.authentication.spi.PodRequestAuthentication
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.rdf.RdfWriterUtil
import org.sempods.spec.PodRef
import java.io.ByteArrayInputStream
import java.net.URI
import kotlin.test.assertEquals

/**
 * `find` served from a store and a sign-in of a deployment's own: the pod is in no registry of this
 * server, its statements are in no store of this server, and the answer is still the sandboxed one.
 */
class FindPodConnectionsHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  private val name = "elsewhere-${TestUtil.randomId()}"
  private val pod = PodRef(uri = URI("${SempodsModule.config.apiBaseUrl}$name"), name = name, owner = "owner")
  private val public = iri("${pod.uri}/public")
  private val private = iri("${pod.uri}/private")
  private val harbour = iri("${pod.uri}/harbour")
  private val office = iri("${pod.uri}/harbour-office")
  private val article = iri("https://schema.org/Article")

  private val repository = SailRepository(MemoryStore()).apply {
    init()
    connection.use { conn ->
      conn.add(harbour, RDF.TYPE, article, public)
      conn.add(harbour, RDFS.LABEL, literal("Harbour"), public)
      conn.add(harbour, RDFS.LABEL, literal("Harbour, internal"), private)
      conn.add(office, RDFS.LABEL, literal("Harbour office"), private)
    }
  }

  @AfterEach
  fun shutDown() {
    repository.shutDown()
  }

  private fun reading(vararg contexts: IRI) = PodRequestAuthentication.Authenticated(
    SempodsCredentials(pod = pod, restrictedContexts = contexts.mapTo(mutableSetOf()) { URI(it.stringValue()) }, identified = true),
  )

  private fun find(vararg query: Pair<String, String>): Model {
    val response = http.prepareGet("${SempodsModule.config.apiBaseUrl}$name/_system/find")
      .also { request -> query.forEach { (key, value) -> request.addQueryParam(key, value) } }
      .addQueryParam("include_contexts", "true")
      .addHeader("Accept", "application/n-quads")
      .execute()
    assertEquals(200, response.statusCode, response.responseBody)
    return ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
  }

  @Test
  fun `a hit and its expansion come from the contexts the caller reads in the deployment's store`() = withSetup {
    requestAuthenticator.answerWith { _, _ -> reading(public) }
    podConnections.answerWith(repository)

    val model = find("text" to "harbour")

    assertEquals(setOf(harbour), model.subjects().toSet())
    assertEquals(setOf(public), model.contexts().toSet())
    assertEquals(setOf(literal("Harbour")), model.filter(harbour, RDFS.LABEL, null).objects())
    assertEquals(setOf(article), model.filter(harbour, RDF.TYPE, null).objects())
  }

  @Test
  fun `a context downscope narrows the search in the deployment's store`() = withSetup {
    requestAuthenticator.answerWith { _, _ -> reading(public, private) }
    podConnections.answerWith(repository)

    val model = find("text" to "harbour", "context" to "private")

    assertEquals(setOf(harbour, office), model.subjects().toSet())
    assertEquals(setOf(private), model.contexts().toSet())
  }
}
