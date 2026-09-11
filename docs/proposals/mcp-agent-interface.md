# Grant-bound app contracts for MCP

> **Disposition: proposed — not implemented.** [Issue #137](https://github.com/sempods/sempods-kotlin/issues/137) owns the
> design review, decisions and adoption links. Accepting this proposal does not implement it.

Current retrieval and hosted fan-out are documented in [MCP tools](../mcp/tools.md) and
[hosted MCP](../concepts/hosted-mcp.md). The proposal concerns additional write constraints;
SHACL-derived prompt guidance is not server-enforced validation.

## SHACL-gated app contracts

An app that installs into a pod could bundle three things the core MCP
does not offer on its own:

- **Hints / affordances** specific to the app's domain — what the app
  is for, what resources matter, what the caller is expected to do.
- **SHACL shapes** attached to the caller's grant, restricting the
  *shape* of acceptable writes.
- **Grant downgrade** — the session's effective grants are narrowed
  to what the app needs, even if the caller has broader
  grants at the pod.

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
without out-of-band configuration (the proposed grant contract would need discovery alongside
`initialize` and `list_contexts`) and legible in logs and UIs (the
`client_id` and its context grants name the app).

### Why a SHACL sandbox

Context grants answer *"which contexts can this caller touch?"*. They do
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

1. Context grant → which contexts.
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

A JSON Schema entry path could reduce authoring effort; its coverage and translation
into enforceable SHACL constraints need evaluation.

### Use case 2 — Reactive agents triggered by markers

A user tags a resource with a marker property (`sempods:needsAiHelp`).
A background agent with a narrow SHACL-bounded grant scans for these
markers, does its work, and writes the result back under the same
shape constraint. The design must prove that the write constraint limits mutations to the
permitted operation; read visibility remains the separate grant boundary.

Implementation sketch:

- Polling option: SPARQL poll every few hours + local LLM (Ollama). No
  ChangeStreams, no cloud dependency, real behavior.
- Event-driven option: a separately specified change-notification service as trigger. Agent reacts
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

### Dependencies

| Prerequisite | Reason |
|---|---|
| Commons MCP endpoint (shipped — `find` + CRUD) | Endpoint to attach contracts to |
| Server-enforced SHACL validation | Shape enforcement on write paths |
| Change-notification contract | Optional — only for reactive agents without polling |

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

- [Retrieval extensions](graph-retrieval.md) — separate proposal and owning issue.

- [`graph-retrieval.md`](../concepts/graph-retrieval.md) — the graph retrieval
  pattern the primitives operationalize.
- [`../vision.md`](../vision.md) §"What comes later" — where SHACL,
  reactivity, the vector index and the enhanced MCP interface sit in the
  overall direction.
