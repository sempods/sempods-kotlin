# MCP Authentication

The MCP endpoint reuses the pod's OAuth 2.1 stack. This file documents
only the pieces specific to MCP clients; the full pod auth model lives
in [`../auth`](../auth).

## Modes

A request to `POST /{pod}/_system/mcp` is in one of three
states:

| Mode | Bearer | What works |
|---|---|---|
| **Anonymous** | none | `initialize`, `tools/list`, `resources/list`, `prompts/list`, `list_contexts`, `sparql_select`, `sparql_graph` — restricted to the pod's public contexts. The synthetic `authorize` tool emits a 401 with `WWW-Authenticate`. Write tools throw `InvalidBearerException` (also 401). |
| **Public-read bearer** | `scope=public-read` only | Same as anonymous, but the `sub` is either the user's WebID or a synthetic `urn:sempods:anon:<uuid>`. The `authorize` tool still emits 401 to upgrade. |
| **Context-granted bearer** | a normal user/service token whose `(client_id, sub)` has per-context grants in the store; the token's `scope` claim carries only feature scopes, optionally `public-read` | Sandbox = granted contexts (resolved server-side per request from `PodGrantsDao` / `PodServiceClientDao`) ∪ (public contexts only if `public-read` is in the token's scope set). The token does **not** carry `<context>#…` scopes. `public-read` is *additive*, not implicit: a normal feature scope the consent UI pre-checks by default but the user may deselect ([`../auth/authorization.md`](../auth/authorization.md) "The `public-read` pseudo-scope"; enforced in `GrantStorePodAuthorizer.authorize()`, which unions the pod's public contexts in only when the token carries the scope). Without it, the bearer sees only its resolved grants. |

An *invalid* bearer (manipulated, expired, signature mismatch) is
always rejected — the server never silently downgrades a failed auth
attempt to anonymous.

For the underlying scope grammar, the `manage` slash-delimited rule, and
the public-read token semantics, see
[`../auth/authorization.md`](../auth/authorization.md).

## The `authorize` tool

Three of the four major MCP clients we test against (Claude, Copilot,
Open-Code) are **defensive**: they call `list_contexts`, see no
writable contexts, and tell the user to reconnect manually instead of
calling a write tool that would have produced the standard 401.
ChatGPT is the exception — it triggers OAuth proactively when a pod is
added.

The synthetic `authorize` tool covers the defensive case: it is always
visible in `tools/list`, so the model can call it the moment the user
asks for something that needs more than public-read.

### Server-side decision matrix

```
                                 │  reauthorize=false   │  reauthorize=true
─────────────────────────────────┼──────────────────────┼──────────────────────────
 anonymous / public-read-only    │  401 + WWW-Auth      │  401 + WWW-Auth
 context-scoped bearer           │  no-op JSON ack      │  401 + WWW-Auth
                                 │                      │  (refresh tokens revoked,
                                 │                      │   replay challenge recorded)
```

The 401 carries `WWW-Authenticate: Bearer
resource_metadata="<…/.well-known/oauth-protected-resource>"`; the MCP
client follows the link, runs the standard OAuth flow advertised by the
PRM, and replays the same `tools/call` with the new bearer.

`outcome=auth_trigger` is logged for the deliberate 401, distinct from
`outcome=error error=invalid_bearer` which only fires for actually
broken bearers. This separation matters when grepping audit logs to
verify a client picked up the trigger.

### Reauthorize replay

`reauthorize=true` is used to *extend* an existing grant (request more
contexts) without losing the current session. Two near-indistinguishable
calls share the same arguments:

1. The genuine extension request — must answer 401.
2. The MCP client's automatic replay after the OAuth roundtrip,
   carrying the brand-new bearer with the same body — must answer with
   the idempotent ack, not a second 401.

[`ReauthorizeChallengeStore`](../../sempods-mcp-core/src/main/kotlin/org/sempods/mcp/core/ReauthorizeChallengeStore.kt)
disambiguates them, shared with the hosted service. On the original 401
it records `(pod, clientId, sub, jti, recordedAt)` with a 5-minute TTL.
The replay is recognised when:

- The same `(pod, clientId, sub)` arrives,
- with a *different* `jti`,
- whose `iat` (token-issuance time) is at-or-after `recordedAt`.

Iat is checked at second precision (JWT serialization rounds `iat` to
seconds; recording is sub-second). A token issued in an earlier second
is rejected — that excludes parallel-session bearers on the same
client_id. Anonymous challenges have no client/sub yet and are keyed by
pod only; the first fresh authenticated token on that pod consumes them.
Anonymous entries are last-write-wins per pod; concurrent anonymous flows
against the same pod may need to retry.

Refresh tokens for the affected `(podId, clientId, webId)` are revoked
on the original 401 — explicit reauthorize means *review current
consent*, so parallel sessions must not silently rotate around the
consent UI.

The store is Mongo-backed and its rows are TTL-indexed, so a deploy
inside the five-minute window does not cost the caller its consent
roundtrip, and the confirmation call may land on a different replica
than the one that issued the challenge. The replay predicate is one
conditional `findOneAndDelete`, so exactly one of N concurrent
confirmations consumes the challenge; a non-matching call leaves it in
place for the proper replay still to come.

## Bearer challenge format

The `WWW-Authenticate` header on every 401 carries:

```
Bearer realm="<pod-base-url>", resource_metadata="<resource-metadata-url>"
```

The `resource_metadata` URL is the pod-level PRM
(`…/{pod}/.well-known/oauth-protected-resource`) for every caller, MCP
or REST: the pod is the protected resource in both cases.

## DCR fingerprint

MCP clients with no persistent client-state (Claude Code / Desktop and
similar) re-register on every reconnect. Without dedup, each call would
mint a fresh `dyn:<random>` the user has never consented to, orphaning
the previous consent. sempods therefore digests the stable parts of the
registration —
[`DynamicClientFingerprint`](../../sempods-auth-core/src/main/kotlin/org/sempods/auth/core/DynamicClientFingerprint.kt):

```
fingerprint = SHA-256( clientName · userAgent · normalized-redirect-uris )
```

Loopback redirect URIs are matched with port-stripping (RFC 8252 §7.3),
which is what makes the digest survive the ephemeral port a desktop
client picks per launch.

Consequence: a client that re-registers with the same `clientName` /
`userAgent` and redirect-URI shape reuses its existing `dyn:` clientId,
so consent and grants stay anchored to one row.

The digest has a fourth slot — a realm — that the pod leaves empty. It
used to carry the MCP path, which forced one OAuth client per MCP URL on
cloud connectors that otherwise collapse several UI entries onto one; the
hosted MCP service fills the same slot with its profile. What the pod
gives up with it is in [`clients.md`](clients.md#chatgpt).

## Setup for clients

For per-client setup snippets and observed quirks (Claude Desktop /
Code / Web, ChatGPT, Copilot / VS Code, Open-Code) see
[`clients.md`](clients.md).

## Related

- [`endpoint.md`](endpoint.md) — discovery routes and 401 challenge
  embedding in JSON-RPC error envelopes.
- [`tools.md`](tools.md) — the `authorize` tool description and
  per-tool scope checks.
- [`../auth/oauth.md`](../auth/oauth.md) — OAuth 2.1 flow, refresh
  rotation, public-read flow, error semantics at the pod level.
- [`../auth/authorization.md`](../auth/authorization.md) — scope
  grammar, `manage` rule, sandbox enforcement.
