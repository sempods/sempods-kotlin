# sempods-mcp — docs

A standalone **hosted MCP service**: one MCP server that fronts many pods over a single
connection. MCP as an LLM-tooling layer *over* the pod's primitives (external-first),
structurally a client to pods.

## Start here

- **Why / trade-offs / design** — the concept doc lives with the MCP surface docs:
  [`../../docs/concepts/hosted-mcp.md`](../../docs/concepts/hosted-mcp.md).
  It covers what it buys (one connection, server-side token refresh), what it costs
  (token custody), the two OAuth layers, profile paths, the conformance profile, SSRF
  policy, and cross-pod read/write rules.
- **As-built status, in order** — the **Phase status** section of
  [`../AGENTS.md`](../AGENTS.md) (M1–M6 done; the `mcp.sempods.org` milestone is closed).
  Forward-looking direction (conformance profile, versioned contract, anti-drift) is
  concept, not schedule — see the concept doc above.
- **Module conventions** — [`../AGENTS.md`](../AGENTS.md).

## Status

**Pod-token refresh runs on the refresh-token clock, not the access-token one.** The background
sweep (`TokenRefreshScheduler`) has two tiers. **Warm** — a connection used within
`POD_TOKEN_WARM_IDLE_SECONDS` (default 3600) has its access token renewed ahead of expiry, so an
active agent never pays the four sequential pod requests a cold rotation costs. **Preservation** —
every refreshable connection is rotated once per `POD_TOKEN_FAMILY_PRESERVE_SECONDS` (default 30 d)
regardless of access-token expiry, which is the only cadence the pod's ninety-day refresh-token
family and the service's DCR registration there actually ask for; the pod advertises no TTL for
either, so the default is a conservative guess. "Used" is a throttled `lastUsedAt` on the vault row,
written in `PodTokenProvider.validAccessToken` — the one place a pod read or write gets its token,
so `list_pods`, `authorize` and the dashboard never count. Both selections are index-backed and bounded per tick — order and bound from the access path, not from a sort, on indexes partial to the rows that have something to rotate with. A test pins it by explaining the queries the sweep actually issues and asserting each reads no more than it returns. Each tier gets half a tick, preservation's budget starting when preservation does, and the preservation queue is round-robin — a row is marked before it is attempted and that mark orders the selection — so neither a slow warm pass nor a pod that fails slowly can hold the budget against the rows behind it. The
load thereby scales with use rather than with the number of connections, and it is the pods, not
this service, that were paying for the difference.

**M6.4 (multi-tenancy + audit) — done (build green).** The last M6 hardening gate: a persistent
**audit trail** (`auditLog`, `AuditLogDao` + the `audit/AuditLog` emitter) records pod
connect/disconnect, pod token refresh/rotation (success + refusal reasons), service-token
rotation + reuse-triggered family revocation, and **one row per pod-tool `tools/call`** (tool,
target pods, `ok|partial|error`; the `authorize` helper and protocol-level rejections like
unknown-tool are deliberately not audited — see the review doc) — no token material, no
arguments, fixed `detail` labels only;
retention-bounded by a TTL index (`AUDIT_RETENTION_DAYS`, default 90). Audit writes are
synchronous-but-swallowing: a failed write never fails the request. A **per-user quota**
(`USER_RATE_LIMIT_PER_MINUTE`, default 120 strict / off relaxed, like `POD_RATE_LIMIT_PER_MINUTE`)
throttles `tools/call` per verified `(user, profile)` — over-quota is a JSON-RPC `-32000` error
(handshake methods stay free), audited sampled. The **multi-tenant isolation review** is
[`multi-tenancy-review.md`](multi-tenancy-review.md): every collection's tenant keying verified,
the unbounded `PodTokenProvider` lock map fixed (swept past 4096 entries — safe because the M6.3
vault claim is the correctness primitive), and the per-replica rate-limit budget settled as
deliberate. **This closes the `mcp.sempods.org` milestone (M1–M6 done);** the forward-looking
conformance / anti-drift work is concept, not schedule (see the concept doc).

