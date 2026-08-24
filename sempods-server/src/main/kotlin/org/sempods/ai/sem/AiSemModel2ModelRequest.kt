package org.sempods.ai.sem

import com.fasterxml.jackson.databind.JsonNode

/**
 * Internal semweb update contract used by [AiSemFacade.model2model].
 *
 * This type maps endpoint DTOs into a stable facade-level request.
 */
data class AiSemModel2ModelRequest(
  val instructionText: String,
  val instructionLanguage: String? = null,
  val currentModel: JsonNode,
  val shaclSyntax: String,
  val shaclData: String,
  val guidanceInstructions: String? = null,
  val guidanceTerms: JsonNode? = null,
  val guidanceExamples: JsonNode? = null,
  val strict: Boolean = true,
  val allowNoChange: Boolean = true,
)
