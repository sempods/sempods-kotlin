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

## Start here

- [`docs/agents/ai-instructions.md`](docs/agents/ai-instructions.md) — how instructions are
  discovered and which file wins where two disagree. Every agent frontend routes through it.
- [`docs/agents/documentation-strategy.md`](docs/agents/documentation-strategy.md) — the four
  documentation types, and the rules for when *not* to document something. Read it before touching
  any `*.md`.

`CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md` and `.cursor/rules/` are compatibility
pointers back to this file — Codex and opencode read it directly. Everything canonical is here or
under `docs/agents/`; a pointer that grows rules of its own is a pointer that drifts.

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

## Documentation

[`docs/agents/documentation-strategy.md`](docs/agents/documentation-strategy.md) is the authority.
The short version:

- **Code contracts are the source of truth.** Field-level detail goes in KDoc; markdown stays
  high-level and links to the code path.
- **Everything except roadmaps and SOLL sections describes what is true today.** Where a document
  and the code disagree, the code is right and the document is a bug. Never mix the two in one
  section.
- **Logic that follows the standard needs no documentation at all** — and when a special case
  becomes ordinary, its documentation and its comments are deleted. Documentation shrinking is what
  a simplification is supposed to produce.
- **No history and no decision log.** Keep only the reasoning a future reader needs in order not to
  undo the decision; the rest is what commit messages are for.
- The four types — `vision.md`, `concepts/`, `roadmaps/`, IST documents — nest under any `docs/`
  directory, at the repository root and at a module.

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

## Documentation map

Agent instructions: `docs/agents/` — the hub, the documentation strategy, and the two procedures
(`roadmap-lifecycle.md`, `documentation-sync.md`).

Vision and concepts:

- Vision: `docs/vision.md`
- Concepts: `docs/concepts/` — one document per topic, each stating IST and SOLL. Currently
  modular deployment, graph retrieval, hosted MCP, the MCP agent interface, inference contexts,
  app installation
- Roadmaps: `docs/roadmaps/` — the milestone being implemented, if one is. Dissolved when it
  ships. Running: `owner-app-installation.md` — a pod owner registers a service client on their
  own pod

IST documentation:

- Naming (IST): `docs/naming.md` — the authority for how "sempods" is written in prose and
  in code, the package namespace, and the names that are frozen because a deployed host, a database
  or a published IRI depends on them
- Pod client (IST): `docs/pod-client.md` — the JVM client for the pod surface and its admin-surface sibling: the tiers and which one a consumer takes, the rule for what may be added at which tier, why the transport is OkHttp (SSRF resolve-and-pin needs a DNS hook the JDK client does not offer) without an OkHttp type ever reaching a consumer, and what the client deliberately is not
- Pod data layer (PodRepository, PodFacade): `sempods-server/src/main/kotlin/org/sempods/pods/AGENTS.md`
- Collection layer (IST): `docs/persistence.md` — the fifteen MongoDB collections: hand-written driver DAOs, the three whose store belongs to a shared module instead, the document contract (null and empty omitted, `Instant` at milliseconds, `_id`), and the two places where a filter and the decoder disagree
- AI layer (IST): `docs/ai-layer.md`
- MCP per-pod surface (IST): `docs/mcp/` — JSON-RPC endpoint, tools, OAuth-gated access, client behavioral clusters
- LOD/REST/CRUD layer (spec): `docs/lod-crud/` — two-layer model (LOD resource HTTP + System slot HTTP via `_system/resources/{b64url(uri)}`), context rules, base64url convention
- Context namespace (IST): `docs/auth/authorization.md` §"Contexts as the permission boundary" — where context IRIs live (`_system/contexts/…`, typed when delegated, freely named when the owner keeps them), what a context may be called, and why a `_system` IRI is protected but still describable
- Text2Model endpoint (IST): `docs/ai/semweb/text2model.md`
- Task use-case playbook (IST, includes `model2model` examples): `docs/ai/semweb/use-cases/tasks.md`
- What agents may be told about this project: `context7.json` — its `rules` array asserts facts
  about grants, contexts, SPARQL, client identity and trademark language, and is served to agents
  outside this repository. A behaviour change can turn one of them into a lie

