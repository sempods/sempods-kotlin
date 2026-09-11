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
editing anything here. What it means for this folder:

- English.
- `vision.md` gives direction; maintained documentation describes current code; occasional
  proposals carry explicitly unimplemented design.
- Never mix runtime facts and target state in the same section.
- High-level and example-driven; field-level contracts go into KDoc on interfaces and DTOs, and the
  document links to the file.
- Logic that follows the standard gets no document at all. When a special case becomes ordinary,
  its section goes.

## Key references

- `docs/agents/` — the AI instruction hub, documentation strategy,
  [issue work](agents/issue-work.md) and `documentation-sync.md`
- [docs/concepts/](concepts/README.md) — current architecture: deployment seams, graph retrieval,
  hosted MCP and service-client provisioning
- [docs/proposals/](proposals/README.md) — proposed designs and their owning issues
- `docs/naming.md` — how the name is written in prose and in code, the package
  namespace, and the names that are frozen because something outside this repo depends on them (IST)
- `docs/vision.md` — core standard
- `sempods-commons-mongo/docs/document-contract.md` — the document contract the `commons-mongo`
  helpers implement, the two query asymmetries that follow from it, and the DAOs that bypass the
  helpers and therefore do not keep it
- `sempods-server/docs/collections.md` — the pod server's collection layer: hand-written driver
  DAOs, which database, and the boot-time updater
- `docs/ai-layer.md` — AI provider abstraction
- `docs/media.md` — pod-owned binaries: routes, authorization, the store seam and its
  three configuration states, the reference-counting lifecycle, and what is deliberately outside
- `docs/ai/semweb/text2model.md`
- `docs/ai/semweb/use-cases/tasks.md`

## Auth and security docs

Pod-side authentication and authorization are **specified elsewhere** (above); what lives
under `docs/auth/` is this implementation's side of it:

**The authorization model itself is not here.** Contexts, grants, the OAuth profile and the client
identity shapes are [sempods-spec `spec/core/contexts.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/contexts.md),
[`grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) and [`auth.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/auth.md). What stays under
`docs/auth/` is what this implementation does around that contract:

- `docs/auth/README.md` — overview, mental model, doc map
- `docs/auth/identity.md` — WebID identities, identity JWT, OIDC bridge concept
- `docs/auth/oauth.md` — the numbers and limits the specification leaves open: the token endpoint's
  rate budget, the OIDC leg timeouts, the sharp edges
- `docs/auth/service-clients.md` — provisioning over the admin surface, the audit trail and its
  retention
- `docs/auth/oauth-errors.md` — the page every OAuth `error_uri` points at: one heading per error
  code a redirect can carry

Identity service (`sempods-auth/docs/`):

- `sempods-auth/docs/README.md` — module overview
- `sempods-auth/docs/identity-service.md` — id-server internals: URI namespaces, OIDC bridge, identity merge, federation

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
