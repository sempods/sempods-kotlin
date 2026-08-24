# AGENTS.md — sempods-mcp

Scope: applies to `sempods-mcp/**`.

## What this module is

A standalone Kotlin/Ktor service: **one hosted MCP service that fronts many pods** over a
single connection, so one AI client reaches all of a user's pods (including pods run by
others that implement the sempods HTTP/Auth profile) without configuring N servers.

It treats **MCP as an LLM-tooling layer over the pod's primitives** (RDF, contexts,
SPARQL, the HTTP System layer), not a per-pod feature. The pod-immanent MCP
(`/{pod}/_system/mcp` in the `sempods-server` module) and the chat app's client-side
tool layer are the other two surfaces; all three stay and each is exercised (retiring any is
a possible-later, not-current decision — see the concept doc's *Direction: one semantics,
three surfaces*). What is no longer three is the *implementation*: this service and the
pod-immanent MCP run the same `PodToolExecutor` from `:sempods-mcp-core` against the same
routes, the pod server dialling its own public base URL. Toward each pod this service is
structurally an ordinary OAuth client; it adds no cross-pod primitive to any pod.

Planned port **8092**, deployed as a separate container (`ghcr.io/haed/sempods-mcp`).

## Architecture constraints

- **Independent of the per-pod MCP.** No imports from
  `org.sempods.api.pod.system.mcp.*` and no coupling to the pod services. Pod access
  goes through each pod's **public HTTP System layer** (`_system/resources/...`, SPARQL),
  the same surface the chat app uses — and, since the consolidation, the same surface the
  pod-immanent MCP uses on its own pod. What the two share is `:sempods-mcp-core`, which
  neither of them owns.
- **No application-framework dependency** — standalone service, like `sempods-auth`.
- **Ktor** for the HTTP/MCP surface (lambda routing) — inbound only. **Outbound is
  `:sempods-client`**: the pod System layer and the pod OAuth surface both go through
  `SempodsHttpTransport`, which carries the SSRF hardening this service used to own. That is a
  deliberate widening of the dependency rule below: the module used to depend on nothing but
  `:commons` and `:commons-mongo`, and it now takes `:sempods-client` too (RDF4J rides along on the
  runtime classpath unused). The price of one pod client instead of two, and cheaper than the drift
  two of them produced — see `docs/pod-client.md` §"What the client is not".
- **Blocking client, `suspend` service.** `pods/PodIo` is the bridge: a virtual-thread executor, a
  cancel handle, and the caller's trace carried across the hop. Two traps it exists to avoid —
  `Thread.interrupt()` does not unblock an OkHttp read, and `Job.invokeOnCompletion` fires only
  after the blocking body returns.
- **Guice** for DI (services only).
- **Stays a client.** It never becomes an authority a pod depends on. Token custody is
  the real cost — see the concept doc.
- **Canonical key** for registry / token vault / pod DCR client is `(user, profile, pod)`,
  with an implicit default profile from day one.

## Deployment stance (PoC — no migrations)

The deployment is a **PoC used only by the maintainer**: a breaking schema / crypto change
assumes a **fresh setup** (drop the DB, re-connect pods and AI clients) instead of carrying
migration logic. The code deliberately holds **no legacy-tolerant reads and no startup
migration passes** — it always reflects the current state (this is *why* e.g. M6.1
encryption-at-rest expects ciphertext with no plaintext fallback). Once the service carries
**real user state**, migrations become a hard requirement — a requirement *then*, not now.

## Documentation

- `docs/README.md` — module overview + entry points.
- Concept / rationale, and the forward-looking direction (one semantics across three tool
  surfaces, conformance profile, versioned tool-contract, anti-drift):
  `docs/mcp/hosted-mcp.md`.
- As-built phase status: the **Phase status** section below (the single source of truth).

## Phase status

