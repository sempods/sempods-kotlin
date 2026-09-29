# Documentation

Guides for using, extending and operating this implementation. The protocol is defined in
[sempods-spec](https://github.com/sempods/sempods-spec), rendered at [spec.sempods.org](https://spec.sempods.org).

## Start by task

| Task | Guide |
|---|---|
| Use the JVM client | [Client family and quick start](../sempods-client/README.md) |
| Choose service or delegated access | [Auth overview](auth/README.md) |
| Configure a backend service | [Service access](../sempods-server/docs/auth/service-clients.md) |
| Connect a user-facing app | [Delegated access](../sempods-server/docs/auth/user-access.md) |
| Operate the identity service | [sempods-auth](../sempods-auth/README.md) |
| Work on shared OAuth components | [sempods-auth-core](../sempods-auth-core/README.md) |

The [repository README](../README.md) is the entry point. Module READMEs introduce local APIs;
module `docs/` pages explain their details. This index links both local and shared documentation.

## Vision — why this exists

- [`vision.md`](vision.md) — the model and why it is shaped this way. Independent of what is built.

## Architecture

[`concepts/`](concepts/) explains current code and its boundaries.

- [`concepts/modularity.md`](concepts/modularity.md) — which behaviours are deployment-selected
  seams and which invariants are not
- [`concepts/graph-retrieval.md`](concepts/graph-retrieval.md) — `find`, then traverse: the read
  pattern every consumer builds on
- [`concepts/hosted-mcp.md`](concepts/hosted-mcp.md) — one MCP service fronting many pods
- [`concepts/service-access.md`](concepts/service-access.md) — how a service reaches a pod, and
  how long delegated access lasts

## Proposed designs

[Proposals](proposals/README.md) cover unimplemented retrieval, inference, app-contract and
deployment designs. Each links its owning issue; none is a statement of shipped behavior.

## Reference — what the system is today

These pages describe the current implementation.

**By area**

- [`auth/`](auth/) — what this implementation does around the OAuth contract: identity and WebIDs,
  the OAuth surface, service clients, and the recovery page every `error_uri` points at
- [`mcp/`](mcp/) — the pod's MCP surfaces: the tool reference, the endpoint, authentication, and how
  real clients actually behave
- [`architecture/`](architecture/) — [`module-layering.md`](architecture/module-layering.md), which
  module may depend on which, and [`dependency-injection.md`](architecture/dependency-injection.md),
  how wiring is done and why constructor injection is the rule
- [`ai/`](ai/) — the semantic-web side of the AI layer:
  [`semweb/text2model.md`](ai/semweb/text2model.md) and its
  [use cases](ai/semweb/use-cases/tasks.md)

**Repository-wide, one file each**

- [`ai-layer.md`](ai-layer.md) — the AI layer at a high level; the exact contracts are KDoc
- [`media.md`](media.md) — the media storage seam: which backends exist, how a deployment picks one
- [`naming.md`](naming.md) — how the name is spelled, everywhere, and why it is not negotiable
- [`logging.md`](logging.md) — what is logged at which level, and what must never be
- [`request-tracing.md`](request-tracing.md) — correlating a request across the three services
- [`testing.md`](testing.md) — the test layers and which one a change belongs in

## Moving between versions

- [Migration to 0.2](migration/0.2.md) — changes a 0.1 client needs to make.

## Instructions for contributors, human and AI

The [instruction hub](agents/ai-instructions.md) routes to repository and module rules.
The [documentation strategy](agents/documentation-strategy.md) defines page ownership and example
checks; [documentation review](agents/doc-review.md) is the per-change checklist.
[GitHub issues](https://github.com/sempods/sempods-kotlin/issues) own public plans and progress.
