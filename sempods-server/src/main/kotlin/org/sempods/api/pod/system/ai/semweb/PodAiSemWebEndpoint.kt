package org.sempods.api.pod.system.ai.semweb

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.google.inject.Inject
import org.sempods.commons.jaxrs.errors.ApiException
import org.sempods.ai.AiSemValidationException
import org.sempods.ai.AiServiceException
import org.sempods.ai.sem.AiSemFacade
import org.sempods.ai.sem.AiSemModel2ModelRequest
import org.sempods.ai.sem.AiSemText2ModelRequest
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import io.github.oshai.kotlinlogging.KotlinLogging

@Path("{pod}/_system/ai/semweb")
@Consumes(MediaType.APPLICATION_JSON)
@Produces("application/ld+json")
class PodAiSemWebEndpoint @Inject constructor(
  private val aiSemFacade: AiSemFacade,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  @POST
  @Path("text2model")
  fun text2model(
    @PathParam("pod") pod: String,
    request: Text2ModelRequest,
  ): Response {
    val credentials = requirePodAppTokenOrThrow(pod)

    val contentText = request.content?.text?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw webError(
        status = 400,
        code = "missing_content",
        message = "request.content.text must not be blank",
      )
    val shacl = requireShaclTarget(request.target)

    val result = try {
      aiSemFacade.text2model(
        AiSemText2ModelRequest(
          contentText = contentText,
          contentLanguage = request.content.language,
          shaclSyntax = shacl.syntax,
          shaclData = shacl.data,
          guidanceInstructions = request.guidance?.instructions,
          guidanceTerms = request.guidance?.terms,
          guidanceExamples = request.guidance?.examples,
          strict = request.options?.strict ?: true,
          allowEmpty = request.options?.allowEmpty ?: true,
        )
      )
    } catch (error: AiSemValidationException) {
      throw webError(
        status = error.status,
        code = error.code,
        message = error.message ?: "invalid model output",
      )
    } catch (error: AiServiceException) {
      logger.warn { "text2model failed for pod '$pod', appId='${credentials.oauthClientId}': ${error.message}" }
      throw webError(
        status = 500,
        code = "ai_provider_error",
        message = "AI provider request failed",
      )
    }

    val response = Text2ModelResponse(
      status = result.status,
      graph = result.graph,
      validation = Text2ModelValidation(
        shaclConforms = result.shaclConforms,
        mode = result.validationMode,
      ),
    )
    return Response.ok(response)
      .type("application/ld+json")
      .build()
  }

  @POST
  @Path("model2model")
  fun model2model(
    @PathParam("pod") pod: String,
    request: Model2ModelRequest,
  ): Response {
    val credentials = requirePodAppTokenOrThrow(pod)

    val instructionText = request.instruction?.text?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw webError(
        status = 400,
        code = "missing_instruction",
        message = "request.instruction.text must not be blank",
      )
    val currentModel = request.currentModel
      ?: throw webError(
        status = 400,
        code = "missing_current_model",
        message = "request.currentModel must not be null",
      )
    val shacl = requireShaclTarget(request.target)

    val result = try {
      aiSemFacade.model2model(
        AiSemModel2ModelRequest(
          instructionText = instructionText,
          instructionLanguage = request.instruction.language,
          currentModel = currentModel,
          shaclSyntax = shacl.syntax,
          shaclData = shacl.data,
          guidanceInstructions = request.guidance?.instructions,
          guidanceTerms = request.guidance?.terms,
          guidanceExamples = request.guidance?.examples,
          strict = request.options?.strict ?: true,
          allowNoChange = request.options?.allowNoChange ?: true,
        )
      )
    } catch (error: AiSemValidationException) {
      throw webError(
        status = error.status,
        code = error.code,
        message = error.message ?: "invalid model output",
      )
    } catch (error: AiServiceException) {
      logger.warn { "model2model failed for pod '$pod', appId='${credentials.oauthClientId}': ${error.message}" }
      throw webError(
        status = 500,
        code = "ai_provider_error",
        message = "AI provider request failed",
      )
    }

    val response = Model2ModelResponse(
      status = result.status,
      graph = result.graph,
      validation = Text2ModelValidation(
        shaclConforms = result.shaclConforms,
        mode = result.validationMode,
      )
    )
    return Response.ok(response)
      .type("application/ld+json")
      .build()
  }

  private fun requireShaclTarget(target: Text2ModelTarget?): ShaclTarget {
    val shaclSyntax = target?.shacl?.syntax?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw webError(
        status = 400,
        code = "missing_shacl_syntax",
        message = "request.target.shacl.syntax must not be blank",
      )
    val shaclData = target.shacl.data?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw webError(
        status = 400,
        code = "missing_shacl_data",
        message = "request.target.shacl.data must not be blank",
      )
    if (shaclSyntax !in supportedShaclSyntax) {
      throw webError(
        status = 400,
        code = "unsupported_shacl_syntax",
        message = "unsupported SHACL syntax '$shaclSyntax'",
        details = mapOf("supported" to supportedShaclSyntax.sorted()),
      )
    }
    return ShaclTarget(
      syntax = shaclSyntax,
      data = shaclData,
    )
  }

  private fun webError(
    status: Int,
    code: String,
    message: String,
    details: Map<String, Any?>? = null,
  ): ApiException {
    val parameters = details
      ?.mapNotNull { (key, value) -> value?.let { key to it } }
      ?.toMap()
      ?: emptyMap()
    return ApiException(
      errorId = code,
      errorMessage = message,
      errorParameters = parameters,
      statusCode = status,
    )
  }

  private data class ShaclTarget(
    val syntax: String,
    val data: String,
  )

  companion object {
    private val logger = KotlinLogging.logger {}
    private val supportedShaclSyntax = setOf("text/turtle", "application/ld+json")
  }
}

