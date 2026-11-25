package org.sempods.probe.find

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.multibindings.Multibinder
import jakarta.ws.rs.core.Configuration
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values.bnode
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.glassfish.jersey.internal.MapPropertiesDelegate
import org.glassfish.jersey.server.ApplicationHandler
import org.glassfish.jersey.server.ContainerRequest
import org.glassfish.jersey.server.ResourceConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.sempods.api.pod.system.find.FindEndpoint
import org.sempods.commons.net.SempodsVocabulary
import org.sempods.pods.authentication.spi.PodApiRequest
import org.sempods.pods.authentication.spi.PodRequestAuthentication
import org.sempods.pods.authentication.spi.PodRequestAuthenticator
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.query.SparqlQueryService
import org.sempods.query.spi.PodConnections
import org.sempods.rdf.RdfWriterUtil
import org.sempods.retrieval.FindAdapter
import org.sempods.retrieval.FindRequest
import org.sempods.retrieval.FindSandbox
import org.sempods.retrieval.FindService
import org.sempods.retrieval.ResourceExpander
import org.sempods.retrieval.impls.sparql.SparqlResourceExpander
import org.sempods.retrieval.impls.virtualthreads.VirtualThreadFindExecutor
import org.sempods.retrieval.spi.FindExecutor
import org.sempods.spec.PodRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `find` as a deployment with a store and a sign-in of its own serves it: the bindings listed in
 * `docs/concepts/graph-retrieval.md` §"Embedding find", and no other, with MongoDB off the classpath.
 * A request goes through Jersey in memory, so the endpoint is routed and its parameters bound as in
 * a server.
 */
class FindEmbeddingTest {

  @Test
  fun `the injector binds the documented types and no other`() {
    val bound = injector.bindings.keys
      .map { it.typeLiteral.rawType }
      .filter { it.name.startsWith("org.sempods.") }
      .toSet()

    assertEquals(
      setOf(
        FindEndpoint::class.java,
        PodRequestAuthenticator::class.java,
        FindService::class.java,
        FindExecutor::class.java,
        FindAdapter::class.java,
        ResourceExpander::class.java,
        SparqlResourceExpander::class.java,
        SparqlQueryService::class.java,
        PodConnections::class.java,
      ),
      bound,
    )
  }

  @Test
  fun `a GET answers what the caller reads, whatever the index returns`() {
    val response = send("GET", "demo/_system/find?text=harbour&include_contexts=true", READER to "public")

    assertEquals(200, response.status, response.text)
    val answer = response.nQuads()
    assertEquals(setOf(harbour), answer.subjects().filterIsInstance<IRI>().toSet())
    assertEquals(setOf(public), answer.contexts().toSet())
    assertEquals(setOf(literal("Harbour")), answer.filter(harbour, NAME, null).objects(), "the store's name, not the index's")
    assertEquals(1, answer.filter(null, FIND_RESULT, null).size)
    assertEquals(listOf("private, no-store"), response.headers["Cache-Control"])
    assertTrue(response.headers["Vary"].orEmpty().any { READER in it }, "Vary names the authenticator's header: ${response.headers}")
  }

  @Test
  fun `a POST narrows to the contexts it names, and a match elsewhere is no hit`() {
    val response = send(
      "POST",
      "demo/_system/find",
      READER to "all",
      "Content-Type" to "application/json",
      body = """{"text":"harbour","contexts":["private"],"include_contexts":true}""",
    )

    assertEquals(200, response.status, response.text)
    val answer = response.nQuads()
    assertEquals(setOf(office), answer.subjects().filterIsInstance<IRI>().toSet())
    assertEquals(setOf(private), answer.contexts().toSet())
  }

  @Test
  fun `the authenticator's refusals are the answer`() {
    assertEquals(404, send("GET", "elsewhere/_system/find?text=harbour", READER to "public").status)

    val unauthenticated = send("GET", "demo/_system/find?text=harbour")
    assertEquals(401, unauthenticated.status)
    assertEquals(listOf(CHALLENGE), unauthenticated.headers["WWW-Authenticate"])
  }

  private class Exchange(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
    fun nQuads(): Model = ByteArrayInputStream(body).use { RdfWriterUtil.readNQuads(it) }
  }