- **M0 (scaffold)** — done. Module + build + deployable shell + roadmap.
- **M1 (service login & identity)** — done. The service now serves: it is its own
  MCP-OAuth resource server / authorization server (RFC 9728 + 8414 discovery, DCR with
  fingerprint dedup, `/authorize` + consent + `/token` with PKCE-S256 and refresh-token
  rotation, RS256 token issuer + JWKS), federates user login to id.sempods.org as an OIDC
  relying party (`user` = stable WebID), and ships the MCP JSON-RPC front-door. The hosted
  service has **no anonymous mode** (unlike the per-pod MCP): every id-bearing request —
  `initialize` / `tools/list` / `tools/call` / `resources/list` / `prompts/list` / `ping` —
  requires a valid bearer; a missing or invalid token gets the 401 OAuth-upgrade challenge
  (notifications, which carry no id, are the only anonymous-acked exception). Persistence is the
  plain Mongo sync driver (no Morphia, no framework), keyed `(user, profile, pod)`, in the
  service's own database `sempods-mcp`. The fourteen collection names are declared in
  `SempodsMcpCollections` and pinned by `SempodsMcpCollectionsTest`; the `oauth.*` ones are
  spelled exactly as the pod server and the identity service spell them.
- **M2 (connect a pod)** — done. The service is now also an OAuth **client** toward pods:
  a session-protected web-UI bundled under **`/_system/ui`** (web-session = service-signed
  `web_session` JWT cookie, `typ`-separated from MCP access tokens) lets a signed-in user
  connect pods via RFC 9728/8414 discovery + DCR + Authorization-Code/PKCE + token exchange
  (`pods/PodOAuthClient`), fills the `(user, profile, pod)` token vault + connection registry,
  and a background `TokenRefreshScheduler` keeps the connections alive headlessly (refresh-token
  rotation) on **two tiers, on the clock the thing it protects actually runs on**. What dies from
  disuse is the pod's refresh-token *family* — ninety days, reset in full by any single rotation,
  and the same `/token` call holds the service's DCR registration at that pod open against the same
  boundary; an expired **access** token costs no person and no dialog, because
  `PodTokenProvider.validAccessToken` renews it on demand. So: a **warm** tier renews the access
  token of a connection used within `POD_TOKEN_WARM_IDLE_SECONDS` (default 1 h), which is the only
  thing warm-keeping ever bought — latency on the next call; and a **preservation** tier rotates
  every refreshable connection once per `POD_TOKEN_FAMILY_PRESERVE_SECONDS` (default 30 d) whatever
  its access token says, including the unknown-expiry rows the warm tier can never select. The
  service cannot read the pod's refresh-token TTL (RFC 6749 has no field for it), so that cadence is
  a deliberately conservative guess against a value the pod owns. "Used" is written at one
  chokepoint — `validAccessToken`, whose only callers are the pod-touching tool calls, so
  `list_pods`, `authorize` and the dashboard mark nothing — as a throttled `lastUsedAt` on the vault
  row, which the warm selection is indexed on (both selections are, and a test pins that). The
  sweep's load therefore scales with **use** rather than with inventory, which matters because the
  traffic lands at the pods rather than at the service generating it; each tier is
  time-budgeted per tick (half a tick each), and the preservation budget is anchored where
  preservation *starts* rather than where the sweep did, so a slow warm pass cannot hand it a
  deadline already spent. Within the tier, each row is marked as attempted *before* it is
  attempted and the selection is ordered by that mark (never-attempted first, then
  least-recently-attempted, ties by oldest rotation), so an attempted row goes to the back and the
  backlog is traversed once through before any row is retried. That ordering is load-bearing, not
  tidiness: a failed refresh persists nothing, so ordered by rotation stamp alone a slow pod would
  sit at the head and spend every tick's budget while the families behind it expired quietly. Each
  tier also reads at most a batch per tick, which the ordering makes safe to bound — what it leaves
  behind is the back of the queue, not rows that would be skipped. The preservation queue is two
  index-served queries (unmarked head, marked tail) rather than one sorted selection: a single sort
  on `(lastRefreshAttemptAt, updatedAt)` cannot have both halves bounded and ordered by an index, and
  costs a blocking sort over the whole backlog before the batch bound applies. Whether the head is
  exhausted is decided on the raw document count, and a row whose ciphertext will not decrypt is
  named back to the sweep rather than dropped, so it gets the same mark as any visited row — nothing
  else could move it out of the head, and a batch of them would mask the queue behind them for good.
  Both sweep indexes are **partial** on
  having a refresh token — a row without one can never be swept and nothing ever moves it, so in a
  plain index it would sit in the access path for good, fetched every tick to be discarded, and the
  batch bound would stop bounding reads. A test explains all three sweep queries as the sweep issues
  them — filter, order and bound — and asserts that each reads exactly as many index keys and
  documents as it returns. The plan shape alone did not catch either fault: explaining the filter
  without the sort passed while a blocking sort was there, and `IXSCAN` with a residual filter
  passes while the scan reads everything. Against the pod server's own `/token` budget (`../docs/auth/oauth.md` §"Rate
  limit": 20 a minute per `<address>|<client identity>`) this stays clear by a wide margin, and the
  cadence widens it: only one of a refresh's four requests is the token POST, and the pod's DCR
  dedup is per pod, so every connection this service holds *there* spends one shared `dyn:` key. A
  connection under active warm-keeping spends 0.02 of that 20 — about 1,100 simultaneously-used
  connections at one pod to meet it — while an idle one, touched once per preservation cadence,
  spends 0.00002. What the *warm* tier drops needs no such marker — it is only ever pre-warming, and the
  on-demand path still serves those connections one rotation later.
  `POD_TOKEN_WARM_IDLE_SECONDS=0` leaves preservation alone — the smallest shape of the sweep, where
  every first call after an idle period rotates on demand. Pod-URL guard (`PodUrlPolicy`) — since M6.2 the string checks are layer 1 of the
  two-layer SSRF defense (layer 2 = connect-time DNS vetting, see the M6.2 bullet). Default profile
  only. **RFC 8414 + DCR are preferred but not required:** a pod that serves only RFC 9728 (a
  minimal / `did:web`-static-client pod, e.g. the Staffbase KG pod) is connected by **convention**
  — the AS endpoints are derived from the issuer (`…/authorize`, `…/token`), the service presents a
  **static `did:web:<mcp-host>` client** (resolved by the pod against the service's
  `/.well-known/did.json`) instead of registering, and no JWKS means the pod token's subject is
  trusted via the direct TLS token (`subject_verified: false`). The convention is taken **only on a
  genuine 404** for the AS metadata — a transient failure propagates rather than silently
  downgrading a full pod. The machine MCP/AS endpoints stay at the root; `/_system` is the reserved system
  namespace.
