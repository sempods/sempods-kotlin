# AGENTS.md — sempods

Scope: project-level guidance for the whole repository — everything below holds for every module
unless a narrower file says otherwise. The IST documentation is in [`docs/`](docs/).

**Three modules carry their own `AGENTS.md`**: [`sempods-auth`](sempods-auth/AGENTS.md),
[`sempods-mcp`](sempods-mcp/AGENTS.md) and [`sempods-server`](sempods-server/AGENTS.md), the last
with two more scoped to packages inside it
([`pods/`](sempods-server/src/main/kotlin/org/sempods/pods/AGENTS.md),
[`ai/`](sempods-server/src/main/kotlin/org/sempods/ai/AGENTS.md)). The other thirteen have none and
take this file directly — that is the normal case, not a gap to be filled.

**The pod server is [`sempods-server/`](sempods-server/)**, one module among sixteen rather than
the product. It carries its own [`sempods-server/AGENTS.md`](sempods-server/AGENTS.md), which
points back here for the mission, terminology and documentation map below, and restates the
invariants that bind changes to the code. This file remains the authority where the two overlap.

## Project mission

sempods.org is a private, non-profit project that defines an open, copyable standard for self-hosted "semantic pods".
A pod is an isolated tenant (conceptually a separate store/account) that can be hosted by anyone and used by multiple
apps.

Core goals:

- Linked Data / JSON-LD CRUD over HTTP
- OAuth-based authorization for apps and agents
- Graph-based access control where the 4th RDF dimension (named graph) is called "Context"
- A SPARQL endpoint that enforces a context sandbox (read and write)
- Future: agents and dataflows/sync between pods

This is not a business idea. Optimize for openness, clarity, and interoperability.

## Terminology

- Pod: a tenant / account boundary. Implementations may use separate repositories/stores per pod.
- Context: named graph / RDF4J context. Every statement belongs to exactly one context.
- Grant: a permission on a context, written `<context-iri>#read|write|manage`. Durable server-side
  policy, resolved per request from the grant store — it never travels in an access token.
- Scope: an OAuth scope in the RFC 6749 sense — a coarse feature capability such as `public-read`.
  These *do* travel in the token. See `docs/auth/authorization.md` §"Terminology: scope vs. grant";
  parts of the code still say "scope" where "grant" is meant.

## Non-negotiable invariants

1) Every edge/statement always has exactly one Context (named graph).
2) Read sandbox: a request can only read contexts it has read rights for.
3) Write sandbox: a request can only write into contexts it has write rights for.
4) A CRUD write names its target context explicitly — there is no implicit fallback context.
5) Pods are isolated by default. Do not introduce cross-pod access without explicit, spec-defined sync mechanisms.
6) Prefer explicit specs + conformance tests over clever query rewriting.

## Security stance

- Enforce sandboxing server-side (do not trust client-supplied FROM/FROM NAMED).
- Forbid or strictly gate risky SPARQL features (e.g., SERVICE / federated queries) unless explicitly supported.
- Always return deterministic HTTP errors (401/403/400/429/500).

## Documentation rules (important)

- **Code contracts are source of truth.**
- Keep an explicit split between:
  - **IST**: currently implemented behavior
  - **SOLL/Roadmap**: planned behavior
- Keep markdown docs high-level; put field-level contract details into code-level KDoc and reference code paths from docs.
- Move items from roadmap to IST docs only after runtime behavior is actually implemented.

## Auth layer (sempods-auth)

The `sempods-auth` module is a **standalone Ktor service** (port 8091) implementing the WebID identity
registry and the OIDC bridge that turns a provider login into a sempods JWT — see `sempods-auth/AGENTS.md`.

Key design choices:
- No application-framework dependency. It builds on the `sempods-commons` family directly: `sempods-commons`
  for configuration (`Env`), logging, `BaseModule`, WebID derivation (`WebIdUriDeriver`), URL
  handling (`UrlUtil`) and HTML escaping; `sempods-commons-ktor` for the trace binding;
  `sempods-commons-mongo` for the document helpers — plus `sempods-auth-core` for the OAuth
  machinery all three services share
