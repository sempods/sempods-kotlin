package org.sempods.ai

import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.SempodsIntegrationTest
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AiServiceIntegrationTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var aiService: AiService

  @Inject
  private lateinit var aiServiceTestObserver: AiServiceTestObserver

  @Test
  fun `should use ai test implementation by default in integration tests`() {
    val response = aiService.generateStructuredOutput(
      AiStructuredOutputRequest(
        messages = listOf(
          AiChatMessage(
            role = AiChatRole.user,
            content = "Morgen Auto zur Reparatur bringen",
          )
        ),
        jsonSchema = JsonMappers.default().readTree("""{"type":"object"}"""),
      )
    )

    assertEquals("test-model", response.model)
    assertEquals("Morgen Auto zur Reparatur bringen", response.json.get("echo").asText())
    assertEquals(1, response.json.get("messageCount").asInt())
  }

  @Test
  fun `should allow observing and overriding ai service calls`() {
    aiServiceTestObserver.observeWithTestImpl { delegate ->
      every { delegate.generateStructuredOutput(any()) } returns AiStructuredOutputResponse(
        model = "override-model",
        content = """{"name":"synthetic"}""",
        json = JsonMappers.default().readTree("""{"name":"synthetic"}"""),
        doneReason = "stop",
      )

      val response = aiService.generateStructuredOutput(
        AiStructuredOutputRequest(
          messages = listOf(
            AiChatMessage(
              role = AiChatRole.user,
              content = "Test input",
            )
          ),
          jsonSchema = JsonMappers.default().readTree("""{"type":"object"}"""),
        )
      )

      assertEquals("override-model", response.model)
      assertEquals("synthetic", response.json.get("name").asText())
      verify(exactly = 1) {
        delegate.generateStructuredOutput(
          match { request ->
            request.messages.size == 1 &&
              request.messages.first().role == AiChatRole.user &&
              request.messages.first().content == "Test input"
          }
        )
      }
    }
  }

  @Test
  fun `should allow observing with explicit delegate instance`() {
    val delegate = object : AiService {
      override fun generateStructuredOutput(request: AiStructuredOutputRequest): AiStructuredOutputResponse {
        return AiStructuredOutputResponse(
          model = "explicit-delegate",
          content = """{"ok":true}""",
          json = JsonMappers.default().readTree("""{"ok":true}"""),
          doneReason = "stop",
        )
      }
    }

    aiServiceTestObserver.observeWithDelegate(delegate) {
      val response = aiService.generateStructuredOutput(
        AiStructuredOutputRequest(
          messages = listOf(
            AiChatMessage(
              role = AiChatRole.user,
              content = "ignored",
            )
          ),
          jsonSchema = JsonMappers.default().readTree("""{"type":"object"}"""),
        )
      )
      assertEquals("explicit-delegate", response.model)
      assertEquals(true, response.json.get("ok").asBoolean())
    }
  }
}
