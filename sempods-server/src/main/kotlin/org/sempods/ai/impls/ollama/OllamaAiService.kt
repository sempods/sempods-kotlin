package org.sempods.ai.impls.ollama

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
import io.github.oshai.kotlinlogging.KotlinLogging

class OllamaAiService @Inject constructor(
  okHttpClient: OkHttpClient,
  @param:Named(OllamaAiConfig.OLLAMA_BASE_URL) private val ollamaBaseUrl: String,
  @param:Named(OllamaAiConfig.OLLAMA_MODEL) private val defaultModel: String,
) : AiService {

  private val httpClient = CommonsHttpClient(okHttpClient, JsonMappers.default())

  override fun generateStructuredOutput(request: AiStructuredOutputRequest): AiStructuredOutputResponse {
    val normalizedMessages = request.messages.map { message ->
      val content = message.content.trim()
      if (content.isEmpty()) {
        throw AiServiceException("message content must not be blank")
      }
      OllamaMessage(
        role = message.role.value,
        content = content,
      )
    }
    if (normalizedMessages.isEmpty()) {
      throw AiServiceException("at least one message is required")
    }

    val model = request.model?.trim()?.takeIf { it.isNotEmpty() } ?: defaultModel
    val payload = mutableMapOf<String, Any>(
      "model" to model,
      "stream" to false,
      "messages" to normalizedMessages,
      "format" to request.jsonSchema,
    )

    request.temperature?.let { temperature ->
      payload["options"] = mapOf(
        "temperature" to temperature,
      )
    }

    val responseBody = try {
      httpClient.execute(
        httpClient.prepareJsonPost("${ollamaBaseUrl.trimEnd('/')}/api/chat", payload),
        200,
      )
    } catch (error: Exception) {
      (error as? HttpResponseException)?.let { httpError ->
        throw AiServiceException(
          "ollama returned unexpected response (${httpError.statusCode})",
          error,
        )
      }
      throw AiServiceException("ollama request failed", error)
    }

    val parsed = try {
      httpClient.jsonUtil.read(responseBody, OllamaChatResponse::class.java)
    } catch (error: Exception) {
      throw AiServiceException("failed to parse ollama response json", error)
    }

    val content = parsed.message?.content?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw AiServiceException("ollama response did not contain assistant content")

    val json = parseAssistantJsonOrThrow(content)
    val resolvedModel = parsed.model?.trim()?.takeIf { it.isNotEmpty() } ?: model

    logger.debug { "ollama structured response done: model='$resolvedModel', doneReason='${parsed.doneReason}'" }

    return AiStructuredOutputResponse(
      model = resolvedModel,
      content = content,
      json = json,
      doneReason = parsed.doneReason?.takeIf { it.isNotBlank() },
    )
  }

  private fun parseAssistantJsonOrThrow(content: String): JsonNode {
    return try {
      JsonMappers.default().readTree(content)
    } catch (error: Exception) {
      throw AiServiceException("ollama assistant content is not valid json", error)
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}

data class OllamaMessage(
  val role: String,
  val content: String,
)

data class OllamaChatResponse(
  val model: String? = null,
  val message: OllamaMessage? = null,
  val doneReason: String? = null,
)
