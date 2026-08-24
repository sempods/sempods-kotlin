# Hosted MCP — a standalone MCP service for pods (vision)

**hosted-mcp** is an **additional, standalone service** (e.g. `mcp.sempods.org`) that
provides the **MCP / LLM-tooling layer** over sempods pods. One service fronts **many
pods** — including pods run by others that implement the sempods HTTP/Auth profile,
addressed by pod base URL — so one AI client reaches all of a user's pods over one
connection.

Implemented in the `sempods-mcp` module and **live on `mcp.sempods.org`**: service
login / identity, pod-connect, the read + write tool surface, named profiles with hard
isolation, and the full hosting hardening (secrets-at-rest, the two-layer SSRF defense,
durable multi-instance state, and multi-tenancy + audit + per-user quotas) are all
shipped. The authoritative as-built record is
[`../../sempods-mcp/AGENTS.md`](../../sempods-mcp/AGENTS.md) (phase status) and
[`../../sempods-mcp/docs/tool-contract.md`](../../sempods-mcp/docs/tool-contract.md)
(the tool contract).

The remaining forward-looking work — the [conformance
profile](#what-counts-as-a-pod--conformance-profile), a versioned tool-contract spec, and
cross-implementation conformance tests (see [toolset divergence](#toolset-divergence)) — is
described below as **concept, not schedule, and is not yet actionable**. It is gated on two
things that do not exist yet: **third-party pods that implement the profile at all** (the
code is not public yet, so there is nothing external to front), and **the three tool
implementations stabilising** — freezing a versioned contract against still-moving impls
would be premature. Picked up when those preconditions hold, not before.

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

Even as the primary MCP layer, the service is structurally a **client** to each pod: its
own DCR registration and bearer, keyed `(user, profile, pod)` (see
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
        │  (2) per pod: OAuth client (DCR), bearer per (user, profile, pod)
        ▼
  Pod A (HTTP System layer)  …  Pod B  …  Pod C    ← each enforces its own grants
```

Pod access goes through each pod's **public HTTP System layer** (`_system/resources/...`,
SPARQL) — the pod's primitive API, the same surface the chat app uses. The service
depends only on that, not on any embedded per-pod MCP.

The reference design already exists, client-side: the **chat app**
(`sempods-apps/apps/chat`) implements the full multi-pod tool layer — a toolset
(`list_contexts`, a whole-resource read, `sparql_select` / `sparql_graph`, `find`, the
CRUD + property tools), `targets`-based pod selection, AST SPARQL rewriting, and per-`(pod,
context)` result envelopes. The service lifts that layer server-side; the semantics, not
the TS code, are the reference.

## What counts as a pod — conformance profile

A target is usable only if the service can deterministically discover and exercise it.
"Pod base URL" therefore implies a **named conformance profile** the target must satisfy:

- **Endpoints** — the System-layer routes (`_system/resources/...`,
  `find`) and the SPARQL query/construct endpoints, at a discoverable base.
- **OAuth metadata** — RFC 9728 protected-resource metadata and RFC 8414
  AS metadata at the well-known locations, so the service can register
  (DCR) and obtain bearers without per-pod hand-configuration.
- **Contexts** — `list_contexts` semantics: the authoritative,
  permission-annotated set the bearer covers.
- **SPARQL guardrails** — the same read-only / no-`SERVICE` / timeout
  contract, so a rewritten cross-pod query behaves identically everywhere.
- **Capability discovery** — a way to learn which operations a target
  supports, so the service degrades gracefully against partial
  implementations instead of failing opaquely.

The bullets above are the conceptual requirements; they stay abstract on
purpose at this stage. Before implementation, discovery should resolve to a
**concrete, versioned mechanism** — e.g. a `_system/capabilities` (or
profile) endpoint, or a fixed discovery document — that advertises the
profile version and supported operations, rather than the service probing
each route. Without this profile, "front any pod" is not implementable.
Defining it (versioned, testable) is a prerequisite, tracked under
[Toolset divergence](#toolset-divergence) and conformance tests.

## What it buys — and what it costs

What the hosted service buys over a purely client-side (in-browser / in-app) tool layer:

- **One MCP connection instead of N.** Pod selection moves into tool
  arguments (`targets`), not into N separately-configured servers.
- **Server-side token refresh** → headless / cron / agentic use without an
  open browser. This is the capability a purely client-side tool layer
  cannot have.
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
multi-tenant** instance the custody is the main liability (hardening in M6). It does not
change that the service stays a **client** — it adds no authority a pod depends on.

Two-layer consent follows: (1) the user authenticates **once** to the service (MCP OAuth —
they trust it); (2) the user authorizes the service **per pod** through its own connect
flow.

## Connecting a pod (OAuth)

The service runs a plain **"connect pod" web flow** — standard server-side OAuth, not the
per-pod MCP's interactive `authorize` tool (which is built for an AI client driving OAuth
from inside its own JSON-RPC stream; see
[`authentication.md`](authentication.md#the-authorize-tool)):

1. User enters a pod base URL in the service UI; the service resolves the
   pod's OAuth metadata and **registers via DCR** at that pod.
2. Standard **Authorization Code + PKCE**: redirect the user's browser to
   the pod's `authorize`, with the `state` parameter **bound to the full
   expected flow context** — `(user, profile, pod_base_url, issuer,
   client_id)` or equivalent — not just `(user, profile)`. Binding the
   expected pod / authorization server / DCR registration is what closes
   the mix-up risk when several connect flows run in parallel. The redirect
   URI points back to the service.
3. On callback the service validates `state`, exchanges the code, and
   stores the resulting tokens in the vault under `(user, profile, pod)`.

The pod sees an ordinary OAuth client; consent and grants stay pod-side.
This needs spelling out concretely — `state`/nonce binding, redirect-URI
registration per profile, and re-consent / scope-upgrade handling — before
implementation.

## Cross-pod reads vs. writes

Reads fan out (scatter-gather across `targets`); writes must not.

- **Reads** may address multiple pods/contexts; results stay
  **provenance-stable**: per pod, per context, with partial errors
  surfaced individually. **No global result fusion without provenance** —
  the caller always sees which pod and context each row came from (the
  chat app's per-`(pod, context)` envelope is the model).
- **Writes** require **exactly one explicit target pod and one explicit
  `context_iri`**. No default-all-targets, no implicit context, no
  fan-out write. A write whose target is ambiguous is rejected, not
  broadened — the same "&gt;1 pod without `targets` → error" stance the chat
  app already takes, tightened to "writes are always singular and explicit".

## Security — pod URLs and SSRF

> **As-built (M6.2, done).** Implemented as a two-layer defense: URL-string checks at admission
> (`PodUrlPolicy.reject`) plus connect-time DNS vetting on the single outbound client
> (`:sempods-client`'s `SempodsOutboundGuard`: OkHttp engine, proxy pinned off, `VettingDns`, plus a
> per-request check for IP-literal hosts no resolver hook can see) — every resolved A/AAAA is
> checked against the blocked-range set and the engine connects to exactly the vetted addresses,
> so resolve and connect are one event (rebinding/TOCTOU closed); a mixed public/private
> resolution rejects the whole lookup. One deliberate strengthening: **redirects are not followed
> at all** (instead of per-hop re-validation). Per-pod rate limits (`POD_RATE_LIMIT_PER_MINUTE`,
> keyed host + first path segment) and timeouts bound every fetch. The pod client carries no
> trust exemptions; only the identity verifier's issuer-JWKS fetch runs on a separate hardened
> client with the configured issuer hosts exempt. The strict/relaxed split is the deploy-time
> `ALLOW_LOCAL_PODS`. See
> [`../../sempods-mcp/AGENTS.md`](../../sempods-mcp/AGENTS.md) (phase status, M6.2).

Because users supply arbitrary pod base URLs, a **hosted** instance
(`mcp.sempods.org`) treats every target URL as untrusted input and the
fetch path as an SSRF surface:

- **HTTPS by default**; canonicalize the URL before use.
- **Block private / reserved IP ranges** (RFC 1918, loopback, link-local,
  ULA, metadata IPs) and defend against **DNS rebinding** (re-resolve and
  re-check at connect time, pin or re-validate on each request).
- **Constrained redirect policy** (no redirects into blocked ranges),
  explicit **allow/deny** rules, per-pod **timeouts** and **rate limits**.

These constraints may be relaxed for **self-hosted / local** instances
(where reaching a private pod is the point) but **not** for the public
hosted instance. The split is a deploy-time policy, not a per-request one.

## Naming — optional profile paths

> **As-built (M5, done).** This is implemented exactly in the suffix-free form below: the default
> profile is the service root and a named profile is `mcp.sempods.org/<profile>` (no `/mcp`
> segment — the pre-M5 `/mcp` endpoint was removed, no aliases). Named profiles are materialised
> either in the `/_system/ui` profile switcher or automatically on first authorization against
> `…/<profile>` (so pointing an AI client at a fresh profile URL just works); the MCP endpoint
> enforces hard isolation (a token's `profile` claim must match the path). See
> [`../../sempods-mcp/AGENTS.md`](../../sempods-mcp/AGENTS.md) (phase status, M5).

The service carves **its own** URL namespace into profiles:

- **Default `mcp.sempods.org`** — the **default profile's** connected pods.
  There is always exactly one (implicit) default profile; the root path is
  not a privileged "sees everything" surface, just the profile a user gets
  before naming any others.
- **Optional `mcp.sempods.org/<profile>`** — a **named profile**: its own
  OAuth identity, its own token set, its own (narrower) connection bundle.
  `…/private` can structurally reach only "Mein Pod", `…/playground` only
  the sandbox, `…/cron-agent` only one pod with a narrow scope.

The driving fact: MCP OAuth keys auth on the **resource URL**, so two
independent identities / token sets require two URLs. The per-pod MCP had
a variable path segment for the same reason and gave it up — one surface
per pod, one consent per client (see
[`endpoint.md`](endpoint.md#url)). Here the URL carries a profile, which
is a thing a user creates and names, not a free segment.

### Two OAuth layers — do not conflate them

The profile path lives on the service's URL, so it directly separates
**only the first OAuth layer**:

1. **AI client → service.** Different profile paths are different MCP
   resource URLs, so they flow into the DCR fingerprint as its realm (see
   [`authentication.md#dcr-fingerprint`](authentication.md#dcr-fingerprint)
   for the shared digest), forcing distinct OAuth clients on the connector
   side.
2. **Service → pod.** This separation is **not** automatic from the
   path. The service must enforce it explicitly: a separate DCR
   registration, token, and grant set per `(user, profile, pod)` (or a
   deliberately justified weaker isolation). Without that, `…/private` and
   `…/cron-agent` would share pod tokens — UI isolation only, not hard
   token / grant isolation.

### Identity and keying

The canonical key throughout — connection registry, token vault, pod-side
DCR client — is **`(user, profile, pod)`**, with the implicit default
profile filling the slot before any named profiles exist. Keeping the
profile in the key from day one is what makes profiles a real isolation
boundary later rather than a relabelling of a shared token pool.

`user` itself is the root of that key and must be a **stable identity from an explicit
provider**, not a per-session placeholder — recommended default a **sempods WebID via
`id.sempods.org`** (the `sempods-auth` module), with the service as a pluggable OIDC / JWT
relying party. Fixing this is M1's job (see the roadmap); everything downstream keys off it.

Keep two separation axes distinct:

| Axis | Example | Solved by | Needs a path? |
|---|---|---|---|
| **Pod** separation | "Mein Pod" vs. "AI-Playground" | `targets` + connection registry, *inside* one service | No |
| **Profile / identity / scope** separation | private vs. sandbox vs. cron-agent | own OAuth client + connection bundle | Yes |

A path **per pod** would re-fragment the very thing the service unifies
(back to N URLs) — an anti-pattern. Profiles are coarse, identity- and
scope-bound, and give **isolation by construction** (a profile cannot
address a pod outside its bundle), which runtime `targets` alone does not.
This is the cross-pod analogue of the per-pod `users/<slug>/...` and
`<instance>` disambiguator, but anchored in the service account rather than
in a pod.

Default-profile-only (one path, one connection bundle) is enough for a
first cut; additional named profiles are an additive later step, but the
URL form should admit segments from the start so adding them is not a
breaking change.

## Direction: one semantics, three surfaces

The MCP tool surface is reachable in three places — the pod-immanent `McpEndpoint`, the
chat app's client-side TS layer, and this hosted service. All three stay; **none of them is
being pre-selected as a winner and none is being retired**. What changed is that "coequal"
no longer means "each with its own implementation of the tools".

The pod-immanent surface and this service now run the **same** `PodToolExecutor` from
`:sempods-mcp-core` against the same `_system/…` routes; the pod endpoint keeps route,
authentication, discovery, `authorize` and delegation, and nothing else. The property it
uniquely carries — direct, no-third-party access — is untouched: it is still the pod
answering, still without an intermediary. What it stopped carrying is a second copy of the
semantics, which only one of the two had production traffic from outside exercising.

The chat app's TS layer is still outside that, and is the remaining place where drift is
possible rather than impossible; see [below](#toolset-divergence).

Each of the three still serves a use-case that is needed now:

- **Client-side / in-stack (the chat app).** Lowest cost, most direct: the client does
  OAuth straight against the pod and holds its own token — no third party in the path,
  nothing to operate, no server-side custody. The natural fit when the tool layer already
  lives inside an app's own stack.
- **Hosted service (`mcp.sempods.org`).** One MCP connection fronting many pods. Two things
  it uniquely buys beyond server-side token refresh (headless / cron use, above):
  - **One multi-pod MCP beats N per-pod MCPs.** Pointing an AI client at a dozen separate
    sempods MCP servers invites **tool confusion** — the same `sparql_select` / `find`
    repeated per server, the model picking the wrong one. One server with `targets`
    selection collapses that to a single, unambiguous tool set.
  - **A bridge / adapter for pods with no MCP layer of their own.** The service adapts any
    conformant pod's plain HTTP System layer into MCP, so **a pod need not implement MCP at
    all** — MCP stays out of the pod baseline, which makes the sempods spec **simpler to
    implement** (implement the HTTP/Auth profile; the MCP surface comes for free from the
    front, and need not be baseline functionality).
- **Pod-immanent (integrated, decentralised).** MCP built into the pod itself: **direct,
  no-third-party access** and full decentralisation — no intermediary, no central
  chokepoint. The one property external-first gives up, kept alive here.

The problems that showed up did inform the shape rather than force a collapse: triple
maintenance is now double (the two server-side surfaces share their semantics, the chat app
does not), and custody on the hosted instance is unchanged and still the price named above.

## Toolset divergence

Between the two MCP surfaces this is settled rather than managed: the per-pod MCP and this
service build `tools/list` from **one** `ToolCatalog` in `:sempods-mcp-core`, and what
differs between them is a variant — `MULTI_POD` adds `targets` / `target` and `list_pods`,
`SINGLE_POD` does not. Tool names, argument schemas, `required` lists and the descriptions
themselves are a single declaration, so they cannot drift apart by being edited in one place
and not the other. The JSON-RPC envelope, the protocol-version list and the
`WWW-Authenticate` challenge moved with them.

The chat app's client-side tool layer is still outside that: the gap is visible as
`get_resource` (MCP) vs. `retrieve` (chat app). For it the measure remains to **pin the
contract** — a **versioned tool-contract spec** plus **cross-implementation conformance
tests** — the same "pin it down" discipline as the
[conformance profile](#what-counts-as-a-pod--conformance-profile) a target pod must satisfy,
and the M7 work above. What the shared module changes is the starting point: the spec would
now be written from one existing declaration rather than reconciled from two.

Collapsing the *count of surfaces* — retiring the per-pod MCP, the chat app becoming a
hosted-mcp client — stays on the table as a *possible later* simplification and is
**explicitly not the current direction**: each surface earns its keep first. Collapsing the
count of *implementations* is a different move and is done for the two server-side ones.

## Relationship to the per-pod MCP

The per-pod MCP (`McpEndpoint`, documented across this folder) is shipped, validated
cross-client, and canonical. It is **not** treated as redundant: as
[the direction above](#direction-one-semantics-three-surfaces) sets out, it is one of three
surfaces, and the only one carrying the **direct, no-third-party access** property
the hosted service gives up. Since the consolidation it runs the same executor this service
does, so "which surface" is a question about access and operations, not about behaviour. A pod that exposes the HTTP/Auth profile *can* be reached
through hosted-mcp without an embedded MCP — but "can" is a bridge for pods that lack one,
not a reason to remove it where it exists.

Whether to **retire** the per-pod MCP later is a **separate, larger decision** — one that
would touch the rest of this folder (`README.md`, `tools.md`, `authentication.md`) and
`McpEndpoint` itself, and weigh the lost direct-access property. It is
**not the current lean**: all three coexist and are exercised first.

## Open questions

- **Token vault.** *Addressed in M6:* AES-256-GCM encryption-at-rest
  (`SecretCipher`), strict per-user tenancy (isolation review), and an audit
  trail all shipped. Key management is still envelope-style (`MCP_SECRET_KEY`),
  not KMS, and per-vault-key rotation (`v1:<kid>:…`) is deferred — the residual
  open edge on what remains a high-value target.
- **Centralization optics.** A sempods-operated `mcp.sempods.org` is a
  chokepoint in a decentralized system. The service must stay self-hostable
  by third parties so it is "one optional instance", not the gateway. (The
  self-host knobs are in place — both hardening budgets default off on a
  relaxed deployment.)
- **LLM loop.** This concept is an MCP **server** that fans tool calls out;
  the AI client runs the model loop, so no LLM keys live in the service. A
  hosted agent loop (à la the chat app's `adapter.ts`) is a separate, later
  question.

## Parked / later

Forward-looking capability work, not currently scheduled (no use case has
pulled it in yet):

- **SPARQL per-context provenance.** Context *scoping* for SPARQL is **done pod-side**: the pod's
  `/_system/sparql/query` honors the SPARQL-1.1-protocol `default-graph-uri` / `named-graph-uri`
  params, and the service forwards `context_iri` to them — the same `{requested} ∩ readable`
  downscope as `find` / `get_resource`, no AST rewriter. What stays parked is *provenance
  attribution* — telling which context each result row came from — which needs a `GRAPH ?g`-binding
  rewrite (the chat app has a TS one); parked until a use case needs it.

**Not a bridge concern — belongs to the pod:** `find` is an **abstract**
primitive, and *how* a pod satisfies it (lexical, vector, hybrid — best
combined) is the pod's own retrieval strategy. The service just calls `find`
and repackages the result; it adds no retrieval capability of its own, so
**vector / hybrid search is per-pod substrate work, not a bridge backlog
item** — the service inherits the improvement for free when a pod's `find`
gets better.

## Related

- [`../../sempods-mcp`](../../sempods-mcp) — the module that
  implements this concept; the as-built phase status is in its
  [`AGENTS.md`](../../sempods-mcp/AGENTS.md).
- [`vision.md`](vision.md#cross-pod-orchestration-client-side) — the
  client-side cross-pod pattern this service hosts.
- [`README.md`](README.md#design-principles) — the per-pod MCP's design
  principles; the service stays a client and adds no server-side cross-pod
  primitive, but see [Relationship to the per-pod MCP](#relationship-to-the-per-pod-mcp).
- [`authentication.md`](authentication.md#dcr-fingerprint) — the DCR
  fingerprint both surfaces share; profile paths fill its realm slot.
- `sempods-apps/apps/chat` — the client-side reference implementation of
  the multi-pod tool layer.
