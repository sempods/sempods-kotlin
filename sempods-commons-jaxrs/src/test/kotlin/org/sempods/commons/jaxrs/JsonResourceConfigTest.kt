package org.sempods.commons.jaxrs

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.glassfish.jersey.internal.MapPropertiesDelegate
import org.glassfish.jersey.server.ApplicationHandler
import org.glassfish.jersey.server.ContainerRequest
import org.sempods.commons.json.JsonMappers
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [jsonResourceConfig]: which mapper Jersey serialises with, and what a body it cannot read answers.
 *
 * Both fail silently when they break. A resolver the provider does not find leaves Jersey on a stock
 * mapper, and a missing exception mapper turns a client's malformed body into a `500`.
 */
class JsonResourceConfigTest {

  class Shown(private val secret: String = "s") {
    @Suppress("unused")
    fun getComputed(): String = "not serialised"
  }

  data class Counted(val count: Int)

  @Path("/")
  class Resource {

    @GET
    @Path("shown")
    @Produces(MediaType.APPLICATION_JSON)
    fun shown(): Shown = Shown()

    @POST
    @Path("counted")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun counted(body: Counted): Counted = body
  }

  private val handler = ApplicationHandler(
    jsonResourceConfig()
      .registerInstances(Resource(), ObjectMapperResolver(JsonMappers.default())),
  )

  private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
    val request = ContainerRequest(
      URI.create("http://x/"), URI.create("http://x$path"), method,
      null, MapPropertiesDelegate(), null,
    )
    request.header("Accept", MediaType.APPLICATION_JSON)
    if (body != null) {
      request.header("Content-Type", MediaType.APPLICATION_JSON)
      request.entityStream = body.byteInputStream()
    }
    val out = ByteArrayOutputStream()
    val response = handler.apply(request, out).get()
    return response.status to out.toString(Charsets.UTF_8)
  }

  @Test
  fun `a response is written with the resolver's mapper`() {
    assertEquals(200 to """{"secret":"s"}""", call("GET", "/shown"))
  }

  @Test
  fun `a body is read with the resolver's mapper`() {
    assertEquals(200 to """{"count":3}""", call("POST", "/counted", """{"count":3}"""))
  }

  @Test
  fun `a body that is not JSON answers 400`() {
    assertEquals(400, call("POST", "/counted", "{not json").first)
  }

  @Test
  fun `a body of the wrong shape answers 400`() {
    assertEquals(400, call("POST", "/counted", """{"count":"many"}""").first)
  }
}
