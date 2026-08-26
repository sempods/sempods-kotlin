# MCP agent interface (Concept)

Where the per-pod MCP surface is going, beyond what it does today. Everything
here is **SOLL — not implemented**; the file exists so the design is not lost
while the base ships. The breakdown of whatever is currently being built lives
in [`../roadmaps/`](../roadmaps/); the overarching direction is
[`../vision.md`](../vision.md).

## Retrieval primitives — what remains

The `find` entry primitive shipped — as `GET /{pod}/_system/find` and an
MCP `find` tool over one `FindService` (see [`../mcp/tools.md`](../mcp/tools.md#find-read)
and the contract in [`graph-retrieval.md`](graph-retrieval.md)). Structural
traversal uses the shipped `get_resource` / `sparql_*`; there is no separate
`retrieve` / `expand` primitive. What is still vision, all **behind the same
contract** (no consumer change):

- **Vector / hybrid `find`** — a vector engine (or OpenSearch hybrid) behind
  the same swappable adapter SPI, so fuzzy questions land the right resources
  without exact label matches. Follows the pod-level vector index (see
  [`../vision.md`](../vision.md)), and brings cross-adapter rank fusion
  that replaces the PoC's deterministic IRI-order cap once several engines
  merge.
- **Per-type expansion registry** — replace the fixed `type`/`label`/`name`
  expansion with a per-pod / per-context, manage-extendable set ("app-context
  infos"), conditionally recursive (e.g. `Event → location → {name, address}`).
- **General predicate filter** — a caller filter on arbitrary predicates
  beyond the shipped `type` facet, plus a `POST` form to carry it; the
  difficulties are spelled out in [`graph-retrieval.md`](graph-retrieval.md).

## Cross-pod orchestration (client-side)

`list_contexts` returning IRIs that point at other pods invites a
multi-MCP-client pattern: one AI client connected to several pod-scoped
MCP servers in parallel. Cross-pod navigation happens in two ways:

- **Explicit** — the AI calls `get_resource` on a URI that points to a
  different pod; the MCP server for that pod handles the request with
  its own token.
- **Implicit** — the AI client has two servers open, queries both,
  merges the result graph itself.

This is a **client-side** pattern, intentionally. The server stays
strictly pod-immanent: each pod runs its own DCR, its own grants, its
own consent UI. That the same logical agent shows up as a different
`dyn:…` per pod is accepted; reconciliation, if ever needed, belongs
on the client.

The client need not be a desktop app or browser: a **hosted MCP service**
that fronts many pods over one connection is the same pattern, deployed
as a standalone client to pods. It buys one connection + server-side
token refresh (headless use) at the cost of token custody. An
external-first reading goes further — MCP as an LLM-tooling layer *over*
the pod's primitives, making the pod-immanent MCP optional. See
[`hosted-mcp.md`](hosted-mcp.md).

## SHACL-gated app contracts

An app that installs into a pod could bundle three things the core MCP
does not offer on its own:

- **Hints / affordances** specific to the app's domain — what the app
  is for, what resources matter, what the caller is expected to do.
- **SHACL shapes** attached to the caller's grant, restricting the
  *shape* of acceptable writes.
- **Grant downgrade** — the session's effective scopes are narrowed
  to what the app needs, even if the caller's token carries broader
  grants against the pod.

All three hang off the **grant**, not off a URL: the grant is what the
consent dialog produces, what the token resolves to per request, and what
the sandbox already enforces. An earlier sketch gave each app its own MCP
sub-tree (`apps/<appId>/<surface>`) and hung the contract off that path.
The pod now has one MCP surface, so that carrier is gone — and with it one
property nothing else replaces: a per-app URL forced distinct DCR clients
on cloud connectors that collapse several UI entries onto one OAuth
client. That was given up knowingly; see
[`../mcp/endpoint.md`](../mcp/endpoint.md#url).

What survives the move is what mattered: the contract is discoverable
without out-of-band configuration (the grant is what `initialize` and
`list_contexts` already describe) and legible in logs and UIs (the
`client_id` and its granted scopes name the app).

### Why a SHACL sandbox

OAuth scopes answer *"which contexts can this caller touch?"*. They do
not answer *"what shape of write is acceptable?"*. Two illustrative
scenarios:

- Family members may check off tasks in a shared context but must not
  create new ones or change titles.
- A reactive agent may append a result note to a task but must not
  delete the task or rewrite unrelated fields.

A SHACL shape attached to the caller's grant enforces this
structurally. Writes that violate the shape are rejected by the pod
— the AI client cannot work around it, because the pod is the single
enforcement point. Effectively a **second sandbox layer**:

1. OAuth scope → which contexts.
2. SHACL shape on the grant → which shapes of resource, which
   properties, which values.

### Use case 1 — Shareable contracts (apps without developers)

A non-developer user defines a contract (JSON Schema first, SHACL as
the contract grows): *"read everything in context `family-tasks`, write
only `:done true` on existing `schema:Task` resources"*. The pod turns
this into a grant + shape bundle with a stable identifier. The user
shares the identifier with family members; each one's AI client
authorizes against the core MCP with that grant attached. No app
developer, no app store, no UI code — **user-authored, structurally
enforced, shareable AI access**.

JSON Schema is the right entry point because SHACL is heavier; covering
~80% of contracts via JSON Schema and bridging to SHACL for the rest is
a pragmatic path.

### Use case 2 — Reactive agents triggered by markers

A user tags a resource with a marker property (`sempods:needsAiHelp`).
A background agent with a narrow SHACL-bounded grant scans for these
markers, does its work, and writes the result back under the same
shape constraint. Scope creep is structurally impossible; there is no
path from "append note" to "read passport data" without a new grant
the user explicitly issues.

Implementation sketch:

- v0: SPARQL poll every few hours + local LLM (Ollama). No
  ChangeStreams, no cloud dependency, real behavior.
- v1: ChangeStreams / pub-sub as trigger (depends on V4). Agent reacts
  in near-real-time instead of on a cron.

### Graduated trust model

The pattern that emerges from both use cases:

```
Level 1: SHACL contract "read only"
Level 2: SHACL contract "read + flip a boolean"
Level 3: SHACL contract "read + write within shape"
Level N: ... expanding the shape step by step
```

Each level is authorized explicitly by the user and enforced
structurally by the pod. Trust grows by widening the shape, not by
handing over an ever-larger bearer token.

This is the piece missing from most of today's agentic-AI stack: local
agents (OpenClaw-style) run with root; hosted agents run with broad
OAuth scopes. Neither lets the user start small and widen
deliberately.

### Dependencies

| Prerequisite | Reason |
|---|---|
| Commons MCP endpoint (shipped — `find` + CRUD) | Endpoint to attach contracts to |
| V1 (SHACL enforcement) | Shape enforcement on write paths |
| V4 (ChangeStreams) | Optional — only for reactive agents without polling |

### Open questions

- Who authors the "standard shape sets" for common use cases? That is
  a policy / marketplace layer.
- Conflict resolution between context-level shape and grant-level
  shape: intersection, or reject-on-conflict?
- How to derive MCP tool descriptions from shapes automatically, so an
  AI client discovers what it may write without reading the SHACL
  itself.
- Debugging reactive agents: a shape tells you *what the agent was
  allowed to do*, not *why it did the specific thing it did*.
  Observability layer needed.
- UX for non-developers to author a shape-backed contract. Without
  this, "apps without developers" collapses back into a developer-only
  feature.

## Related

- [`graph-retrieval.md`](graph-retrieval.md) — the graph retrieval
  pattern the primitives operationalize.
- [`../roadmaps/`](../roadmaps/) — the breakdown of whichever of these is
  currently being implemented, if any is. `find` itself shipped, see
  [`../mcp/tools.md`](../mcp/tools.md#find-read).
- [`../vision.md`](../vision.md) §"What comes later" — where SHACL,
  reactivity, the vector index and the enhanced MCP interface sit in the
  overall direction.
