package org.sempods.ai.sem

import com.fasterxml.jackson.databind.JsonNode

/**
 * Internal semweb extraction contract used by [AiSemFacade.text2model].
 *
 * This type maps endpoint DTOs into a stable facade-level request.
 */
data class AiSemText2ModelRequest(
  val contentText: String,
  val contentLanguage: String? = null,
  val shaclSyntax: String,
  val shaclData: String,
  val guidanceInstructions: String? = null,
  val guidanceTerms: JsonNode? = null,
  val guidanceExamples: JsonNode? = null,
  val strict: Boolean = true,
  val allowEmpty: Boolean = true,
)