## Working rules

- Any behavior change must come with tests (prefer HTTP-level conformance tests).
- Keep docs/spec aligned with implementation.
- Be conservative with backward-incompatible changes.
- Most modules here are published. An artifact whose types appear in a module's public signatures
  is declared by that module, on `api` — not inherited from a sibling that brings it, and not the
  artifact one level up from the one the type is in. `./gradlew buildHealth` checks this against
  the bytecode and fails the build; `:consumer-probe:auth` and `:consumer-probe:mcp` cover the
  embedding contract of the two services the plugin structurally cannot see — that contract only,
  not their wider accidental surface. See `docs/concepts/modularity.md` §"Open-source readiness".

## Quick reference

| | |
|---|---|
| Infrastructure for tests | `docker compose -f deployments/local/compose.yaml -f deployments/test/compose.test.yaml up -d` then `deployments/test/garage/init.sh` |
| Full suite | `./gradlew test` |
| Everything CI runs | `./gradlew test checkNoLoggingBinding checkNoTestLibrariesInPom checkDocLinks` |
| Dependency boundary | `./gradlew buildHealth` → `build/reports/dependency-analysis/build-health-report.txt` |
| Run the pod server | `./gradlew :deployments:sempods:image:run` → `http://localhost:8090` |
| Publish a snapshot | `./gradlew publishAllPublicationsToCentralSnapshotsRepository` |

The infrastructure step is not optional and not obvious: without it `S3PodMediaStoreTest` **skips
silently** and the Mongo-backed suites fail in ways that read like product bugs.

Test flags: `-PtestStdout` restores standard streams for one run, `-PtestMethodsConcurrent` runs
methods concurrently as well as classes, `-PtestPortBase=<n>` moves the port range when two builds
share a machine.

Java 25 is required to build; published bytecode targets Java 21.

## What this repository deliberately does not have

**No formatter and no linter** — no ktlint, no detekt, no spotless, no `.editorconfig`. Do not
introduce one, and do not assume one has run. Style comes from the surrounding file:

- two-space indentation, in Kotlin and in `.kts`
- trailing commas in parameter lists, named arguments at call sites
- backtick test method names, written as sentences
- no file-level licence headers — a file starts with `package`
- KDoc on interfaces and DTOs carries the field-level contract; most files open with one

Build files, workflow files and `gradle.properties` carry several paragraphs of reasoning per
value, saying what went wrong before and why the value is what it is. That is the house style
there. Match it; do not compress it away.

**No schema-migration system.** `SempodsUpdater` runs a hardcoded list every boot, with no history
and no already-applied check. Do not propose a migration framework as a fix for a data change.

## Before you commit

1. `./gradlew test checkNoLoggingBinding checkNoTestLibrariesInPom checkDocLinks` — after the
   infrastructure step above.
2. `./gradlew buildHealth` — the `api`/`implementation` boundary. A dependency in the wrong
   configuration fails a separate CI job, not this one.
3. Documentation, in this same change:
   [`docs/agents/documentation-strategy.md`](docs/agents/documentation-strategy.md) §"Definition of
   done". IST documents, KDoc, the roadmap tick, and `context7.json`. The `sync-docs` procedure
   ([`docs/agents/documentation-sync.md`](docs/agents/documentation-sync.md)) walks it.
4. `git commit -s`. The DCO workflow fails the pull request without a `Signed-off-by` line. Work
   done with an AI assistant also carries `Co-Authored-By` for the model —
   [`CONTRIBUTING.md`](CONTRIBUTING.md) §"AI-assisted contributions" is the policy, and it is the
   human who signs off who is the author.
5. Commit messages are **full imperative sentences in plain English**, not Conventional Commits —
   "Take MongoDB's key type out of the seams, and check that it stayed out", not `refactor: …`.
   The body explains what was wrong and why the fix has the shape it does.

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