- **M3 (read tools)** — done (build green). The MCP front door now serves the read surface across
  connected pods: `list_pods`, `list_contexts`, `get_resource`, `sparql_select`, `sparql_graph`,
  `find`, `get_property_values`. Tools are advertised only to an authenticated session and proxy to
  each pod's HTTP **System layer** (SSRF-guarded + pod-scoped bearer), fanning
  out across the profile's connected pods (optional `targets`) into a **per-pod envelope** where one
  pod's failure does not poison the others. *Since the MCP consolidation what one pod answers is
  `PodToolExecutor` in `:sempods-mcp-core`, shared with the pod-immanent MCP; `pods/PodApi` — a
  `suspend` pass-through to `PodWireClient` — is gone, and `api/mcp/ReadTools` is fan-out, tokens,
  envelope and audit only.* A shared `pods/PodTokenProvider` (refactored out of
  `TokenRefreshScheduler`) supplies a fresh pod token on demand — issuer-pinned, per-key-locked
  against double rotation. Contract-first: one source `ToolCatalog` in
  `:sempods-mcp-core` (shared with the pod-immanent MCP; this service is its `MULTI_POD` variant) +
  [`docs/tool-contract.md`](docs/tool-contract.md). Provenance per pod (per context where the tool
  carries it; free-form SPARQL is per-pod only).
- **M4 (write tools)** — done (build green). The MCP front door now also serves the seven write /
  property-mutation tools (`create_resource`, `update_resource`, `delete_resource`,
  `add_property_value`, `set_property_values`, `remove_property_value`, `clear_property_values`) via
  `api/mcp/WriteTools`. Unlike the read tools these **never fan out**: each takes a required single
  `target` pod + single `context_iri` and returns a single-pod envelope. The pod stays the authority
  on the `<context_iri>#write` scope and on ETag preconditions (`if_match` / `if_none_match: "*"` pass
  through; a 403/412/400 surfaces as the per-pod error, not a crash). Reads gained **partial-error
  surfacing**: a stable per-pod error `kind` plus a `partial` / `failed_pods` flag on the envelope.
  *The reserved-area guard this shipped with — refusing any target under a pod's `_system` /
  `.well-known` — was removed: the pod's own registry already answers 404/403, and the guard refused
  resource subjects the pod allows on purpose. Argument validation (absolute IRIs, ETag
  normalization) moved to `PodToolExecutor` with M3.*
