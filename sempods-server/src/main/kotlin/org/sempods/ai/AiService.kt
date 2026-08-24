package org.sempods.ai

/**
 * Provider-agnostic AI interface for structured generation.
 *
 * Contract source of truth:
 * - request shape: [AiStructuredOutputRequest]
 * - response shape: [AiStructuredOutputResponse]
 * - failure model: [AiServiceException]
 */
interface AiService {

  /**
   * Generates structured JSON output constrained by [AiStructuredOutputRequest.jsonSchema].
   *
   * Implementations must throw [AiServiceException] for provider/network/runtime failures.
   */
  fun generateStructuredOutput(request: AiStructuredOutputRequest): AiStructuredOutputResponse
}
