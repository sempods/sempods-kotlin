# Hosted MCP runtime

Current implementation and operational constraints. The [tool contract](tool-contract.md)
owns the tool surface; [hosted MCP architecture](../../docs/concepts/hosted-mcp.md) owns
the cross-module credential boundaries.

## Service identity and MCP access

The service is its own MCP-OAuth resource server / authorization server (RFC 9728 + 8414 discovery, DCR with
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

## Pod connections and token renewal

The service is an OAuth **client** toward pods:
a session-protected web-UI bundled under **`/_system/ui`** (web-session = service-signed
`web_session` JWT cookie, `typ`-separated from MCP access tokens) lets a signed-in user
connect pods via RFC 9728/8414 discovery + DCR + Authorization-Code/PKCE + token exchange
([PodOAuthClient](../src/main/kotlin/org/sempods/mcp/pods/PodOAuthClient.kt)), fills the `(user, profile, pod)` token vault + connection registry,
and `TokenRefreshScheduler` rotates refreshable connections on two tiers. The service cannot
observe a pod's refresh-family terms: rotation may extend an idle window but cannot outlast
a pod-imposed deadline. `invalid_grant` marks a family dead and requires reconnect. An expired
access token can be renewed on demand by `PodTokenProvider.validAccessToken` while the family
remains usable. A **warm** tier renews the access token of a connection used within
`POD_TOKEN_WARM_IDLE_SECONDS` (default 1 h), reducing latency on the next call; and a **preservation** tier rotates
every refreshable connection once per `POD_TOKEN_FAMILY_PRESERVE_SECONDS` (default 30 d) whatever
its access token says, including the unknown-expiry rows the warm tier can never select. The
cadence is a guess, and the KDoc on `SempodsMcpConfig.podTokenFamilyPreserveSeconds` owns why.
"Used" is written at one chokepoint — `validAccessToken`, whose only callers are the pod-touching
tool calls, so `list_pods`, `authorize` and the dashboard mark nothing — as a
throttled `lastUsedAt` on the vault row, which the warm selection is indexed on (both selections
are, and a test pins that). The
warm tier's traffic therefore follows **use**; preservation still walks refreshable inventory.
The selection of dead rows remains open in [#134](https://github.com/sempods/sempods-kotlin/issues/134). Each tier is
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
passes while the scan reads everything. Against the pod server's own `/token` budget (`../../docs/auth/oauth.md` §"Rate
limit": 20 a minute per `<address>|<client identity>`) this stays clear by a wide margin, and the
cadence widens it: only one of a refresh's four requests is the token POST, and the pod's DCR
dedup is per pod and profile, so every connection one profile holds *there* spends one `dyn:` key. A
connection under active warm-keeping spends 0.02 of that 20 — about 1,100 simultaneously-used
connections at one pod to meet it — while an idle one, touched once per preservation cadence,
spends 0.00002. What the *warm* tier drops needs no such marker — it is only ever pre-warming, and the
on-demand path still serves those connections one rotation later.
`POD_TOKEN_WARM_IDLE_SECONDS=0` leaves preservation alone — the smallest shape of the sweep, where
every first call after an idle period rotates on demand. The outbound policy is described under [Outbound requests](#outbound-requests).
**RFC 8414 + DCR are preferred but not required:** a pod that serves only RFC 9728 (a
minimal / `did:web`-static-client pod, e.g. the Staffbase KG pod) is connected by **convention**
— the AS endpoints are derived from the issuer (`…/authorize`, `…/token`), the service presents a
**static `did:web` client** instead of registering: `did:web:<mcp-host>` for the default profile
and, for a named one, an identifier scoped to that profile's callback, which is how a profile is a
separate client on the path that has no registration to vary. What the pod makes of that identifier is the pod's
own business, and this fallback is for pods we did not write: a sempods pod matches the origin
and fetches nothing, while a third party following the did:web method may resolve the document —
at `/.well-known/did.json` for the host-only identifier and at the profile's own callback plus
`/did.json` for a named one, which is where the method's read algorithm looks. The service serves
both. No JWKS means the pod
token's subject is trusted via the direct TLS token response (`subject_verified: false`). The convention is
taken **only on a genuine 404** for the AS metadata — a transient failure propagates rather than
silently downgrading a full pod. The machine MCP/AS endpoints stay at the root; `/_system` is the reserved system
namespace.

## Read tools

The MCP front door serves the read surface across
connected pods: `list_pods`, `list_contexts`, `get_resource`, `sparql_select`, `sparql_graph`,
`find`, `get_property_values`. Tools are advertised only to an authenticated session and proxy to
each pod's HTTP **System layer** (SSRF-guarded + pod-scoped bearer), fanning
out across the profile's connected pods (optional `targets`) into a **per-pod envelope** where one
pod's failure does not poison the others. `ReadTools` handles fan-out, tokens, the result envelope and audit; `PodToolExecutor`
owns single-pod execution. A shared `pods/PodTokenProvider` supplies a fresh pod token on demand — issuer-pinned, per-key-locked
against double rotation. Contract-first: one source `ToolCatalog` in
`:sempods-mcp-core` (shared with the pod-immanent MCP; this service is its `MULTI_POD` variant) +
[`tool-contract.md`](tool-contract.md). Provenance per pod (per context where the tool
carries it; free-form SPARQL is per-pod only).

## Write tools

The MCP front door serves the seven write /
property-mutation tools (`create_resource`, `update_resource`, `delete_resource`,
`add_property_value`, `set_property_values`, `remove_property_value`, `clear_property_values`) via
`api/mcp/WriteTools`. Unlike the read tools these **never fan out**: each takes a required single
`target` pod + single `context_iri` and returns a single-pod envelope. The pod stays the authority
on the `<context_iri>#write` grant and on ETag preconditions (`if_match` / `if_none_match: "*"` pass
through; a 403/412/400 surfaces as the per-pod error, not a crash). Reads provide **partial-error
surfacing**: a stable per-pod error `kind` plus a `partial` / `failed_pods` flag on the envelope.
Resource authorization belongs to the pod. Argument parsing, absolute IRIs and ETag
normalization belong to `PodToolExecutor`.

## Profile isolation

Profile paths are **suffix-free**:
the default profile is the service **root** (`mcp.sempods.org`), a named profile is
`mcp.sempods.org/<profile>`. The OAuth and MCP surfaces expose a `/{profile}` variant alongside the root — discovery (`OAuthMetadataEndpoint`, RFC-9728 path-insertion +
append-style), DCR/authorize/token (`AuthEndpoint`), and the MCP endpoint (`McpEndpoint`, `POST /` + `POST /{profile}`). `ProfilePath` guards the segment
(path-safe, reserved root segments refused; `default` is reserved so `/default` is not a second
URL for the root). `ProfileDao` (`profiles`) records named profiles (default implicit),
materialised by the UI **Create** button **or auto-created on first authorization** against
`…/<profile>` (so an AI client pointed at a fresh profile URL just works). The MCP endpoint
enforces **hard isolation** — a
token whose `profile` claim ≠ the path it arrived on is refused. `/_system/ui` is a
single-WebID-session **profile switcher** (create / switch via `?profile=` / copy the MCP URL /
connect pods into the selected profile). `/mcp` is not an alias for the root.

## Credentials at rest

A standalone AES-256-GCM `crypto/SecretCipher`
(`v1:` envelope; key from `MCP_SECRET_KEY`, **mandatory when https or `PRODUCTION=true`**, with a
deterministic dev key only on genuine local dev) encrypts the two secrets the service must keep
recoverable: the pod access/refresh tokens (`TokenVaultDao`) and the private OAuth signing-key JWK
(`SigningKeyDao`). Reads expect ciphertext, always — there is **no legacy-plaintext tolerance and
no startup migration** (see [Deployment stance](../AGENTS.md#deployment-stance-poc--no-migrations)). Decrypt-failure semantics are
**asymmetric**: an undecryptable pod-token row is treated as unreadable (`find` → null → "reconnect
this pod"; the refresh sweep skips it), while an undecryptable **signing key is fatal** (`findAll`
throws an actionable error naming `MCP_SECRET_KEY`, rather than silently minting a replacement).
The JWKS endpoint publishes only public parameters; the service's own refresh tokens are
SHA-256-hashed. [Key rotation](https://github.com/sempods/sempods-kotlin/issues/141) is proposed separately.

## Outbound requests

Outbound protection lives in `:sempods-client`: `SempodsOutboundGuard`, `VettingDns` and
`SempodsUrlPolicy`. IP-literal hosts need an additional request check because no DNS hook sees them.
The single outbound transport resolves DNS through a
vetting hook (`VettingDns`): every A/AAAA record is checked against the blocked-range set
(`SempodsUrlPolicy.rejectAddress` — RFC 1918/loopback/link-local/metadata plus CGNAT, benchmarking,
TEST-NETs, multicast, 240/4, ULA, `2001:db8::/32`, 6to4/Teredo, and embedded-IPv4 forms incl.
NAT64) and OkHttp connects to exactly the vetted addresses — resolve and connect are one event,
closing DNS rebinding/TOCTOU; a mixed public/private resolution rejects the **whole** lookup, and
the client pins `Proxy.NO_PROXY` (a JVM-property proxy would bypass the DNS hook). **Redirects
are not followed at all** (both OkHttp switches; a 3xx surfaces as the per-pod fetch error).
**Per-pod rate limit** (`sempods-commons`' `ratelimit/TokenBucketRateLimiter` behind the client's
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

## Durable state and refresh coordination

The six OAuth flow stores (`AuthorizationCodeStore`, `LoginStateStore`, `ConsentTransactionStore`,
`ReauthorizeChallengeRegistry`, `WebLoginStateStore`, `PodConnectStateStore`; the reauthorize implementation
is `ReauthorizeChallengeStore` in `:sempods-mcp-core`, shared with the
pod-immanent MCP) are
**Mongo-backed** (`oauth.authCodes` / `oauth.loginStates` / `oauth.consentTransactions` / `oauth.reauthChallenges`
/ `oauth.webLoginStates` / `oauth.podConnectStates`), each TTL-indexed on `expiresAt`  — in-flight logins/consents/pod-connects survive a restart and span
replicas. One-time consume is an **atomic `findOneAndDelete`** (exactly one of N concurrent
consumers wins; reads still check `expiresAt` because the TTL reaper is periodic); the
reauthorize replay predicate compiles into a single conditional `findOneAndDelete`. Secret
lookup keys (codes / states / txn ids) are stored as **SHA-256 `_id`s** (`crypto/sha256Hex`),
and the pod-connect PKCE `codeVerifier` is additionally `SecretCipher`-encrypted (it is
redeemable at an external token endpoint). Double-refresh across
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
Both refresh entries short-circuit **ahead of** that claim on `PodTokens.deadGrantSince`: a
connection the pod answered RFC 6749 §5.2 `invalid_grant` for is finished until a reconnect, so it
costs a field on a row already in hand instead of a claim, a metadata discovery, a token POST and a
release — and it records when the grant died rather than when it was last retried. On the vault
row, so a reconnect lifts it in the same write that installs the new family, and written under the
claim a rotation persists under (`TokenVaultDao.markDeadGrantIfClaimedBy`) so a reconnect landing
mid-refresh wins.
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

## Audit and quotas

A persistent
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
`sempods-commons`) throttles **only `tools/call`**, enforced in `McpEndpoint` *after* the bearer +
profile-isolation gates — over-quota is a protocol-level JSON-RPC `-32000` on HTTP 200
(handshake methods stay free, an unauthenticated spray cannot drain a budget), audited with
1/min-per-key sampling. `USER_RATE_LIMIT_PER_MINUTE` defaults 120 strict / 0 relaxed; in-memory
per replica by design (budget scales with replica count). `PodTokenProvider` sweeps unlocked mutexes past 4096 keys under a CAS gate; the vault
claim supplies cross-replica correctness. [Isolation review](multi-tenancy-review.md)
records the tenant-keying evidence.

## Connection state and blocking calls

`PodTokens` owns the family's registration, issuer, identity, verification and dead-grant
status. Refresh writes that row alone; displays read identity and verification together.
The registry gates connection existence and supplies descriptive fallbacks through
`PodClientIdentity.registrationOf` and `PodConnection.actingSubject`. Connect and disconnect
write the vault before the registry. These writes are non-atomic: failure can leave stale
metadata or an unlisted token after a first connect. Refresh claims do not serialize all
connection lifecycle operations across replicas.

`PodIo` runs blocking client calls on virtual threads and carries the trace across the hop.
Cancellation must reach the client call: interrupting a thread does not unblock an OkHttp
read, and `Job.invokeOnCompletion` alone runs too late, after the blocking body returns.
The bridge uses a cancellation handle for this reason.
