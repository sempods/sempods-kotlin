# Portable MCP interoperability

> **Disposition: proposed — not implemented.** [Issue #140](https://github.com/sempods/sempods-kotlin/issues/140)
> owns review, decisions and adoption links. Accepting this design does not implement it.

## Target pod profile

A target is usable only if the service can deterministically discover and exercise it.
"Pod base URL" therefore implies a **named conformance profile** the target must satisfy:

- **Endpoints** — the System-layer routes (`_system/resources/...`,
  `find`) and the SPARQL query/construct endpoints, at a discoverable base.
- **OAuth metadata** — RFC 9728 protected-resource metadata and RFC 8414
  AS metadata at the well-known locations, so the service can register
  (DCR) and obtain bearers without per-pod hand-configuration.
- **Contexts** — `list_contexts` semantics: the authoritative,
  permission-annotated set the bearer covers.
- **SPARQL guardrails** — the same read-only / no-`SERVICE` / timeout
  contract, so a rewritten cross-pod query behaves identically everywhere.
- **Capability discovery** — a way to learn which operations a target
  supports, so the service degrades gracefully against partial
  implementations instead of failing opaquely.

The bullets above are the conceptual requirements; they stay abstract on
purpose at this stage. Before implementation, discovery should resolve to a
**concrete, versioned mechanism** — e.g. a `_system/capabilities` (or
profile) endpoint, or a fixed discovery document — that advertises the
profile version and supported operations, rather than the service probing
each route. Without this profile, "front any pod" is not implementable.
Defining it (versioned, testable) is a prerequisite, tracked under
the owning issue and conformance tests.


The `_system/capabilities` example above is a design candidate, not an endpoint. Reconcile
this sketch with the specification's conformance declaration and [#54](https://github.com/sempods/sempods-kotlin/issues/54).
The repository is public; independent interoperability still needs an exercised external target.

## Tool contract and provenance

The two JVM surfaces already share `ToolCatalog` and `PodToolExecutor`. A portable,
versioned contract and cross-implementation tests would cover independent implementations,
including TS consumers after verifying their current behavior. Sharing the JVM implementation
prevents drift between those two consumers but does not establish external conformance.

Free-form SPARQL is currently scoped to requested readable contexts, with per-pod results.
Per-context attribution would require a query-preserving `GRAPH ?g` rewrite and a defined
result envelope. Decide demand and semantics before implementing it; a downscope alone does
not provide provenance for each row.

The direct pod MCP surface avoids third-party credential custody. Retiring it, or moving a
consumer to the hosted service, is a separate decision that must justify losing that property.
A hosted model loop is also a separate, unscheduled product decision: the current service
executes tools and holds no LLM keys.