**M6.3 (durable OAuth state + leader-elected refresh) — done (build green).** The service is now
multi-instance-safe: the six one-time OAuth flow stores (auth codes, login states, consent
transactions, reauthorize challenges, web-login states, pod-connect states) are Mongo-backed —
TTL-indexed, consumed via atomic `findOneAndDelete`, secret keys stored as SHA-256 `_id`s, the
pod-connect PKCE verifier encrypted at rest — so in-flight logins survive a restart and span
replicas. Double-refresh of pod tokens is closed by a per-token claim on the vault row
(`TokenVaultDao.tryClaimRefresh`, dueness re-checked under the claim) plus a singleton sweep
lease (`LeaseDao`, `leases`) so only one replica runs the refresh sweep; a connection the pod has
already declared dead (`PodConnection.deadGrantSince`) is short-circuited out **ahead of** that
claim, so it is not re-refreshed on every tick until someone reconnects it. M6.1 encryption-at-rest assumes a fresh setup (no legacy-plaintext tolerance or startup migration).

**M6.2 (SSRF resolve-and-pin) — done (build green).** The outbound fetch path is hardened for
untrusted pod URLs: the single transport (`:sempods-client`'s `SempodsOutboundGuard`, OkHttp
engine, no proxy) vets every
DNS-resolved address against the blocked-range set at connect time (`VettingDns` +
`PodUrlPolicy.rejectAddress` — pin, DNS rebinding closed), follows **no redirects**, and applies
a per-pod rate limit (`POD_RATE_LIMIT_PER_MINUTE`, keyed host + first path segment); the identity
verifier's issuer-JWKS fetch runs on its own client with only the issuer hosts exempt. Relaxation
for self-host stays the deploy-time `ALLOW_LOCAL_PODS`.

**M4 (write tools) — done (build green).** The MCP front door also serves the seven write /
property-mutation tools (`create_resource`, `update_resource`, `delete_resource`,
`add_property_value`, `set_property_values`, `remove_property_value`, `clear_property_values`) via
`WriteTools`. These **never fan out**: each takes a required single `target` pod + single
`context_iri` and returns a single-pod envelope. The pod stays the authority on where a write may
land — it resolves `?context=` against its own registry and enforces the `<context_iri>#write`
scope — as it does on ETag preconditions (`if_match` / `if_none_match`). Reads gained
**partial-error surfacing** (per-pod error `kind` + a `partial` / `failed_pods` envelope flag).
**Next: M5 (named profiles & hard isolation).**

**M3 (read tools) — done (build green).** On top of live M1 + M2 (`https://mcp.sempods.org`: own
MCP-OAuth resource server / AS + OIDC relying party to id.sempods.org, and the `/_system/ui`
pod-connect web-UI filling the `(user, profile, pod)` token vault + connection registry), the MCP
front door now serves the **read surface across connected pods**: `list_pods`, `list_contexts`,
`get_resource`, `sparql_select`, `sparql_graph`, `find`, `get_property_values`. Each tool is
advertised only to an authenticated session and proxies to every connected pod's HTTP **System
layer** (`PodToolExecutor` in `:sempods-mcp-core` over `:sempods-client`'s `PodWireClient`,
SSRF-guarded, pod-scoped bearer; shared with the pod-immanent MCP since consolidation M3),
fanning out (optional `targets`) into a
**per-pod envelope** — one pod's failure never poisons the others. A shared `PodTokenProvider`
(refactored out of the refresh scheduler) yields a fresh, issuer-pinned pod token on demand. The
tool surface is contract-first: one source `ToolCatalog` in `:sempods-mcp-core`, shared with the
pod-immanent MCP, + [`tool-contract.md`](tool-contract.md).
Pod-URL guard at admission; since M6.2 the fetch path additionally pins DNS-vetted addresses.
See [`../AGENTS.md`](../AGENTS.md) (phase status).

## Reference design

The chat app (`sempods-apps/apps/chat`) already implements the full multi-pod tool layer
client-side (toolset, `targets` selection, AST SPARQL rewriting, per-`(pod, context)`
envelopes). This module lifts that layer server-side. The semantics — not the TS code —
are the reference; see the concept doc's
[`toolset divergence`](../../docs/concepts/hosted-mcp.md#toolset-divergence) on keeping the
implementations (per-pod MCP, chat app, hosted-mcp) from drifting — or collapsing them by
retiring the per-pod MCP.
