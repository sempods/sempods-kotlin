# Documentation

Guides for using, extending and operating this implementation. The protocol is defined in
[sempods-spec](https://github.com/sempods/sempods-spec), rendered at [spec.sempods.org](https://spec.sempods.org).

The [repository README](../README.md#documentation) is the entry point and links guides by task.
This index lists shared and module documentation.

## Module documentation

- [JVM client](../sempods-client/README.md): [API guide](../sempods-client/docs/client.md) and
  [transport](../sempods-client/docs/transport.md)
- [Pod OAuth](../sempods-server/docs/auth/README.md),
  [host provisioning](../sempods-server/docs/host-provisioning.md) and
  [collections](../sempods-server/docs/collections.md) in the pod server
- [Identity service](../sempods-auth/README.md): [details](../sempods-auth/docs/identity-service.md)
- [Shared auth library](../sempods-auth-core/README.md)
- [Hosted MCP service](../sempods-mcp/docs/README.md)
- [MongoDB document contract](../sempods-commons-mongo/docs/document-contract.md)

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

- [`auth/`](auth/README.md) — the cross-module auth overview and the OAuth error recovery page
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
