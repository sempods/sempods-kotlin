# Hosted MCP — a standalone MCP service for pods

**hosted-mcp** is an **additional, standalone service** (e.g. `mcp.sempods.org`) that
provides the **MCP / LLM-tooling layer** over sempods pods. One service fronts **many
pods** — including pods run by others that implement the sempods HTTP/Auth profile,
addressed by pod base URL — so one AI client reaches all of a user's pods over one
connection.

The implementation lives in `sempods-mcp`. Its [runtime documentation](../../sempods-mcp/docs/runtime.md)
owns persistence, token renewal and operational constraints; the [tool contract](../../sempods-mcp/docs/tool-contract.md)
owns the tool surface. This document explains the cross-module architecture and credential boundaries.
[Portable interoperability](../../sempods-mcp/docs/proposals/interoperability.md) remains proposed.

## Why a separate layer, not pod-immanent

The pod's job is **data and access control**: RDF resources, contexts, the HTTP System /
LOD layer, SPARQL, and OAuth grants over that data. These are the sempods **primitives**.

MCP is a different layer. The MCP tool catalog, the synthetic `authorize` tool,
DCR-fingerprinting to separate cloud connectors, JSON-RPC framing, and tool descriptions
tuned empirically per AI client are **LLM-client adapter concerns**, not data primitives.
An MCP tool like `get_resource` or `sparql_select` adds **no capability** the pod's HTTP
API does not already have — it repackages existing primitives for one class of client.

Two consequences — arguments for the hosted layer *existing*, not against a pod also
carrying its own MCP:

- **Making MCP mandatory pod baseline would couple LLM-client concerns into every pod
  core**, complicating the sempods spec for a layer that is pure adaptation. Keeping MCP
  *off* the required baseline is what makes the spec simpler to implement — a pod need only
  serve the HTTP/Auth profile.
