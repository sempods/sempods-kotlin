package org.sempods.ai.sem

import tools.jackson.databind.JsonNode

data class AiSemText2ModelResult(
  val status: String,
  val graph: JsonNode,
  val shaclConforms: Boolean?,
  val validationMode: String,
)
