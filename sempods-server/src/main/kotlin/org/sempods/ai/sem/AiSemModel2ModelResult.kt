package org.sempods.ai.sem

import com.fasterxml.jackson.databind.JsonNode

data class AiSemModel2ModelResult(
  val status: String,
  val graph: JsonNode,
  val shaclConforms: Boolean?,
  val validationMode: String,
)
