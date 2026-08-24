# sempods.org - AI Layer (Current State / IST)

## Purpose

This document explains the AI layer at a high level.
Exact request/response contracts are documented in code (KDoc) and treated as source of truth.

## High-Level Design

- `AiService` is the provider-agnostic entry point for structured generation.
- `AiSemFacade` uses `AiService` for semweb extraction/update (`text2model`, `model2model`).
- `PodAiSemWebEndpoint` maps HTTP request/response to the facade.

Simple runtime paths:

1. Endpoint receives `text2model` or `model2model` request.
2. Facade builds prompts + JSON schema and calls `AiService`.
3. Active provider (Ollama or OpenAI) executes the model call.
4. Facade normalizes output to JSON-LD `@graph` envelope.

## Contract Source (Code)

Use these files for exact field-level contracts:

- `sempods-server/src/main/kotlin/org/sempods/ai/AiService.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/AiStructuredOutputRequest.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/AiStructuredOutputResponse.kt`
- `sempods-server/src/main/kotlin/org/sempods/api/pod/system/ai/semweb/PodAiSemWebEndpoint.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/sem/AiSemText2ModelRequest.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/sem/AiSemModel2ModelRequest.kt`

## Minimal Example

```kotlin
val response = aiService.generateStructuredOutput(
  AiStructuredOutputRequest(
    messages = listOf(AiChatMessage(AiChatRole.user, "Extract one schema:Action.")),
    jsonSchema = schemaNode,
  )
)
```

## Providers (IST)

- `AI_PROVIDER=ollama` -> `OllamaAiService`
- `AI_PROVIDER=openai` -> `OpenAiService`
- unset -> `ollama`, and any other value fails the boot

**There is no off state.** `SempodsModule.bindAiService` always binds an `AiService`, so the AI
endpoints exist in every deployment. A deployment with no provider running does not answer them
with `404` or `503`: the call reaches the binding, the provider request fails, and
`PodAiSemWebEndpoint` maps the `AiServiceException` to `500 ai_provider_error`. Ollama's address and
model default too (`OLLAMA_BASE_URL`, `OLLAMA_MODEL`), while `AI_PROVIDER=openai` requires
`OPENAI_API_KEY` and `OPENAI_MODEL` and fails the boot without them.

## Testing (IST)

CI/integration tests use observer/delegate-based test bindings by default, not real provider runtimes.

## Related Docs

- `docs/ai/semweb/text2model.md` (IST endpoint behavior)
- `docs/ai/semweb/use-cases/tasks.md` (task-oriented `text2model`/`model2model` playbook)
- AI/SemWeb roadmap (SOLL, currently internal)
- `docs/vision.md` (vision)
