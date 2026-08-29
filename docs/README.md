# Documentation

**The contract is not here.** What a pod must do — contexts, grants, auth, CRUD, SPARQL, `find`,
and the three modules — lives in [`sempods-spec`](https://github.com/sempods/sempods-spec), rendered
at [spec.sempods.org](https://spec.sempods.org). Read that to implement a pod. Read this to
understand, extend or operate *this* implementation.

Everything below is one of the four types in
[`agents/documentation-strategy.md`](agents/documentation-strategy.md), and the type is what tells
you how much to trust it: **IST** is verifiable in code, **SOLL** is not yet.

## Vision — why this exists

- [`vision.md`](vision.md) — the model and why it is shaped this way. Independent of what is built.

## Concepts — one topic each, IST and SOLL side by side

[`concepts/`](concepts/) — the level above the reference documentation. Each states what is true
today and what the target is, and links down to both.

- [`concepts/modularity.md`](concepts/modularity.md) — which behaviours are deployment-selected
  seams and which invariants are not
- [`concepts/graph-retrieval.md`](concepts/graph-retrieval.md) — `find`, then traverse: the read
  pattern every consumer builds on
- [`concepts/hosted-mcp.md`](concepts/hosted-mcp.md) — one MCP service fronting many pods
- [`concepts/mcp-agent-interface.md`](concepts/mcp-agent-interface.md) — where the per-pod MCP
  surface is going
- [`concepts/inference-context.md`](concepts/inference-context.md) — a TBox layer for type and
  predicate coverage. **SOLL throughout; nothing here is implemented.**
- [`concepts/app-installation.md`](concepts/app-installation.md) — how an owner turns an interactive
  decision into a durable service-client credential

## Reference — what the system is today

All IST. Where one of these disagrees with the code, the code is right and the document is the bug.

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
- [`pod-client.md`](pod-client.md) — the client library, for consumers building against a pod
- [`media.md`](media.md) — the media storage seam: which backends exist, how a deployment picks one
- [`naming.md`](naming.md) — how the name is spelled, everywhere, and why it is not negotiable
- [`logging.md`](logging.md) — what is logged at which level, and what must never be
- [`request-tracing.md`](request-tracing.md) — correlating a request across the three services
- [`testing.md`](testing.md) — the test layers and which one a change belongs in

## Roadmaps — temporary, dissolved when the milestone ships

[`roadmaps/`](roadmaps/) — a breakdown and its status, linking to its concept rather than repeating
it. Two are open: [offline access and refresh
tokens](roadmaps/offline-access-refresh-tokens.md) and [owner app
installation](roadmaps/owner-app-installation.md).

## Instructions for contributors, human and AI

[`agents/`](agents/) — reached from [`../AGENTS.md`](../AGENTS.md) rather than from here, because
they govern how the rest of this directory is written rather than describing the system:
[`ai-instructions.md`](agents/ai-instructions.md) is the hub,
[`documentation-strategy.md`](agents/documentation-strategy.md) the authority on the four types, and
[`documentation-sync.md`](agents/documentation-sync.md) and
[`roadmap-lifecycle.md`](agents/roadmap-lifecycle.md) the two procedures.

## Not yet filed

[`chat-app-brainstorm.md`](chat-app-brainstorm.md) opens by saying the brainstorm moved to
`sempods-apps`, and then carries seven server-side gaps the chat app pushes against — CORS policy,
a server-side `find` entry, per-context summaries, ergonomic `public-read` bearers, operator CORS
guidance, token revocation from the pod, and a SHACL-gated app-MCP analog. Those belong to this
repository, not to the app, and each names a roadmap it has not been added to. The pointer half is
also stale: the target file does not exist in `sempods-apps`.
