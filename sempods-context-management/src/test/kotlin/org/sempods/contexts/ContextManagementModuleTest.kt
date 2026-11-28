package org.sempods.contexts

import com.google.inject.Binding
import com.google.inject.Key
import com.google.inject.spi.Elements
import jakarta.ws.rs.HttpMethod
import org.junit.jupiter.api.Test
import org.sempods.pods.contexts.ContextManagementInstalled
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What installing the module adds to a composition: the lifecycle route and the marker the server
 * reads to advertise it. Read off the module's bindings, without an injector: building one needs the
 * whole server, and the lifecycle behind the route runs in the server's HTTP suite.
 */
class ContextManagementModuleTest {

  private val bindings = Elements.getElements(ContextManagementModule).filterIsInstance<Binding<*>>()

  @Test
  fun `it binds the lifecycle route and tells the server it serves it`() {
    assertTrue(bindings.any { it.key == Key.get(PodContextLifecycleEndpoint::class.java) }, "the route")
    assertTrue(bindings.any { it.key.typeLiteral.rawType == ContextManagementInstalled::class.java }, "the marker")
  }

  @Test
  fun `the route creates and deletes, and reads nothing`() {
    val verbs = PodContextLifecycleEndpoint::class.java.methods
      .flatMap { method -> method.annotations.mapNotNull { it.annotationClass.java.getAnnotation(HttpMethod::class.java)?.value } }
      .toSet()

    assertEquals(setOf("PUT", "DELETE"), verbs)
  }
}
