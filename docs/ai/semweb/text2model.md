# sempods.org - AI SemWeb `text2model` (Current State / IST)

## Scope

This document explains the current behavior of:

- `POST /{pod}/_system/ai/semweb/text2model`

It is intentionally high-level. Exact API contracts are documented in code.

## Contract Source (Code)

For exact request/response and validation details, use:

- `sempods-server/src/main/kotlin/org/sempods/api/pod/system/ai/semweb/PodAiSemWebEndpoint.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/sem/AiSemFacade.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/sem/AiSemText2ModelRequest.kt`

## What the Endpoint Does (IST)

- accepts free text plus a target SHACL payload
- builds an AI extraction request via `AiSemFacade`
- injects current server time context (UTC timestamp + date + weekday) into the AI prompt as authoritative reference
- auto-derives baseline guidance from SHACL metadata (`sh:targetClass`, `sh:path`, `sh:description`)
- normalizes model output to JSON-LD graph envelope (`@graph`)
- returns either non-empty model (`status=ok`) or empty model (`status=empty`)

Important: SHACL is currently used as prompt context. Hard SHACL validation is not yet active.

## Simple Request Example

```json
{
  "content": {
    "text": "Morgen Auto zur Reparatur bringen",
    "language": "de"
  },
  "target": {
    "shacl": {
      "syntax": "text/turtle",
      "data": "@prefix sh: <http://www.w3.org/ns/shacl#> . ..."
    }
  },
  "options": {
    "strict": true,
    "allowEmpty": true
  }
}
```

Note: `guidance` is optional. Default recommendation is SHACL-only first, then add optional client guidance only if needed.

## Simple Response Example

```json
{
  "status": "ok",
  "graph": {
    "@graph": [
      {
        "@type": "https://schema.org/Action",
        "https://schema.org/name": "Auto zur Reparatur bringen"
      }
    ]
  },
  "validation": {
    "shaclConforms": null,
    "mode": "not_evaluated_v1"
  }
}
```

## Auth and Errors (IST)

- valid pod app bearer token required
- endpoint returns deterministic HTTP errors for auth, input, and provider failures
- exact status/code mapping is defined in endpoint code

## Related Docs

- `docs/ai-layer.md` (AI layer IST)
- AI/SemWeb roadmap (SOLL, currently internal)
- `docs/ai/semweb/use-cases/tasks.md` (task-focused use-case playbook)
- `docs/vision.md` (vision)
