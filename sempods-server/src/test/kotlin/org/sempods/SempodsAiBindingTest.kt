package org.sempods

import com.google.inject.Binding
import com.google.inject.spi.Element
import com.google.inject.spi.Elements
import com.google.inject.spi.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.sempods.ai.AiService
import org.sempods.ai.sem.AiSemFacade
import org.sempods.api.pod.system.ai.semweb.PodAiSemWebEndpoint
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Isolated("Changes AI_PROVIDER while recording module bindings")
class SempodsAiBindingTest {

  @Test
  fun `unset blank and disabled providers register no AI service or routes`() {
    for (provider in listOf(null, "", "  ", "disabled", " DISABLED ")) {
      val elements = elementsFor(provider)
      assertTrue(elements.filterIsInstance<Message>().isEmpty(), "Configuration failed for $provider")
      val types = elements.filterIsInstance<Binding<*>>().map { it.key.typeLiteral.rawType }
      assertFalse(AiService::class.java in types)
      assertFalse(AiSemFacade::class.java in types)
      assertFalse(PodAiSemWebEndpoint::class.java in types)
    }
  }

  @Test
  fun `explicit ollama provider registers the AI service and routes`() {
    val elements = elementsFor(" OLLAMA ")
    assertTrue(elements.filterIsInstance<Message>().isEmpty())
    val types = elements.filterIsInstance<Binding<*>>().map { it.key.typeLiteral.rawType }
    assertTrue(AiService::class.java in types)
    assertTrue(AiSemFacade::class.java in types)
    assertTrue(PodAiSemWebEndpoint::class.java in types)
  }

  @Test
  fun `unknown provider fails configuration instead of silently enabling AI`() {
    val errors = elementsFor("typo")
      .filterIsInstance<Message>()
    assertTrue(errors.any { it.message.contains("unsupported AI_PROVIDER") })
  }

  private fun elementsFor(provider: String?): List<Element> {
    val previous = System.getProperty("AI_PROVIDER")
    try {
      if (provider == null) System.clearProperty("AI_PROVIDER")
      else System.setProperty("AI_PROVIDER", provider)
      return Elements.getElements(SempodsModule())
    } finally {
      if (previous == null) System.clearProperty("AI_PROVIDER")
      else System.setProperty("AI_PROVIDER", previous)
    }
  }
}
