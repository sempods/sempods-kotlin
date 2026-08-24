package org.sempods.ai

import com.fasterxml.jackson.databind.JsonNode

/**
 * Canonical request contract for [AiService.generateStructuredOutput].
 *
 * @property messages ordered chat messages sent to the provider.
 * @property jsonSchema target JSON schema the provider output must follow.
 * @property model optional provider model override.
 * @property temperature optional sampling setting, interpreted by provider implementation.
 */
data class AiStructuredOutputRequest(
  val messages: List<AiChatMessage>,
  val jsonSchema: JsonNode,
  val model: String? = null,
  val temperature: Double? = null,
)
