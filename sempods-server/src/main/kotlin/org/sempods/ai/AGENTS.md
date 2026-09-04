# AGENTS.md — sempods AI package

Scope: applies to `sempods-server/src/main/kotlin/org/sempods/ai/**`.

## Context

This package contains the AI abstraction layer and SemWeb extraction facade.

Main components:

- `AiService` contract and shared DTOs/exceptions
- provider implementations (`impls/ollama`, `impls/openai`)
- `sem/AiSemFacade` for semweb `text2model` / `model2model` prompt assembly and response normalization

## Rules for changes

- Keep provider-agnostic contracts stable in `AiService` DTOs.
- Keep field-level contract documentation in KDoc on interfaces/DTOs; markdown docs should stay high-level.
- Document provider-specific behavior in `docs/ai-layer.md`.
- Keep endpoint-level behavior documented in `docs/ai/semweb/text2model.md` and use-case guidance in `docs/ai/semweb/use-cases/tasks.md`.
- Planned SHACL hard-validation work belongs to the maintainer's internal roadmap until implemented.

## References

- `docs/ai-layer.md`
- `docs/ai/semweb/text2model.md`
- `docs/ai/semweb/use-cases/tasks.md`
- the maintainer's internal roadmap
- `AGENTS.md` at the repository root