- **An adapter can also live once, outside the pod.** A single hosted MCP service adapts
  *any* conformant pod's HTTP API into MCP — a bridge for pods that expose no MCP of their
  own, and a place to keep the tool semantics coherent (see
  [toolset divergence](#toolset-divergence)) rather than re-derived per pod.

This is the case for **external-first** as a *separate hosting layer*: the pod stays small
and primitive; the MCP layer can live outside it. That argues for the hosted service
**existing** — not for the pod-immanent MCP
([`McpEndpoint`](../../sempods-server/src/main/kotlin/org/sempods/api/pod/system/mcp/McpEndpoint.kt)) being
redundant. It, the client-side layer, and this service are
[three surfaces over one semantics](#direction-one-semantics-three-surfaces).

### What external-first gives up, honestly

The per-pod MCP had one real property this loses: **direct, no-third-party access**. An AI
client did OAuth straight against the pod and held its own token; nothing else sat in the
path. The hosted service moves that token into a third party — the **token custody** cost
below. The honest mitigation: the service is **self-hostable** (and can be co-deployed
with a pod), so for a self-hosted operator the tokens stay with them; custody only
genuinely centralizes on a **multi-tenant public** instance like `mcp.sempods.org`. The
secondary change is that an AI client now reaches a pod *through* the service rather than
directly. This trade is the whole decision — taken deliberately, not by omission.

## It stays a client

Even as the primary MCP layer, the service is structurally a **client** to each pod: its own client
identity there — a DCR registration, or the static `did:web` client at a pod offering no DCR — and a
bearer per `(user, profile, pod)` (see
[identity and keying](#identity-and-keying)); the pod runs its own grants, consent, and
server-side enforcement. The service adds **no cross-pod identity, grant, or revocation
primitive** to any pod. "AI agents are clients, structurally identical to any other app"
still holds — the client is just hosted rather than bundled in a desktop app or browser.

```
  AI client (Claude Desktop, ChatGPT, …)
        │  (1) MCP + OAuth   ── one fixed URL
        ▼
  mcp.sempods.org   ── standalone service, a client to pods
   ├─ MCP server surface (initialize / tools/list / tools/call)
   ├─ tool layer        (targets, SPARQL rewrite, per-(pod,context) envelope)
   ├─ connection registry  (user, profile → [pod URL, OAuth client, scopes])
   └─ token vault          (encrypted, per (user, profile, pod); refresh loop)
        │  (2) per pod: OAuth client (DCR or did:web), bearer per (user, profile, pod)
        ▼
  Pod A (HTTP System layer)  …  Pod B  …  Pod C    ← each enforces its own grants
```

Pod access goes through each pod's **public HTTP System layer** (`_system/resources/...`,
SPARQL) — the pod's primitive API, the same surface the chat app uses. The service
depends only on that, not on any embedded per-pod MCP.

## What it buys — and what it costs

What the hosted service buys over a purely client-side (in-browser / in-app) tool layer:

- **One MCP connection instead of N.** Pod selection moves into tool
  arguments (`targets`), not into N separately-configured servers.
- **Server-side token refresh** → headless / cron / agentic use without an
  open browser. This is the capability a purely client-side tool layer
  cannot have. It rests on a pod-issued refresh token, and how long one stays
  usable is that pod's policy: the service rotates on a conservative cadence
  and learns of an ending by being refused.
  At a sempods pod the person decides that length at consent
  ([`../auth/oauth.md`](../auth/oauth.md#offline_access)), and a connection
  consented as short-lived lapses after hours of disuse — this service does not
  hold it open, because holding it is the authority that answer declined.
- **Cross-pod calls in one tool invocation**, with per-`(pod, context)`
  isolation so one unreachable pod does not poison the others.

The cost is **token custody** — the price of making MCP a separate service:

| | Bundled client (chat app) | Hosted MCP service |
|---|---|---|
| Pod tokens live | in the user's browser | server-side, in the service |
| Trust chain | pod ↔ client (2 parties) | pod ↔ service ↔ AI client (3) |
| Attack surface | one browser per user | one service holding many users' pod keys |

The service becomes a credential custodian. This is acceptable when it is **self-hostable**
and the user **connects pods explicitly and can revoke** at any time; on a **public
multi-tenant** instance the custody is the main liability (see the module runtime documentation). It does not
change that the service stays a **client** — it adds no authority a pod depends on.

Two-layer consent follows: (1) the user authenticates **once** to the service (MCP OAuth —
they trust it); (2) the user authorizes the service **per pod** through its own connect
flow.

## Connecting a pod (OAuth)

A plain server-side OAuth flow in the session-protected `/_system/ui`
([`WebUiEndpoint`](../../sempods-mcp/src/main/kotlin/org/sempods/mcp/api/web/WebUiEndpoint.kt)) —
not the per-pod MCP's interactive `authorize` tool, which exists for an AI client driving OAuth
inside its own JSON-RPC stream (see
[`../mcp/authentication.md`](../mcp/authentication.md#the-authorize-tool)).

1. The user enters a pod base URL. The service vets it ([SSRF](#security--pod-urls-and-ssrf)) and
   reads the pod's OAuth metadata. A registration endpoint means DCR; RFC 9728 alone means the
   service's static `did:web:<mcp-host>` client.
2. Authorization Code + PKCE at the pod's `authorize`, with `scope=offline_access` where the pod
   advertises it (see [what it buys](#what-it-buys--and-what-it-costs)).
3. `state` is an opaque handle to a one-time, expiring server-side row
   ([`PodConnectStateStore`](../../sempods-mcp/src/main/kotlin/org/sempods/mcp/pods/PodConnectStateStore.kt)),
   which holds the flow's context. That is the mix-up defense — the callback resumes the exact
   connect it belongs to — and it lets a connect started on one replica finish on another.
4. The callback consumes the row, requires the signed-in user to be the one who started the flow,
   exchanges the code, and stores the tokens under `(user, profile, pod)`. No `nonce`: there is no
   `id_token` on this leg. The session cookie binds the callback to the signed-in *identity*, not
   to the browser that started the flow — a second browser signed in as the same user completes it
   too; the login legs pin the browser, this one does not.

**One redirect URI per profile**
([`PodClientIdentity`](../../sempods-mcp/src/main/kotlin/org/sempods/mcp/pods/PodClientIdentity.kt)):
`…/_system/ui/pods/callback` for the default profile, plus a segment of its own for a named one.
That forks both registration paths at once — a sempods pod dedups DCR on (client name,
`User-Agent`, redirect URIs), and a `did:web` identifier covers a path prefix. The profile goes into
the client name too, or the consent screen lists two entries that look alike.

The segment sits *below* the callback rather than at the service root because the session cookie is
scoped to `/_system/ui`: outside it the callback arrives with no session and the connect ends at the
sign-in screen.

It has to fork, because the permissions are the pod's:

> Allow *finance* and *notes* in `…/private`, allow *notes* in `…/cron-agent`. The pod reads what a
> request may see from `(pod, client_id, WebID)`, not from the token — so as one `client_id` the
> cron agent reaches finance.

**An identity belongs to the connection, not to the profile.** The default profile keeps the one it
has, and a connection made before the fork keeps its shared `client_id` and callback on every path,
connect and re-authorize alike. The dashboard marks it *shared client* and offers to separate it —
its own action, because the new client has no grants at the pod and the person is asked again:

> While two profiles share a `client_id`, connecting in `…/cron-agent` retires the refresh-token
> family `…/private` holds, so `…/private` reports "reconnect required". Separated, it costs the
> first nothing.

**Per profile, not per service user.** Two accounts signing in at a pod as the same WebID are one
person to that pod — one grant set, one refresh-token family, whatever this service sends. The
WebID is what separates people there, so the fork stops at the profile.

**Re-authorize** runs the same leg again from the dashboard. A sempods pod always shows a `dyn:`
client its consent screen, with prior context grants pre-checked; those durable grants are edited
there, rather than encoded in the OAuth `scope` request parameter. Elsewhere that is the pod's
call: the service sends no `prompt=consent`, so a pod free to reuse the prior authorization will,
and the button then changes nothing. The stored
`client_id` is reused — except for a dead (`invalid_grant`) `dyn:` connection at a pod offering
DCR, which re-registers; a static `did:web` client has no registration to lose and keeps its
identity. The callback stores the scopes the token response returned, not the ones asked for, and
clears the reconnect mark.

The pod sees an ordinary OAuth client; consent and grants stay pod-side.

## Cross-pod reads vs. writes

Reads fan out (scatter-gather across `targets`); writes must not.

- **Reads** may address multiple pods/contexts. Results and errors stay in per-pod envelopes;
  the service does not merge them into one result set. Context attribution depends on the tool's
  result shape: narrowing a SPARQL query to contexts does not annotate its rows with their source
  contexts. See the [tool contract's provenance rules](../../sempods-mcp/docs/tool-contract.md#provenance).
- **Writes** require **exactly one explicit target pod and one explicit
  `context_iri`**. No default-all-targets, no implicit context, no
  fan-out write. A write whose target is ambiguous is rejected, not
  broadened — the same "&gt;1 pod without `targets` → error" stance the chat
  app already takes, tightened to "writes are always singular and explicit".

## Security — pod URLs and SSRF

User-supplied pod URLs are untrusted. Admission checks and connect-time address vetting
apply to the pod HTTP client; it follows no redirects. The issuer verifier uses a separate
client with narrowly configured issuer exemptions. [Runtime documentation](../../sempods-mcp/docs/runtime.md#outbound-requests)
owns the two-layer defense, rate limits and the deploy-time local-pod policy.

## Naming — optional profile paths

The service uses its URL namespace for profiles. `ProfilePath` rejects reserved names;
profiles are created in the UI or on first authorization. The MCP endpoint requires the
token's profile claim to match the request path.

The profile addresses are:

- **Default `mcp.sempods.org`** — the **default profile's** connected pods.
  There is always exactly one (implicit) default profile; the root path is
  not a privileged "sees everything" surface, just the profile a user gets
  before naming any others.
- **Optional `mcp.sempods.org/<profile>`** — a **named profile**: its own
  OAuth identity, its own token set, its own (narrower) connection bundle.
  `…/private` can structurally reach only "Mein Pod", `…/playground` only
  the sandbox, `…/cron-agent` only one pod with narrowly granted context permissions.

The driving fact: MCP OAuth keys auth on the **resource URL**, so two
independent identities / token sets require two URLs. The per-pod MCP had
a variable path segment for the same reason and gave it up — one surface
per pod, one consent per client (see
[`../mcp/endpoint.md`](../mcp/endpoint.md#url)). Here the URL carries a profile, which
is a thing a user creates and names, not a free segment.

### Two OAuth layers — do not conflate them

The profile path lives on the service's URL, and both OAuth layers are
separated by it — the first by the URL itself, the second because the
service sends the profile's own callback:

1. **AI client → service.** Different profile paths are different MCP
   resource URLs, so they flow into the DCR fingerprint as its realm (see
   [`authentication.md#dcr-fingerprint`](../mcp/authentication.md#dcr-fingerprint)
   for the shared digest), forcing distinct OAuth clients on the connector
   side.
2. **Service → pod.** The **tokens** are isolated by the key: registry and
   vault rows are `(user, profile, pod)`. The **pod-side client identity** is
   isolated by the redirect URI, the fingerprint input this service can give
   meaning to. Grants are keyed `(pod, client_id, WebID)`, so the profile and
   the WebID separate different things there: which client, and which person.
   See [connecting a pod](#connecting-a-pod-oauth).

### Identity and keying

The canonical key throughout — connection registry, token vault — is
**`(user, profile, pod)`**, with the implicit default profile filling the
slot before any named profiles exist. Keeping the profile in the key from
day one is what makes profiles a real isolation boundary rather than a
relabelling of a shared token pool. The pod-side client identity is not in
that key but follows the profile all the same, through the callback the
profile registers under (above).

`user` is the root of that key and is a **stable identity from an explicit provider**, never a
per-session placeholder: the service is an OIDC relying party to its configured issuer
(`SEMPODS_AUTH_ISSUERS`, `id.sempods.org` by default, a local `sempods-auth` on a self-hosted
deployment), so `user` is a WebID. Everything downstream keys off it.

Keep two separation axes distinct:

| Axis | Example | Solved by | Needs a path? |
|---|---|---|---|
| **Pod** separation | "Mein Pod" vs. "AI-Playground" | `targets` + connection registry, *inside* one service | No |
| **Profile / identity / permissions** separation | private vs. sandbox vs. cron-agent | own OAuth client + connection bundle | Yes |

A path **per pod** would re-fragment the very thing the service unifies
(back to N URLs) — an anti-pattern. Profiles bind an OAuth client identity to a connection bundle
and give **isolation by construction** (a profile cannot address a pod outside its bundle),
which runtime `targets` alone does not.
This is the cross-pod analogue of the per-pod `users/<slug>/...` and
`<instance>` disambiguator, but anchored in the service account rather than
in a pod.

## Direction: one semantics, three surfaces

The hosted and pod-immanent MCP surfaces share `ToolCatalog` and `PodToolExecutor` from
`:sempods-mcp-core`. Execution uses one pod's public HTTP routes through `PodWireClient`.
The hosted service adds profile-scoped fan-out, tokens, quotas and audit; the pod endpoint
keeps route, discovery, authentication and delegation. `MULTI_POD` adds target selection
and `list_pods`; `SINGLE_POD` uses the same declaration without them.

These surfaces serve different access paths. Direct pod MCP requires no credential-custody
intermediary. Hosted MCP provides one connection and one catalog over multiple pods, avoiding
several identically named tool sets, and can adapt a pod that supplies the required HTTP/Auth
operations without MCP. An application can also own its tool loop and call pods directly.
The behavior of an external application's implementation must be checked in that repository.

## Toolset divergence

The shared JVM declaration owns tool names, schemas, required arguments and descriptions;
its executor owns argument normalization and results. Versioned external interoperability
and conformance tests are [proposed separately](../../sempods-mcp/docs/proposals/interoperability.md).

## Relationship to the per-pod MCP

The [per-pod MCP](../mcp/) keeps direct access to the pod while using the same executor.
Choosing between it and the hosted service changes credential custody and operations.
It does not select a second JVM implementation of the tools.

## Current boundaries

- The token vault uses one configured encryption key; [key rotation design](https://github.com/sempods/sempods-kotlin/issues/141)
  is separate work.
- [Forgotten client registrations](https://github.com/sempods/sempods-kotlin/issues/142)
  need a recovery decision; blindly re-registering can orphan grants at pods without deduplication.
- The AI client owns the model loop. This service executes tools and holds no LLM keys.
- SPARQL downscope is enforced at the pod. Free-form SPARQL results have per-pod provenance;
  per-context attribution is part of the interoperability proposal.
- `find` adapters belong to the pod. A richer adapter requires no new retrieval engine in this bridge.

## Related

- [`../../sempods-mcp`](../../sempods-mcp) — the module that
  implements this architecture; [runtime details](../../sempods-mcp/docs/runtime.md).
- [`../mcp/README.md`](../mcp/README.md#design-principles) — the per-pod MCP's design
  principles; the service stays a client and adds no server-side cross-pod
  primitive, but see [Relationship to the per-pod MCP](#relationship-to-the-per-pod-mcp).
- [`../mcp/authentication.md`](../mcp/authentication.md#dcr-fingerprint) — the DCR
  fingerprint both surfaces share; profile paths fill its realm slot.
- `sempods-apps/apps/chat` — an external consumer; verify its implementation in that repository.
