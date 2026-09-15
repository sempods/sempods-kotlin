package org.sempods.client

import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.core.SempodsOkHttp
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsSession
import org.sempods.client.core.SempodsWriteOptions
import org.sempods.commons.net.SempodsPodRoutes
import org.slf4j.event.Level

/**
 * The routes the core's endpoint groups send and the copies the wire layer and the RDF tiers read from
 * [SempodsPodRoutes]. They are held equal here until #152 moves those layers onto the core and removes
 * the copies.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsPodRoutesParityTest {

  private lateinit var server: ClientAndServer

  /** Each request's path as OkHttp wrote it; MockServer would record it decoded. */
  private val paths = CopyOnWriteArrayList<String>()

  private val client = SempodsOkHttp.install(
    OkHttpClient.Builder().addNetworkInterceptor { chain ->
      paths += chain.request().url.encodedPath
      chain.proceed(chain.request())
    },
  ).build()

  @BeforeAll
  fun start() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    server.`when`(request()).respond(response().withStatusCode(200).withBody("{}"))
  }

  @AfterAll
  fun stop() {
    server.stop()
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }

  @BeforeEach
  fun forget() {
    paths.clear()
  }

  private fun pod() = SempodsPod(SempodsSession(SempodsPodBase.of("http://localhost:${server.port}/alice")), client)

  @Test
  fun `a subject, a slot and an edge go where SempodsPodRoutes points`() {
    val predicate = "http://xmlns.com/foaf/0.1/knows"

    listOf("did:web:bob.example", "urn:x:ab~", "https://example.org/ü", "https://pods.example/alice/contacts/grüße").forEach { iri ->
      paths.clear()

      pod().subjects().getText(iri)
      pod().slots().getJson(iri, predicate)
      pod().slots().removeEdge(iri, predicate, iri, SempodsWriteOptions.inContext("urn:tasks"))

      val subject = URI.create(iri)
      val routes = listOf(
        SempodsPodRoutes.resource(subject),
        SempodsPodRoutes.slot(subject, URI.create(predicate)),
        SempodsPodRoutes.slotValue(subject, URI.create(predicate), subject),
      )
      assertEquals(routes.map { "/alice/$it" }, paths, iri)
    }
  }

  @Test
  fun `pod metadata and SPARQL go where SempodsPodRoutes points`() {
    pod().metadata().dateModifiedJson()
    pod().sparql().resultsJson("ASK {}")

    assertEquals(listOf(SempodsPodRoutes.META_DATE_MODIFIED, SempodsPodRoutes.SPARQL_QUERY).map { "/alice/$it" }, paths)
  }
}
