# AGENTS.md — sempods/docs

Scope: applies to `docs/**`.

## Context

This folder contains sempods specification and implementation-facing documentation.

## Documentation policy

- Keep docs in English.
- Keep **IST** docs strictly aligned with current code behavior.
- Keep future/planned work in roadmap docs.
- Avoid mixing runtime facts and target-state assumptions in the same section.
- Keep docs high-level and example-driven; put field-level contracts into code (KDoc on interfaces/DTOs) and link to those files.

## Key references

- `docs/naming.md` — how the name is written in prose and in code, the package
  namespace, and the names that are frozen because something outside this repo depends on them (IST)
- `docs/vision.md` — core standard (IST)
- `docs/modularity.md` — deployment-selected seams of the reference
  implementation, the invariants that are not selectable, open-source blockers
- `docs/persistence.md` — the collection layer: hand-written driver DAOs, the document
  contract every one of them writes to, and the two query asymmetries that follow from it
- `docs/ai-layer.md` — AI provider abstraction
- `docs/media.md` — pod-owned binaries: routes, authorization, the store seam and its
  three configuration states, the reference-counting lifecycle, and what is deliberately outside
- `docs/ai/semweb/text2model.md`
- `docs/ai/semweb/use-cases/tasks.md`
- `sempods/AGENTS.md`

## Auth and security docs

Pod-side authentication & authorization is consolidated under
`docs/auth/`:

- `docs/auth/README.md` — overview, mental model, doc map
- `docs/auth/identity.md` — WebID identities, identity JWT, OIDC bridge concept
- `docs/auth/authorization.md` — contexts, scopes, grants, server-side enforcement
- `docs/auth/oauth.md` — Authorization Code + PKCE, refresh, public-read, PRM
- `docs/auth/service-clients.md` — 2-leg client credentials for backend services (service tokens, manage-root sandbox, audit)
- `docs/auth/oauth-errors.md` — the page every OAuth `error_uri` points at: one heading per error code a redirect can carry

Identity service (`sempods-auth/docs/`):

- `sempods-auth/docs/README.md` — module overview
- `sempods-auth/docs/identity-service.md` — id-server internals: URI namespaces, OIDC bridge, identity merge, federation

## LOD-CRUD docs

HTTP CRUD layer for RDF resources and slots, consolidated under
`docs/lod-crud/`:

- `docs/lod-crud/README.md` — two-layer architecture,
  identity vs. operations, base64url encoding convention, doc map
- `docs/lod-crud/lod-layer.md` — LOD-layer spec:
  GET/PUT/PATCH/DELETE on resource URIs, context rule for writes
  and reads, conformance requirements
- `docs/lod-crud/system-layer.md` — System-layer spec: slot
  model, base64url routes under `_system/resources/...`, HTTP verbs
  per slot, conditional requests, local-vs-external URI handling

## MCP docs

Per-pod MCP specification and design docs live in `docs/mcp/`:

- `docs/mcp/README.md` — overview, mental model, doc map
- `docs/mcp/endpoint.md` — JSON-RPC endpoint, methods, OAuth discovery routes
- `docs/mcp/tools.md` — tool catalog, sandbox, autodiscovery, examples
- `docs/mcp/authentication.md` — bearer / anonymous / public-read,
  the synthetic `authorize` tool, DCR fingerprint
- `docs/mcp/clients.md` — client setup + observed behavioral clusters
  (Claude, ChatGPT, Copilot, Open-Code)
- `docs/mcp/vision.md` — retrieval primitives, SHACL-gated app
  contracts, cross-pod orchestration
