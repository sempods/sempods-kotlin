package org.sempods.commons.jaxrs

import com.google.inject.Injector
import com.google.inject.Key
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.TypeLiteral
import org.sempods.commons.jaxrs.filters.TraceContextFilter
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.ContextHandler
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.eclipse.jetty.server.handler.gzip.GzipHandler
import org.eclipse.jetty.util.thread.VirtualThreadPool
import org.glassfish.jersey.jackson.JacksonFeature
import org.glassfish.jersey.server.ContainerFactory
import org.glassfish.jersey.server.ResourceConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.commons.guice.BaseModule

object JaxRsServerModule : BaseModule() {

  @Provides
  @Singleton
  internal fun server(injector: Injector): Server {

    logger.info { "starting jetty server" }

    val threadPool = VirtualThreadPool().apply {
      name = "jetty-vthreads"
    }
    val server = Server(threadPool)
    server.stopAtShutdown = true
    server.stopTimeout = 30_000

    val applications = createApplications(injector)

    // Create handlers and connectors
    val contextHandlerCollection = ContextHandlerCollection()
    val connectors = mutableListOf<ServerConnector>()

    applications.forEach { (httpPort, application) ->

      logger.info { "Creating connector and handler for port $httpPort" }

      // Create connector for this port
      val config = HttpConfiguration()
      val connector = ServerConnector(server, HttpConnectionFactory(config))
      connector.port = httpPort
      connector.name = "port-$httpPort"  // Give the connector a unique name
      connectors.add(connector)

      // Create Jersey container
      val jerseyHandler = requireNotNull(
        ContainerFactory.createContainer(Handler::class.java, application),
      ) { "Could not create Jetty handler for port $httpPort" }

      // Wrap in gzip handler
      val gzipHandler = GzipHandler()
      gzipHandler.handler = jerseyHandler

      // Wrap in context handler and set virtual host to bind to specific connector
      val context = ContextHandler()
      context.contextPath = "/"
      context.handler = gzipHandler
      context.virtualHosts = mutableListOf("@port-$httpPort")  // Bind to specific connector by name

      contextHandlerCollection.addHandler(context)
    }

    // Set all handlers and connectors
    server.handler = contextHandlerCollection
    server.connectors = connectors.toTypedArray()

    server.start()

    logger.info {
      "Done: started jetty server with ${connectors.size} connectors on ports: ${applications.keys.sorted()}"
    }

    return server
  }

  private fun createApplications(injector: Injector): Map<Int, ResourceConfig> {

    val key = Key.get(object : TypeLiteral<Set<JaxRsEndpointBinding>>() {})
    val endpointBindings = injector.getInstance(key)

    val map = mutableMapOf<Int, MutableSet<Class<out Any>>>()
    endpointBindings.forEach { binding ->
      map.computeIfAbsent(binding.httpPort) { mutableSetOf() }.addAll(binding.endpoints)
    }

    return map.map { (httpPort, endpointClasses) ->

      // TODO: split "resources" (instances?) and "endpoints" (types -> singletons)
      val jaxRsResources = buildSet {
        // Get instances from the injector for each endpoint class
        endpointClasses.forEach { endpointClass ->
          add(injector.getInstance(endpointClass))
        }
        // The two carriers, on every connector rather than per composition: `ApiExceptionMapper`
        // reads both to name the request that failed, and a port whose composition forgot one
        // would log failures nothing can be traced back to.
        add(TraceContextFilter())
        add(ContainerRequestHolderFilter())
        add(JaxRsDebugFilter())
      }

      // Log registered endpoints with their paths
      logger.info { "Registering ${jaxRsResources.size} JAX-RS resources for port $httpPort:" }
      jaxRsResources.forEach { resource ->
        val pathAnnotation = resource.javaClass.getAnnotation(jakarta.ws.rs.Path::class.java)
        val path = pathAnnotation?.value ?: "(no @Path)"
        logger.info { "  - ${resource.javaClass.simpleName}: @Path(\"$path\")" }
      }

      return@map httpPort to ResourceConfig()
        .register(JacksonFeature::class.java)
        .registerInstances(jaxRsResources)
    }.toMap()
  }

  private val logger = KotlinLogging.logger {}
}
