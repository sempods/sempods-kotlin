# MCP Endpoint

JSON-RPC 2.0 over HTTP POST, one endpoint per pod.

## URL

```
POST /{pod}/_system/mcp
```

- `{pod}` — pod name (tenant boundary).
- `Content-Type: application/json` required.

One surface per pod, at a fixed path — nothing routes below it. The
handler is
[`McpEndpoint`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/system/mcp/McpEndpoint.kt),
which carries the route, authentication, discovery and the `authorize`
tool — and nothing else: the thirteen data tools run over the pod's own
HTTP surface, see [below](#how-a-tool-call-reaches-the-pod).

The path used to carry a free `{mcpPath}` segment that fed the DCR
fingerprint, so a cloud connector could be forced into separate OAuth
clients per URL. It was retired: the path was opaque to server semantics
(every value reached the same handler) and the separation it bought
belongs to the hosted MCP service, where profiles are a product feature
rather than a path variable. The cost is named under
[`clients.md`](clients.md#chatgpt).

The cut is hard — there is no alias. The old `/{pod}/_system/mcps/…` URL
falls into the LOD resource namespace like any other unknown pod path, so
a POST to it answers `405`, never JSON-RPC. Clients configured against it
have to be re-added.

## Protocol negotiation

`initialize` is the only handshake. The server advertises the highest
spec revision it supports and accepts any of them on the wire:

```
2025-11-25 · 2025-06-18 · 2025-03-26 · 2024-11-05
```

If the client requests a version not in this list, the server negotiates
`2025-11-25` (the first entry). Sending a blank `protocolVersion`
returns `-32602` with the supported list in `data.supported`.

`capabilities.tools.listChanged = true` is advertised so future
`tools/list_changed` notifications stay an option.

## Methods

| Method | Auth | Notes |
|---|---|---|
| `initialize` | anon or bearer | Returns the negotiated protocol version, server info, and per-session `instructions` (built from the granted contexts — see [`tools.md`](tools.md#instructions-block)). |
| `notifications/initialized` | anon or bearer | Notification (no response). Logged as `outcome=notification`. |
| `tools/list` | anon or bearer | Returns the full tool list. The `authorize` tool's description is auth-state-aware. |
| `tools/call` | anon or bearer (write tools require bearer) | Dispatches by `params.name`. See `tools.md`. |
| `resources/list` | anon or bearer | Returns `{ "resources": [] }`. sempods does not surface anything via the MCP `resources/*` family — everything is exposed through tools. The empty stub exists to keep ChatGPT / Open-Code probes from logging `method-not-found` noise. |
| `prompts/list` | anon or bearer | Same shape and rationale as `resources/list`, returns `{ "prompts": [] }`. |

Any other method returns JSON-RPC `-32601` (method not found).

### Request envelope

```json
{ "jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {} }
```

`id == null` is treated as a JSON-RPC notification — the server returns
`202 Accepted` with no body and audits the call as
`outcome=notification`.

`202` asserts the input was accepted, so it is withheld where it cannot be:
a notification addressed to a pod that does not exist is rejected with
`404` and audited as `outcome=error error=pod_not_found`, like every other
method. That body is a JSON-RPC error carrying no `id` — the shape the
Streamable HTTP transport names for a rejected notification POST
([transports §Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server),
rule 4).

### Response envelope

```json
{ "jsonrpc": "2.0", "id": 1, "result": { /* method-specific */ } }
```

Errors use the standard JSON-RPC error envelope with these codes:

| Code | When |
|---|---|
| `-32700` | Parse error (malformed JSON) |
| `-32600` | Invalid request shape |
| `-32601` | Method not found / unknown tool |
| `-32602` | Invalid params (missing `query` argument, malformed `initialize` params, …) |
| `-32603` | Unexpected internal error (also returns HTTP 500) |
| `-32001` | Bearer rejected — paired with HTTP 401 + `WWW-Authenticate`, see [`authentication.md`](authentication.md) |
| `-32002` | Unknown pod — the `{pod}` segment names no pod (also returns HTTP 404) |

## How a tool call reaches the pod

`tools/call` does not run against the pod's services in process. The
handler resolves the tool through
[`PodToolExecutor`](../../sempods-mcp-core/src/main/kotlin/org/sempods/mcp/core/PodToolExecutor.kt)
in `sempods-mcp-core` and executes it over **HTTP against the pod's own
public base URL** (`SEMPODS_PUBLIC_BASE_URL`) — the same `_system/…`
routes an external client calls, with no special case:

```
MCP client ──POST──► {pod}/_system/mcp ──POST/GET/PUT/PATCH/DELETE──► {pod}/_system/…
                     (route, auth, authorize)      (the tools, over the wire)
```

The caller's bearer is forwarded untouched, so authentication, the
context sandbox and every scope check happen in the code REST callers
already exercise. No bearer stays no bearer: anonymous is a supported
mode and the pod answers from its public contexts. `initialize` builds
its instructions block the same way, through `list_contexts`.

The hosted MCP service in `sempods-mcp` runs the same executor over the
same routes; what differs between them is fan-out, not semantics. The
shared module and the cut between them are in
[`../modularity.md`](../modularity.md).

### What it costs

Stated rather than discovered:

- **A tool call is two HTTP requests, not one** — three for
  `get_resource` with `include_contexts=true`, which needs a second read
  for the write-precondition `ETag` (see [`tools.md`](tools.md)).
- **The reverse proxy is in the path.** A proxy outage takes the MCP
  surface down while the pod itself is up.
- **The deployment must be able to reach itself** under the address it
  publishes. Local development and the test run reach `localhost`, which
  the outbound SSRF guard would otherwise refuse; the pod server's
  transport carries a trusted-host exemption for exactly that one
  configured host and no other.

What it is *not* is a thread-pool hazard: Jetty runs on a virtual-thread
pool, so a request waiting on the pod's answer holds no platform thread.

The alternative — an internal address for the self-call — is not taken.
The public URL is the simple choice and the one that keeps the two MCP
surfaces exercising identical code. The `trustedHosts` mechanism already
carries the alternative if a deployment turns up that cannot reach itself.

## Audit logging

Each request emits one `[mcp/audit]` line. `outcome=` is one of:

```
initialize · tools_list · tool_call · tool_error · resources_list ·
prompts_list · notification · accepted · auth_trigger · error
```

`tool_call` vs. `tool_error` distinguishes a successful tool invocation
from a tool that returned `isError=true` (used by the per-context
write-scope check). `auth_trigger` is the deliberate 401 emitted by the
synthetic `authorize` tool — see `authentication.md`.

`outcome=error` carries an `error=` value naming the cause, so a caller
mistake and a server failure stay apart in the stream: `pod_not_found`
(HTTP 404), `invalid_bearer` (401), `jsonrpc_<code>`, `http_<status>`
for any other mapped status, and `internal` (500).

The request body is logged at INFO with `params._meta` redacted. ChatGPT
puts user geolocation, session and organization IDs into `_meta`; the
[`McpEndpoint.redactMetaForLog`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/system/mcp/McpEndpoint.kt)
helper replaces the field with `"<redacted>"` before logging. The
bearer is verified independently of this best-effort redaction.

## OAuth discovery routes

A pod-bound MCP needs to advertise where to obtain a bearer. Different
clients probe different paths; the pod serves all of them. The protected
resource is always the pod URL, and the pod has exactly one issuer.

### Pod-level

```
GET /{pod}/.well-known/oauth-protected-resource
GET /.well-known/oauth-protected-resource/{pod}                              ← RFC-9728 §3.1 strict
GET /{pod}/_system/auth/.well-known/oauth-authorization-server
GET /.well-known/oauth-authorization-server/{pod}/_system/auth               ← RFC-8414 strict
```

### At the MCP URL

MCP 2025-11-25 clients treat the MCP URL as the protected-resource
identifier and probe it before they ever see a 401. Both routes serve the
pod-level body — the MCP URL is another spelling of the same resource:

```
GET /{pod}/_system/mcp/.well-known/oauth-protected-resource
GET /.well-known/oauth-protected-resource/{pod}/_system/mcp                  ← RFC-9728 §3.1 strict
```

There is deliberately **no** `oauth-authorization-server` route under the
MCP URL. The MCP URL is not an issuer identifier, and RFC 8414 §3.3 wants
the served `issuer` to match the URL it was fetched from. Both probes
return 404; clients reach the issuer through `authorization_servers` in
the PRM.

### Body shape

Protected-resource metadata (RFC 9728 + sempods extensions):

```json
{
  "resource": "https://<host>/<pod>",
  "authorization_servers": ["https://<host>/<pod>/_system/auth"],
  "bearer_methods_supported": ["header"],
  "public_contexts": 2,
  "name": "<optional pod display name>"
}
```

Notes:

- `authorization_servers` is the pod's single issuer, whichever of the
  four PRM routes the client asked.
- `public_contexts` is a **count**, never the URI list — pods do not
  leak topology to advertise the existence of public-read content.
- `name` is `PodDbo.displayName` when set; SDKs surface it as
  `PodConnection.displayName`.
- `scopes_supported` is intentionally omitted — the scope space is
  per-context and partially synthesised by apps, so the consent dialog
  is the single source of truth.

Authorization-server metadata (RFC 8414):

```json
{
  "issuer":                                "https://<host>/<pod>/_system/auth",
  "authorization_endpoint":                "https://<host>/<pod>/_system/auth/authorize",
  "token_endpoint":                        "https://<host>/<pod>/_system/auth/token",
  "registration_endpoint":                 "https://<host>/<pod>/_system/auth/register",
  "jwks_uri":                              "https://<host>/<pod>/_system/auth/jwks.json",
  "response_types_supported":              ["code"],
  "grant_types_supported":                 ["authorization_code", "refresh_token"],
  "code_challenge_methods_supported":      ["S256"],
  "token_endpoint_auth_methods_supported": ["none"]
}
```

A pod has one registration endpoint. `POST /register/<anything>` is a
404 — the path-segment variant existed only to fork DCR fingerprints per
MCP surface (see [`authentication.md`](authentication.md#dcr-fingerprint)).

**Both documents are hand-built maps, on purpose**, here and on the
hosted service. An OAuth library's metadata type serialises defaults for
everything it knows how to express, and RFC 8414 §2 asks a server to
advertise what it actually implements; production clients depend on
exactly the field set above. The map *is* the contract, so the parsing
side uses the library and the producing side does not.

## Related

- [`tools.md`](tools.md) — every tool definition + examples.
- [`authentication.md`](authentication.md) — bearer / anonymous /
  public-read / `authorize` tool / DCR.
- [`../auth/oauth.md`](../auth/oauth.md) — full OAuth flow at the pod
  level.
