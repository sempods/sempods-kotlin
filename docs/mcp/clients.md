# MCP Clients

Per-client setup snippets and the behavioral clusters we observe in
practice. The list covers the four clients we test against; other MCP
clients should fall into one of the same clusters.

## Behavioral clusters

| Cluster | Clients | OAuth trigger | Workaround if anonymous-only |
|---|---|---|---|
| **Proactive** | ChatGPT | Reads PRM at connector add time, runs OAuth before the first JSON-RPC call. | n/a — OAuth always runs first. |
| **Defensive with reconnect** | Claude.ai (web), Claude Desktop, Claude Code | Connects anonymously; surfaces a UI affordance (`Disconnect/Reconnect` or `/mcp` re-add) that re-runs DCR + `/authorize`. Does not trigger 401 by trying a write tool. | The synthetic `authorize` tool is the in-flow path; reconnect is the out-of-flow fallback. |
| **Defensive without reconnect** | Copilot / VS Code, Open-Code | Connects anonymously; their re-connect UI does **not** start a new OAuth flow. | The synthetic `authorize` tool is the **only** path to authentication. |

The synthetic `authorize` tool is described in
[`authentication.md`](authentication.md#the-authorize-tool). It is visible
in `tools/list` for every cluster so a model that sees no writable
contexts can call it on its own.

## Setup

The shape of the client config differs per client; below are the
canonical entries. All point at the pod's MCP URL — the rest is
discovery + OAuth.

None of them carries a scope, and none needs one. A client builds its own
`/authorize` request from the discovery documents, so whether it asks for
`offline_access` is the client's business — and asking is not what decides
the outcome: staying connected past the access token's hour is a control on
the pod's consent dialog, which the person answers
([`authentication.md`](authentication.md#durable-connections)). So a
connection that drops back to a sign-in every hour is a consent worth
revisiting, not a config line missing here.

### Claude Desktop / Code / Web

```json
{
  "mcpServers": {
    "my-sempod": {
      "url": "https://<host>/<pod>/_system/mcp"
    }
  }
}
```

Claude connects anonymously first. If the model needs private data or
write access, it can call the synthetic `authorize` tool to trigger a
401, or the user can use the client's reconnect flow. The client then
reads `.well-known/oauth-protected-resource`, opens `id.sempods.org`,
the user picks contexts in the consent dialog, and subsequent calls
carry the bearer.

### ChatGPT

Add a Custom Connector in ChatGPT settings, point it at
`https://<host>/<pod>/_system/mcp`. ChatGPT runs OAuth immediately as
part of the connector add.

Known limit: ChatGPT collapses multiple UI connectors onto one DCR client
when the server URL matches, so one ChatGPT account cannot hold two
independently-consented connectors against the same pod — the second
inherits the first's `client_id` and its consent. A free path segment
used to be the workaround (`…/_system/mcps/default/chatgpt-work`); it was
retired with the rest of the MCP path. A pod is one resource with one
consent per client, and the hosted MCP service covers the multi-identity
case with profiles.

### Copilot / VS Code

The Copilot MCP integration accepts the same
`https://<host>/<pod>/_system/mcp` URL in workspace settings.
A user request that needs writes prompts the model to call the
`authorize` tool; the resulting 401 + `WWW-Authenticate` triggers VS
Code's MCP-OAuth flow.

### Open-Code

Same URL, configured per Open-Code's connector settings. Same
`authorize` tool path as Copilot.

## Known client-side limits

These are issues *outside* sempods that we document for context. They
do not get worked around server-side; the pod's role is to expose the
synthetic `authorize` tool, the path-aware PRM, and the
WWW-Authenticate hint correctly.

- **Open-Code stops after DCR.** The Open-Code client successfully
  registers via `/register` but does not continue to `/authorize` —
  the model is left to "tell the user the URL", which it cannot
  generate without the DCR `client_id` / PKCE / `redirect_uri`. Should
  be reported upstream to Open-Code.
- **VS Code Copilot mis-parses PRM `authorization_servers`.** Observed
  in the R4 spike: the popup shows the host origin
  (`https://<host>/`) instead of the MCP-path-specific issuer the PRM
  advertises. The DCR endpoint is therefore not auto-discovered.
  Should be reported upstream to the VS Code MCP project.
- **Claude.ai dedupes tools across connectors.** A `create_resource`
  tool registered for one pod can disappear from the model's tool
  inventory if another connected pod exposes the same tool name. The
  server-side `tools/list` is correct in both connections; the
  deduplication is in Claude.ai's UI layer.
- **Claude.ai does not auto-retry OAuth on a public-read upgrade
  401.** When a public-read bearer triggers `OAuthUpgradeRequired`,
  Claude.ai surfaces "Authentication failed" instead of starting a
  new OAuth flow. Manual `Disconnect/Reconnect` works around it.
- **"Connected" status without auth.** Copilot / VS Code and Open-Code
  show a green "Connected" badge as soon as anonymous `initialize`
  succeeds, even when no useful scopes are available. The synthetic
  `authorize` tool is still the path forward; the badge is misleading
  but cosmetic.

## Telemetry observations

- **ChatGPT** posts a `params._meta` block with user geolocation
  (city, region, country, timezone, lat/long), opaque session and
  organization IDs, and a UA string. The pod redacts the entire
  `params._meta` field before logging the request body — see
  [`endpoint.md`](endpoint.md#audit-logging). The bearer is verified
  independently of this best-effort redaction.
- **Copilot / VS Code** sends `progressToken` and
  `vscode.{conversationId, requestId}` — no PII.
- **Open-Code** sends no `_meta` field.
- **Claude** sends a small, non-identifying `_meta`.

## Related

- [`endpoint.md`](endpoint.md) — discovery routes and audit log shape.
- [`authentication.md`](authentication.md) — `authorize` tool, replay
  registry, DCR fingerprint.
