package org.sempods

import com.google.inject.Binding
import com.google.inject.spi.Elements
import com.google.inject.spi.Message
import org.junit.jupiter.api.Test
import org.sempods.ai.AiService
import org.sempods.ai.sem.AiSemFacade
import org.sempods.api.pod.system.ai.semweb.PodAiSemWebEndpoint
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SempodsAiBindingTest {

  @Test
  fun `unset blank and disabled providers register no AI service or routes`() {
    for (provider in listOf(null, "", "  ", "disabled", " DISABLED ")) {
      val elements = Elements.getElements(SempodsModule(aiProvider = provider))
      assertTrue(elements.filterIsInstance<Message>().isEmpty(), "Configuration failed for $provider")
      val types = elements.filterIsInstance<Binding<*>>().map { it.key.typeLiteral.rawType }
      assertFalse(AiService::class.java in types)
      assertFalse(AiSemFacade::class.java in types)
      assertFalse(PodAiSemWebEndpoint::class.java in types)
    }
  }

  @Test
  fun `explicit ollama provider registers the AI service and routes`() {
    val elements = Elements.getElements(SempodsModule(aiProvider = " OLLAMA "))
    assertTrue(elements.filterIsInstance<Message>().isEmpty())
    val types = elements.filterIsInstance<Binding<*>>().map { it.key.typeLiteral.rawType }
    assertTrue(AiService::class.java in types)
    assertTrue(AiSemFacade::class.java in types)
    assertTrue(PodAiSemWebEndpoint::class.java in types)
  }

  @Test
  fun `unknown provider fails configuration instead of silently enabling AI`() {
    val errors = Elements.getElements(SempodsModule(aiProvider = "typo"))
      .filterIsInstance<Message>()
    assertTrue(errors.any { it.message.contains("unsupported AI_PROVIDER") })
  }
}