/** API request contract for `POST /{pod}/_system/ai/semweb/text2model`. */
data class Text2ModelRequest(
  val content: Text2ModelContent?,
  val target: Text2ModelTarget?,
  val guidance: Text2ModelGuidance? = null,
  val options: Text2ModelOptions? = null,
)

/** API request content block for `POST /{pod}/_system/ai/semweb/text2model`. */
data class Text2ModelContent(
  val text: String?,
  val language: String? = null,
)

/** API request target model block (current v1 supports SHACL payload input). */
data class Text2ModelTarget(
  val shacl: Text2ModelShacl?,
)

/** SHACL payload descriptor used as extraction target context. */
data class Text2ModelShacl(
  val syntax: String?,
  val data: String?,
)

/** Optional extraction hints forwarded to the AI prompt assembly. */
data class Text2ModelGuidance(
  val instructions: String? = null,
  val terms: JsonNode? = null,
  val examples: JsonNode? = null,
)

/** Endpoint options controlling strictness and empty-result behavior. */
data class Text2ModelOptions(
  val strict: Boolean? = null,
  val allowEmpty: Boolean? = null,
)

/** API response contract for `POST /{pod}/_system/ai/semweb/text2model`. */
data class Text2ModelResponse(
  val status: String,
  val graph: JsonNode,
  val validation: Text2ModelValidation,
)

/** API request contract for `POST /{pod}/_system/ai/semweb/model2model`. */
data class Model2ModelRequest(
  val instruction: Model2ModelInstruction?,
  val currentModel: JsonNode?,
  val target: Text2ModelTarget?,
  val guidance: Text2ModelGuidance? = null,
  val options: Model2ModelOptions? = null,
)

/** API instruction block for `POST /{pod}/_system/ai/semweb/model2model`. */
data class Model2ModelInstruction(
  val text: String?,
  val language: String? = null,
)

/** Endpoint options controlling strictness and no-change behavior. */
data class Model2ModelOptions(
  val strict: Boolean? = null,
  val allowNoChange: Boolean? = null,
)

/** API response contract for `POST /{pod}/_system/ai/semweb/model2model`. */
data class Model2ModelResponse(
  val status: String,
  val graph: JsonNode,
  val validation: Text2ModelValidation,
)

/** Validation metadata returned with the extracted graph. */
data class Text2ModelValidation(
  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_NULL)]
  val shaclConforms: Boolean?,
  val mode: String,
)
