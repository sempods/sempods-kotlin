# AGENTS.md — sempods-server

Scope: applies to `sempods-server/**`.

## What this module is

`sempods-server` is the pod server — the **reference implementation** of the sempods standard,
not one particular hosting: RDF4J store, contexts, grants, OAuth, the SPARQL sandbox, media, the
AI layer and the per-pod MCP surface. It composes from `org.sempods.commons.guice.BaseModule`
over the `sempods-commons` family and takes **no application-framework** dependency; its configuration is
`SempodsConfig` and its collections are on the raw MongoDB driver.

What a deployment may replace is expressed as a **seam** — an interface with a
deployment-selected binding. Before adding an abstraction, read
[`../docs/modularity.md`](../docs/modularity.md): it lists the seams that exist,
the ones that deliberately do not yet, and the invariants that are not selectable at all.

## The project-level authority is not this file

[`../AGENTS.md`](../AGENTS.md) holds the mission, the terminology (pod, context, grant,
scope), the documentation rules and the AI documentation map for the whole of sempods.
**Read it before changing behaviour here** — this file does not repeat it.

This directory holds the server's code and nothing else: `src/` and `build.gradle.kts`. Where the
project-level material sits beside it is that file's subject, not this one's.

## Non-negotiable invariants

Restated here because they bind every change to this module; `../sempods/AGENTS.md` is the
authority if the two ever disagree.

1) Every edge/statement always has exactly one Context (named graph).
2) Read sandbox: a request can only read contexts it has read rights for.
3) Write sandbox: a request can only write into contexts it has write rights for.
4) A CRUD write names its target context explicitly — there is no implicit fallback context.
5) Pods are isolated by default. Do not introduce cross-pod access without explicit,
   spec-defined sync mechanisms.
6) Prefer explicit specs + conformance tests over clever query rewriting.

## Security stance

- Enforce sandboxing **server-side** — do not trust client-supplied `FROM` / `FROM NAMED`.
- Forbid or strictly gate risky SPARQL features (e.g. `SERVICE` / federated queries) unless
  explicitly supported. `SparqlQueryService` rejects every Update form and refuses `SERVICE`
  anywhere in the algebra.
- Always return deterministic HTTP errors (401 / 403 / 400 / 429 / 500).

## Working rules

- Any behaviour change must come with tests — prefer HTTP-level conformance tests.
- Keep docs and spec aligned with the implementation: `../docs/` is IST, and what is
  planned but not built is SOLL and lives in the maintainer's internal roadmap.
- Be conservative with backward-incompatible changes.
- Naming: [`../docs/naming.md`](../docs/naming.md) is the authority. Before
  renaming anything that already reads `sempods`, check its §3 "Frozen" — the `SEMPODS_*`
  variables, the `sempods.*` collections, `urn:sempods:` and the vocabulary IRIs are contracts,
  not spellings. Gradle module names are explicitly *not* frozen.

## Deeper scopes

Two subtrees add rules of their own and take precedence where they conflict:

- [`src/main/kotlin/org/sempods/pods/AGENTS.md`](src/main/kotlin/org/sempods/pods/AGENTS.md) —
  the pod store, the write path and its sinks
- [`src/main/kotlin/org/sempods/ai/AGENTS.md`](src/main/kotlin/org/sempods/ai/AGENTS.md) —
  the AI layer and retrieval