  private fun send(method: String, target: String, vararg headers: Pair<String, String>, body: String? = null): Exchange {
    val request = ContainerRequest(BASE, BASE.resolve(target), method, null, MapPropertiesDelegate(), jersey.configuration as Configuration)
    request.header("Accept", "application/n-quads")
    headers.forEach { (name, value) -> request.header(name, value) }
    if (body != null) request.entityStream = ByteArrayInputStream(body.toByteArray())
    val out = ByteArrayOutputStream()
    val response = jersey.apply(request, out).get()
    return Exchange(response.status, response.stringHeaders.mapValues { it.value.toList() }, out.toByteArray())
  }

  companion object {
    private val BASE = URI("http://deployment.example/api/")
    private const val READER = "X-Reader"
    private const val CHALLENGE = "Session realm=\"deployment\""

    private val NAME = iri("https://schema.org/name")
    private val FIND_RESULT = iri(SempodsVocabulary.FIND_RESULT)

    private val pod = PodRef(uri = URI("${BASE}demo"), name = "demo", owner = "owner")
    private val public = iri("${pod.uri}/public")
    private val private = iri("${pod.uri}/private")
    private val harbour = iri("${pod.uri}/harbour")
    private val office = iri("${pod.uri}/harbour-office")
    private val article = iri("https://schema.org/Article")

    /** The deployment's store for the one pod it serves. */
    private val store = SailRepository(MemoryStore()).apply {
      init()
      connection.use { conn ->
        conn.add(harbour, RDF.TYPE, article, public)
        conn.add(harbour, NAME, literal("Harbour"), public)
        conn.add(harbour, NAME, literal("Harbour, internal"), private)
        conn.add(office, RDF.TYPE, article, private)
        conn.add(office, NAME, literal("Harbour office"), private)
      }
    }

    /** The deployment's sign-in: a header names what the caller reads. */
    private val authenticator = object : PodRequestAuthenticator {
      override fun authenticate(pod: String, request: PodApiRequest): PodRequestAuthentication {
        if (pod != this@Companion.pod.name) return PodRequestAuthentication.PodNotFound
        val reads = when (request.headers.entries.firstOrNull { it.key.equals(READER, ignoreCase = true) }?.value?.firstOrNull()) {
          "public" -> setOf(public)
          "all" -> setOf(public, private)
          else -> return PodRequestAuthentication.Unauthenticated(CHALLENGE)
        }
        return PodRequestAuthentication.Authenticated(
          SempodsCredentials(pod = this@Companion.pod, restrictedContexts = reads.mapTo(HashSet()) { URI(it.stringValue()) }, identified = true),
        )
      }

      override fun challenge(pod: String) = CHALLENGE
      override val credentialHeaders = setOf(READER)
    }

    private val connections = object : PodConnections {
      override fun <T> withConnection(pod: PodRef, block: (RepositoryConnection) -> T): T = store.connection.use(block)
    }

    /**
     * An index that cannot scope itself: it answers everything it holds, each match placed where it
     * was, and a fact of the private context filed under the public one.
     */
    private val index = object : FindAdapter {
      override fun find(request: FindRequest, sandbox: FindSandbox): Model = LinkedHashModel().apply {
        listOf(harbour to public, office to private).forEach { (hit, context) ->
          val observation = bnode()
          add(hit, FIND_RESULT, observation, context)
          add(observation, iri("https://schema.org/text"), literal("…harbour…"), context)
        }
        add(harbour, NAME, literal("Harbour, internal"), public)
      }
    }

    private val injector: Injector = Guice.createInjector(object : AbstractModule() {
      override fun configure() {
        binder().requireExplicitBindings()
        bind(FindEndpoint::class.java)
        bind(PodRequestAuthenticator::class.java).toInstance(authenticator)
        bind(FindService::class.java)
        bind(FindExecutor::class.java).toInstance(VirtualThreadFindExecutor())
        Multibinder.newSetBinder(binder(), FindAdapter::class.java).addBinding().toInstance(index)
        bind(ResourceExpander::class.java).to(SparqlResourceExpander::class.java)
        bind(SparqlResourceExpander::class.java)
        bind(SparqlQueryService::class.java)
        bind(PodConnections::class.java).toInstance(connections)
      }
    })

    private val jersey = ApplicationHandler(ResourceConfig().register(injector.getInstance(FindEndpoint::class.java)))

    @JvmStatic
    @AfterAll
    fun shutDown() {
      store.shutDown()
    }
  }
}
