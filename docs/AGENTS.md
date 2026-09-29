# AGENTS.md — sempods/docs

Scope: applies to `docs/**`.

## Context

This folder documents *this repository* — all three services and the libraries under them, not the
pod server alone. The specification moved to `sempods-spec`; a statement about what a pod must do
belongs there, and what is written here is what this implementation does about it.
[`README.md`](README.md) indexes the folder by documentation type.

## Documentation policy

[`agents/documentation-strategy.md`](agents/documentation-strategy.md) is the authority — the three
document types, how they nest, and when something should not be documented at all. Read it before
editing anything here. [`agents/doc-review.md`](agents/doc-review.md) checks a change, a pull
request or a path against it.

## Key references

- `docs/agents/` — the AI instruction hub, documentation strategy,
  [issue work](agents/issue-work.md) and [doc review](agents/doc-review.md)
- [docs/concepts/](concepts/README.md) — current architecture: deployment seams, graph retrieval,
  hosted MCP and service access
- [docs/proposals/](proposals/README.md) — proposed designs and their owning issues
- `docs/naming.md` — how the name is written in prose and in code, the package
  namespace, and the names that are frozen because something outside this repo depends on them (IST)
- `docs/vision.md` — core standard
- `sempods-commons-mongo/docs/document-contract.md` — the document contract the `commons-mongo`
  helpers implement, the two query asymmetries that follow from it, and the DAOs that bypass the
  helpers and therefore do not keep it
- `sempods-server/docs/collections.md` — the pod server's collection layer: hand-written driver
  DAOs, which database, and startup maintenance
- `docs/ai-layer.md` — AI provider abstraction
- `docs/media.md` — pod-owned binaries: routes, authorization, the store seam and its
  three configuration states, the reference-counting lifecycle, and what is deliberately outside
- `docs/ai/semweb/text2model.md`
- `docs/ai/semweb/use-cases/tasks.md`

## Client and auth documentation

- [Client family](../sempods-client/README.md) — quick start, API guide and adapter links.
- [Auth overview](auth/README.md) — flow selection and module responsibilities.
- [Pod OAuth](../sempods-server/docs/auth/README.md) — service and delegated access, identity trust and operation.
- [Identity service](../sempods-auth/README.md) and [shared auth library](../sempods-auth-core/README.md).
- [OAuth errors](auth/oauth-errors.md) — public recovery page linked by configured `error_uri` URLs;
  preserve this published location when moving other auth documentation.

The normative contracts remain in [sempods-spec](https://github.com/sempods/sempods-spec).

## The CRUD layer is not documented here

It is [sempods-spec `spec/core/lod-crud.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md) — both layers, the context
rule, the canonical representation, the slot and edge routes, and the acknowledged deviations from
HTTP. This code cites it by requirement identifier, and `./gradlew checkDocLinks` checks every
citation against the vendored index in `gradle/spec/`.

## MCP docs

Per-pod MCP specification and design docs live in `docs/mcp/`:

- `docs/mcp/README.md` — overview, mental model, doc map
- `docs/mcp/endpoint.md` — JSON-RPC endpoint, methods, OAuth discovery routes
- `docs/mcp/tools.md` — tool catalog, sandbox, autodiscovery, examples
- `docs/mcp/authentication.md` — bearer / anonymous / public-read,
  the synthetic `authorize` tool, DCR fingerprint
- `docs/mcp/clients.md` — client setup + observed behavioral clusters
  (Claude, ChatGPT, Copilot, Open-Code)
- `docs/proposals/mcp-agent-interface.md` — proposed grant-bound shape contracts and reactive agents
