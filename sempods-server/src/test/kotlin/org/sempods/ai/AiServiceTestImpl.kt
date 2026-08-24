package org.sempods.ai

import org.sempods.commons.json.JsonMappers

class AiServiceTestImpl : AiService {

  override fun generateStructuredOutput(request: AiStructuredOutputRequest): AiStructuredOutputResponse {
    val messages = request.messages
    if (messages.isEmpty()) {
      throw AiServiceException("at least one message is required")
    }

    val lastUserContent = messages.lastOrNull { it.role == AiChatRole.user }?.content
      ?: messages.last().content
    val json = JsonMappers.default().createObjectNode()
      .put("echo", lastUserContent)
      .put("messageCount", messages.size)

    return AiStructuredOutputResponse(
      model = request.model?.takeIf { it.isNotBlank() } ?: "test-model",
      content = json.toString(),
      json = json,
      doneReason = "stop",
    )
  }
}
