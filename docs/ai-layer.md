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

- `AI_PROVIDER=disabled`, unset or blank -> no AI service or AI routes
- `AI_PROVIDER=ollama` -> `OllamaAiService`
- `AI_PROVIDER=openai` -> `OpenAiService`
- any other value fails the boot

AI is opt-in. Disabled deployments do not register the `text2model` or `model2model` endpoints
and never construct a provider client. Ollama defaults to `http://localhost:11434` and
`qwen3.5:4b`, configurable through `OLLAMA_BASE_URL` and `OLLAMA_MODEL`. Selecting `openai`
requires `OPENAI_API_KEY` and `OPENAI_MODEL`; missing values fail the boot.

## Testing (IST)

CI/integration tests use observer/delegate-based test bindings by default, not real provider runtimes.

## Related Docs

- `docs/ai/semweb/text2model.md` (IST endpoint behavior)
- `docs/ai/semweb/use-cases/tasks.md` (task-oriented `text2model`/`model2model` playbook)
- [Shape-contract design](https://github.com/sempods/sempods-kotlin/issues/137) — proposed validation
- `docs/vision.md` (vision)
