package org.sempods.ai.sem

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.trace.TraceContextHolder
import org.sempods.ai.*
import org.sempods.ai.sem.prompts.SempodsPromptBuilder
import org.sempods.ai.sem.prompts.SempodsPromptBuilderFactory
import io.github.oshai.kotlinlogging.KotlinLogging

class AiSemFacade @Inject constructor(
  private val aiService: AiService,
  private val sempodsPromptBuilderFactory: SempodsPromptBuilderFactory,
  private val shaclGuidanceDeriver: AiSemShaclGuidanceDeriver,
) {

  fun text2model(request: AiSemText2ModelRequest): AiSemText2ModelResult {
    val aiResponse = executeSemWebStructuredOutput(
      operation = "text2model",
      systemPrompt = buildText2ModelSystemPrompt(request),
      userPrompt = buildText2ModelUserPrompt(
        contentText = request.contentText,
        language = request.contentLanguage,
      ),
    )
    val graph = normalizeJsonLdGraph(aiResponse.json)
      ?: if (request.strict) {
        throw AiSemValidationException(
          code = "invalid_jsonld_output",
          message = "model output is not a valid JSON-LD graph object",
        )
      } else {
        emptyJsonLdGraph()
      }

    val emptyGraph = isEmptyGraph(graph)
    if (emptyGraph && !request.allowEmpty) {
      throw AiSemValidationException(
        code = "empty_model_not_allowed",
        message = "model returned an empty graph while allowEmpty=false",
      )
    }

    return AiSemText2ModelResult(
      status = if (emptyGraph) "empty" else "ok",
      graph = graph,
      shaclConforms = null,
      validationMode = "not_evaluated_v1",
    )
  }

  fun model2model(request: AiSemModel2ModelRequest): AiSemModel2ModelResult {
    val normalizedCurrentModel = normalizeJsonLdGraph(request.currentModel)
      ?: throw AiSemValidationException(
        code = "invalid_current_model",
        message = "request.currentModel must be a valid JSON-LD graph object",
        status = 400,
      )
    val aiResponse = executeSemWebStructuredOutput(
      operation = "model2model",
      systemPrompt = buildModel2ModelSystemPrompt(
        request = request,
        currentModel = normalizedCurrentModel,
      ),
      userPrompt = buildModel2ModelUserPrompt(
        instructionText = request.instructionText,
        instructionLanguage = request.instructionLanguage,
        currentModel = normalizedCurrentModel,
      ),
    )
    val graph = normalizeJsonLdGraph(aiResponse.json)
      ?: if (request.strict) {
        throw AiSemValidationException(
          code = "invalid_jsonld_output",
          message = "model output is not a valid JSON-LD graph object",
        )
      } else {
        normalizedCurrentModel.deepCopy()
      }

    val noChange = graph == normalizedCurrentModel
    if (noChange && !request.allowNoChange) {
      throw AiSemValidationException(
        code = "no_model_change_not_allowed",
        message = "model update produced no change while allowNoChange=false",
      )
    }

    return AiSemModel2ModelResult(
      status = if (noChange) "no_change" else "ok",
      graph = graph,
      shaclConforms = null,
      validationMode = "not_evaluated_v1",
    )
  }

  private fun executeSemWebStructuredOutput(
    operation: String,
    systemPrompt: String,
    userPrompt: String,
  ): AiStructuredOutputResponse {
    val aiRequest = AiStructuredOutputRequest(
      messages = listOf(
        AiChatMessage(
          role = AiChatRole.system,
          content = systemPrompt,
        ),
        AiChatMessage(
          role = AiChatRole.user,
          content = userPrompt,
        ),
      ),
      jsonSchema = jsonLdGraphSchema.deepCopy(),
    )
    logAiRequest(operation = operation, aiRequest = aiRequest)
    return aiService.generateStructuredOutput(aiRequest).also { aiResponse ->
      logAiResponse(operation = operation, aiResponse = aiResponse)
    }
  }

  private fun buildText2ModelSystemPrompt(
    request: AiSemText2ModelRequest,
  ): String {
    val builder = sempodsPromptBuilderFactory.newBuilder()
    builder.appendLine("You extract semantic model data from free text.")
    builder.appendLine("Return only JSON, no markdown.")
    builder.appendLine("Return JSON-LD graph object with '@graph' array.")
    builder.appendLine("If there are no reliable assertions, return {'@graph':[]}.")
    builder.appendTimestampBlock()
    appendTargetAndGuidance(
      builder = builder,
      shaclSyntax = request.shaclSyntax,
      shaclData = request.shaclData,
      guidanceInstructions = request.guidanceInstructions,
      guidanceTerms = request.guidanceTerms,
      guidanceExamples = request.guidanceExamples,
    )
    return builder.toString().trim()
  }

  private fun buildText2ModelUserPrompt(
    contentText: String,
    language: String?,
  ): String {
    val langLine = language?.trim()?.takeIf { it.isNotEmpty() }?.let { "\nLanguage hint: $it" } ?: ""
    return """
      Input text:
      $contentText$langLine
    """.trimIndent()
  }

  private fun buildModel2ModelSystemPrompt(
    request: AiSemModel2ModelRequest,
    currentModel: ObjectNode,
  ): String {
    val builder = sempodsPromptBuilderFactory.newBuilder()
    builder.appendLine("You update semantic model data from user instructions.")
    builder.appendLine("Return only JSON, no markdown.")
    builder.appendLine("Return JSON-LD graph object with '@graph' array.")
    builder.appendLine("Always return the full updated model graph, not a patch.")
    builder.appendLine("If instruction does not imply a reliable change, return the model unchanged.")
    builder.appendTimestampBlock()
    appendTargetAndGuidance(
      builder = builder,
      shaclSyntax = request.shaclSyntax,
      shaclData = request.shaclData,
      guidanceInstructions = request.guidanceInstructions,
      guidanceTerms = request.guidanceTerms,
      guidanceExamples = request.guidanceExamples,
    )
    builder.appendLine("Current model JSON-LD:")
    builder.appendLine(currentModel.toString())
    return builder.toString().trim()
  }

  private fun buildModel2ModelUserPrompt(
    instructionText: String,
    instructionLanguage: String?,
    currentModel: ObjectNode,
  ): String {
    val langLine = instructionLanguage
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { "\nLanguage hint: $it" }
      ?: ""
    return """
      Current model JSON-LD:
      ${currentModel}
      Instruction text:
      $instructionText$langLine
    """.trimIndent()
  }

  private fun appendTargetAndGuidance(
    builder: SempodsPromptBuilder,
    shaclSyntax: String,
    shaclData: String,
    guidanceInstructions: String?,
    guidanceTerms: JsonNode?,
    guidanceExamples: JsonNode?,
  ) {
    builder.appendLine("Target SHACL (v1 subset) follows.")
    builder.appendLine("SHACL syntax: $shaclSyntax")
    builder.appendLine(shaclData)
    shaclGuidanceDeriver.derive(shaclSyntax, shaclData)?.let { derivedGuidance ->
      derivedGuidance.instructions
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { instructions ->
          builder.appendLine("Auto-derived SHACL guidance instructions:")
          builder.appendLine(instructions)
        }
      derivedGuidance.terms
        ?.takeIf { !it.isEmpty }
        ?.let { terms ->
          builder.appendLine("Auto-derived SHACL guidance terms JSON:")
          builder.appendLine(terms.toString())
        }
    }
    guidanceInstructions
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { instructions ->
        builder.appendLine("Client guidance instructions:")
        builder.appendLine(instructions)
      }
    guidanceTerms
      ?.takeIf { !it.isNull }
      ?.let { terms ->
        builder.appendLine("Client guidance terms JSON:")
        builder.appendLine(terms.toString())
      }
    guidanceExamples
      ?.takeIf { !it.isNull }
      ?.let { examples ->
        builder.appendLine("Client guidance examples JSON:")
        builder.appendLine(examples.toString())
      }
  }

  private fun normalizeJsonLdGraph(value: JsonNode): ObjectNode? {
    return when {
      value.isObject -> normalizeJsonLdObject(value as ObjectNode)
      value.isArray -> JsonMappers.default().createObjectNode().also { root ->
        root.set<JsonNode>("@graph", (value as ArrayNode).deepCopy())
      }

      else -> null
    }
  }

  private fun normalizeJsonLdObject(value: ObjectNode): ObjectNode? {
    if (value.has("@graph")) {
      val graph = value.get("@graph")
      if (!graph.isArray) {
        return null
      }
      return value.deepCopy()
    }

    val normalized = JsonMappers.default().createObjectNode()
    value.get("@context")?.let { normalized.set<JsonNode>("@context", it.deepCopy()) }

    val node = value.deepCopy()
    node.remove("@context")
    normalized.putArray("@graph").add(node)
    return normalized
  }

  private fun emptyJsonLdGraph(): ObjectNode {
    return JsonMappers.default().createObjectNode().also { root ->
      root.putArray("@graph")
    }
  }

  private fun isEmptyGraph(graph: ObjectNode): Boolean {
    val array = graph.get("@graph") as? ArrayNode ?: return false
    return array.isEmpty
  }

  private fun logAiRequest(
    operation: String,
    aiRequest: AiStructuredOutputRequest,
  ) {
    val traceId = TraceContextHolder.getTraceId() ?: "-"
    val messages = aiRequest.messages.joinToString(separator = "\n") { message ->
      "${message.role.value}: ${message.content}"
    }
    logger.info { "ai.semweb.$operation request traceId='$traceId' messages=\n$messages" }
  }

  private fun logAiResponse(
    operation: String,
    aiResponse: AiStructuredOutputResponse,
  ) {
    val traceId = TraceContextHolder.getTraceId() ?: "-"
    logger.info {
      "ai.semweb.$operation response traceId='$traceId' model='${aiResponse.model}' doneReason='${aiResponse.doneReason ?: "-"}' content=${aiResponse.content}"
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    // TODO(ai-semweb-v2): Replace this temporary generic graph schema with compiled constraints from SHACL subset.
    private val jsonLdGraphSchema: JsonNode = JsonMappers.default().readTree(
      """
        {
          "type": "object",
          "additionalProperties": true,
          "required": ["@graph"],
          "properties": {
            "@context": {},
            "@graph": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": true
              }
            }
          }
        }
      """.trimIndent()
    )
  }
}
