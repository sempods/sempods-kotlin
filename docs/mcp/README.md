# Model Context Protocol (MCP)

**The MCP contract is [`spec/modules/mcp.md`](https://github.com/sempods/sempods-spec/blob/main/spec/modules/mcp.md)** — the endpoint,
the authentication modes, the `authorize` tool, the tool catalogue and the closed schemas.
It is an optional module: a pod advertises it or does not provide it.

This folder is how *this* pod server realises it, and what the module deliberately leaves
open — the exact tool arguments, the challenge store behind re-authorization, the
registration fingerprint, the session instructions, and how real clients behave against
all of it.

## Mental model

Three primitives:

- **Endpoint** — one MCP endpoint per pod at `/{pod}/_system/mcp`.
  JSON-RPC 2.0 over HTTP POST. One surface per pod; nothing routes below
  that path. It carries route, auth, discovery and `authorize`, and
  delegates the tools themselves.
- **Tools** — pod primitives surfaced as MCP tools: `sparql_select`,
  `sparql_graph` and `find` for reads, `create_resource` / `update_resource` /
  `delete_resource` for writes, plus `list_contexts` and the synthetic
  `authorize` tool that drives the OAuth handshake. System-layer
  property-value tools (`set_property_values`, `add_property_value`,
  `remove_property_value`, `clear_property_values`) add
  triple-granular writes and must ship in the same delivery cut as the
  LOD-CRUD HTTP System layer.
- **Sandbox** — every tool call is restricted to the contexts the
  caller's bearer (or anonymous public-read) covers. Not enforced twice:
  a tool call *is* a call to the pod's `_system/…` routes, made over HTTP
  with the caller's own bearer, so REST and MCP cannot drift because
  there is only one of them (see
  [`endpoint.md`](endpoint.md#how-a-tool-call-reaches-the-pod)).

## Design principles

- **Expose pod primitives, not abstractions.** SPARQL queries and
  resource CRUD, gated by OAuth and the context sandbox. The `find` entry
  primitive ships as the one retrieval ergonomic; richer retrieval (vector,
  expansion registry) and SHACL-gated contracts are the concept, not the
  current surface (see
  [`../concepts/mcp-agent-interface.md`](../concepts/mcp-agent-interface.md)).
- **Commons server, not one-per-use-case.** A single MCP server per pod
  serves all usage patterns; new patterns require new contexts, not new
  MCP variants. App-/user-defined MCP surfaces under `apps/…` and
  `users/…` are a future extension along the same path schema.
- **Auth by token, sandbox by context.** Every tool call resolves the
  caller's sandbox from OAuth scopes. The pod's own consent UI drives
  grant changes — no MCP-specific auth UI.
- **Reuse existing flows.** `/{pod}/_system/auth/authorize`, the token
  endpoint, and `id.sempods.org` are already in place. The MCP rides on
  top of them; nothing new in the auth layer apart from the
  protected-resource metadata pointer and the synthetic `authorize`
  tool.
- **Pod-immanent.** Every concept the MCP deals with (clientId, grants,
  tokens, consent, "active connections") lives inside a single pod.
  Cross-pod orchestration is a client-side pattern (one AI client, many
  pod MCPs), not a server-side concern.

## Standards used

| Standard | Where it shows up |
|---|---|
| MCP 2024-11-05 / 2025-03-26 / 2025-06-18 / 2025-11-25 | `initialize` protocol negotiation (`endpoint.md`) |
| JSON-RPC 2.0 | All MCP requests (`endpoint.md`) |
| OAuth 2.1 + PKCE | Bearer issuance (`authentication.md`, `../auth/oauth.md`) |
| RFC 7591 — Dynamic Client Registration | `dyn:*` MCP clients (`authentication.md`) |
| RFC 9728 — Protected Resource Metadata | `.well-known/oauth-protected-resource` routes (`endpoint.md`) |
| RFC 8414 — Authorization Server Metadata | `.well-known/oauth-authorization-server` routes (`endpoint.md`) |
| RFC 6750 — Bearer Token usage | `Authorization: Bearer …` on every authenticated call |
| RFC 7396 — JSON Merge Patch | `update_resource` body shape (`tools.md`) |
| RFC 4648 §5 — base64url | System-layer HTTP routes wrapped by slot tools |

Standards are *named*, not re-explained in these docs.

## Doc map

- **`endpoint.md`** — endpoint URL, JSON-RPC envelope, supported
  methods (`initialize`, `tools/list`, `tools/call`, `resources/list`,
  `prompts/list`, notifications), the OAuth discovery routes
  (RFC 9728 / 8414, append- and host-rooted variants), error codes.
- **`tools.md`** — every tool (`authorize`, `list_contexts`,
  `sparql_select`, `sparql_graph`, `create_resource`,
  `update_resource`, `delete_resource`) plus planned System-layer slot
  tools, the autodiscovery recipe, the SPARQL guardrails (write-keyword
  + `SERVICE` rejection, 10 s timeout), and request/response examples.
- **`authentication.md`** — anonymous vs. bearer behavior,
  `public-read` semantics, the synthetic `authorize` tool and its
  `reauthorize=true` upgrade flow, the WWW-Authenticate replay,
  per-MCP DCR fingerprinting, and what `offline_access` does and does
  not settle about a durable connection.
- **`clients.md`** — setup snippets and observed behavioral clusters
  (proactive vs. defensive) for Claude Desktop / Code / Web, ChatGPT,
  Copilot / VS Code, Open-Code, plus known client-side limits we
  document but do not work around.
- **[`../concepts/mcp-agent-interface.md`](../concepts/mcp-agent-interface.md)** — the concept:
  SHACL-gated app contracts, vector-assisted retrieval (the `find` primitive
  itself shipped — see `tools.md`), cross-pod orchestration. Target state,
  without committing to a delivery shape.
- **[`../concepts/hosted-mcp.md`](../concepts/hosted-mcp.md)** — a standalone MCP service (the `sempods-mcp` module)
  that fronts many pods over one connection, treating MCP as an
  LLM-tooling layer *over* the pod's primitives rather than a per-pod
  feature. Covers the value (one connection, server-side token refresh),
  the cost (token custody), profile-path naming, and the **direction on the
  three tool surfaces** (per-pod MCP, chat app, hosted) — all three stay,
  and the two server-side ones now run one shared implementation. Live on
  `mcp.sempods.org` (M1–M6 done).
- **[`../roadmaps/`](../roadmaps/)** — the milestone currently being implemented,
  if one is. Completed items stay marked done until the whole milestone is
  consolidated; the file is dissolved then, not item by item.
