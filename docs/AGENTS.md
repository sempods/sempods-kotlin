# AGENTS.md — sempods/docs

Scope: applies to `docs/**`.

## Context

This folder contains sempods specification and implementation-facing documentation.

## Documentation policy

[`agents/documentation-strategy.md`](agents/documentation-strategy.md) is the authority — the four
types, how they nest, and when something should not be documented at all. Read it before editing
anything here. What it means for this folder:

- English.
- `vision.md` is the repository-wide vision; `concepts/` holds one document per topic, each stating
  IST and SOLL; `roadmaps/` holds the milestone being implemented, if one is; everything else
  describes what the code does today.
- Never mix runtime facts and target state in the same section.
- High-level and example-driven; field-level contracts go into KDoc on interfaces and DTOs, and the
  document links to the file.
- Logic that follows the standard gets no document at all. When a special case becomes ordinary,
  its section goes.

## Key references

- `docs/agents/` — the AI instruction hub, the documentation strategy, and the two procedures
  (`roadmap-lifecycle.md`, `documentation-sync.md`)
- `docs/concepts/` — one document per topic, each stating IST and SOLL: modular deployment
  (deployment-selected seams, the invariants that are not selectable, open-source blockers), graph
  retrieval, hosted MCP, the MCP agent interface, inference contexts, app installation
- `docs/roadmaps/` — the milestone being implemented, if one is. Dissolved when it ships.
  Running: `owner-app-installation.md` — a pod owner installs a service client through first-party
  OAuth and protected DCR
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
- `docs/concepts/mcp-agent-interface.md` — retrieval primitives, SHACL-gated app
  contracts, cross-pod orchestration