- **M5 (named profiles & hard isolation)** — done (build green). Profile paths are **suffix-free**:
  the default profile is the service **root** (`mcp.sempods.org`), a named profile is
  `mcp.sempods.org/<profile>`. The whole OAuth + MCP surface gained a `/{profile}` variant
  alongside the root — discovery (`OAuthMetadataEndpoint`, RFC-9728 path-insertion +
  append-style), DCR/authorize/token (`AuthEndpoint`), and the MCP endpoint (`McpEndpoint`, now
  `POST /` + `POST /{profile}`, the old `POST /mcp` removed). `ProfilePath` guards the segment
  (path-safe, reserved root segments refused; `default` is reserved so `/default` is not a second
  URL for the root). `ProfileDao` (`profiles`) records named profiles (default implicit),
  materialised by the UI **Create** button **or auto-created on first authorization** against
  `…/<profile>` (so an AI client pointed at a fresh profile URL just works). The MCP endpoint
  enforces **hard isolation** — a
  token whose `profile` claim ≠ the path it arrived on is refused. `/_system/ui` is now a
  single-WebID-session **profile switcher** (create / switch via `?profile=` / copy the MCP URL /
  connect pods into the selected profile). **Breaking change:** `/mcp` is gone (404), no aliases —
  already-connected clients must re-add the server at the root (default) or `…/<profile>`.
- **M6.1 (secrets at rest)** — done (build green). A standalone AES-256-GCM `crypto/SecretCipher`
  (`v1:` envelope; key from `MCP_SECRET_KEY`, **mandatory when https or `PRODUCTION=true`**, with a
  deterministic dev key only on genuine local dev) encrypts the two secrets the service must keep
  recoverable: the pod access/refresh tokens (`TokenVaultDao`) and the private OAuth signing-key JWK
  (`SigningKeyDao`). Reads expect ciphertext, always — there is **no legacy-plaintext tolerance and
  no startup migration** (PoC stance, see **Deployment stance** above: breaking schema/crypto
  changes assume a fresh setup). Decrypt-failure semantics are
  **asymmetric**: an undecryptable pod-token row is treated as unreadable (`find` → null → "reconnect
  this pod"; the refresh sweep skips it), while an undecryptable **signing key is fatal** (`findAll`
  throws an actionable error naming `MCP_SECRET_KEY`, rather than silently minting a replacement).
  The JWKS endpoint still publishes only public parameters; the service's own refresh tokens were
  already SHA-256-hashed. Key rotation (a `v1:<kid>:…` form) is deferred.
- **M6.2 (SSRF resolve-and-pin)** — done (build green). *Since the client consolidation this lives
  in `:sempods-client` (`net/SempodsOutboundGuard`, `net/VettingDns`, `net/SempodsUrlPolicy`) and is
  shared with the consumer-side dereference guard, which had the same hole open; a second
  per-request layer now also refuses
  IP-literal hosts, which no resolver hook can see. The description below is otherwise unchanged.*
  The single outbound transport resolves DNS through a
  vetting hook (`VettingDns`): every A/AAAA record is checked against the blocked-range set
  (`SempodsUrlPolicy.rejectAddress` — RFC 1918/loopback/link-local/metadata plus CGNAT, benchmarking,
  TEST-NETs, multicast, 240/4, ULA, `2001:db8::/32`, 6to4/Teredo, and embedded-IPv4 forms incl.
  NAT64) and OkHttp connects to exactly the vetted addresses — resolve and connect are one event,
  closing DNS rebinding/TOCTOU; a mixed public/private resolution rejects the **whole** lookup, and
  the client pins `Proxy.NO_PROXY` (a JVM-property proxy would bypass the DNS hook). **Redirects
  are not followed at all** (both OkHttp switches; a 3xx surfaces as the per-pod fetch error).
  **Per-pod rate limit** (`commons`' `ratelimit/TokenBucketRateLimiter` behind the client's
  `OutboundRateLimiter` seam — the budget stays here because this service also keys one per user;
  token bucket keyed host + first path segment —
  pods are path-scoped on shared domains, so a bare-host key would pool tenants;
  `POD_RATE_LIMIT_PER_MINUTE`, default 120 strict / off relaxed) plus the existing timeouts bound
  every fetch; a throttled/blocked host surfaces as `pod_error` (not `no_token`, which would
  suggest a needless reconnect). The **pod client carries no trust exemptions** (pod URLs and the
  endpoints their metadata advertises are user input); the identity verifier gets its **own**
  hardened transport with only the configured auth-issuer hosts exempt from vetting — scoped to the
  issuer-JWKS fetch and never injectable, so a private-network issuer works on a strict deployment
  without opening a bypass for user-supplied pod URLs. That exemption covers **both** address
  layers, decided once in `SempodsOutboundGuard`: an issuer at an IP literal or a loopback name
  never reaches a resolver, so an exemption wired only into the DNS hook would be useless for
  exactly the hosts that need it. The strict/relaxed split stays the deploy-time `ALLOW_LOCAL_PODS`
  (relaxed = vetting off); the pod transport is deliberately the only one bound in the injector.
