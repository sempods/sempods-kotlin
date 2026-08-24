package org.sempods.ai.impls.openai

import com.fasterxml.jackson.databind.JsonNode
import com.google.inject.Inject
import com.google.inject.name.Named
import okhttp3.OkHttpClient
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.CommonsHttpClient
import org.sempods.commons.okhttp.HttpResponseException
import org.sempods.ai.AiService
import org.sempods.ai.AiServiceException
import org.sempods.ai.AiStructuredOutputRequest
import org.sempods.ai.AiStructuredOutputResponse

class OpenAiService @Inject constructor(
  okHttpClient: OkHttpClient,
  @param:Named(OpenAiConfig.OPENAI_BASE_URL) private val openAiBaseUrl: String,
  @param:Named(OpenAiConfig.OPENAI_API_KEY) private val openAiApiKey: String,
  @param:Named(OpenAiConfig.OPENAI_MODEL) private val defaultModel: String,
) : AiService {

  private val httpClient = CommonsHttpClient(okHttpClient, JsonMappers.default())

  override fun generateStructuredOutput(request: AiStructuredOutputRequest): AiStructuredOutputResponse {
    val normalizedMessages = request.messages.map { message ->
      val content = message.content.trim()
      if (content.isEmpty()) {
        throw AiServiceException("message content must not be blank")
      }
      mapOf(
        "role" to message.role.value,
        "content" to content,
      )
    }
    if (normalizedMessages.isEmpty()) {
      throw AiServiceException("at least one message is required")
    }

    val model = request.model?.trim()?.takeIf { it.isNotEmpty() } ?: defaultModel
    val payload = mutableMapOf<String, Any>(
      "model" to model,
      "messages" to normalizedMessages,
      "response_format" to mapOf(
        "type" to "json_schema",
        "json_schema" to mapOf(
          "name" to "sempods_structured_output",
          "strict" to false,
          "schema" to request.jsonSchema,
        ),
      ),
    )
    request.temperature?.let { temperature ->
      payload["temperature"] = temperature
    }

    val responseBody = try {
      httpClient.execute(
        httpClient.prepareJsonPost("${openAiBaseUrl.trimEnd('/')}/v1/chat/completions", payload)
          .addHeader("Authorization", "Bearer ${openAiApiKey.trim()}")
          .addHeader("Accept", "application/json"),
        200,
      )
    } catch (error: Exception) {
      (error as? HttpResponseException)?.let { httpError ->
        val bodySnippet = compactSnippet(httpError.responseBody)
        throw AiServiceException(
          buildString {
            append("openai returned unexpected response (${httpError.statusCode})")
            if (bodySnippet != null) {
              append(": ")
              append(bodySnippet)
            }
          },
          error,
        )
      }
      throw AiServiceException("openai request failed", error)
    }

    val parsedResponse = try {
      JsonMappers.default().readTree(responseBody)
    } catch (error: Exception) {
      throw AiServiceException("failed to parse openai response json", error)
    }

    val firstChoice = parsedResponse.path("choices").firstOrNull()
      ?: throw AiServiceException("openai response did not contain choices")
    val content = firstChoice.path("message").path("content").asText("").trim()
      .takeIf { it.isNotEmpty() }
      ?: throw AiServiceException("openai response did not contain assistant content")

    val json = parseAssistantJsonOrThrow(content)
    val resolvedModel = parsedResponse.path("model").asText("").trim().ifEmpty { model }
    val doneReason = firstChoice.path("finish_reason").asText("").trim().ifEmpty { null }

    return AiStructuredOutputResponse(
      model = resolvedModel,
      content = content,
      json = json,
      doneReason = doneReason,
    )
  }

  private fun parseAssistantJsonOrThrow(content: String): JsonNode {
    return try {
      JsonMappers.default().readTree(content)
    } catch (error: Exception) {
      throw AiServiceException("openai assistant content is not valid json", error)
    }
  }

  private fun compactSnippet(value: String?): String? {
    val trimmed = value?.trim()?.replace("\\s+".toRegex(), " ") ?: return null
    if (trimmed.isEmpty()) {
      return null
    }
    return trimmed.take(500)
  }
}