- Separate MongoDB database (`sempods-auth`), raw driver (no Morphia)
- Ktor routing: extension functions in `api/` packages
- WebID URI space: `id.sempods.org/e/<sha256(email)>` (EMAIL) and `id.sempods.org/oidc/<sha256(iss+sub)>` (OIDC)
- Live: WebID profile CRUD + content negotiation (Turtle / JSON-LD / HTML), the OIDC bridge with
  Google and Apple, and a standard provider surface (`/.well-known/openid-configuration`,
  `/authorize`, `/token`) that the pod server and the hosted MCP service sign in against. `/login`
  — an implicit grant with unrestricted callbacks, which `docs/auth/oauth.md` rules out — is gone;
  everything it issued stays valid until the signing-key rows are cleared, which is an operator
  step against the `oauth.signingKeys` collection rather than a release

## AI documentation map

- Naming (IST): `docs/naming.md` — the authority for how "sempods" is written in prose and
  in code, the package namespace, and the names that are frozen because a deployed host, a database
  or a published IRI depends on them
- Vision: `docs/vision.md`
- Modular deployment (concept, IST + SOLL): `docs/modularity.md` — sempods as a *reference implementation*: which behaviors are deployment-selected seams (store, find, admin authority, pod resolution, authorization), which invariants are not, and what blocks open-sourcing the module
- Pod client (IST): `docs/pod-client.md` — the JVM client for the pod surface and its admin-surface sibling: the tiers and which one a consumer takes, the rule for what may be added at which tier, why the transport is OkHttp (SSRF resolve-and-pin needs a DNS hook the JDK client does not offer) without an OkHttp type ever reaching a consumer, and what the client deliberately is not
- Pod data layer (PodRepository, PodFacade): `sempods-server/src/main/kotlin/org/sempods/pods/AGENTS.md`
- Collection layer (IST): `docs/persistence.md` — the fifteen MongoDB collections: hand-written driver DAOs, the three whose store belongs to a shared module instead, the document contract (null and empty omitted, `Instant` at milliseconds, `_id`), and the two places where a filter and the decoder disagree
- AI layer (IST): `docs/ai-layer.md`
- Graph retrieval pattern (IST + vision): `docs/ai-retrieval.md` — consumer-agnostic `find` (shipped: REST `_system/find` + MCP `find` tool) + structural traversal, foundational for app and AI reads (only `model2text` is AI-specific)
- MCP per-pod surface (IST + vision): `docs/mcp/` — JSON-RPC endpoint, tools, OAuth-gated access, client behavioral clusters
- LOD/REST/CRUD layer (spec): `docs/lod-crud/` — two-layer model (LOD resource HTTP + System slot HTTP via `_system/resources/{b64url(uri)}`), context rules, base64url convention
- Context namespace (IST): `docs/auth/authorization.md` §"Contexts as the permission boundary" — where context IRIs live (`_system/contexts/…`, typed when delegated, freely named when the owner keeps them), what a context may be called, and why a `_system` IRI is protected but still describable
- Text2Model endpoint (IST): `docs/ai/semweb/text2model.md`
- Task use-case playbook (IST, includes `model2model` examples): `docs/ai/semweb/use-cases/tasks.md`

## Working rules

- Any behavior change must come with tests (prefer HTTP-level conformance tests).
- Keep docs/spec aligned with implementation.
- Be conservative with backward-incompatible changes.
- Most modules here are published. An artifact whose types appear in a module's public signatures
  is declared by that module, on `api` — not inherited from a sibling that brings it, and not the
  artifact one level up from the one the type is in. `./gradlew buildHealth` checks this against
  the bytecode and fails the build; `:consumer-probe:auth` and `:consumer-probe:mcp` cover the
  embedding contract of the two services the plugin structurally cannot see — that contract only,
  not their wider accidental surface. See `docs/modularity.md` §"Open-source readiness".

## Naming conventions

[`docs/naming.md`](docs/naming.md) is the authority — spelling, code identifiers, the package
namespaces, and the list of values that are frozen because something outside this repository
depends on them. The short version:

- Official product name: **sempods** (all lowercase) — in prose, log messages, env vars, package
  names, collection names. Never "SemPods" and never "Sempods" when writing the name itself.
- Kotlin class/interface names: **`Sempods`** prefix (e.g. `SempodsClient`) — the product name in
  PascalCase, not a camel hump. The older `SemPods…` spelling is gone from code, build files,
  configuration and current documentation; it survives only where a document records a type that
  was retired under that name (see `docs/naming.md` §2).
- A pod is not the product: `newPod()`, "a pod", "the pod owner" take no brand prefix.
- Before renaming anything that already reads `sempods`, check §3 "Frozen" in `docs/naming.md`.
  `SEMPODS_*` variables, `sempods.*` collections, `urn:sempods:` and the vocabulary IRIs are
  contracts, not spellings.
