package org.sempods.ai

import com.fasterxml.jackson.databind.JsonNode

/**
 * Canonical response contract for [AiService.generateStructuredOutput].
 *
 * @property model provider/model identifier used for the generation.
 * @property content raw textual provider content.
 * @property json parsed JSON representation of [content].
 * @property doneReason optional provider-specific finish reason.
 */
data class AiStructuredOutputResponse(
  val model: String,
  val content: String,
  val json: JsonNode,
  val doneReason: String? = null,
)