- **M6.3 (durable OAuth state + leader-elected refresh)** — done (build green). The six in-memory
  single-instance stores (`AuthorizationCodeStore`, `LoginStateStore`, `ConsentTransactionStore`,
  `ReauthorizeChallengeRegistry`, `WebLoginStateStore`, `PodConnectStateStore`; the reauthorize one
  is `ReauthorizeChallengeStore` in `:sempods-mcp-core` since consolidation M5, shared with the
  pod-immanent MCP) are now
  **Mongo-backed** (`oauth.authCodes` / `oauth.loginStates` / `oauth.consentTransactions` / `oauth.reauthChallenges`
  / `oauth.webLoginStates` / `oauth.podConnectStates`), each TTL-indexed on `expiresAt` with the
  same public API as before — in-flight logins/consents/pod-connects survive a restart and span
  replicas. One-time consume is an **atomic `findOneAndDelete`** (exactly one of N concurrent
  consumers wins; reads still check `expiresAt` because the TTL reaper is periodic); the
  reauthorize replay predicate compiles into a single conditional `findOneAndDelete`. Secret
  lookup keys (codes / states / txn ids) are stored as **SHA-256 `_id`s** (`crypto/sha256Hex`),
  and the pod-connect PKCE `codeVerifier` is additionally `SecretCipher`-encrypted (it is
  redeemable at an external token endpoint) — a DB dump replays nothing. Double-refresh across
  replicas is closed at two levels: a **per-token claim** on the vault row
  (`TokenVaultDao.tryClaimRefresh` / `releaseRefreshClaim`, claim TTL 60 s, keyed by a boot-time
  `persist/InstanceId`) is the correctness primitive — `PodTokenProvider` claims before every
  refresh (sweep and on-demand), **re-checks dueness under the claim** (a competitor's finished
  refresh releases its claim only by persisting, so the re-read sees it), a claim-losing sweep
  skips, a claim-losing on-demand call briefly polls (≤ 5 s) for the winner's token and falls back
  to the optimistic current one. The refresh **persists conditionally**
  (`TokenVaultDao.replaceIfClaimedBy` — only while the claim is still this replica's), so a
  `/_system/ui` re-connect landing mid-refresh wins (its `upsert` clears the claim; the stale
  rotation of the superseded family is discarded) and a disconnect's delete is not resurrected.
  Both refresh entries short-circuit **ahead of** that claim on `PodConnection.deadGrantSince`: a
  connection the pod answered RFC 6749 §5.2 `invalid_grant` for is finished until a reconnect writes
  a fresh registry row, so it costs two point reads a tick instead of a claim, a metadata discovery,
  a token POST and a release — and `deadGrantSince` now records when the grant died rather than when
  it was last retried (the mark's compare-and-set matched the row its own predecessor had written).
  A claim-*losing* caller re-checks the mark before its optimistic fallback too: the winner persists
  nothing when it finds the grant dead, so the polled row never moves and the fallback would
  otherwise hand back a still-unexpired token for the rest of the skew window. The answer does not
  depend on which replica found out.
  On top, a **singleton sweep lease** (`persist/LeaseDao`, `leases`,
  conditional-upsert acquire with the `matchedCount`/DUPLICATE_KEY race semantics; acquire = renew)
  makes only one replica run the `TokenRefreshScheduler` sweep per tick — an efficiency layer, so
  an expired lease mid-sweep is harmless. The **signing-key
  bootstrap is race-safe** (`SigningKeyDao.createInitial` — a fixed bootstrap `_id` admits exactly
  one first key; a losing replica loads the winner's, so all replicas sign with the same kid).
  Deploy note: the switch dropped whatever was in the old in-memory stores (≤ 10-min flows; users
  just retry).
- **M6.4 (multi-tenancy + audit)** — done (build green). The last M6 hardening gate. A persistent
  **audit trail** (`auditLog` — `persist/AuditLogDao`, TTL-bounded via a write-time `expiresAt`
  from `AUDIT_RETENTION_DAYS`, default 90 d; compound `(user, profile, ts)` index; the only read
  is tenant-keyed) fed by a typed `audit/AuditLog` emitter that is **synchronous-but-swallowing**
  (an audit failure is logged, never propagated — auditing must not fail the request path). Events:
  pod connect/disconnect (`WebUiEndpoint`), pod token refresh/rotation with fixed refusal labels
  (`issuer_mismatch` / `verification_failed` / `identity_drift` / `refresh_failed`; emitted in
  `PodTokenProvider.refreshLocked`, the single chokepoint for sweep + on-demand; transient
  throttle/SSRF failures deliberately not audited), service-token rotation + reuse-triggered
  family revocation (`AuthEndpoint.handleRefreshToken`), and **one `TOOL_CALL` row per pod-tool
  `tools/call`** emitted in the dispatchers — the `authorize` helper and the endpoint-level
  unknown-tool rejection never reach them and are deliberately not audited
  (`ReadTools.fanOut` outcome `ok|partial|error` over the
  resolved targets incl. validation refusals; `WriteTools.runWrite` single-target, `detail` = the
  per-pod error `kind`). Rows carry **no token material, no arguments, no SPARQL, no messages** —
  `detail` is always a fixed label. **Per-user quota:** `api/mcp/UserRateLimiter` (a thin
  `(user, profile)` wrapper over the generalized `ratelimit/TokenBucketRateLimiter`, which lives in
  `commons` since the pod server's token endpoint took a budget of its own; renamed from
  `PodRateLimiter`) throttles **only `tools/call`**, enforced in `McpEndpoint` *after* the bearer +
  M5 profile-isolation gates — over-quota is a protocol-level JSON-RPC `-32000` on HTTP 200
  (handshake methods stay free, an unauthenticated spray cannot drain a budget), audited with
  1/min-per-key sampling. `USER_RATE_LIMIT_PER_MINUTE` defaults 120 strict / 0 relaxed; in-memory
  per replica by design (budget scales with replica count — settled, ex-TODO). The **isolation
  review** (`docs/multi-tenancy-review.md`) verified tenant keying across all collections and fixed
  the unbounded `PodTokenProvider.locks` map (CAS-gated sweep of unlocked mutexes past 4096 keys —
  safe because the M6.3 vault claim is the double-refresh correctness primitive).

**This service holds no MCP semantics of its own.** The tool catalog, the JSON-RPC envelope, the
bearer challenge and the execution all live in `:sempods-mcp-core`, shared with the pod-immanent
MCP: `PodToolExecutor` runs the thirteen tools against **one** pod over `PodWireClient` — argument
parsing, the absolute-IRI rule, ETag normalization and the result shape — and what is left here is
what is genuinely many-pod: `targets` / `target`, the fan-out, the per-pod envelope, tokens, quota
and audit. `pods/PodIo` is the `suspend`↔blocking bridge, applied around the executor call. The one
piece of shared *state* is `ReauthorizeChallengeStore`, which takes its collection name per surface
and its tenant as a `realm` — the path profile here, a pod name on the pod server. The OAuth half
went the same way, into `:sempods-auth-core`; `docs/modularity.md` holds the cut and the
reasons, `docs/tool-contract.md` the semantics.

**Milestone `mcp.sempods.org` closed (M1–M6 done).** The service is live and hardened for a
multi-tenant hosted instance. The remaining forward-looking work — a versioned **conformance
profile + capability-discovery** ("what counts as a pod"), promoting `tool-contract.md` to a
**versioned** normative spec, and cross-implementation **conformance tests** — the
**drift-check** that keeps the three tool surfaces (per-pod MCP, chat app, hosted)
coherent — is **concept, not schedule**: it is gated on third-party conformant pods existing
(the code is not public yet), so freezing a contract now would be premature. Between the two
server-side surfaces drift is no longer possible — they run one implementation — so what the
check is actually for is the chat app's TS layer and any third-party pod. All three surfaces
stay; none collapses onto another. It lives in the concept doc, not a roadmap:
[`docs/mcp/hosted-mcp.md`](../docs/mcp/hosted-mcp.md) (direction, conformance
profile, toolset divergence).
